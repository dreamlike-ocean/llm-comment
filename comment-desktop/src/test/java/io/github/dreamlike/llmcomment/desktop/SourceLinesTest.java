package io.github.dreamlike.llmcomment.desktop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SourceLinesTest {
  @Test
  void preservesMixedNewlinesAndUtf16Offsets() {
    var lines = new SourceLines("中文😀\r\n第二行\r第三行\n");
    assertEquals(4, lines.lines().size());
    assertEquals(4, lines.lines().getFirst().endOffset());
    assertEquals(6, lines.lines().get(1).startOffset());
    assertEquals("中文😀\r\n第二行\r第三行", lines.selectedText(1, 3));
    assertEquals("中文😀\r\n第二行\r第三行\n", lines.selectedText(1, 4));
    assertEquals("", lines.selectedText(4, 4));
  }

  @Test
  void retainsEmptyFileAndConsecutiveEmptyLines() {
    assertEquals(1, new SourceLines("").lines().size());
    var lines = new SourceLines("\r\n\r\n\n");
    assertEquals(4, lines.lines().size());
    assertEquals("\r\n\r\n\n", lines.selectedText(1, 4));
    assertEquals("", lines.selectedText(0, 1));
    assertEquals("", lines.selectedText(3, 2));
  }

  @Test
  void selectionRetainsLongLines() {
    String text = "中😀".repeat(20_000);
    assertEquals(text, new SourceLines(text).selectedText(1, 1));
  }
}
