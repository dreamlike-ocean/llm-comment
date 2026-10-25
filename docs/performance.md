# 性能测量与实现取舍

本次比较优化前的可执行 JAR 与当前实现，另外测试 `-O3` MCP 程序。主要数据集由中文正文组成，包含中文评论范围、重复段落、局部修改和文件重写。emoji、增补平面汉字及 CRLF 的位置计算另有行为测试。

## 中文定位实测

单位为毫秒，倍数使用 p50 计算。

| 场景 | 优化前 p50 | 优化后 p50 | 优化前 p95 | 优化后 p95 | 倍数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 中文前置插入，1000 评论 | 64.289 | 3.965 | 66.432 | 5.164 | 16.2 |
| 中文多处插入，1000 评论 | 123.629 | 7.213 | 125.928 | 8.703 | 17.1 |
| 中文范围内部修改，1000 评论 | 124.189 | 9.061 | 145.304 | 11.119 | 13.7 |
| 重复中文段落，1000 评论 | 1399.954 | 2.348 | 1408.576 | 3.984 | 596.2 |
| 整文件中文重写，1000 评论 | 105.163 | 8.614 | 130.598 | 10.135 | 12.2 |
| 小文件中文，20 评论 | 0.349 | 0.277 | 0.629 | 0.293 | 1.3 |
| 中文长前缀近似匹配，1 评论 | 39.980 | 0.221 | 40.992 | 0.339 | 181.1 |
| emoji 前置插入，1000 评论 | 64.051 | 4.009 | 69.134 | 5.253 | 16.0 |

范围内部修改场景包含 20 条被修改的评论。优化前结果为 980 条 `ACTIVE`、20 条 `ORPHANED`；优化后为 980 条 `ACTIVE`、20 条 `MODIFIED`。所选原文与当前快照逐条核对。

中文长前缀近似匹配使用 100,001 个 UTF-16 单元、1,001 单元的失效原文，计时包括 `relocate` 的恢复处理。短调用对 JIT 编译状态敏感，这个数字对应上述固定预热配置。Native Image 组另测更长输入的协议往返。

索引带来临时分配：中文前置插入由每次 1.88 MB 增至 5.77 MB，整文件重写由 0.50 MB 增至 20.47 MB（MB 按一百万字节计）。原始记录包含每种场景的分配量、状态、校验值和所有样本：[优化前](performance/algorithms-before.json)、[优化后](performance/algorithms-after.json)。

## 中文存储实测

单位为毫秒，均包含源码重新读取、SQLite 事务与行号计算。

| 场景 | 优化前 p50 | 优化后 p50 | 优化前 p95 | 优化后 p95 | 倍数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 无变化，1000 评论列表 | 4328.177 | 16.283 | 4358.620 | 18.686 | 265.8 |
| 前置编辑后，1000 评论列表 | 4533.758 | 32.499 | 4573.809 | 39.346 | 139.5 |
| 单条评论查询 | 19.732 | 10.413 | 21.226 | 12.463 | 1.9 |

无变化列表的累计 Java 对象分配由约 14.62 GB 降至 37.42 MB，变更后列表由约 14.63 GB 降至 69.82 MB。这里记录每次调用累计分配的字节数，进程堆上限仍为 512 MiB。大量对象被垃圾回收，并非同时存活。完整记录：[优化前](performance/storage-before.json)、[优化后](performance/storage-after.json)。

## Native Image 实测

两个程序均为 `-O3`。下表为 MCP 协议往返耗时，单位为毫秒。

| 场景 | 优化前 p50 | 优化后 p50 | 优化前 p95 | 优化后 p95 | 倍数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 中文变更后列表，1000 评论 | 8374.879 | 78.555 | 8421.371 | 80.816 | 106.6 |
| 长中文前缀近似匹配，1 评论 | 806.799 | 44.233 | 813.089 | 45.460 | 18.2 |

列表结果逐条检查状态和行号。近似匹配场景持续编辑文件以触发恢复搜索，验证评论仍为 `ORPHANED` 且正文版本保持不变。完整样本与两个可执行文件的 SHA256 见[原生程序记录](performance/native.json)。

## 测量方式

环境为 Linux x64、Intel i5-13600KF、Oracle GraalVM JDK 25.0.2。JVM 使用 `-Xms128m -Xmx512m`，Native Image 使用 `-Xmx512m`。性能测量与 Maven 编译分开执行。

中文大文件有 8192 行，共 1,048,576 个 UTF-16 单元，UTF-8 文件占 3,112,960 字节。1000 条评论均匀分布在文件中。小文件场景使用 100 行、20 条评论。emoji 场景包含完整的代理对，仍以 UTF-16 单元计算 offset。

算法基准每个版本、每种场景启动两个独立 JVM，每个进程预热 20 次、采样 20 次。存储基准每个进程预热 5 次、采样 10 次。p50 和 p95 由该场景的全部有效样本计算。每次调用后校验状态、数量与可定位范围的原文，校验时间排除在计时外。

JVM 计时围绕 `AnchorTracker.relocate` 或 `CommentService` 调用，输入生成、建库及模拟编辑的文件写入排除在计时外。数据准备通过公开 API 创建首条记录，再用 SQL 批量填充其余评论和历史节点。线程分配量来自 `ThreadMXBean`，包含本次调用产生的 Java 对象，不能等同于存活堆大小或进程 RSS。

Native Image 基准使用持久 MCP 进程，测量一次 `tools/call` 的往返时间，包含源码读取、SQLite、定位、JSON 编解码与管道传输。测试变更后的列表，以及单条失效评论在 1,000,001 单元文件中搜索 10,001 单元原文的近似匹配。进程启动及数据准备排除在计时外。每个版本运行两个进程，各预热 2 次、采样 5 次。

这是固定数据集的回归基准。结果用于比较本机上的两个实现，文件分布、磁盘缓存、CPU、JIT 和垃圾回收都会影响绝对耗时。

## diff 与搜索

`HierarchicalDiff` 先去掉相同前后缀。纯插入或删除直接产生 offset 变化；其余大块先用唯一行配对和最长递增子序列切分，再对小块执行字符 Myers。长行或缺少唯一行的区域按内容切成约 32 到 256 个 UTF-16 单元的块，继续寻找相同片段。切块哈希用于确定切点，片段配对仍检查完整文本相等。

字符 Myers 的输入乘积预算为每块 4,000,000，切分深度上限为 4。分层切块及字符 Myers 均使用显式工作栈。每块另有 32,000,000 单位的前沿推进与字符比较预算，超限时以粗块交给原文和上下文恢复。

## 非递归 diff 回归测量

本节比较初始化提交 `9a445b3` 的递归 diff 与显式工作栈实现，单独测量 `HierarchicalDiff.diff`。同一 JVM 中每种实现预热 8 次、采样 11 次，使用 `-Xss256k -Xmx256m`，取中位数，单位为毫秒。输入生成与输出区间比较在计时之外。

| 输入 | 递归实现 | 显式工作栈及计算预算 |
| --- | ---: | ---: |
| 20,000 行中文、emoji、CRLF，多处插入 | 5.166 | 3.678 |
| 1 对 6,000 字符，完全不同 | 21.851 | 26.850 |
| 1,999 字符重复近似匹配 | 0.023 | 0.026 |
| 1,999 对 1,999 字符，完全替换 | 13.537 | 15.641 |

四组结果的编辑区间逐字段相同。中文大文件有 268,890 个 UTF-16 单元；逐行内容为 `行号:中文😀目标\r\n`，用替换 `00:`、`999:` 的方式制造分散编辑。预算检查在部分输入上有开销，本次修改没有取得所有场景的加速。单进程微基准用于发现回退，不能代表整个 CLI 或 MCP 请求耗时。[原始数据](performance/iterative-diff.csv)。

1 对 1,000,000 个中文字符及反向输入在前沿数组分配前降级，预热 10 次、采样 21 次，`-Xss256k -Xmx128m` 下中位数分别为 0.001895 ms、0.001511 ms。这仅测粗块生成，未包含后续原文恢复。[偏斜输入数据](performance/diff-budget.csv)。

行为测试用独立 `-Xss256k -Xmx128m` 子进程覆盖偏斜长度、全替换、重复文本和大文件分散修改，并用 3,000 轮随机 UTF-16 数据比较编辑区间、应用 diff 还原目标。旧递归实现在上述代表样例中也通过小栈测试，未复现 StackOverflow；显式工作栈从实现结构上去除了输入相关的调用栈增长。额外测试确认预算降级后的唯一中文原文可恢复，同分重复原文返回 `AMBIGUOUS`。

## 搜索策略

搜索按输入规模选择实现：

| 条件 | 实现 |
| --- | --- |
| 少量模式或小文件 | JDK `String.indexOf` |
| 大文件、多条不同原文 | 紧凑数组实现的 Aho–Corasick 多模式计数 |
| 长原文存在重复前缀 | KMP 完整重叠匹配 |

多模式自动机要求不同原文数量大于 8、总长度不超过 131,072，且文件长度乘模式数量大于 1,000,000。前后快照共享自动机，分别按需计算出现次数。计数通过失败链接汇总，重复数据的所有匹配位置不会一次性展开。

KMP 用于长度至少 256 个 UTF-16 单元、且首 64 个单元在模式内部再次出现的原文。这个条件针对重复中文、重复代码和长近似匹配。前缀表共享缓存上限为 512 KiB，超出后按单个模式临时计算。普通短中文继续使用 `indexOf`。

每个快照最多缓存 65,536 个候选位置，超出后以迭代器逐个检查。已经精确计数为零的原文直接返回空结果。所有路径均检查完整原文，歧义状态与上下文判断仍然生效。

`String.indexOf` 的 UTF-16 实现可由 JVM intrinsic 优化，普通输入上有优势；长重复前缀需要单独测试。[OpenJDK 25 源码](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/share/classes/java/lang/StringUTF16.java#L560)提供了对应实现。KMP 的线性扫描来自[原论文](https://epubs.siam.org/doi/10.1137/0206024)。Git 的[算法说明](https://git-scm.com/docs/diff-algorithm-option.html)列出了 Myers、Patience 和 Histogram；此处使用独立 Java 实现组合行级切分与局部字符比较。

## 存储与范围计算

列表查询按文件读取完整评论记录，同一文件共享一次快照读取和一次行索引构造。offset 到行号通过二分查找计算。全工作区列表逐文件处理，最后按创建时间与 ID 排序，全文缓存占用随当前文件大小变化。

同步位置复用预编译语句并批量写入。文件内容相同时跳过快照写入和位置更新，仍重新读取源码检查变化。保持 mtime 不变的编辑也能被检测到。历史和快照查询使用只读事务，当前评论的同步及修改使用 `BEGIN IMMEDIATE`。

索引加速有额外的临时分配成本，尤其是大型 diff 的行切分与多模式搜索。报告同时记录分配量；定位耗时降低不代表每种场景都分配更少。模式长度和候选缓存有上限，全文快照依然会持久保留。

## 复测

优化前的 JAR 与可执行文件需要在重新构建前另存。当前项目的基线副本位于 `target/performance/`，清理构建目录前请复制到其他位置。

```sh
python3 scripts/benchmark.py \
  --baseline-jar target/performance/baseline.jar \
  --jar comment-cli/target/comment-cli-0.1.0-SNAPSHOT.jar \
  --output target/performance/chinese-algorithms.json \
  --warmup 20 --samples 20 --forks 2 \
  --cases anchor-chinese-prefix anchor-chinese-wide anchor-chinese-wide-internal \
          anchor-chinese-repeat anchor-chinese-rewrite anchor-chinese-small-many \
          anchor-chinese-nearmiss anchor-emoji-prefix

python3 scripts/benchmark.py \
  --baseline-jar target/performance/baseline.jar \
  --jar comment-cli/target/comment-cli-0.1.0-SNAPSHOT.jar \
  --output target/performance/chinese-storage.json \
  --warmup 5 --samples 10 --forks 2 \
  --cases service-chinese-unchanged service-chinese-changed service-chinese-get

mvn -Pnative-release verify
python3 scripts/native_benchmark.py \
  --before target/performance/baseline-mcp \
  --after comment-mcp/target/llm-comment-mcp \
  --output target/performance/chinese-native.json \
  --warmup 2 --samples 5 --forks 2 \
  --cases list-changed get-nearmiss
```

## 行为验证

51 项测试通过，覆盖非贪婪范围、历史不可变性、跨进程版本冲突、中文与增补平面字符、重复文本歧义、文件缺失及恢复、固定 mtime 编辑检测、快照查询并发读取。`-O0`、`-O3` 的 CLI 和 MCP 集成检查，以及两个入口共用数据库的检查均通过。
