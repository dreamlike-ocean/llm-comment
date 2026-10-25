package io.github.dreamlike.llmcomment.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnicodeFileTest {
  @TempDir Path root;

  @Test
  void utf8SnapshotAndHistoryPreserveSupplementaryAndCombinedCharacters() throws Exception {
    String selected = "简体繁體𠮷𠀀龘，。：「」\r\ne\u0301 →≤≥✓ 😀🚀🧑🏽‍💻👩‍💻❤️";
    String text = "\uFEFF开头\r\n" + selected + "\r\n结尾";
    Path file = root.resolve("中文𠮷.java");
    byte[] original = text.getBytes(StandardCharsets.UTF_8);
    Files.write(file, original);
    try (var service = new CommentService(root)) {
      var view = service.inspect("中文𠮷.java", false);
      assertEquals(text, view.snapshot().text());
      var comment = service.create("中文𠮷.java", 2, 3, selected, "审阅者𠮷", view.snapshot().id());
      assertEquals(selected, comment.location().selectedText());
      assertEquals(selected, service.history(comment.id()).getFirst().body());
      Files.writeString(file, "新增\r\n" + text);
      assertEquals(3, service.get(comment.id()).location().startLine());
      assertEquals(text, service.snapshot(view.snapshot().id()).text());
      Files.write(file, original);
      assertEquals(2, service.get(comment.id()).location().startLine());
      assertArrayEquals(original, Files.readAllBytes(file));
    }
  }

  @Test
  void malformedUtf8ReportsEncodingErrorAndPreservesSavedSnapshot() throws Exception {
    Path file = root.resolve("代码.java");
    Files.writeString(file, "中文源码");
    try (var service = new CommentService(root)) {
      var comment = service.create("代码.java", 1, 1, "原评论", "甲");
      Files.write(file, new byte[] {(byte) 0xE4, (byte) 0xB8});
      var error = assertThrows(CommentException.class, () -> service.inspect("代码.java", false));
      assertEquals("FILE_READ_ERROR", error.code());
      assertTrue(error.getMessage().contains("UTF-8"));
      assertEquals("中文源码", service.snapshot(comment.location().fileVersionId()).text());
      assertEquals(1, service.history(comment.id()).size());
      Files.writeString(file, "中文源码");
      assertEquals(AnchorStatus.ACTIVE, service.get(comment.id()).location().status());
    }
  }
}
