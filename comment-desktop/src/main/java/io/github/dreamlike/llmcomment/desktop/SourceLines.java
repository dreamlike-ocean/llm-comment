package io.github.dreamlike.llmcomment.desktop;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 保存原文的 UTF-16 行边界，保留所有换行形式和末尾空行。 */
public final class SourceLines {
  public record Line(int number, int startOffset, int endOffset, String text) {}

  private final String text;
  private final List<Line> lines;

  public SourceLines(String text) {
    this.text = Objects.requireNonNull(text);
    var result = new ArrayList<Line>();
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      char character = text.charAt(i);
      if (character == '\r' || character == '\n') {
        result.add(new Line(result.size() + 1, start, i, text.substring(start, i)));
        if (character == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        start = i + 1;
      }
    }
    result.add(new Line(result.size() + 1, start, text.length(), text.substring(start)));
    lines = List.copyOf(result);
  }

  public List<Line> lines() {
    return lines;
  }

  /** 返回连续行的原始正文及内部换行，不包含最后一行的换行符。 */
  public String selectedText(int startLine, int endLine) {
    if (startLine < 1 || endLine < startLine || endLine > lines.size()) return "";
    return text.substring(
        lines.get(startLine - 1).startOffset(), lines.get(endLine - 1).endOffset());
  }
}
