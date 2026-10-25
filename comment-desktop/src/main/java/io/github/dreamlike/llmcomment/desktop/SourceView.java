package io.github.dreamlike.llmcomment.desktop;

import io.github.dreamlike.llmcomment.core.CommentView;
import java.util.List;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/** 只读、虚拟化源码视图；选择和复制始终使用完整原文。 */
public final class SourceView extends BorderPane {
  private static final int DISPLAY_LIMIT = 10_000;
  private final ListView<SourceLines.Line> list = new ListView<>();
  private final ReadOnlyStringWrapper selectionText = new ReadOnlyStringWrapper("选择代码行");
  private CommentRangeIndex markers = new CommentRangeIndex(List.of());
  private Consumer<CommentView> commentChosen = ignored -> {};
  private Runnable userSelection = () -> {};
  private SourceLines source = new SourceLines("");
  private boolean selecting;
  private int first;
  private int last;

  public SourceView() {
    getStyleClass().add("source-view");
    list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    // 保留虚拟化，由可见行的字体、字形和 CSS 留白决定高度。
    list.setCellFactory(ignored -> new SourceCell());
    list.getSelectionModel()
        .getSelectedIndices()
        .addListener((ListChangeListener<Integer>) change -> updateSelection());
    list.setOnKeyPressed(
        event -> {
          if (event.isShortcutDown() && event.getCode() == KeyCode.C) {
            copySelection();
            event.consume();
          }
        });
    list.addEventHandler(
        MouseEvent.MOUSE_RELEASED,
        event -> {
          if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
          javafx.scene.Node target =
              event.getTarget() instanceof javafx.scene.Node node ? node : null;
          while (target != null && target != list) {
            if (target instanceof ListCell<?> cell && !cell.isEmpty()) {
              notifyUserSelection();
              return;
            }
            target = target.getParent();
          }
        });
    list.addEventHandler(
        KeyEvent.KEY_RELEASED,
        event -> {
          if (event.getCode() == KeyCode.UP
              || event.getCode() == KeyCode.DOWN
              || event.getCode() == KeyCode.HOME
              || event.getCode() == KeyCode.END
              || event.getCode() == KeyCode.PAGE_UP
              || event.getCode() == KeyCode.PAGE_DOWN
              || (event.isShortcutDown() && event.getCode() == KeyCode.A)) notifyUserSelection();
        });
    setCenter(list);
  }

  public void show(String text, List<CommentView> comments) {
    source = new SourceLines(text);
    markers = new CommentRangeIndex(comments);
    clearSelection();
    list.setItems(FXCollections.observableArrayList(source.lines()));
    list.scrollTo(0);
  }

  public void onCommentChosen(Consumer<CommentView> callback) {
    commentChosen = java.util.Objects.requireNonNull(callback);
  }

  public void onUserSelection(Runnable callback) {
    userSelection = java.util.Objects.requireNonNull(callback);
  }

  List<CommentView> commentsAtLine(int line) {
    return markers.at(line);
  }

  private void notifyUserSelection() {
    if (first != 0) userSelection.run();
  }

  public void highlight(int startLine, int endLine) {
    if (startLine < 1 || endLine < startLine || endLine > source.lines().size()) {
      clearSelection();
      return;
    }
    selecting = true;
    try {
      list.getSelectionModel().clearSelection();
      list.getSelectionModel().selectRange(startLine - 1, endLine);
    } finally {
      selecting = false;
    }
    updateSelection();
    list.scrollTo(startLine - 1);
  }

  public void selectLine(int line) {
    highlight(line, line);
  }

  public int startLine() {
    return first;
  }

  public int endLine() {
    return last;
  }

  public ReadOnlyStringProperty selectionTextProperty() {
    return selectionText.getReadOnlyProperty();
  }

  public String selectedText() {
    return source.selectedText(first, last);
  }

  public void clearSelection() {
    list.getSelectionModel().clearSelection();
    first = last = 0;
    selectionText.set("选择代码行");
  }

  public void copySelection() {
    if (first == 0) return;
    var content = new ClipboardContent();
    content.putString(selectedText());
    Clipboard.getSystemClipboard().setContent(content);
  }

  private void updateSelection() {
    if (selecting) return;
    var indices = list.getSelectionModel().getSelectedIndices();
    if (indices.isEmpty()) {
      first = last = 0;
      selectionText.set("选择代码行");
      return;
    }
    first = indices.getFirst() + 1;
    last = indices.getLast() + 1;
    if (indices.size() != last - first + 1) {
      selecting = true;
      try {
        list.getSelectionModel().selectRange(first - 1, last);
      } finally {
        selecting = false;
      }
    }
    selectionText.set(first == last ? "第 " + first + " 行" : "第 " + first + " 至 " + last + " 行");
  }

  private static String commentLabel(CommentView comment) {
    var location = comment.location();
    String body = comment.body().replace('\n', ' ').replace('\r', ' ');
    if (body.length() > 100) {
      int end = Character.isHighSurrogate(body.charAt(99)) ? 99 : 100;
      body = body.substring(0, end) + "…";
    }
    return (comment.deleted() ? "[已删除] " : "")
        + "第 "
        + location.startLine()
        + " 至 "
        + location.endLine()
        + " 行 · "
        + comment.author()
        + " · "
        + body;
  }

  private final class SourceCell extends ListCell<SourceLines.Line> {
    private final Label number = new Label();
    private final Label marker = new Label();
    private final Label code = new Label();
    private final HBox row = new HBox(number, marker, code);

    private SourceCell() {
      getStyleClass().add("source-line");
      number.getStyleClass().add("line-number");
      marker.getStyleClass().add("line-marker");
      marker.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> event.consume());
      marker.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> event.consume());
      marker.setOnMouseClicked(
          event -> {
            var line = getItem();
            if (line == null) return;
            var comments = markers.at(line.number());
            if (comments.size() == 1) commentChosen.accept(comments.getFirst());
            else if (!comments.isEmpty()) {
              var menu = new ContextMenu();
              for (var comment : comments) {
                var item = new MenuItem(commentLabel(comment));
                item.setOnAction(ignored -> commentChosen.accept(comment));
                menu.getItems().add(item);
              }
              menu.show(marker, javafx.geometry.Side.BOTTOM, 0, 0);
            }
            event.consume();
          });
      code.getStyleClass().add("code-text");
      row.getStyleClass().add("source-row");
      number.setAlignment(Pos.CENTER_RIGHT);
      code.setMinWidth(USE_PREF_SIZE);
      row.setAlignment(Pos.CENTER_LEFT);
      row.setMinWidth(USE_PREF_SIZE);
    }

    @Override
    protected void updateItem(SourceLines.Line line, boolean empty) {
      super.updateItem(line, empty);
      setText(null);
      if (empty || line == null) {
        setGraphic(null);
        return;
      }
      number.setText(Integer.toString(line.number()));
      var comments = markers.at(line.number());
      boolean deleted = !comments.isEmpty() && comments.stream().allMatch(CommentView::deleted);
      marker.setText(
          comments.isEmpty()
              ? ""
              : (deleted ? "○" : "●")
                  + (comments.size() > 99
                      ? "99+"
                      : comments.size() > 1 ? Integer.toString(comments.size()) : ""));
      marker.setOpacity(deleted ? 0.45 : 1);
      marker.setTooltip(
          comments.isEmpty()
              ? null
              : new Tooltip(
                  comments.stream()
                      .map(SourceView::commentLabel)
                      .collect(java.util.stream.Collectors.joining("\n"))));
      String value = line.text();
      if (value.length() > DISPLAY_LIMIT) {
        int boundary = DISPLAY_LIMIT;
        if (Character.isHighSurrogate(value.charAt(boundary - 1))) boundary--;
        code.setText(value.substring(0, boundary) + "  …（超长行仅显示前 10000 字符，选择和复制保留全文）");
      } else {
        // 空白行同样使用源码字体的行盒；选择及复制仍读取未修改的原文。
        code.setText(value.isEmpty() ? " " : value);
      }
      setGraphic(row);
    }
  }
}
