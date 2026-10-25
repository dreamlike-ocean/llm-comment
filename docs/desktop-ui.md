# 桌面端选型

评估日期：2026-10-25。当前工程使用 Java 25、Maven、SQLite，桌面端希望采用 Google Material Design 风格，并尽可能发布 GraalVM Native Image。

建议优先验证 JavaFX 标准控件与 Material 风格 CSS。它适合继续使用 Java 和 Maven，源码浏览、选区高亮、评论面板也可以放在同一个场景中。Native Image 是需要原型实测的条件，当前 CLI/MCP 的构建成功不能证明 GUI 同样可行。

## 三种方案

| 方案 | Java / Maven | Material 外观 | Native Image |
| --- | --- | --- | --- |
| JavaFX | 可直接复用现有工程 | 用 CSS 定义主题，按需加入 Material 控件 | Gluon/Substrate 提供构建路线；Java 25、JavaFX 和平台库的具体组合需验证 |
| Swing + FlatLaf | 可直接复用现有工程 | 默认接近 IntelliJ，需要另做 Material 配色、形状及交互 | Oracle 说明 Linux、Windows 可配合可达性元数据使用，macOS 暂不支持 AWT |
| Compose Desktop | UI 采用 Kotlin 和 Compose 编译器，官方使用 Gradle | Material 组件路线最直接 | 官方发行方案通过 jpackage 打包 JVM；未找到对当前 GraalVM 组合的官方支持承诺 |

JavaFX 25 要求 JDK 23 或更高，Java 25 满足其 JVM 运行条件。Linux 需要 GTK 3.20 或更高。这些要求来自 [OpenJFX 25 发布说明](https://openjfx.io/highlights/25/)，不代表同版本已经通过 Native Image 验证。

[Gluon Substrate](https://github.com/gluonhq/substrate) 会处理 JavaFX 的平台代码、资源和原生链接，并提供 Maven 插件入口。原型需要固定 JDK、JavaFX、插件和平台库版本，测试现有 Java 25 core 能否一起编译。

具体版本仍有待验证：[GluonFX Maven 1.0.29](https://github.com/gluonhq/gluonfx-maven-plugin/blob/1.0.29/pom.xml) 依赖 Substrate 0.0.69，评估时 [Substrate 的默认配置](https://github.com/gluonhq/substrate/blob/master/src/main/java/com/gluonhq/substrate/Constants.java) 仍列 Java 静态 SDK 18、JavaFX 静态 SDK 21 的预发布版本。升级普通 OpenJFX Maven 依赖并不等于更换这些静态库。目前的资料不足以确认 GraalVM 25.0.2 与 JavaFX 25 的组合已经验证，也不足以断言其不可用。

[MaterialFX](https://github.com/palexdev/MaterialFX) 提供 JavaFX Material 控件，但其存在不能保证 Material 3 的全部行为或本项目的 Native Image 兼容性。先用标准控件做主题，在实际需要某个组件时再验证并引入相应依赖。

[FlatLaf 官方说明](https://www.formdev.com/flatlaf/)明确其风格主要受 IntelliJ/Darcula 启发。Swing 的原生编译具有平台差异：Oracle Graal 维护者在 [2026-02-24 的说明](https://github.com/oracle/graal/issues/13032)中指出，AWT 在 Linux、Windows 上需要可达性元数据，macOS 当时尚不支持。如果 Linux Native Image 成为首要约束，Swing 是值得同时验证的备选。

[Compose 官方桌面发行文档](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html)说明安装包由 jpackage 创建，使用 jlink 裁剪并内含 Java 运行时。用户可以在未安装 JDK 的机器上运行这种安装包，但程序仍在 JVM 上执行。选择 Compose 意味着接受 Kotlin UI 及对应构建工具，并单独承担 GraalVM 支持验证。

## 建议的桌面结构

桌面端作为后续独立的 `comment-desktop` 模块，直接依赖 `comment-core`。核心的工作区解析、SQLite 事务、定位和历史 API 继续由 CLI、MCP、桌面端共用。

界面采用三栏：左侧文件树和搜索，中间只读源码及行号、选区和评论标记，右侧评论正文、历史版本及定位状态。选中文本后显示实际行范围；创建和更新调用 core，并用返回的 `fileVersionId` 与正文版本刷新界面。已经失效或歧义的评论保留在列表中，显示保存的原文和状态。

Material 主题先定义浅色、深色的颜色、字号、圆角和焦点样式，再实现按钮、输入框、导航及评论卡片。评论历史需要可比较的节点列表，大文件需要虚拟化显示。SQLite、文件读取和 diff 在后台执行，切换工作区或文件后丢弃此前请求的过期结果。

## Native Image 原型验证

在确定完整控件库之前，用最小 JavaFX 界面验证以下流程：

1. 打开工作区，读取中文和 emoji 源码，选行创建评论，查询历史，再由外部修改源码检查位置平移。
2. 检查中文输入法、复制粘贴、文件选择器、CRLF、大文件滚动、HiDPI 和深浅主题。
3. 使用 Java 25 验证 JVM 运行，再以 `--no-fallback -O0` 构建 GUI 原型；通过后验证 `-O3`。
4. 在没有 JDK 的 Linux x64 环境运行，确认字体、GTK/X11、JavaFX 平台库及 SQLite JNI 资源均可加载。记录完整发布目录大小、首次显示窗口耗时和 RSS。

如果当前 JavaFX 工具链无法编译 Java 25 core，先用相同业务流程验证 Swing + FlatLaf，再决定是否接受 JVM 安装包作为发布形式。GUI 原型尚未实施，本文的推荐基于官方资料和当前项目约束。
