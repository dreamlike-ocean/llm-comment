package io.github.dreamlike.llmcomment.desktop;

import io.github.dreamlike.llmcomment.core.AnchorStatus;
import io.github.dreamlike.llmcomment.core.CommentView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/** 只读、虚拟化源码视图；选择和复制始终使用完整原文。 */
public final class SourceView extends BorderPane {
  private static final int DISPLAY_LIMIT = 10_000;
  private final ListView<SourceLines.Line> list = new ListView<>();
  private final ReadOnlyStringWrapper selectionText = new ReadOnlyStringWrapper("选择代码行");
  private final Map<Integer, Boolean> markers = new HashMap<>();
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
    setCenter(list);
  }

  public void show(String text, List<CommentView> comments) {
    source = new SourceLines(text);
    markers.clear();
    for (var comment : comments) {
      var location = comment.location();
      if (location != null
          && location.startLine() != null
          && (location.status() == AnchorStatus.ACTIVE
              || location.status() == AnchorStatus.MODIFIED)) {
        // 同一行只要存在未删除评论，就使用正常标记。
        markers.merge(location.startLine(), comment.deleted(), (left, right) -> left && right);
      }
    }
    clearSelection();
    list.setItems(FXCollections.observableArrayList(source.lines()));
    list.scrollTo(0);
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

  private final class SourceCell extends ListCell<SourceLines.Line> {
    private final Label number = new Label();
    private final Label marker = new Label();
    private final Label code = new Label();
    private final HBox row = new HBox(number, marker, code);

    private SourceCell() {
      getStyleClass().add("source-line");
      number.getStyleClass().add("line-number");
      marker.getStyleClass().add("line-marker");
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
      Boolean deleted = markers.get(line.number());
      marker.setText(deleted == null ? "" : deleted ? "○" : "●");
      marker.setOpacity(Boolean.TRUE.equals(deleted) ? 0.45 : 1);
      marker.setTooltip(deleted == null ? null : new Tooltip(deleted ? "此行有已删除评论" : "此行有评论"));
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
