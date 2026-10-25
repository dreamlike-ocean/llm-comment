package io.github.dreamlike.llmcomment.desktop;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.InputMethodRequests;

/** 通过 JavaFX 公开接口，将各平台的输入法候选锚点统一放在当前输入框下方。 */
final class InputMethodPlacement {
  private final Scene scene;
  private TextInputControl editor;
  private PlacementRequests wrapper;
  private boolean replacing;
  private final ChangeListener<InputMethodRequests> requestsChanged =
      (observable, oldValue, newValue) -> {
        if (!replacing) wrap(newValue);
      };

  static void install(Scene scene) {
    if (!Platform.isFxApplicationThread()) throw new IllegalStateException("输入法锚点必须在 JavaFX 线程安装");
    if (scene.getProperties().containsKey(InputMethodPlacement.class)) return;
    var placement = new InputMethodPlacement(scene);
    scene.getProperties().put(InputMethodPlacement.class, placement);
  }

  private InputMethodPlacement(Scene scene) {
    this.scene = scene;
    scene.focusOwnerProperty().addListener(ignored -> attach());
    attach();
  }

  private void attach() {
    if (editor != null) {
      editor.inputMethodRequestsProperty().removeListener(requestsChanged);
      if (wrapper != null && editor.getInputMethodRequests() == wrapper)
        editor.setInputMethodRequests(wrapper.delegate);
    }
    wrapper = null;
    editor = scene.getFocusOwner() instanceof TextInputControl input ? input : null;
    if (editor != null) {
      editor.inputMethodRequestsProperty().addListener(requestsChanged);
      wrap(editor.getInputMethodRequests());
    }
  }

  private void wrap(InputMethodRequests delegate) {
    // skin 可能先清空接口再安装新接口；保留 null，让 skin 的默认安装逻辑继续执行。
    if (delegate == null || delegate == wrapper) return;
    wrapper = new PlacementRequests(editor, delegate);
    replacing = true;
    try {
      editor.setInputMethodRequests(wrapper);
    } finally {
      replacing = false;
    }
  }

  private record PlacementRequests(TextInputControl editor, InputMethodRequests delegate)
      implements InputMethodRequests {
    @Override
    public Point2D getTextLocation(int offset) {
      var bounds = editor.getBoundsInLocal();
      // localToScreen 已处理场景图变换、窗口位置和平台缩放；不叠加平台补偿。
      Point2D location = editor.localToScreen(bounds.getMinX(), bounds.getMaxY());
      return location == null ? delegate.getTextLocation(offset) : location;
    }

    @Override
    public int getLocationOffset(int x, int y) {
      return delegate.getLocationOffset(x, y);
    }

    @Override
    public void cancelLatestCommittedText() {
      delegate.cancelLatestCommittedText();
    }

    @Override
    public String getSelectedText() {
      return delegate.getSelectedText();
    }
  }
}
