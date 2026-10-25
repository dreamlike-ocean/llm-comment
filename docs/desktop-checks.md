# 独立桌面检查

诊断工具位于 `comment-desktop/checks/java`，由 Maven 的 `build-helper:add-test-source` 编译到 `target/test-classes`。正常 JUnit 测试继续位于 `src/test/java`。发布 JAR 和生产 Native Image 仅使用 `target/classes`，不包含这些检查工具。

生产入口只支持工作区、数据库、文件和显示参数。`--smoke-test`、`--screenshot`、`--ime-test` 已从生产入口移除，统一由独立 `DesktopCheckMain` 解析。检查子类覆盖窗口初始化后的 `openInitialWorkspace()` 生命周期，直接启动 smoke；不会打开启动目录或用户工作区。检查入口不接受 `--workspace`、`--database` 或 `--file`。

先编译 JAR 和检查类，再默认以 JVM 运行：

```sh
mvn -pl comment-desktop -am package
python3 scripts/check_desktop.py --smoke-test
python3 scripts/check_desktop.py --smoke-test --scale 1.75 --screenshot /tmp/desktop-check.png
python3 scripts/check_desktop.py --scroll 100000 dense
```

可通过 `--java-home PATH` 指定 JVM，通过可重复的 `--vm-arg=-Xms256m --vm-arg=-Xmx2g` 为 JVM 或原生检查程序指定相同堆参数。smoke 在临时工作区验证评论、历史、范围同步、目录树、字体、布局、交互和公开输入法候选锚点，并清理临时内容；截图只写到明确指定的路径。滚动探针构造内存样本并打开独立窗口。各平台使用相同输入控件锚点、几何与缩放规则。

原生 `package` 仍仅构建生产程序。`mvn -Pnative-test verify` 和 `mvn -Pnative-release verify` 在验证阶段另外构建 `target/llm-comment-desktop-checks`，再运行该检查程序。两个 profile 的 checker 分别使用相同的 `-O0`、`-O3` 参数。checker 的构建文件、许可证和 `build-recipe.json` 放在 `target/desktop-native-checks`，不覆盖生产目录 `target/desktop-native`。

```sh
python3 scripts/check_desktop.py --native --smoke-test
python3 scripts/check_desktop.py --native --scroll 100000 dense
```

已有编译结果时也可手动构建 checker：

```sh
python3 scripts/build_desktop_native.py --module comment-desktop --java-home "$JAVA_HOME" --optimization=-O3 --checks
```

GUI 检查需要图形会话，自动化 smoke 和滚动探针可在 Xvfb 中运行。Linux 系统输入法检查另需已运行的 fcitx5 拼音及 `xwininfo`，并发送系统按键，因此只应在可持续获得焦点的临时检查窗口中执行：

```sh
python3 scripts/check_desktop.py --ime-test 1.75 /tmp/ime-check.png
python3 scripts/check_desktop.py --native --ime-test 1.75 /tmp/ime-check-native.png
```

`InputMethodPlacementCheck` 不发送系统按键，只检查 JavaFX 的公开接口；真实输入法提交和候选窗口由 `ImeCheck` 单独验证。两者验证内容不同，不能互相替代。移除旧诊断类后第一次验证请使用 `mvn clean`，避免既有 `target/classes` 残留旧 class。
