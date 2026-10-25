# 超长文件源码视图性能

## 探针与计时边界

`SourcePerformanceProbe` 在内存中构造样本，并打开独立的 JavaFX 窗口。构造 10 万及 100 万行样本后再启动计时；正文循环包含空行、简繁中文、补充平面汉字、组合音标、肤色/ZWJ emoji、CRLF，并保留末尾空行。

每个规模分别运行普通场景和 `dense` 场景。普通场景没有评论；`dense` 包含 32 条评论，其中 8 条覆盖全文，其余 24 条交叉覆盖文件的一半，每条正文有 20 万个 UTF-16 单元。评论共用不可变正文，以控制测试数据本身的内存占用。

探针使用 `SourceView`、应用字体、Material CSS 和 1280×900 场景。行高由字体及 CSS 决定，单行显示阈值为 10000 字符。

- `init_ms`：`show` 开始到首次 CSS/布局完成，包含行切分、评论标记索引和可见单元初始化；不包含字体加载及窗口创建。
- `jump_*`：预热 20 个 pulse 后，在 8 个不同 pulse 分别跳到文件末尾、中部、开头等位置；计时包括 `scrollTo` 和实际 CSS/布局，并断言目标单元已经生成。8 个样本的 p95 等于最大值。
- `scroll_*`：向真实 `.virtual-flow` 节点发送 180 个每次向下 64 像素的 `ScrollEvent`，每个 pulse 一次；计时包括事件处理及布局，并断言实际前进超过 100 行。
- `frame_*`：连续滚动阶段的 `AnimationTimer` 间隔，包含事件、布局、pulse/render 调度及上一帧的阻塞；`scrollTo` 的同步耗时另由跳转指标记录。测量包含从最后一次跳转进入滚动的间隔，因此能看到跳转后的长帧。
- `heap_mib`：测量后请求一次 GC，再读取 JVM 已用堆；包含输入正文、行模型、JavaFX、字体及控件。进程 RSS 和峰值内存需另行采集。
- `cells`：探针末尾场景图内的源码单元数。10 万与 100 万行都为 29，保持按可见行虚拟化。

计时后还会校验混排选区的原始 CRLF/UTF-16 文本，并实际打开评论 Tooltip，确认起始于该行的每条评论都有摘要。字号、CSS 留白、空行布局和交叉评论选择另由 `SourceLayoutCheck`、`SourceInteractionCheck` 验证。

## 2026-10-25 实测

下表记录 Tooltip 与 `VirtualFlow` 优化时的数据，当时评论范围内的每一行都显示标记。后续界面改为仅在起始行显示标记，并用 `CommentStartIndex` 替代区间树；这些历史数据不作为新标记逻辑的测量结果。

以下均为 JVM 测量，环境为 Linux x64、Oracle GraalVM Java 25.0.2、JavaFX 21.0.8、同一 DISPLAY，JVM 参数为 `-Xms256m -Xmx2g`。原生 O3 程序的功能回归与这些数据分开记录，JVM 数字不能用作原生程序的性能结论。测量使用日常桌面会话，未隔离其他进程、JIT 编译或运行期间的 GC；帧间隔包含这些停顿及渲染调度噪声，测量后的主动 GC 位于计时范围之外。每个配置各启动一次新 JVM，前后数据、参数和探针相同，按基线、优化版成对顺序执行。基线使用修改前的 `SourceView`，优化版仅替换该类，其他类、资源均来自同一个桌面 JAR。

| 行数 / 场景 | 版本 | 初始化 ms | 跳转最大 ms | 滚动 p95 ms | 最坏帧 ms | GC 后堆 MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 10 万 / 普通 | 基线 | 146.195 | 40.626 | 3.418 | 22.354 | 29.2 |
| 10 万 / 普通 | 优化 | 150.195 | 37.226 | 2.468 | 25.338 | 29.6 |
| 10 万 / 密集评论 | 基线 | 238.815 | 475.937 | 13.425 | 344.629 | 30.4 |
| 10 万 / 密集评论 | 优化 | 148.183 | 42.525 | 2.521 | 21.919 | 30.4 |
| 100 万 / 普通 | 基线 | 197.607 | 77.796 | 6.078 | 40.362 | 147.2 |
| 100 万 / 普通 | 优化 | 217.328 | 83.608 | 6.358 | 39.646 | 147.7 |
| 100 万 / 密集评论 | 基线 | 340.219 | 497.436 | 16.632 | 355.190 | 148.5 |
| 100 万 / 密集评论 | 优化 | 205.377 | 96.742 | 6.818 | 38.259 | 148.3 |

密集评论场景的跳转最大耗时分别降低约 91% 和 81%，滚动 p95 分别降低约 81% 和 59%。普通 100 万行场景没有观察到改善：滚动 p95 从 6.078 ms 变为 6.358 ms，初始化也有波动。单次运行不能推导稳定的细微差异；本次优化针对重复评论摘要工作。

100 万行仍需约 148 MiB JVM 堆，行模型及原文随文件规模增长。此次没有更改行存储模型，也没有把行切分移到后台；打开文件的同步初始化仍约 200 ms，最坏滚动帧仍接近 40 ms。

## 实现变化

原先每次虚拟单元更新都创建新 Tooltip，对所有覆盖评论的完整正文做两次换行替换，再截取前 100 字符。大正文与大范围评论组合后，滚动和跳转会反复扫描与当前显示无关的正文。

现在每个单元复用一个 Tooltip，在实际显示 Tooltip 时生成全部评论摘要；摘要先截取最多 100 个 UTF-16 单元，再替换换行，截断处保留完整代理对。单元缓存本行覆盖评论，点击标记时直接复用；`VirtualFlow` 对同一行及同一范围索引重复测量时复用已有控件内容，CSS 和字体变化仍由 JavaFX 重新布局。

范围索引仍为按评论数量占用空间的区间树，不展开为“范围行数 × 评论”的映射。交叉、包含、同范围及共用端点仍返回所有评论，菜单可逐条选择。源码显示阈值、原文选区及复制、字体和动态行高均保持原有语义。

## 运行方式

先执行 `mvn -pl comment-desktop -am package` 生成桌面 JAR 和独立检查类。检查源码位于 `comment-desktop/checks/java`；通过 `scripts/check_desktop.py` 运行。需要 DISPLAY，也可在安装了 Xvfb 的机器上使用独立虚拟 DISPLAY。输入法检查接收系统按键，运行时需要单独占用窗口焦点。

```sh
python3 scripts/check_desktop.py --vm-arg=-Xms256m --vm-arg=-Xmx2g --scroll 100000 plain
python3 scripts/check_desktop.py --vm-arg=-Xms256m --vm-arg=-Xmx2g --scroll 1000000 dense
```

`mvn -Pnative-release verify` 会另行生成 `-O3` 原生检查程序，随后可用同一脚本运行原生性能探针：

```sh
python3 scripts/check_desktop.py --native --vm-arg=-Xms256m --vm-arg=-Xmx2g --scroll 100000 plain
python3 scripts/check_desktop.py --native --vm-arg=-Xms256m --vm-arg=-Xmx2g --scroll 1000000 dense
```

脚本运行现成产物，构建步骤需要单独执行。

完整复现应运行 `100000 plain`、`100000 dense`、`1000000 plain`、`1000000 dense`。比较前后版本时，将两版 `SourceView.java` 分别 `javac` 到独立目录，把该目录放在同一个探针及桌面 JAR 的 classpath 前端；不要覆盖工作区源码或混用不同版本的测试数据。

本次原始数值，按“基线/优化版、10 万普通、10 万密集、100 万普通、100 万密集”排列：

```text
lines=100000 init_ms=146.195 jump_p95_ms=40.626 jump_max_ms=40.626 scroll_p95_ms=3.418 scroll_max_ms=7.467 frame_p95_ms=18.468 frame_max_ms=22.354 heap_mib=29.2 cells=29
lines=100000 init_ms=150.195 jump_p95_ms=37.226 jump_max_ms=37.226 scroll_p95_ms=2.468 scroll_max_ms=10.216 frame_p95_ms=18.357 frame_max_ms=25.338 heap_mib=29.6 cells=29
lines=100000 init_ms=238.815 jump_p95_ms=475.937 jump_max_ms=475.937 scroll_p95_ms=13.425 scroll_max_ms=15.565 frame_p95_ms=20.702 frame_max_ms=344.629 heap_mib=30.4 cells=29
lines=100000 init_ms=148.183 jump_p95_ms=42.525 jump_max_ms=42.525 scroll_p95_ms=2.521 scroll_max_ms=7.371 frame_p95_ms=17.837 frame_max_ms=21.919 heap_mib=30.4 cells=29
lines=1000000 init_ms=197.607 jump_p95_ms=77.796 jump_max_ms=77.796 scroll_p95_ms=6.078 scroll_max_ms=40.078 frame_p95_ms=17.164 frame_max_ms=40.362 heap_mib=147.2 cells=29
lines=1000000 init_ms=217.328 jump_p95_ms=83.608 jump_max_ms=83.608 scroll_p95_ms=6.358 scroll_max_ms=39.193 frame_p95_ms=17.769 frame_max_ms=39.646 heap_mib=147.7 cells=29
lines=1000000 init_ms=340.219 jump_p95_ms=497.436 jump_max_ms=497.436 scroll_p95_ms=16.632 scroll_max_ms=24.828 frame_p95_ms=20.774 frame_max_ms=355.190 heap_mib=148.5 cells=29
lines=1000000 init_ms=205.377 jump_p95_ms=96.742 jump_max_ms=96.742 scroll_p95_ms=6.818 scroll_max_ms=37.971 frame_p95_ms=19.322 frame_max_ms=38.259 heap_mib=148.3 cells=29
```

## 原生 O3 的静态 SDK 行高重算

独立原生探针暴露出 JVM 测量没有显示的退化：Gluon 静态 SDK `21-ea+11.3` 下，10 万行滚动 p95 约 21 ms，100 万行约 201 ms。普通和密集评论场景接近，说明重复扫描评论正文以外还有问题。以下原生基线已经包含前述 `SourceView` 摘要优化，与本页最初的 JVM 基线分开。

对静态 SDK 和 Maven JavaFX 21.0.8 的 `VirtualFlow` 做字节码比较，发现旧实现的 `resizeCell` 每次都调用总高度重算；`updateCellSize` 在行高相同的情况下也会重新扫描行高缓存、计算当前行和视口偏移。每轮更新多个可见单元时，这些计算反复从第一行遍历到当前位置或缓存末尾，耗时随文件行数增长。

当前构建从固定 SHA-256 的 OpenJFX 21.0.8 源码包生成 `VirtualFlow.java`，JVM 和原生均编译同一份上游源码。该版本在更新单元行高缓存时，已有行高确实发生变化才重新估算，并省去 `resizeCell` 末尾的重复重算。字体、CSS 和内容仍决定每行高度。具体实现见 [固定版本的 OpenJFX VirtualFlow 源码](https://github.com/openjdk/jfx21u/blob/3bbff12986916db4f9416853012a298f690ba43b/modules/javafx.controls/src/main/java/javafx/scene/control/skin/VirtualFlow.java)。

`prepare_desktop_javafx.py` 校验源码包 SHA-256，并保存原始及编译源码、OpenJFX 许可证和来源说明；这些材料随 JAR 及原生发布目录保留。其余 Gluon 静态 SDK 文件仍使用固定下载版本。

两版独立探针均使用 GraalVM Java 25.0.2、`--no-fallback -O3 -march=compatibility`、同一静态链接库及同一 `SourcePerformanceProbe`。构建输出确认 `optimization level: 3`。修复版在原构建命令的 classpath 前端增加从官方源码编译的 `VirtualFlow` 及嵌套类，两版均采用 Serial GC，运行参数均为 `-Xms256m -Xmx2g`。每个配置启动一次新进程，样本及 180 个滚动事件相同。计时在其他构建任务结束后进行。

| 行数 / 场景 | VirtualFlow 版本 | 初始化 ms | 跳转最大 ms | 滚动 p95 ms | 帧 p95 ms | 最坏帧 ms | GC 后堆 MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 10 万 / 普通 | 静态 SDK 旧版 | 23.251 | 36.454 | 21.162 | 21.254 | 43.959 | 20.7 |
| 10 万 / 普通 | 统一 21.0.8 | 23.066 | 5.597 | 1.738 | 17.314 | 23.230 | 21.2 |
| 10 万 / 密集评论 | 静态 SDK 旧版 | 23.895 | 35.896 | 20.948 | 21.120 | 42.617 | 21.2 |
| 10 万 / 密集评论 | 统一 21.0.8 | 22.548 | 5.550 | 1.864 | 17.820 | 28.528 | 21.7 |
| 100 万 / 普通 | 静态 SDK 旧版 | 151.605 | 327.942 | 201.077 | 201.312 | 208.119 | 121.9 |
| 100 万 / 普通 | 统一 21.0.8 | 148.572 | 21.874 | 7.063 | 17.439 | 22.102 | 121.9 |
| 100 万 / 密集评论 | 静态 SDK 旧版 | 151.859 | 323.590 | 203.363 | 204.183 | 229.403 | 122.4 |
| 100 万 / 密集评论 | 统一 21.0.8 | 148.383 | 21.725 | 7.095 | 17.555 | 21.722 | 122.4 |

相同堆参数下，100 万行普通和密集场景的滚动 p95 均降低约 96.5%，降至约 7.1 ms。两种百万行场景的最坏帧分别为 22.102 ms 和 21.722 ms，GC 后堆占用为 121.9 MiB 和 122.4 MiB。打开百万行仍需约 148 ms 同步初始化，行高缓存的剩余计算也随文件规模增长。单次测量只能说明本轮样本的表现。

隔离检查工具后的最终集成产物通过 `scripts/check_desktop.py --native --vm-arg=-Xms256m --vm-arg=-Xmx2g --scroll 1000000 dense` 复核：初始化 152.254 ms、跳转最大 21.643 ms、滚动 p95 6.923 ms、最坏帧 20.922 ms、GC 后堆 122.4 MiB。该复核使用 Maven 构建的独立检查程序及正式应用类。

原生原始数值，顺序与上表一致：

```text
lines=100000 init_ms=23.251 jump_p95_ms=36.454 jump_max_ms=36.454 scroll_p95_ms=21.162 scroll_max_ms=43.843 frame_p95_ms=21.254 frame_max_ms=43.959 heap_mib=20.7 cells=29
lines=100000 init_ms=23.066 jump_p95_ms=5.597 jump_max_ms=5.597 scroll_p95_ms=1.738 scroll_max_ms=22.928 frame_p95_ms=17.314 frame_max_ms=23.230 heap_mib=21.2 cells=29
lines=100000 init_ms=23.895 jump_p95_ms=35.896 jump_max_ms=35.896 scroll_p95_ms=20.948 scroll_max_ms=42.412 frame_p95_ms=21.120 frame_max_ms=42.617 heap_mib=21.2 cells=29
lines=100000 init_ms=22.548 jump_p95_ms=5.550 jump_max_ms=5.550 scroll_p95_ms=1.864 scroll_max_ms=23.803 frame_p95_ms=17.820 frame_max_ms=28.528 heap_mib=21.7 cells=29
lines=1000000 init_ms=151.605 jump_p95_ms=327.942 jump_max_ms=327.942 scroll_p95_ms=201.077 scroll_max_ms=207.952 frame_p95_ms=201.312 frame_max_ms=208.119 heap_mib=121.9 cells=29
lines=1000000 init_ms=148.572 jump_p95_ms=21.874 jump_max_ms=21.874 scroll_p95_ms=7.063 scroll_max_ms=11.887 frame_p95_ms=17.439 frame_max_ms=22.102 heap_mib=121.9 cells=29
lines=1000000 init_ms=151.859 jump_p95_ms=323.590 jump_max_ms=323.590 scroll_p95_ms=203.363 scroll_max_ms=229.225 frame_p95_ms=204.183 frame_max_ms=229.403 heap_mib=122.4 cells=29
lines=1000000 init_ms=148.383 jump_p95_ms=21.725 jump_max_ms=21.725 scroll_p95_ms=7.095 scroll_max_ms=7.892 frame_p95_ms=17.555 frame_max_ms=21.722 heap_mib=122.4 cells=29
```

## 起始行标记复核

评论圆点改为仅显示当前起始行后，`CommentStartIndex` 按起始行聚合，查询直接复用该行的不可变列表；范围长度不增加索引空间。百万行密集评论复核仍使用 `-O3` 独立检查程序、`-Xms256m -Xmx2g` 及相同样本，全部构建结束后单独运行一次。选区原文和同起点评论 Tooltip 检查通过。滚动 p95 为 7.336 ms，未观察到相较此前约 7 ms 的明显整体变化；此次主要改变标记密度和查询逻辑。

```text
lines=1000000 init_ms=148.133 jump_p95_ms=21.507 jump_max_ms=21.507 scroll_p95_ms=7.336 scroll_max_ms=8.715 frame_p95_ms=17.622 frame_max_ms=22.971 heap_mib=122.4 cells=29
```
