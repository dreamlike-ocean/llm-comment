package io.github.dreamlike.llmcomment.desktop;

import io.github.dreamlike.llmcomment.core.CommentView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.stage.Stage;
import javafx.stage.Window;

/** 冒烟检查通过真实控件事件验证选择及评论标记，不绕过 ListView 行为。 */
final class SourceInteractionCheck {
  private SourceInteractionCheck() {}

  static void clickLine(SourceView source, int line, boolean shift) {
    requireFxThread();
    var cell = cellAt(source, line);
    var code = cell.lookup(".code-text");
    if (code == null) throw new IllegalStateException("源码行没有文本控件：" + line);
    click(code, shift);
  }

  /** 返回实际弹出的菜单，调用方可逐项 fire 验证应用的评论选择回调。 */
  static ContextMenu clickMarker(SourceView source, int line) {
    requireFxThread();
    var marker = cellAt(source, line).lookup(".line-marker");
    if (marker == null) throw new IllegalStateException("源码行没有评论标记：" + line);
    click(marker, false);
    return Window.getWindows().stream()
        .filter(window -> window instanceof ContextMenu && window.isShowing())
        .map(window -> (ContextMenu) window)
        .filter(menu -> menu.getOwnerNode() == marker)
        .findFirst()
        .orElse(null);
  }

  static void verifyMarkers(SourceView source, List<CommentView> comments) {
    requireFxThread();
    var expected = new CommentRangeIndex(comments).at(6);
    if (expected.size() < 2 || source.commentsAtLine(6).size() != expected.size()) {
      throw new IllegalStateException("第 6 行必须保留所有交叉评论");
    }
    // 使用独立视图，检查回调时不替换应用控制器已安装的处理器。
    var check = new SourceView();
    int last =
        comments.stream()
            .filter(comment -> comment.location() != null)
            .map(comment -> comment.location().endLine())
            .filter(java.util.Objects::nonNull)
            .mapToInt(Integer::intValue)
            .max()
            .orElse(12);
    check.show("源码\n".repeat(Math.max(12, last)), comments);
    var selected = new ArrayList<String>();
    var selectionEvents = new AtomicInteger();
    check.onCommentChosen(comment -> selected.add(comment.id()));
    check.onUserSelection(selectionEvents::incrementAndGet);
    var stage = new Stage();
    stage.setScene(new Scene(check, 800, 600));
    stage.show();
    try {
      check.applyCss();
      check.layout();
      check.highlight(2, 3);
      check.selectLine(6);
      if (selectionEvents.get() != 0) {
        throw new IllegalStateException("程序高亮误触发用户选择回调");
      }
      // 同一范围重新点击也应通知控制器，让编辑状态可以切换到新增。
      clickLine(check, 6, false);
      clickLine(check, 8, true);
      if (check.startLine() != 6 || check.endLine() != 8 || selectionEvents.get() != 2) {
        throw new IllegalStateException(
            "真实鼠标 Shift 选择没有保留第 6 至 8 行："
                + check.startLine()
                + "–"
                + check.endLine()
                + "，回调 "
                + selectionEvents.get());
      }
      var menu = clickMarker(check, 6);
      if (menu == null || menu.getItems().size() != expected.size()) {
        throw new IllegalStateException("交叉评论菜单遗漏评论");
      }
      try {
        for (var item : menu.getItems()) item.fire();
        if (!selected.equals(expected.stream().map(CommentView::id).toList())) {
          throw new IllegalStateException("菜单没有分别选择所有评论 ID");
        }
        if (selectionEvents.get() != 2 || check.startLine() != 6 || check.endLine() != 8) {
          throw new IllegalStateException("点击评论标记误触发源码选择");
        }
      } finally {
        menu.hide();
      }
    } finally {
      stage.close();
    }
  }

  private static ListCell<?> cellAt(SourceView source, int line) {
    @SuppressWarnings("unchecked")
    var list = (ListView<SourceLines.Line>) source.lookup(".list-view");
    if (list == null) throw new IllegalStateException("源码列表尚未布局");
    list.scrollTo(line - 1);
    source.applyCss();
    source.layout();
    list.layout();
    return list.lookupAll(".list-cell").stream()
        .filter(
            node ->
                node instanceof ListCell<?> cell && !cell.isEmpty() && cell.getIndex() == line - 1)
        .map(node -> (ListCell<?>) node)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("源码行不可见：" + line));
  }

  private static void click(Node target, boolean shift) {
    fire(target, MouseEvent.MOUSE_PRESSED, shift, true);
    fire(target, MouseEvent.MOUSE_RELEASED, shift, false);
    fire(target, MouseEvent.MOUSE_CLICKED, shift, false);
  }

  private static void fire(Node target, EventType<MouseEvent> type, boolean shift, boolean down) {
    var screen = target.localToScreen(3, 3);
    var scene = target.localToScene(3, 3);
    target.fireEvent(
        new MouseEvent(
            type,
            scene.getX(),
            scene.getY(),
            screen.getX(),
            screen.getY(),
            MouseButton.PRIMARY,
            1,
            shift,
            false,
            false,
            false,
            down,
            false,
            false,
            false,
            false,
            true,
            new PickResult(target, scene.getX(), scene.getY())));
  }

  private static void requireFxThread() {
    if (!Platform.isFxApplicationThread()) throw new IllegalStateException("界面检查必须在 FX 线程执行");
  }
}
