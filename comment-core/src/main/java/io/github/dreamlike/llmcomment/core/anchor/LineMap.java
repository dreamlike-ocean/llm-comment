package io.github.dreamlike.llmcomment.core.anchor;

import java.util.Arrays;
import java.util.Objects;

/** 行终止符支持 LF、CRLF 和单独的 CR；末尾终止符之后仍有一行。 */
public final class LineMap {
  private LineMap() {}

  public static int lineAt(String text, int offset) {
    Objects.requireNonNull(text, "text");
    if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("offset 超出文件范围");
    int line = 1;
    for (int i = 0; i < offset; i++) {
      char c = text.charAt(i);
      if (c == '\r') {
        if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          if (i + 1 < offset) {
            line++;
            i++;
          }
        } else line++;
      } else if (c == '\n') line++;
    }
    return line;
  }

  public static int lineCount(String text) {
    return lineAt(text, text.length());
  }

  /** 一次扫描后可用二分查找同一快照中的多个位置；offset 仍以 UTF-16 单元计。 */
  public static Index index(String text) {
    return new Index(text);
  }

  public static final class Index {
    private final int length;
    private final int[] starts;

    private Index(String text) {
      Objects.requireNonNull(text, "text");
      length = text.length();
      int[] buffer = new int[Math.min(length, 255) + 1];
      int size = 1;
      for (int i = 0; i < length; i++) {
        char c = text.charAt(i);
        if (c != '\r' && c != '\n') continue;
        if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') i++;
        if (size == buffer.length) buffer = Arrays.copyOf(buffer, buffer.length * 2);
        buffer[size++] = i + 1;
      }
      starts = Arrays.copyOf(buffer, size);
    }

    public int lineAt(int offset) {
      if (offset < 0 || offset > length) throw new IllegalArgumentException("offset 超出文件范围");
      int index = Arrays.binarySearch(starts, offset);
      return index >= 0 ? index + 1 : -index - 1;
    }

    public int lineCount() {
      return starts.length;
    }
  }
}
