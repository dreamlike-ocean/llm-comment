package io.github.dreamlike.llmcomment.desktop;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.robot.Robot;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

/** 独立的系统输入法检查：只操作临时窗口，通过 Robot 发送真实 X11 按键。 */
public final class ImeCheck {
  private ImeCheck() {}

  public static void main(String[] args) {
    double scale = args.length == 0 ? 1 : Double.parseDouble(args[0]);
    Platform.startup(
        () -> {
          DesktopInputMethods.initialize();
          TextArea editor = new TextArea();
          editor.getTransforms().add(new Scale(scale, scale));
          editor.setMaxSize(420 / scale, 140 / scale);
          Stage stage = new Stage();
          stage.setTitle("llm-comment 独立系统输入法检查");
          stage.setScene(new Scene(new StackPane(editor), 640, 360));
          stage.show();
          stage.toFront();
          editor.requestFocus();
          var committed = new StringBuilder();
          editor.addEventHandler(
              InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
              event -> committed.append(event.getCommitted()));
          Thread.ofPlatform()
              .name("ime-os-check")
              .start(
                  () -> {
                    try {
                      Thread.sleep(1500);
                      onFx(
                          () -> {
                            if (!stage.isFocused()) {
                              throw new IllegalStateException("测试窗口未获得焦点，拒绝发送按键");
                            }
                            var point = editor.localToScreen(20, 20);
                            Robot robot = new Robot();
                            robot.mouseMove(point.getX(), point.getY());
                            robot.mouseClick(MouseButton.PRIMARY);
                          });
                      // 只激活当前测试窗口的 fcitx 上下文；不启动、重启或改写输入法配置。
                      Process activate = new ProcessBuilder("fcitx5-remote", "-o").start();
                      if (!activate.waitFor(5, TimeUnit.SECONDS) || activate.exitValue() != 0) {
                        throw new IllegalStateException("无法激活当前窗口的 fcitx5 输入法");
                      }
                      Thread.sleep(600);
                      for (KeyCode key :
                          new KeyCode[] {
                            KeyCode.N, KeyCode.I, KeyCode.H, KeyCode.A, KeyCode.O, KeyCode.SPACE
                          }) {
                        onFx(
                            () -> {
                              if (!stage.isFocused()) {
                                throw new IllegalStateException("测试窗口失去焦点，停止发送按键");
                              }
                              new Robot().keyType(key);
                            });
                        Thread.sleep(200);
                      }
                      Thread.sleep(1000);
                      onFx(
                          () -> {
                            if (!"你好".equals(editor.getText()) || !"你好".contentEquals(committed)) {
                              throw new AssertionError(
                                  "系统输入法未提交预期中文：text="
                                      + editor.getText()
                                      + ", committed="
                                      + committed);
                            }
                            System.out.println("IME_OS_OK scale=" + scale + " committed=你好");
                            stage.close();
                            Platform.exit();
                          });
                    } catch (Throwable error) {
                      error.printStackTrace(System.err);
                      System.exit(1);
                    }
                  });
        });
  }

  private static void onFx(Runnable action) throws Exception {
    var completed = new CompletableFuture<Void>();
    Platform.runLater(
        () -> {
          try {
            action.run();
            completed.complete(null);
          } catch (Throwable error) {
            completed.completeExceptionally(error);
          }
        });
    completed.get(10, TimeUnit.SECONDS);
  }
}
