package io.github.dreamlike.llmcomment.desktop;

import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.InputMethodRequests;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

/** 验证公开候选锚点与委托行为，不发送系统按键，不验证特定平台输入法。 */
final class InputMethodPlacementCheck {
  private InputMethodPlacementCheck() {}

  static void verify() {
    if (!Platform.isFxApplicationThread()) throw new IllegalStateException("检查必须在 JavaFX 线程执行");
    Stage stage = new Stage();
    TextArea body = new TextArea();
    TextField author = new TextField();
    VBox content = new VBox(body, author);
    Scene scene = new Scene(content, 600, 400);
    InputMethodPlacement.install(scene);
    InputMethodPlacement.install(scene);
    stage.setScene(scene);
    try {
      stage.show();
      content.applyCss();
      content.layout();
      for (double scale : new double[] {1, 1.75}) {
        content.getTransforms().setAll(new Scale(scale, scale));
        for (TextInputControl input : new TextInputControl[] {body, author}) {
          input.requestFocus();
          require(scene.getFocusOwner() == input, "测试输入框未获得场景焦点");
          checkAnchor(input);
          stage.setX(stage.getX() + 31);
          stage.setY(stage.getY() + 23);
          checkAnchor(input);
          var delegate = new Requests();
          input.setInputMethodRequests(delegate);
          var wrapped = input.getInputMethodRequests();
          require(wrapped != delegate, "skin 替换接口后未重新包装");
          require(wrapped.getLocationOffset(19, 27) == 46, "位置查询未委托");
          require("选中文字".equals(wrapped.getSelectedText()), "选中文本未委托");
          wrapped.cancelLatestCommittedText();
          require(delegate.cancelled, "撤销提交未委托");
          checkAnchor(input);
          // skin 的 null -> 新接口生命周期不能被包装器阻止，也不能递归监听。
          input.setInputMethodRequests(null);
          require(input.getInputMethodRequests() == null, "未保留 skin 清空接口状态");
          input.setInputMethodRequests(delegate);
          checkAnchor(input);
        }
      }
    } finally {
      stage.close();
    }
  }

  private static void checkAnchor(TextInputControl input) {
    var bounds = input.getBoundsInLocal();
    Point2D expected = input.localToScreen(bounds.getMinX(), bounds.getMaxY());
    Point2D actual = input.getInputMethodRequests().getTextLocation(0);
    require(
        expected != null && actual != null && expected.distance(actual) < 0.01,
        "候选锚点与输入框底边不一致：expected=" + expected + ", actual=" + actual);
    require(actual.equals(input.getInputMethodRequests().getTextLocation(12)), "锚点随正文 offset 改变");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class Requests implements InputMethodRequests {
    private boolean cancelled;

    public Point2D getTextLocation(int offset) {
      return new Point2D(-1, -1);
    }

    public int getLocationOffset(int x, int y) {
      return x + y;
    }

    public void cancelLatestCommittedText() {
      cancelled = true;
    }

    public String getSelectedText() {
      return "选中文字";
    }
  }
}
