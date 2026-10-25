# 文件树图标

文件树沿用 IDE 的按文件类型识别方式，先比较不区分大小写的完整文件名，再匹配最后一个扩展名，最后回退通用文件。例如 `pom.xml` 显示 Maven 的 M 标记，不显示 XML 尖括号。无扩展名的普通文件和未知扩展名均有通用图标；目录使用独立的展开、折叠图形。这里只比较目录条目的文件名，不打开源码、不推断文件内容。

实现参考：

- [VS Code File Icon Theme](https://code.visualstudio.com/api/extension-guides/file-icon-theme)：文件名、扩展名及通用图标的关联方式，以及展开目录图标。
- [IntelliJ 注册文件类型](https://plugins.jetbrains.com/docs/intellij/registering-file-type.html)：文件类型提供各自图标。
- [IntelliJ 图标规范](https://plugins.jetbrains.com/docs/intellij/icons.html)：文件类型以 16×16 为设计尺寸。

本项目没有复制上述产品的图标资源、商标或主题素材。`FileTypeIcons` 中的几何路径均为本项目自行绘制：折角文件轮廓加语言字母或语法标记，目录使用文件夹轮廓。JavaFX `SVGPath` 直接渲染，既不依赖 emoji、图标字体和图片解码，也不引入第三方图标库；Native Image 无需增加反射或资源配置。

颜色引用现有 Material 主题的 `-app-primary` 和 `-app-muted`，随深浅主题切换。图标尺寸绑定对应 TreeCell 字号，场景继续由 `ScaledPane` 统一缩放。单元格各自持有图形；换节点时解除旧目录的展开监听，监听同时采用弱引用防止虚拟化回收时泄漏。

`FileTreeCheck` 的桌面 smoke 检查覆盖文件名优先级、扩展名大小写、中文文件名、未知类型和每次独立创建矢量节点。实际界面还应观察展开/折叠、滚动复用、筛选及刷新后图标，并在深浅主题和不同字号、缩放下检查对齐和裁切。
