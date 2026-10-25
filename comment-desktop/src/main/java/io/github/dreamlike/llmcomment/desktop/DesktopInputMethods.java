package io.github.dreamlike.llmcomment.desktop;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import javafx.application.Platform;

/** 初始化 Linux Glass 使用的 XIM；GTK_IM_MODULE 只配置 GTK 控件，不配置 Glass。 */
final class DesktopInputMethods {
  private DesktopInputMethods() {}

  static void install(javafx.scene.Scene scene) {
    InputMethodPlacement.install(scene);
    LinuxImePosition.install(scene);
  }

  /** 必须在 Toolkit 启动后、首个窗口创建前调用；不修改环境变量或系统输入法设置。 */
  static void initialize() {
    if (!System.getProperty("os.name", "").startsWith("Linux")) return;
    if (!Platform.isFxApplicationThread()) {
      throw new IllegalStateException("输入法初始化必须在 JavaFX 线程执行");
    }
    // GTK 初始化 locale 后，显式让 Xlib 读取 XMODIFIERS。Glass 21 直接调用
    // XOpenIM，却不调用 XSetLocaleModifiers；仅有 GTK_IM_MODULE 会退回本地输入。
    // 空字符串采用用户已有的 XMODIFIERS，也保留没有输入法时的 Xlib 默认行为。
    try (Arena arena = Arena.ofConfined()) {
      var library = SymbolLookup.libraryLookup("libX11.so.6", arena);
      var configure =
          Linker.nativeLinker()
              .downcallHandle(
                  library.find("XSetLocaleModifiers").orElseThrow(),
                  FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
      MemorySegment result = (MemorySegment) configure.invokeExact(arena.allocateFrom(""));
      if (result.equals(MemorySegment.NULL)) {
        System.err.println("无法初始化 XIM locale modifiers；请检查 XMODIFIERS 和系统输入法。");
      }
    } catch (Throwable error) {
      // 输入法不可用不应阻止只读浏览；原始错误写 stderr，便于检查运行环境。
      System.err.println("无法初始化 Linux 输入法：" + error);
    }
  }
}
