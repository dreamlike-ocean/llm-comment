# 项目协作约定

本项目提供本地代码评论的无头接口和 JavaFX 桌面端。使用 Java 25，评论和文件快照存入 SQLite，功能独立于 Git 和 IDE。源码文件只能读取，评论操作不能修改源码。

## 模块

- `comment-core`：公开 Java API、SQLite 存储、文件快照、评论版本及范围定位。
- `comment-cli`：命令行入口，调用 core，输出结构化 JSON。
- `comment-mcp`：MCP stdio 服务，调用 core。标准输出只写协议消息，日志写标准错误。
- `comment-desktop`：JavaFX 标准控件及 Material 风格 CSS，提供只读源码浏览、评论编辑和历史查看。
- 根 `pom.xml` 聚合四个模块，集中管理依赖及构建参数。CLI、MCP、桌面端分别依赖 core，core 不依赖 UI。
- 工作区发现和路径解析共用 core 的 `Workspace`。省略工作区时向上查找最近的 `.llm-comments`，找不到时采用启动目录；指定工作区时直接采用该目录。文件、正文文件和数据库的相对路径均以工作区为基准。

## 定位与历史

- 对外行号从 1 开始，起止行均包含。内部 offset 使用 Java UTF-16 单元，范围为 `[startOffset, endOffset)`。
- 参考 IntelliJ `RangeMarker` 的行为：前置编辑平移范围，内部编辑扩缩范围，范围首尾插入采用固定的非贪婪规则。整段替换结合 diff 与原文校验。
- 同一文件共享上次处理的快照。访问评论时检查源码变化，批量更新该文件的评论位置。文件监听不是正确性的前提。
- 无法确认位置时返回失效或歧义状态，保留评论。已经失效的标记可以在文本恢复后重新定位。
- `commentId` 稳定；修改和删除追加不可变 `revisionId`，记录父版本、作者、时间、正文和当时的文件版本、范围。
- offset 自动变化不新增正文版本。历史版本及其引用的文件快照需要保留。
- 使用 SQLite 事务协调跨进程写入。读取当前状态、同步定位、追加版本、更新当前指针的操作在 `BEGIN IMMEDIATE` 事务中完成。更新校验 `baseRevisionId`。仅查询历史或快照使用只读事务，不争用写锁。
- 性能优化必须保留定位语义。按文件复用当前事务内的快照和行索引，不能凭 mtime 跳过源码校验；批量查询避免逐评论重复读取全文。性能基准的数据构造与计时分离，前后版本使用相同数据与参数。
- diff 使用显式工作栈，字符比较和前沿推进设计算量预算。超限块交给原文恢复流程；复杂输入回归覆盖小 JVM 栈、偏斜长度和中文重复文本。
- 源码由外部程序修改，SQLite 锁不覆盖源码文件。输出位置对应明确的文件快照，读取失败不得销毁旧状态。
- 桌面端通过 `inspect` 在同一事务取得文件快照和评论，创建评论携带选区对应的 `expectedFileVersionId`；源码已变化时返回 `FILE_CONFLICT`，保留草稿。正文更新继续检查 `baseRevisionId`。

## 桌面端

- 文件、SQLite 和 diff 操作在单个后台执行器中串行处理。JavaFX 线程只更新控件；异步结果校验请求序号，过期结果不覆盖当前界面。
- 源码按行虚拟化显示，保留 UTF-16、中文、emoji、CRLF 和末尾空行语义。显示截断不得影响复制及范围计算。
- 历史评论使用其引用的历史快照。切换文件、离开草稿或关闭窗口时检查未保存修改；作者变更同样属于修改。
- 目录逐层加载，不递归遍历工作区，不跟随目录符号链接。隐藏内部数据库、构建目录和版本控制目录。

## 构建与验证

- `mvn verify`：JVM 测试及 JAR 构建。
- `mvn -Pnative-test verify`：构建 `-O0` 原生程序，并执行原生集成检查。
- `mvn -Pnative-release verify`：构建 `-O3` 发布程序，并执行原生集成检查。
- 桌面原生构建当前支持 Linux x64，使用 Gluon 固定版本的 JavaFX 静态 SDK、Substrate 元数据和本地 JNI 兼容层，见 `comment-desktop/src/main/native/README.md`。需要 Python 3、GCC、objcopy、pkg-config 和 GTK3/X11 开发库；Debian/Ubuntu 缺少开发包时允许下载解包到用户缓存，不修改系统或 JDK。
- 桌面原生 `verify` 会实际打开窗口，需要 `DISPLAY`；无图形环境可通过 Xvfb 执行验证。仅构建使用 `package`，不能将其报告为通过界面测试。只构建无头模块时加 `-pl comment-cli,comment-mcp -am`。
- 第三方下载文件固定 SHA-256，发布桌面程序时保留 `target/desktop-native/licenses`；不要擅自替项目选定根许可证。
- Native Image 使用 `--no-fallback`。SQLite JDBC 的平台 JNI 库嵌入可执行文件，启动时解压加载。
- 优先使用 Xerial SQLite JDBC；仅在已证实不支持目标场景时考虑 SQLite C API 与 FFM。
- 定位、历史不可变性、事务冲突和 CLI/MCP 协议需要有行为测试。覆盖中文、CRLF、插入/删除/替换、文件缺失及恢复、重复文本等情况。桌面 `--smoke-test` 使用临时工作区验证完整界面流程，不操作用户工作区。

## 实现与协作

- 注释和项目文档使用中文，说明具体行为及约束。
- 用户已授权使用 GPT-6.1-sol 子代理并行实现。分配文件所有权，修改公共 API 前通知其他代理。
- 不覆盖其他代理的修改，不提交临时数据库、Native Image 产物或第三方下载文件。
- 测试失败需要修复后复跑；发布构建必须确认实际使用 `-O3`。
