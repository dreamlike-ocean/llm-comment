# 桌面端设计与构建

`comment-desktop` 使用 JavaFX 标准控件和 Material 风格 CSS，直接调用 `comment-core`。界面包含目录浏览、只读源码、当前评论和修改历史。UI 与 CLI、MCP 共用工作区、快照、范围迁移和 SQLite 事务。

## 界面与状态

界面分三栏：左侧逐层加载目录并过滤文件名，中间显示源码和行选区，右侧编辑评论或查看历史。浅色与深色主题共用 CSS 颜色变量，按钮、评论卡片、输入框采用统一圆角与间距。当前未引入 MaterialFX、FXML 或 WebView。

`WorkspaceModel` 封装 core API 和目录访问，不依赖 JavaFX。目录一次只加载一层，过滤 `.git`、`.idea`、`target`、`.llm-comments`、当前数据库及其辅助文件，不跟随目录符号链接。文件符号链接的实际目标需要在工作区内。

`SourceView` 使用固定行高的 `ListView`，仅创建可见行控件。`SourceLines` 保存 UTF-16 offset 与原始换行，支持中文、emoji、LF、CRLF、CR、空文件及末尾空行。点击和 Shift 选择形成连续行范围，复制保留范围内原始换行。超过 10,000 个 UTF-16 单元的单行限制显示长度，并提示可复制全文；截断不改变真实选区或复制内容。

`DesktopApplication` 在 JavaFX 线程处理界面状态，通过单个后台执行器读取文件、执行 diff 和数据库操作。任务运行期间禁用可能冲突的操作，回调校验请求序号，窗口退出后不再回写 UI。切换文件或工作区、切换评论、关闭窗口时检查未保存的正文及作者变更。

## 快照与并发

浏览文件调用 `CommentService.inspect`，在一个事务中同步源码并返回 `FileComments(snapshot, missing, comments)`。源码与行标记使用相同快照，避免分别读取文件和评论时混用版本。

新增评论携带屏幕上快照的 `expectedFileVersionId`。如果选择第 250 行后，外部程序在前面新增了函数，保存会返回 `FILE_CONFLICT`；界面保留草稿，要求刷新并重新选择范围。当前评论修改携带原 `baseRevisionId`，CLI、MCP 或另一窗口已修改正文时返回 `CONFLICT`，不会覆盖对方版本。

刷新时，已有评论通过 core 的 diff/offset 规则移动位置。刷新不丢弃未保存正文，也不将草稿的基准版本悄悄换成新版本。历史页面读取节点引用的快照及当时行范围，点击“返回当前文件”恢复当前源码。

删除追加历史节点，默认从当前列表隐藏；勾选“显示已删除”后仍可查阅历史。源码缺失时显示最后保存的快照，已有评论和历史继续保留。

## 构建方式

```sh
# JVM 测试和 JAR
mvn -pl comment-desktop -am verify
java --enable-native-access=ALL-UNNAMED \
  -jar comment-desktop/target/comment-desktop-0.1.0-SNAPSHOT.jar

# 原生构建和完整界面冒烟测试
mvn -pl comment-desktop -am -Pnative-test verify
mvn -pl comment-desktop -am -Pnative-release verify

# 直接运行原生产物
comment-desktop/target/llm-comment-desktop
```

启动参数为可选的 `--workspace PATH`、`--database PATH`、`--file FILE` 和 `--help`。工作区发现规则与 CLI 相同。图形冒烟测试使用 `--smoke-test`，可附加 `--screenshot PATH`；测试数据全部在临时工作区中生成。

原生构建当前实现 Linux x64。需要 GraalVM JDK 25、Maven、Python 3、GCC、binutils 的 `objcopy`、`pkg-config`，以及 GTK3/X11/OpenGL 等开发库。脚本在 Debian/Ubuntu 缺少开发包时通过 `apt download`、`dpkg-deb -x` 解包到用户缓存，不需要 sudo。其他发行版需预装对应开发库。

`verify` 阶段实际显示 JavaFX 窗口，需要可用的 `DISPLAY`；无图形会话的 CI 可在 Xvfb 中运行。仅编译使用 `package`，该命令不执行图形冒烟测试。`mvn verify` 的普通 JVM 测试不启动 GUI；JAR 的完整界面测试需要另行运行 `--smoke-test`。

## Native Image 兼容处理

使用的版本固定如下：

| 组件 | 版本及用途 |
| --- | --- |
| Java / Native Image | Oracle GraalVM 25.0.2，应用和 core 使用 Java 25 |
| Maven OpenJFX | 21.0.8，用于编译和 JVM 运行 |
| Gluon JavaFX 静态 SDK | `21-ea+11.3`，原生构建同时使用其 JavaFX 类与静态库 |
| Gluon Substrate | 0.0.69，提供 Native Image Feature、JNI、反射和资源配置 |

SDK 与 Substrate 文件固定 SHA-256。原生构建排除 Maven 的 OpenJFX JAR，换用静态 SDK 中配套的 `base`、`graphics`、`controls` JAR，避免 Java 类与原生库版本不一致。界面使用两者都支持的标准 API；JVM 和原生界面分别验证。

GluonFX 旧链接器不能直接识别 GraalVM 25 的静态库布局，因此本项目通过 `scripts/build_desktop_native.py` 调用 `native-image`，不使用 GluonFX Maven 插件。脚本始终使用 Maven 所在 JDK 的 Native Image 与 JNI 头文件，显式设置 `--no-fallback`、`-O0` 或 `-O3`。SQLite JDBC 自带的 Feature 继续嵌入 SQLite JNI 库。

另有两处本地兼容处理：

1. `objcopy` 只对构建目录中的 JavaFX 静态库副本重命名 `JNI_OnLoad_*`。C 包装函数执行原初始化后，把成功返回的 JNI 1.6 版本声明提升至静态 JNI 所要求的 1.8；初始化失败仍返回原错误。
2. GCC 驱动适配器在 Native Image 生成的符号导出表中增加 `Java_*` 和 `JNI_*`，供 JavaFX 查找静态 JNI 入口。

完整构建命令、SDK 版本与校验值记录在 `comment-desktop/target/desktop-native/build-recipe.json`。原始 JDK 和 SDK 不改写。这是本项目实测的兼容组合，升级 GraalVM、JavaFX 或 Substrate 后需要重新执行原生界面测试。

运行时不需要 JDK，仍需 GTK3、X11、OpenGL、字体及可写临时目录。原生可执行文件并非完全静态链接。详细链接适配见[原生构建说明](../comment-desktop/src/main/native/README.md)。

## 开源依赖与发布材料

构建只下载公开 SDK、Maven 构件与源码，不使用 Gluon 账户、商业组件或付费服务。`target/desktop-native/licenses` 收集 JavaFX SDK 自带的法律文件、Substrate LICENSE、所用 Feature 源码及来源链接。发布时应随可执行文件保留该目录，并按项目最终许可证处理源码发布；构建脚本不替项目选择根许可证。

来源：[OpenJFX](https://github.com/openjdk/jfx)、[Gluon Substrate 0.0.69](https://github.com/gluonhq/substrate/tree/0.0.69)、[JNI 静态链接规范](https://docs.oracle.com/en/java/javase/25/docs/specs/jni/invocation.html#support-for-statically-linked-libraries)。

## 验证与限制

2026-10-25 在 Linux x64、Oracle GraalVM 25.0.2 上完成 JVM 界面测试、`-O0` 原生构建及界面测试、全工程 `-O3` 发布构建。72 项 JUnit 测试及 CLI/MCP 共享数据库检查通过。桌面 `-O3` ELF 约 76.6 MiB；清除 `JAVA_HOME`、`GRAALVM_HOME`、`JDK_HOME` 并将 `PATH` 指向不存在的目录后，仍通过完整界面测试。动态依赖为系统图形及 C/C++ 库，不依赖 `libjvm`。

下图来自该 `-O3` 产物的临时工作区测试：

![原生桌面界面](desktop.png)

行为测试包括工作区路径、目录与符号链接边界、快照一致性、跨实例正文冲突、CRLF/中文/emoji 行模型。`--smoke-test` 通过真实控件执行选行、新增评论、刷新后的位移、作者与正文修改、历史源码查看、过期选区拒绝、删除及历史保留，同时断言源码字节未被修改。

当前尚未实现语法高亮、自动文件监听、跨文件重命名跟踪或安装包。中文显示和程序化输入有测试，实际中文输入法组合输入、HiDPI、多显示器及 Windows/macOS 原生构建仍需在对应环境验证。
