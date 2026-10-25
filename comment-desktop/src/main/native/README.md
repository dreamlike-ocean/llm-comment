# JavaFX 原生构建

当前支持 Linux x64、GraalVM 25 与系统 GTK3/X11。Maven 所用 JDK 必须提供 `native-image`；Python 3、GCC、binutils 的 `objcopy`、`pkg-config` 需要在 PATH 中。图形冒烟测试需要可用的 `DISPLAY`。

```sh
mvn -pl comment-desktop -am -Pnative-test verify
mvn -pl comment-desktop -am -Pnative-release verify
```

两个配置分别实际传入 `-O0`、`-O3`，都使用 `--no-fallback`，并运行 `--smoke-test`。可执行文件为 `comment-desktop/target/llm-comment-desktop`。完整命令、固定 SDK 版本与 SHA-256 写入 `target/desktop-native/build-recipe.json`。SQLite JDBC 自带的 Native Image Feature 继续负责嵌入平台 JNI 库。

构建脚本下载固定版本的 Gluon JavaFX 静态 SDK `21-ea+11.3` 和 Substrate `0.0.69`，复用其 JNI、反射及资源配置。运行时与静态库采用 SDK 中同一版本的 JavaFX 类；Maven 的 JavaFX 21.0.8 仅用于编译及 JVM 运行。下载缓存位于 `${XDG_CACHE_HOME:-$HOME/.cache}/llm-comments/desktop-native-v1`，不会改写 JDK。缺少开发库时，在 Debian/Ubuntu 上通过 `apt download` 和 `dpkg-deb -x` 解到用户缓存，不执行系统安装；其他系统需要预先安装脚本报出的开发包。

GluonFX 的旧链接器不能识别 GraalVM 25 的静态库目录，因此脚本直接调用 Native Image，并做两项兼容处理：

- `jni-version.c` 调用原始 JavaFX 静态库初始化函数后，将成功返回的 JNI 1.6 版本声明提升到静态 JNI 要求的 1.8。原函数及失败返回值保留；`objcopy` 仅修改 `target` 内的库副本。
- `desktop_native_gcc.py` 给 Graal 生成的导出表增加 `Java_*` 和 `JNI_*`，供 JavaFX 静态 JNI 查找。它随后执行系统 `/usr/bin/gcc`；不改写原始 SDK 或 JDK。

第三方版权与许可文件复制到 `target/desktop-native/licenses`，包括 SDK 随附法律文件、Substrate 的 LICENSE、所用 GluonFeature 源码及来源链接。发布可执行文件时需要一并保留这些材料；此构建配置不替项目选择根许可证。

桌面资源准备还会下载固定 SHA-256 的中文、生僻字及单色 emoji 字体，嵌入可执行文件。字体在启动时解压到临时目录，Fontconfig 的进程内配置通过 Java 25 FFM 调用；`reachability-metadata.json` 注册 downcall 签名。Linux 仍需系统 `libfontconfig.so.1`，不需要另行安装这些内置字体。

`prepare_desktop_javafx.py` 生成修订过的 OpenJFX 21.0.8 `PangoGlyphLayout`，修正不可见字形、补充平面字符的 UTF-16 索引及换行拆分后的 UTF-8 缓存。应用 classes 放在 JavaFX JAR 前，因此 JVM 和 Native Image 使用相同修补。字体许可证和排版兼容源码分别保留在发布目录的 `licenses/fonts` 与 `licenses/javafx-compat`。升级 JavaFX 后须重新验证字形光栅、完整混排及光标命中。

`DesktopInputMethods` 在首个窗口创建前，通过 FFM 调用系统 Xlib 的 `XSetLocaleModifiers("")`，按现有 `XMODIFIERS` 初始化 Glass 使用的 XIM。对应 downcall 签名已注册到 Native Image 元数据。已运行 fcitx5 拼音的桌面会话可通过 `--ime-test 1` 或 `--ime-test 1.75` 检查实际中文输入；此检查发送系统按键，需要测试窗口持续获得焦点。

来源：[Gluon Substrate](https://github.com/gluonhq/substrate/tree/0.0.69)、[JNI 静态库调用规范](https://docs.oracle.com/en/java/javase/25/docs/specs/jni/invocation.html#support-for-statically-linked-libraries)。
