# 文件树图标

文件树使用 [Material Icon Theme](https://github.com/material-extensions/vscode-material-icon-theme) 的开源 SVG，固定到提交 `3f585f46ad50134658fee6c63cc8f41ef353d32b`。项目只保留目前会使用的 20 个图标，不引入完整主题或运行时下载。完整 MIT 许可证、来源和逐文件 SHA-256 随资源保存在 `comment-desktop/src/main/resources/io/github/dreamlike/llmcomment/desktop/file-icons/` 下的 `LICENSE` 和 `SOURCE.md`。

Java 代码只负责文件类型映射、资源加载和缩放，没有手写图标路径。目录图标在上游由生成器生成；本项目按该固定版本生成器的原路径和默认颜色导出 `folder.svg`、`folder-open.svg`，具体生成位置记在 `SOURCE.md`。其他 SVG 与上游原文件完全一致，保留原有填色和渐变。

映射先比较不区分大小写的完整文件名，再匹配最后一个扩展名，最后回退通用文档。例如 `pom.xml` 显示 Maven 图标；未知扩展名、无扩展名普通文件显示通用文档。目录随展开状态切换图标。识别只使用目录条目的文件名，不读取源码。

关联方式参考 [VS Code File Icon Theme](https://code.visualstudio.com/api/extension-guides/file-icon-theme) 的文件名、扩展名和默认图标优先级，以及 [IntelliJ 文件类型图标](https://plugins.jetbrains.com/docs/intellij/registering-file-type.html)。图标资源本身来自 Material Icon Theme，并非本项目仿绘。

`SvgFileIcon` 只加载打包资源中实际使用的 `svg/path/defs/linearGradient/stop` 子集，通过 JavaFX `SVGPath` 和 `LinearGradient` 渲染。加载器禁用 DTD、实体和外部 schema，不执行脚本或加载外链。解析结果只缓存不可变路径数据及画笔，每个单元格创建独立场景节点。未支持的元素会明确失败，升级图标时需要运行全部图标加载测试。

图标根据 SVG 原始 viewBox 等比缩放和居中，包含 JSON 图标的负坐标画布。单元格尺寸随字号变化，场景继续由 `ScaledPane` 统一缩放。图标不依赖 emoji、图标字体、WebView、Batik 或本地屏幕尺寸规则。目录展开监听仍在单元格换行时解除，并使用弱监听避免回收泄漏。

JVM 与 Native Image 使用相同 SVG 资源和 JavaFX 场景图。原生资源配置包含 `io/github/dreamlike/llmcomment/desktop/file-icons/` 目录中的 SVG、LICENSE、SOURCE.md；发布时同时保留该 MIT 许可证。加载器固定使用 JDK 的 `DocumentBuilderFactory.newDefaultInstance()`，不需要反射注册应用类。

`FileTypeIconsTest` 检查所有资源、独立节点、原始颜色与渐变，以及负 viewBox 的缩放。`FileTreeCheck` 在桌面 smoke 中检查映射、资源加载和独立节点。JVM 与 `-O3` 原生桌面 smoke 均已通过，覆盖全部图标加载、目录展开、筛选与刷新。JVM 最大化界面截图见 [desktop.png](desktop.png)。
