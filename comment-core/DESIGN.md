# comment-core 设计

`comment-core` 提供本地代码评论的 Java API。它读取工作区源码，将评论、历史版本、文件快照和范围位置保存在 SQLite 中。CLI、MCP 和桌面端调用同一套 API，定位结果由程序计算。

本文描述当前实现。运行方式见[项目说明](../README.md)，测量方法、原始数据和内存取舍见[性能报告](../docs/performance.md)。

## 1. 设计目标与约定

- 评论操作保持源码文件的字节不变，项目运行独立于 Git 和 IDE。
- 每条评论具有稳定的 `commentId`。正文更新和删除追加历史节点，已有节点保持不变。
- 同一文件共享最近一次处理的快照。读取当前评论时同步该文件的全部评论位置。
- 源码位置由文件版本与 offset 一起描述，行号由该快照计算。
- 无法确认位置时保留评论、原文和上下文，返回失效或歧义状态。
- 数据库中的定位更新与评论版本更新属于同一事务，多个进程通过 SQLite 协调。

IDE 能收到每次文档编辑事件，并据此移动 RangeMarker。本工具按需读取外部文件，通过前后快照恢复编辑区间，再应用类似的范围移动规则。两次读取之间的实际编辑过程可能有多种解释，因此重复文本和大范围重写需要保守处理。

## 2. 模块与代码组织

根 Maven 工程聚合 `comment-core`、`comment-cli`、`comment-mcp`、`comment-desktop`。后三个模块分别依赖 core。core 使用 Java 25，运行时依赖为 Xerial SQLite JDBC 与 java-diff-utils；JUnit 属于测试依赖，core 不依赖 JavaFX。

| 类型 | 职责 |
| --- | --- |
| [CommentService](src/main/java/io/github/dreamlike/llmcomment/core/CommentService.java) | 工作区路径、SQLite 事务、快照同步、评论与版本 API |
| [Workspace](src/main/java/io/github/dreamlike/llmcomment/core/Workspace.java) | CLI、MCP 和 Java API 共用的工作区发现及路径解析 |
| `CommentView` / `RevisionView` / `SnapshotView` | 当前评论、历史节点、文件内容的不可变返回值 |
| `FileComments` | 同一事务内取得的文件快照、缺失标志及评论列表 |
| `Location` / `AnchorStatus` | 对外位置与定位状态 |
| `CommentException` | 稳定的错误代码、说明和补充信息 |
| `Views` | 将返回值转换为 Map，供 CLI/MCP 序列化 |
| [AnchorTracker](src/main/java/io/github/dreamlike/llmcomment/core/anchor/AnchorTracker.java) | 捕获范围、迁移端点、判断失效及恢复位置 |
| [HierarchicalDiff](src/main/java/io/github/dreamlike/llmcomment/core/anchor/HierarchicalDiff.java) | 按行与内容切分变化，生成字符编辑区间 |
| [IterativeMyersDiff](src/main/java/io/github/dreamlike/llmcomment/core/anchor/IterativeMyersDiff.java) | 用显式工作栈执行线性空间字符 Myers，限制前沿推进及比较量 |
| [OccurrenceIndex](src/main/java/io/github/dreamlike/llmcomment/core/anchor/OccurrenceIndex.java) | 共享多模式计数与有界候选缓存 |
| [LiteralSearch](src/main/java/io/github/dreamlike/llmcomment/core/anchor/LiteralSearch.java) | UTF-16 原文查找，选择 `indexOf` 或 KMP |
| [LineMap](src/main/java/io/github/dreamlike/llmcomment/core/anchor/LineMap.java) | 行终止符识别、offset 到行号的索引 |

`AnchorTracker` 及其辅助类型接收字符串和范围，不访问文件或数据库。`CommentService` 负责读取源码并在事务内调用定位算法。搜索索引在一次定位中共享，文件快照和行索引在当前操作内复用。

`CommentService` 持有工作区和数据库路径，每个 API 调用独立打开、关闭连接。实例不持有连接池或跨调用的源码缓存，`close()` 当前无需释放共享句柄。

## 3. 公开 API

入口是 `io.github.dreamlike.llmcomment.core.CommentService`。

```java
new CommentService();
new CommentService(workspace);
new CommentService(workspace, database);
new CommentService(Workspace.resolve(workingDirectory, workspaceOverride, databaseOverride));
```

工作区需要是已存在的目录。无参构造从当前目录向上寻找最近含有 `.llm-comments` 目录的位置，找不到时使用当前目录。传入 `Path workspace` 时直接使用该目录。CLI 和 MCP 都通过 `Workspace.resolve` 采用相同的规则，工作区在每次 CLI 调用或 MCP 启动时确定。

`Workspace.resolve` 的两个 override 参数允许为 `null`。相对的 `workspaceOverride` 基于传入的 `workingDirectory`，相对的 `databaseOverride` 基于最终工作区。默认数据库为 `.llm-comments/comments.db`。解析过程只检查目录，创建目录和打开数据库发生在 `CommentService` 构造时。工作区标识与 Git、Maven 文件无关。

源码参数使用工作区相对路径，或者工作区内的绝对路径；存储及返回值使用规范化的工作区相对路径。CLI 的 `--body-file` 和 `--database` 相对路径也采用工作区为基准。`Workspace.sourcePath` 检查路径范围，`CommentService` 进一步检查实际符号链接目标和数据库文件。

自定义数据库路径作用于本次构造，不会保存为工作区配置。其所在目录不参与自动发现，调用方需要在每次启动时传入相同的数据库位置和工作区。CLI `workspace` 命令通过只读解析报告实际路径，便于检查启动配置。

| 方法 | 返回值 | 源码访问与历史行为 |
| --- | --- | --- |
| `create(file, startLine, endLine, body, author)` | `CommentView` | 同步文件、捕获范围，追加 `CREATE` 节点 |
| `create(file, startLine, endLine, body, author, expectedFileVersionId)` | `CommentView` | 同步后校验预期源码版本，再捕获范围；不一致返回 `FILE_CONFLICT` |
| `get(id)` | `CommentView` | 同步所属文件，返回当前状态，包括已删除评论 |
| `list(file, includeDeleted)` | `List<CommentView>` | 同步目标文件；`file == null` 时处理数据库已记录的文件 |
| `inspect(file, includeDeleted)` | `FileComments` | 同步一次文件，并在同一事务内返回完整快照与评论；无评论的文件也有快照 |
| `update(id, baseRevisionId, body, author)` | `CommentView` | 同步文件、检查版本，追加 `UPDATE` 节点 |
| `delete(id, baseRevisionId, author)` | `CommentView` | 同步文件、检查版本，追加 `DELETE` 节点并标记删除 |
| `history(id)` | `List<RevisionView>` | 从数据库按追加顺序读取节点 |
| `snapshot(fileVersionId)` | `SnapshotView` | 从数据库读取指定版本的完整文件内容 |

正文允许空字符串，正文为 `null` 会被拒绝。作者需要是非空白字符串；`anonymous` 默认值由 CLI/MCP 提供。更新正文时不改变评论所属文件，当前 API 没有显式重新绑定范围的操作。

`list` 按 `createdAt`、`id` 排序，`includeDeleted == false` 时过滤已删除评论。工作区列表处理的是数据库中的文件集合，不扫描目录来发现新文件。指定一个尚未记录的文件查询列表时，会先读取并记录该文件快照。

更新或删除需要携带最近一次结果中的 `currentRevisionId`。这检查正文版本是否过时，源码变化则由本次同步处理。自动位置迁移保持 `currentRevisionId` 和评论的 `updatedAt` 不变。

桌面端先调用 `inspect` 显示源码及评论，两者引用同一份快照。用户选行后，创建操作携带该快照的 ID 作为 `expectedFileVersionId`。若用户选择第 250 行后，外部程序在第 100 行新增函数，保存时会拒绝过时的选区并返回 `FILE_CONFLICT`，调用方刷新后重新选择。检查失败回滚整个事务，不追加评论或历史。传入 `null` 保持原创建接口的行为，即针对操作时读取的源码捕获范围。

`inspect` 不接受空文件路径。已记录的文件缺失时返回最后一次快照、`missing == true` 以及缺失状态的评论；未记录且不存在的文件仍报错。`FileComments` 复制评论列表，调用方不能修改返回的列表结构。

## 4. 数据模型

SQLite 保存四张表。评论和快照 ID 为 UUID，时间使用 `Instant` 的 UTC 文本形式。

| 表 | 主要字段 | 含义 |
| --- | --- | --- |
| `snapshots` | `id`, `file`, `hash`, `text`, `created` | 某文件内容的完整快照，`UNIQUE(file, hash)` 去重 |
| `files` | `file`, `snapshot`, `missing` | 文件路径、最近处理的快照、当前缺失标志 |
| `comments` | `id`, `file`, `revision`, `body`, `author`, `created`, `updated`, `deleted` | 当前正文、版本指针及删除状态 |
| `comments` 的范围字段 | `start`, `end`, `status`, `selected`, `before_text`, `after_text` | 当前锚点或失效前保留的定位依据 |
| `revisions` | `seq`, `id`, `comment_id`, `parent`, `action`, `body`, `author`, `created` | 不可变历史节点，`seq` 决定追加顺序 |
| `revisions` 的位置字段 | `snapshot`, `start`, `end`, `start_line`, `end_line`, `status`, `selected` | 创建节点时的位置和原文 |

数据库外键连接 `files.snapshot` 与 `snapshots`、`comments.file` 与 `files`、`revisions.comment_id` 与 `comments`、`revisions.snapshot` 与 `snapshots`。`comments.revision` 和 `revisions.parent` 的关联由事务中的应用代码维护，当前 DDL 没有为这两个字段声明外键。

索引 `comments_file_order(file, created, id)` 支持按文件读取评论，`revisions_comment_seq(comment_id, seq)` 支持按评论读取历史。

### 4.1 快照与当前位置

快照哈希是文件文本按 UTF-8 编码后的 SHA-256。内容相同时复用该文件已有的快照 ID，因此文件经历 A、B、A 三次内容变化时，可以再次引用第一个 A 的 ID。`fileVersionId` 表示内容版本，不表示递增的修改次数。

当前评论的 `Location` 使用 `files.snapshot` 和当前锚点生成。`selectedText` 是跟踪中的原文；范围失效后保留最后可用的原文。失效时内部旧 offset 也会保留供后续判断，对外的 offset 和行号返回 `null`。

缺失文件通过 `files.missing` 单独表示，原锚点状态保持在评论记录中。此时对外显示 `MISSING_FILE`，`fileVersionId` 仍指向最后读取成功的快照。恢复同样内容的文件后，可恢复缺失前的位置与状态。

### 4.2 评论历史

同一评论的历史节点组成父节点链：创建节点的 `parentRevisionId` 为 `null`，之后每次更新或删除指向此前的当前节点。版本冲突检查保证一次成功修改接在当前节点之后。

节点保存当时的正文、作者、文件版本和位置。创建节点后，源码继续变化只更新 `comments` 的位置，不重写该节点。删除节点保留删除时的正文，当前记录的 `deleted` 置为真，后续正文修改返回错误。

定位失效或文件缺失时，已有评论仍可更新正文，生成的节点记录对应状态和空位置。`history` 与 `snapshot` 直接读数据库，因此当前源码缺失或编码错误时仍可访问历史。

历史不可变性由 core 的追加逻辑保证，数据库没有设置禁止外部 SQL 修改历史的触发器。全部节点与快照持续保留，当前没有压缩、回收或恢复已删除评论的 API。

## 5. 行号、offset 与范围

对外行号从 1 开始，起止行都包含。内部位置使用 Java UTF-16 单元和半开区间 `[startOffset, endOffset)`，与 `String.substring` 一致。

例如文本 `甲😀\r\n乙` 中，第一行范围是 `[0, 3)`，第二行范围是 `[5, 6)`。`😀` 占两个 UTF-16 单元，CRLF 占两个单元但只构成一次换行。`𠮷` 等增补平面汉字同样占两个单元。文件字节数、Unicode 码点数和 offset 分别计算。

`capture` 的终点位于所选最后一行的终止符之前。跨行范围包含中间各行的换行符。行终止符支持 LF、CRLF、CR；空文件有一行，末尾换行后也有一行。空行可以形成零长度锚点。

每个锚点保存原文，以及左右各约 64 个 UTF-16 单元的上下文。上下文遇到代理对时向外多取一个单元，保证保存到 SQLite 时包含完整字符。匹配使用精确原文，不进行中文分词、空白折叠或 Unicode 规范化。

`LineMap.Index` 一次扫描建立行首 offset 数组，后续用二分查找计算行号。范围末行由 `max(startOffset, endOffset - 1)` 计算，零长度范围使用其自身位置。

### 5.1 非贪婪端点

| 编辑位置 | 范围变化 |
| --- | --- |
| 整个范围之前 | 两个端点加上插入长度减去删除长度 |
| 恰好在左端插入 | 左右端点右移，新增内容位于范围外 |
| 恰好在右端插入 | 右端点保持，新增内容位于范围外 |
| 范围内部 | 范围随编辑扩缩，确认仍可跟踪时标为 `MODIFIED` |
| 覆盖范围及周围内容 | 尝试按原文与上下文恢复，无法确认则失效 |

例如第 250 行前新增 20 行，原范围内容未变，下一次同步会将其行号更新为 270。偏移计算依据字符编辑长度，插入的换行数量最终由行索引反映。

### 5.2 定位状态

| 状态 | 含义与后续处理 |
| --- | --- |
| `ACTIVE` | 当前范围与跟踪原文匹配，或原文已成功恢复 |
| `MODIFIED` | 范围仍可定位，内部内容发生过变化 |
| `ORPHANED` | 无法确认原范围，保留原文及上下文等待恢复 |
| `AMBIGUOUS` | 多个候选或重复副本变化导致身份不明确 |
| `MISSING_FILE` | 文件暂时不存在，对外覆盖显示的状态 |

`MODIFIED` 在后续无关编辑中继续保留；它不等价于“相对首次评论内容的实时差异比较”。原文搜索成功恢复位置时会产生 `ACTIVE`。

空范围失效后不会通过搜索空字符串恢复。有效空范围仍可随 offset 移动，但落点需要继续是空行。

## 6. 定位算法

### 6.1 分层 diff

`HierarchicalDiff` 先裁去相同前后缀。纯插入和纯删除直接生成编辑区间。其余区域按大小处理：

1. 输入长度乘积不超过 4,000,000 的块使用线性空间字符 Myers。
2. 大块先按保留换行符的行切分，用两边均唯一的行配对，再用最长递增子序列选择有序匹配。
3. 匹配片段之间继续细分。缺少行匹配时，按内容切成约 32 到 256 个 UTF-16 单元的块，再寻找有序的唯一匹配。
4. 切分深度达到 4 或无法继续细分时，生成带 `coarse` 标志的替换区域。

内容哈希用于选择切点，块是否相同仍比较完整文本。相邻的删除、插入会合并，供端点映射统一处理。

分层切块和字符 Myers 都使用 `ArrayDeque` 保存待处理区间，先压入后半段，再处理前半段，输出保持原文件 offset 顺序。切分深度限制用于控制扫描次数，输入规模不会增加 Java 调用栈深度。Myers 的两个前沿数组和工作栈占用 O(N+M) 空间。

字符 Myers 每块还限制约 32,000,000 单位的前沿推进及字符比较工作量。输入长度乘积无法约束极端偏斜输入，例如 1 个字符与 1,000,000 个字符；长度差已经超过计算预算下界时，在分配前沿数组之前返回粗块。运行中超限会丢弃该块未完成的脚本，交给原文及上下文恢复。降级可能减少可确定的内部修改范围，但唯一原文仍可恢复，多个同分候选仍返回歧义。

字符算法基于 java-diff-utils 4.15 的 `MyersDiffWithLinearSpace`，区间调度改用显式工作栈，并加入计算预算。未超限时保持相同的匹配选择，随机 UTF-16 测试逐条比较两个实现的编辑区间。搜索索引构建、失败链接遍历、KMP、恢复搜索及行号查找均使用迭代。

### 6.2 范围迁移与恢复

`AnchorTracker.relocate` 为同一文件的全部锚点共享一次 diff 与原文索引。有效锚点先检查旧 offset 指向的文本是否匹配；失效锚点或检查失败的锚点进入原文恢复。

若原文在旧快照有多个副本，新快照仍有副本但数量减少，则标记 `AMBIGUOUS`。即使 diff 为某个副本给出了位置，也不足以确定被删除的是哪一个。

与粗粒度替换相交的范围进入恢复搜索。其余范围按编辑区间映射左右端点。映射后的原文不一致时，先尝试寻找原文是否移到了别处；确认仍能跟踪当前范围时更新原文与上下文，并标为 `MODIFIED`。范围被清空、连同周围内容被覆盖，或保留内容不足且邻接上下文不匹配时，标为 `ORPHANED`。

恢复会枚举精确原文的候选位置，分别计算左侧连续匹配的后缀长度和右侧连续匹配的前缀长度，二者相加作为分数。最高分唯一时可恢复，最高分并列时返回 `AMBIGUOUS`。已经处于 `AMBIGUOUS` 的锚点，还需要完整匹配保存的上下文；没有上下文的歧义锚点无法仅凭剩余一个副本恢复。

### 6.3 搜索策略与内存预算

`OccurrenceIndex` 对相同原文去重。前后快照共享模式数据和自动机，各自保存出现次数与候选缓存。

| 条件 | 策略 |
| --- | --- |
| 不同原文不超过 8 个，或文件长度乘原文数量不超过 1,000,000 | 按需使用单模式搜索 |
| 不同原文超过 8 个、总长度不超过 131,072，且扫描量超过上述阈值 | 数组实现的 Aho–Corasick 多模式计数 |
| 原文总长度超过 131,072 | 按需单模式搜索，限制自动机大小 |
| 单模式长度至少 256，且首 64 个 UTF-16 单元在模式内部再次出现 | KMP 重叠匹配 |
| 其余单模式 | JDK `String.indexOf` |

自动机用整数数组和字符数组表示节点，通过失败链接汇总计数。精确计数为零时直接返回空候选。需要恢复位置时才枚举候选，每个快照最多缓存 65,536 个位置，超出后逐个迭代检查。

KMP 前缀表在前后快照间共享，缓存上限为 131,072 个 `int`，即 512 KiB。超过共享缓存预算的模式按需分配单个临时前缀表，全部重叠匹配仍会被检查。

这些预算限制索引占用和单块 diff 工作量，不代表固定的调用延迟。当前范围映射仍按每个锚点遍历编辑区间；有 A 个锚点和 E 个编辑区间时，该部分约为 `O(A × E)`。极端重复内容的恢复还需要检查候选及上下文。

## 7. 事务与同步流程

数据库启用 WAL；每个连接设置 `foreign_keys=ON` 和 10 秒 `busy_timeout`。创建表和索引发生在服务构造阶段。

| 操作 | 事务 | 原因 |
| --- | --- | --- |
| `create` / `get` / `list` / `update` / `delete` | `BEGIN IMMEDIATE` | 查询当前评论也可能更新文件快照和位置 |
| `history` / `snapshot` | `BEGIN` | 读取已保存的历史数据，WAL 下可与写入并行 |

写事务在读取当前数据库状态前取得写锁。SQLite 对数据库写事务串行化，因此多个文件的写入也共用这一写锁；当前没有额外的每文件锁。`baseRevisionId` 在事务内检查，两个进程基于同一版本更新时，一个成功后，另一个会看到版本冲突。

### 7.1 同步文件

1. 检查工作区路径，以 UTF-8 读取源码。
2. 文件缺失时设置 `missing` 并保留锚点；从未记录的文件缺失则返回 `FILE_NOT_FOUND`。
3. 读取最近处理的快照并比较内容。内容相同时复用快照，必要时清除缺失标志。
4. 内容变化时保存或复用新快照，对该文件全部评论执行 `relocate`，包括已删除评论。
5. 用复用的预编译语句批量写入变化的锚点，更新文件快照指针。
6. 为本次查询构造共享行索引，生成返回值。

文件每次都重新读取。mtime 与大小不能充分证明内容没变，因此不会单凭它们跳过检测。无变化的查询保持数据库内容不变。

全工作区列表逐文件同步并生成结果，完成一个文件后结束其全文快照与行索引的复用。最终结果按创建时间和 ID 排序。事务中某个文件读取失败时，整个列表操作回滚。

### 7.2 更新正文或删除

```text
BEGIN IMMEDIATE
  读取当前评论
  同步所属文件及其全部锚点
  校验 baseRevisionId 和 deleted
  追加历史节点，保存本次位置
  更新当前正文、版本指针和删除标志
COMMIT
```

操作失败会回滚，包括在版本冲突之前计算并写入的位置变化。客户端收到 `CONFLICT` 后重新查询当前评论，再决定如何处理自己的正文。

SQLite 锁只覆盖数据库，编辑器和其他进程仍可修改源码。多文件同步也不构成整个文件系统的同时快照。返回位置对应 `fileVersionId` 中的文本；读取后源码再次变化时，需要下一次同步才能得到新的位置。

## 8. 路径与错误处理

源码路径经过规范化及真实路径检查，符号链接解析后的目标需要位于工作区中。工作区元数据目录、数据库本身及其 WAL、SHM、journal 文件不能作为评论目标。数据库可以通过构造参数放在工作区外。

| 错误代码 | 情况 |
| --- | --- |
| `INVALID_ARGUMENT` | 非法行范围、正文或作者参数 |
| `INVALID_PATH` | 工作区不是目录、源码越出工作区或指向元数据 |
| `FILE_NOT_FOUND` | 新目标文件不存在，或创建评论时目标缺失 |
| `FILE_READ_ERROR` | 读取失败、非法 UTF-8 等 I/O 问题 |
| `NOT_FOUND` | 评论或快照 ID 不存在 |
| `CONFLICT` | `baseRevisionId` 过时，附带当前版本 ID |
| `DELETED` | 尝试修改已删除评论 |
| `STORAGE_ERROR` | SQLite 或其他存储执行错误 |

已记录文件的 `NoSuchFileException` 转为缺失状态。其他读取错误返回失败并回滚，保持此前保存的状态。失败的正文更新、定位写入及快照写入不会留下半完成记录。

## 9. Native Image 与验证

core 以普通 JAR 提供 API，CLI 和 MCP 分别构建可执行文件。Xerial SQLite JDBC 已支持当前 Native Image 场景，其平台 JNI 库嵌入产物并在运行时解压加载。该方式需要可写临时目录，SQLite 并非静态链接。

`Views` 使用显式 Map 转换，让 CLI/MCP 序列化公开返回值时能够减少对 record 反射元数据的依赖。定位算法和 SQLite 持久化流程由两个入口共用。

| 验证范围 | 对应测试 |
| --- | --- |
| 范围端点、空行、失效恢复、随机连续编辑 | `AnchorTrackerTest`、`AnchorTrackerRandomizedTest` |
| 大文件切块、重复文本、多模式索引与预算 | `HierarchicalDiffTest` |
| 随机 diff 对照与重建、小 JVM 栈、百万字符偏斜及降级恢复 | `IterativeMyersDiffTest` |
| 中文长前缀、emoji、增补汉字、重叠匹配 | `LiteralSearchTest` |
| LF/CRLF/CR 与所有 offset 的行号一致性 | `LineMapTest` |
| 历史、快照、并发冲突、缺失恢复、路径和事务回滚 | `CommentServiceTest` |
| 工作区发现、路径基准、绝对路径与符号链接 | `WorkspaceTest` |

`mvn verify` 执行 JVM 测试；`native-test` 和 `native-release` 分别构建 `-O0`、`-O3`，在 `verify` 阶段运行入口集成检查。性能脚本另外测量固定输入的耗时与分配量，行为测试不设置脆弱的耗时断言。

## 10. 当前限制与维护要求

文件身份使用规范化路径，重命名和跨文件移动需要调用方另行处理。范围恢复依赖原文和上下文，重复代码无法保证恢复到原来的副本，歧义会保留下来。

所有快照保存全文，历史持续增长。列表与历史 API 当前一次返回完整结果，尚无分页。单次定位的全文、输入锚点和返回值仍需要占用内存，索引预算不等于整个调用的内存上限。

数据库初始化使用 `CREATE TABLE/INDEX IF NOT EXISTS`，目前没有带版本号的结构迁移器。后续改变表结构时，需要设计迁移步骤，并保证已有历史节点和快照可读。

调整范围规则时应补充端点和歧义行为测试；调整 diff 或搜索阈值时应使用相同输入比较性能，并检查中文、emoji、小文件和全文件重写。新增监听器或跨调用缓存时，仍需保留按内容校验的正确性路径。
