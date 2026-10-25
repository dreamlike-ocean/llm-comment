package io.github.dreamlike.llmcomment.desktop;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;

/** 使用真实虚拟化行验证字号和留白变更；不修改源码、剪贴板或持久化设置。 */
final class SourceLayoutCheck {
  private SourceLayoutCheck() {}

  static void verify(SourceView source) {
    if (!Platform.isFxApplicationThread() || source.getScene() == null) {
      throw new IllegalStateException("源码布局检查需要已挂载场景的 JavaFX 线程");
    }
    @SuppressWarnings("unchecked")
    ListView<SourceLines.Line> list = (ListView<SourceLines.Line>) source.getCenter();
    int filled = -1;
    int empty = -1;
    int unicode = -1;
    for (int index = 0; index < list.getItems().size(); index++) {
      if (list.getItems().get(index).text().codePoints().anyMatch(point -> point > 0xFFFF)) {
        unicode = index;
      }
      if (list.getItems().get(index).text().isEmpty()) {
        if (empty < 0) empty = index;
      } else if (filled < 0) filled = index;
    }
    if (filled < 0 || empty < 0 || unicode < 0)
      throw new IllegalStateException("布局样本必须包含有字行、空行及补充平面字符");
    int[] targets = {filled, empty, unicode};
    String originalStyle = source.getStyle();
    List<String> originalSheets = List.copyOf(source.getStylesheets());
    int originalFirst = source.startLine();
    int originalLast = source.endLine();
    source.highlight(Math.min(filled, empty) + 1, Math.max(filled, empty) + 1);
    String selected = source.selectedText();
    int first = source.startLine();
    int last = source.endLine();
    try {
      source.setStyle(originalStyle + "; -fx-font-size: 16px;");
      Size[] baseline = measure(source, list, targets);
      unchanged(source, selected, first, last);
      source.setStyle(originalStyle + "; -fx-font-size: 24px;");
      Size[] large = measure(source, list, targets);
      unchanged(source, selected, first, last);
      for (int index = 0; index < baseline.length; index++) {
        if (large[index].font() <= baseline[index].font()
            || large[index].height() <= baseline[index].height() + 0.5) {
          throw new IllegalStateException(
              "源码行未随字号增高：原始=" + baseline[index] + "，放大=" + large[index]);
        }
      }
      source.setStyle(originalStyle + "; -fx-font-size: 16px;");
      Size[] restored = measure(source, list, targets);
      sameHeights(baseline, restored, "恢复字号");
      String paddingSheet =
          "data:text/css;base64,"
              + Base64.getEncoder()
                  .encodeToString(
                      ".source-line { -fx-padding: 1em 0.875em 1em 0; }"
                          .getBytes(StandardCharsets.UTF_8));
      source.getStylesheets().add(paddingSheet);
      Size[] padded = measure(source, list, targets);
      for (int index = 0; index < baseline.length; index++) {
        if (padded[index].height() <= restored[index].height() + 0.5) {
          throw new IllegalStateException(
              "源码行未随 CSS 上下留白增高：原始=" + restored[index] + "，加大=" + padded[index]);
        }
      }
      source.getStylesheets().setAll(originalSheets);
      sameHeights(baseline, measure(source, list, targets), "恢复留白");
      unchanged(source, selected, first, last);
      System.out.println("源码行布局检查通过：文字/空行/emoji 行随字号与 CSS 留白增减，选区原文及行号不变");
    } finally {
      source.setStyle(originalStyle);
      source.getStylesheets().setAll(originalSheets);
      if (originalFirst == 0) source.clearSelection();
      else source.highlight(originalFirst, originalLast);
      layout(source);
    }
  }

  private static Size[] measure(
      SourceView source, ListView<SourceLines.Line> list, int... indices) {
    Size[] result = new Size[indices.length];
    for (int index = 0; index < indices.length; index++) {
      int target = indices[index];
      list.scrollTo(target);
      layout(source);
      ListCell<?> cell =
          list.lookupAll(".source-line").stream()
              .filter(
                  node ->
                      node instanceof ListCell<?> candidate
                          && candidate.getIndex() == target
                          && !candidate.isEmpty())
              .map(node -> (ListCell<?>) node)
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("未找到目标可见源码行"));
      HBox row = (HBox) cell.getGraphic();
      Label code = (Label) row.lookup(".code-text");
      Label number = (Label) row.lookup(".line-number");
      if (!number.getText().equals(Integer.toString(indices[index] + 1))) {
        throw new IllegalStateException("字号变化后行号改变");
      }
      if (code.getHeight() + 0.5 < code.prefHeight(-1)
          || row.getHeight() + 0.5 < row.prefHeight(-1)
          || cell.getHeight() + 0.5 < cell.prefHeight(cell.getWidth())
          || cell.getHeight() + 0.5
              < row.getHeight() + cell.getInsets().getTop() + cell.getInsets().getBottom()) {
        throw new IllegalStateException(
            "源码行内容被垂直裁切：行="
                + (indices[index] + 1)
                + "，单元高度="
                + cell.getHeight()
                + "，行盒高度="
                + row.getHeight()
                + "，文字高度="
                + code.getHeight()
                + "，文字首选高度="
                + code.prefHeight(-1));
      }
      result[index] = new Size(cell.getHeight(), code.getFont().getSize());
    }
    return result;
  }

  private static void layout(SourceView source) {
    var root = source.getScene().getRoot();
    root.applyCss();
    root.requestLayout();
    root.layout();
    source.layout();
  }

  private static void sameHeights(Size[] expected, Size[] actual, String action) {
    for (int index = 0; index < expected.length; index++) {
      if (Math.abs(expected[index].height() - actual[index].height()) > 0.5) {
        throw new IllegalStateException(
            action + "后行高未恢复：原始=" + expected[index] + "，当前=" + actual[index]);
      }
    }
  }

  private static void unchanged(SourceView source, String text, int first, int last) {
    if (!source.selectedText().equals(text)
        || source.startLine() != first
        || source.endLine() != last) {
      throw new IllegalStateException("样式变化改变了选区原文或行号");
    }
  }

  private record Size(double height, double font) {}
}
