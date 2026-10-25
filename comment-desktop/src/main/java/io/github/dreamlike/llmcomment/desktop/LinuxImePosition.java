package io.github.dreamlike.llmcomment.desktop;

import com.sun.javafx.runtime.VersionInfo;
import com.sun.javafx.stage.WindowHelper;
import com.sun.javafx.tk.quantum.WindowStage;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;

/**
 * 固定 Linux x64 Glass ABI 的候选框定位适配。
 *
 * <p>OpenJFX 21 的 Window.getNativeHandle() 返回 WindowContextBase*，而
 * GtkWindow.getNativeWindow()/getRawHandle() 返回 XID，GtkView.getNativeView() 返回零。 WindowContextBase
 * 的首个成员是 vptr，随后为 XIM、XIC、enabled；XIC 位于偏移 16。 已核对 Maven 21.0.8+2 共享库与 Gluon 21-ea+11.3 静态 SDK
 * 的反汇编，两者一致。 只允许这两个精确运行时版本，随后用 GDK 的 glass_window_context 验证窗口身份，拒绝未知 ABI。 升级 SDK
 * 时须重新核对布局及真实候选窗口，不得放宽版本判断来绕过验证。
 */
final class LinuxImePosition {
  record Diagnostics(int rootX, int rootY, int spotX, int spotY, long clientWindow) {}

  private static Diagnostics lastUpdate;
  private final Scene scene;
  private final NativeCalls nativeCalls;
  private final InvalidationListener changed = ignored -> schedule();
  private TextInputControl editor;
  private Window window;
  private boolean pending;
  private boolean failed;

  static Diagnostics lastUpdate() {
    return lastUpdate;
  }

  static void install(Scene scene) {
    if (!Platform.isFxApplicationThread()) throw new IllegalStateException("输入法定位必须在 JavaFX 线程安装");
    if (!System.getProperty("os.name", "").equals("Linux")) return;
    String arch = System.getProperty("os.arch", "");
    String version = VersionInfo.getRuntimeVersion();
    if (!(arch.equals("amd64") || arch.equals("x86_64"))
        || !(version.equals("21.0.8+2") || version.equals("21-internal+11.2-2024-03-05-115411"))) {
      System.err.println("未启用 XIM 候选定位：尚未验证的 Glass ABI " + arch + "/" + version);
      return;
    }
    if (scene.getProperties().containsKey(LinuxImePosition.class)) return;
    try {
      var position = new LinuxImePosition(scene);
      scene.getProperties().put(LinuxImePosition.class, position);
    } catch (Throwable error) {
      System.err.println("无法安装 XIM 候选定位：" + error);
    }
  }

  private LinuxImePosition(Scene scene) {
    this.scene = scene;
    nativeCalls = new NativeCalls();
    scene.focusOwnerProperty().addListener(ignored -> attachEditor());
    scene.windowProperty().addListener(ignored -> attachWindow());
    scene.xProperty().addListener(changed);
    scene.yProperty().addListener(changed);
    scene.addEventFilter(KeyEvent.KEY_PRESSED, ignored -> schedule());
    scene.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, ignored -> schedule());
    attachEditor();
    attachWindow();
  }

  private void attachEditor() {
    if (editor != null) observeEditor(editor, false);
    editor = scene.getFocusOwner() instanceof TextInputControl input ? input : null;
    if (editor != null) observeEditor(editor, true);
    schedule();
  }

  private void observeEditor(TextInputControl input, boolean add) {
    for (var property :
        new javafx.beans.Observable[] {
          input.localToSceneTransformProperty(),
          input.boundsInLocalProperty(),
          input.caretPositionProperty(),
          input.textProperty(),
          input.visibleProperty()
        }) {
      if (add) property.addListener(changed);
      else property.removeListener(changed);
    }
  }

  private void attachWindow() {
    if (window != null) observeWindow(window, false);
    window = scene.getWindow();
    if (window != null) observeWindow(window, true);
    schedule();
  }

  private void observeWindow(Window target, boolean add) {
    for (var property :
        new javafx.beans.Observable[] {
          target.xProperty(),
          target.yProperty(),
          target.widthProperty(),
          target.heightProperty(),
          target.outputScaleXProperty(),
          target.outputScaleYProperty(),
          target.showingProperty(),
          target.focusedProperty()
        }) {
      if (add) property.addListener(changed);
      else property.removeListener(changed);
    }
  }

  private void schedule() {
    if (pending || failed) return;
    pending = true;
    Platform.runLater(
        () -> {
          pending = false;
          update();
        });
  }

  private void update() {
    if (failed
        || editor == null
        || !editor.isEditable()
        || !editor.isVisible()
        || window == null
        || !window.isShowing()
        || !window.isFocused()) return;
    if (!(WindowHelper.getPeer(window) instanceof WindowStage stage)) return;
    var glass = stage.getPlatformWindow();
    if (glass == null || glass.isClosed() || glass.getNativeHandle() == 0) return;
    if (!glass.getClass().getName().equals("com.sun.glass.ui.gtk.GtkWindow")) {
      failed = true;
      System.err.println("未启用 XIM 候选定位：当前窗口不是已验证的 GtkWindow");
      return;
    }
    // 与 Windows/macOS 共用公开 InputMethodRequests 的屏幕坐标规则。
    var requests = editor.getInputMethodRequests();
    if (requests == null) return;
    Point2D anchor = requests.getTextLocation(0);
    if (anchor == null) return;
    int rootX = glass.getScreen().toPlatformX((float) anchor.getX());
    int rootY = glass.getScreen().toPlatformY((float) anchor.getY());
    try {
      lastUpdate =
          nativeCalls.update(glass.getNativeHandle(), glass.getNativeWindow(), rootX, rootY);
    } catch (Throwable error) {
      failed = true;
      System.err.println("已停止 XIM 候选定位：" + error);
    }
  }

  private static final class NativeCalls {
    private final SymbolLookup library = SymbolLookup.libraryLookup("libX11.so.6", Arena.global());
    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup gdk = SymbolLookup.libraryLookup("libgdk-3.so.0", Arena.global());
    private final SymbolLookup gobject =
        SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global());
    private final MethodHandle defaultDisplay =
        linker.downcallHandle(
            gdk.find("gdk_display_get_default").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS));
    private final MethodHandle lookupWindow =
        linker.downcallHandle(
            gdk.find("gdk_x11_window_lookup_for_display").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private final MethodHandle objectData =
        linker.downcallHandle(
            gobject.find("g_object_get_data").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private final MethodHandle set = variadic("XSetICValues", false);
    private final MethodHandle nested = variadic("XVaCreateNestedList", true);
    private final MethodHandle imOfIc =
        call("XIMOfIC", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private final MethodHandle displayOfIm =
        call("XDisplayOfIM", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private final MethodHandle rootWindow =
        call(
            "XDefaultRootWindow",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
    private final MethodHandle translate =
        call(
            "XTranslateCoordinates",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS));
    private final MethodHandle free =
        call("XFree", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private final MethodHandle flush =
        call("XFlush", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private MethodHandle call(String name, FunctionDescriptor descriptor) {
      return linker.downcallHandle(library.find(name).orElseThrow(), descriptor);
    }

    private MethodHandle variadic(String name, boolean firstInt) {
      return linker.downcallHandle(
          library.find(name).orElseThrow(),
          FunctionDescriptor.of(
              ValueLayout.ADDRESS,
              firstInt ? ValueLayout.JAVA_INT : ValueLayout.ADDRESS,
              ValueLayout.ADDRESS,
              ValueLayout.ADDRESS,
              ValueLayout.ADDRESS),
          Linker.Option.firstVariadicArg(1));
    }

    Diagnostics update(long context, long expectedClient, int rootX, int rootY) throws Throwable {
      try (Arena arena = Arena.ofConfined()) {
        // fcitx 的 xcb-imdkit 对 XGetICValues 的顶层属性只返回 filterEvents，
        // clientWindow/inputStyle/focusWindow 会被拒绝。通过 GDK 公开 API 验证
        // Glass 自己登记的窗口上下文，验证通过之后才读取固定 ABI 的 XIC。
        MemorySegment gdkDisplay = (MemorySegment) defaultDisplay.invokeExact();
        MemorySegment gdkWindow =
            (MemorySegment) lookupWindow.invokeExact(gdkDisplay, expectedClient);
        if (gdkWindow.equals(MemorySegment.NULL)) {
          throw new IllegalStateException("GDK 未找到当前 XIM 客户窗口");
        }
        MemorySegment registeredContext =
            (MemorySegment)
                objectData.invokeExact(gdkWindow, arena.allocateFrom("glass_window_context"));
        if (registeredContext.address() != context) {
          throw new IllegalStateException("GDK Glass 上下文与当前窗口不一致");
        }
        MemorySegment ic =
            MemorySegment.ofAddress(context).reinterpret(24).get(ValueLayout.ADDRESS, 16);
        if (ic.equals(MemorySegment.NULL)) return null;
        // Glass 创建 IC 时只设置 XNClientWindow，没有设置 XNFocusWindow；
        // Xlib 的默认 focusWindow 因此就是 clientWindow，不另行覆盖其生命周期。
        long client = expectedClient;
        MemorySegment im = (MemorySegment) imOfIc.invokeExact(ic);
        MemorySegment display = (MemorySegment) displayOfIm.invokeExact(im);
        long root = (long) rootWindow.invokeExact(display);
        MemorySegment x = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment y = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment child = arena.allocate(ValueLayout.JAVA_LONG);
        int translated =
            (int) translate.invokeExact(display, root, client, rootX, rootY, x, y, child);
        if (translated == 0) throw new IllegalStateException("XIM 窗口坐标转换失败");
        int spotX = x.get(ValueLayout.JAVA_INT, 0);
        int spotY = y.get(ValueLayout.JAVA_INT, 0);
        if (spotX < Short.MIN_VALUE
            || spotX > Short.MAX_VALUE
            || spotY < Short.MIN_VALUE
            || spotY > Short.MAX_VALUE) {
          throw new IllegalStateException("XIM 坐标超出 XPoint 范围");
        }
        MemorySegment point = arena.allocate(4, 2);
        point.set(ValueLayout.JAVA_SHORT, 0, (short) spotX);
        point.set(ValueLayout.JAVA_SHORT, 2, (short) spotY);
        MemorySegment list =
            (MemorySegment)
                nested.invokeExact(
                    0, arena.allocateFrom("spotLocation"), point, MemorySegment.NULL);
        if (list.equals(MemorySegment.NULL)) throw new IllegalStateException("无法分配 XIM 候选坐标");
        try {
          MemorySegment error =
              (MemorySegment)
                  set.invokeExact(
                      ic, arena.allocateFrom("preeditAttributes"), list, MemorySegment.NULL);
          if (!error.equals(MemorySegment.NULL))
            throw new IllegalStateException("XSetICValues 拒绝 spotLocation");
        } finally {
          int ignored = (int) free.invokeExact(list);
        }
        int ignored = (int) flush.invokeExact(display);
        return new Diagnostics(rootX, rootY, spotX, spotY, client);
      }
    }
  }
}
