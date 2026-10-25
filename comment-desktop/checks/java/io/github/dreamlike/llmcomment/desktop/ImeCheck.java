package io.github.dreamlike.llmcomment.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** 在临时窗口中检查系统按键、中文提交及 fcitx5 候选窗口的实际 X11 坐标。 */
public final class ImeCheck {
  private ImeCheck() {}

  public static void main(String[] args) {
    if (args.length > 2) throw new IllegalArgumentException("用法：--ime-test [缩放] [截图路径]");
    double scale = args.length == 0 ? 1 : Double.parseDouble(args[0]);
    if (!Double.isFinite(scale) || scale < .75 || scale > 3)
      throw new IllegalArgumentException("测试缩放必须为 0.75 至 3");
    Path screenshot = args.length == 2 ? Path.of(args[1]) : null;
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          error.printStackTrace(System.err);
          System.exit(1);
        });
    Platform.startup(
        () -> {
          DesktopInputMethods.initialize();
          TextArea editor = new TextArea();
          editor.setPrefHeight(100);
          editor.setMaxHeight(100);
          TextField author = new TextField();
          VBox content = new VBox(16, new Label("评论正文"), editor, new Label("作者"), author);
          content.setPadding(new Insets(24));
          ScaledPane viewport = new ScaledPane(content, 400, 300);
          viewport.setZoom(scale);
          Stage stage = new Stage();
          stage.setTitle("llm-comment 独立系统输入法检查");
          Scene scene = new Scene(viewport, 960, 760);
          stage.setScene(scene);
          DesktopInputMethods.install(scene);
          var screen = Screen.getPrimary().getVisualBounds();
          stage.setX(screen.getMinX() + 250);
          stage.setY(screen.getMinY() + 200);
          stage.show();
          stage.toFront();
          editor.requestFocus();
          var committed = new StringBuilder();
          editor.addEventHandler(
              InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
              event -> committed.append(event.getCommitted()));
          author.addEventHandler(
              InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
              event -> committed.append(event.getCommitted()));
          Thread.ofPlatform()
              .name("ime-os-check")
              .start(
                  () -> {
                    try {
                      Thread.sleep(1200);
                      focus(stage, editor);
                      typePinyin(stage);
                      checkCandidate(stage, editor, "正文");
                      onFx(
                          () -> {
                            stage.setX(stage.getX() + 90);
                            stage.setY(stage.getY() + 60);
                            return null;
                          });
                      Thread.sleep(400);
                      checkCandidate(stage, editor, "移动窗口");
                      onFx(
                          () -> {
                            viewport.setZoom(scale == 1 ? 1.75 : 1);
                            return null;
                          });
                      Thread.sleep(400);
                      checkCandidate(stage, editor, "动态缩放");
                      key(stage, KeyCode.SPACE);
                      Thread.sleep(400);
                      require(onFx(() -> "你好".equals(editor.getText())), "正文未提交中文");
                      onFx(
                          () -> {
                            viewport.setZoom(scale);
                            return null;
                          });
                      Thread.sleep(300);
                      focus(stage, author);
                      typePinyin(stage);
                      checkCandidate(stage, author, "切换输入框");
                      if (screenshot != null) {
                        var capture =
                            onFx(
                                () ->
                                    new Robot()
                                        .getScreenCapture(
                                            null,
                                            stage.getX(),
                                            stage.getY(),
                                            stage.getWidth(),
                                            stage.getHeight()));
                        PngSnapshot.write(capture, screenshot);
                      }
                      key(stage, KeyCode.SPACE);
                      Thread.sleep(400);
                      onFx(
                          () -> {
                            require("你好".equals(author.getText()), "作者未提交中文");
                            require("你好你好".contentEquals(committed), "缺少系统输入法提交事件：" + committed);
                            System.out.println(
                                "IME_OS_OK scale="
                                    + scale
                                    + " committed="
                                    + committed
                                    + " candidatePosition=verified");
                            stage.close();
                            Platform.exit();
                            return null;
                          });
                    } catch (Throwable error) {
                      error.printStackTrace(System.err);
                      System.exit(1);
                    }
                  });
        });
  }

  private static void focus(Stage stage, TextInputControl control) throws Exception {
    onFx(
        () -> {
          require(stage.isFocused(), "测试窗口未获得焦点，拒绝发送按键");
          var point = control.localToScreen(20, 20);
          Robot robot = new Robot();
          robot.mouseMove(point.getX(), point.getY());
          robot.mouseClick(MouseButton.PRIMARY);
          control.requestFocus();
          return null;
        });
    run("fcitx5-remote", "-o");
    Thread.sleep(400);
  }

  private static void typePinyin(Stage stage) throws Exception {
    for (KeyCode key : new KeyCode[] {KeyCode.N, KeyCode.I, KeyCode.H, KeyCode.A, KeyCode.O}) {
      key(stage, key);
      Thread.sleep(150);
    }
    Thread.sleep(500);
  }

  private static void key(Stage stage, KeyCode key) throws Exception {
    onFx(
        () -> {
          require(stage.isFocused(), "测试窗口失去焦点，停止发送按键");
          new Robot().keyType(key);
          return null;
        });
  }

  private static void checkCandidate(Stage stage, TextInputControl control, String step)
      throws Exception {
    Bounds bounds =
        onFx(
            () -> {
              require(stage.isFocused(), "候选框测试窗口失去焦点");
              return control.localToScreen(control.getBoundsInLocal());
            });
    String window = run("xwininfo", "-name", "Fcitx5 Input Window");
    require(window.contains("Map State: IsViewable"), "fcitx5 候选窗口不可见");
    int x = coordinate(window, "Absolute upper-left X");
    int y = coordinate(window, "Absolute upper-left Y");
    // 此系统检查要求 X11 的 JavaFX 输出比例为 1；UI Scale 由 ScaledPane 覆盖。
    require(
        onFx(() -> stage.getOutputScaleX() == 1 && stage.getOutputScaleY() == 1),
        "坐标检查需要 X11 输出比例 1，请使用 --scale 测试界面缩放");
    require(
        Math.abs(x - bounds.getMinX()) <= 32
            && y >= bounds.getMaxY() - 4
            && y <= bounds.getMaxY() + 48,
        step + "候选框未位于输入框下方：popup=" + x + "," + y + "; input=" + bounds);
    System.out.println(
        "IME_POSITION_OK " + step + " popup=" + x + "," + y + " inputBottom=" + bounds.getMaxY());
  }

  private static int coordinate(String text, String name) {
    var match = Pattern.compile(Pattern.quote(name) + ":\\s*(-?\\d+)").matcher(text);
    if (!match.find()) throw new IllegalStateException("xwininfo 缺少坐标：" + name);
    return Integer.parseInt(match.group(1));
  }

  private static String run(String... command) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("命令超时：" + command[0]);
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    require(process.exitValue() == 0, "命令失败：" + command[0] + " " + output);
    return output;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static <T> T onFx(Supplier<T> action) throws Exception {
    var completed = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            completed.complete(action.get());
          } catch (Throwable error) {
            completed.completeExceptionally(error);
          }
        });
    return completed.get(10, TimeUnit.SECONDS);
  }
}
