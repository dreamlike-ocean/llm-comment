package io.github.dreamlike.llmcomment.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverlappingCommentsTest {
  @TempDir Path workspace;

  @Test
  void overlappingRangesMigrateAndKeepIndependentRevisionsAndHistory() throws Exception {
    Path file = workspace.resolve("代码.java");
    String original = "首行\r\n甲😀\r\n乙文\r\n丙文\r\n丁文\r\n尾行";
    Files.writeString(file, original);
    try (var service = new CommentService(workspace)) {
      var a = service.create("代码.java", 2, 4, "交叉甲", "甲");
      var b = service.create("代码.java", 3, 5, "交叉乙", "乙");
      var same = service.create("代码.java", 2, 4, "同范围", "丙");
      var outer = service.create("代码.java", 1, 6, "包含", "丁");
      var boundary = service.create("代码.java", 4, 5, "共线", "戊");
      var comments = List.of(a, b, same, outer, boundary);
      Files.writeString(file, "前置\r\n" + original.replace("乙文", "乙修改文"));
      for (var comment : comments) {
        var moved = service.get(comment.id());
        assertEquals(comment.location().startLine() + 1, moved.location().startLine());
        assertEquals(comment.location().endLine() + 1, moved.location().endLine());
        assertEquals(comment.currentRevisionId(), moved.currentRevisionId());
        assertEquals(1, service.history(comment.id()).size());
        assertEquals(original, service.snapshot(comment.location().fileVersionId()).text());
      }
      var updated = service.update(a.id(), a.currentRevisionId(), "已改正文", "甲");
      assertEquals("同范围", service.get(same.id()).body());
      service.delete(b.id(), b.currentRevisionId(), "乙");
      assertEquals(4, service.list("代码.java", false).size());
      assertEquals(5, service.list("代码.java", true).size());
      assertEquals(updated.currentRevisionId(), service.get(a.id()).currentRevisionId());
      assertEquals(2, service.history(a.id()).size());
      assertEquals(2, service.history(b.id()).size());
      assertEquals(1, service.history(same.id()).size());
      assertEquals("甲😀\r\n乙文\r\n丙文", service.history(a.id()).getFirst().location().selectedText());
      assertEquals("前置\r\n" + original.replace("乙文", "乙修改文"), Files.readString(file));
    }
  }
}
