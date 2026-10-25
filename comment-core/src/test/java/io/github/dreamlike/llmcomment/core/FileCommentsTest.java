package io.github.dreamlike.llmcomment.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCommentsTest {
  @TempDir Path root;

  @Test
  void inspectionSharesSnapshotAndPreservesMissingFile() throws Exception {
    Path file = root.resolve("中文.java");
    Files.writeString(file, "第一行\r\n目标\r\n末行");
    try (CommentService service = new CommentService(root)) {
      FileComments empty = service.inspect("中文.java", false);
      assertEquals("第一行\r\n目标\r\n末行", empty.snapshot().text());
      assertTrue(empty.comments().isEmpty());
      CommentView comment = service.create("中文.java", 2, 2, "检查", "甲", empty.snapshot().id());
      Files.writeString(file, "新增\r\n第一行\r\n目标\r\n末行");
      FileComments moved = service.inspect("中文.java", false);
      assertEquals(3, moved.comments().getFirst().location().startLine());
      assertEquals(moved.snapshot().id(), moved.comments().getFirst().location().fileVersionId());
      assertEquals(comment.currentRevisionId(), moved.comments().getFirst().currentRevisionId());
      assertThrows(UnsupportedOperationException.class, () -> moved.comments().clear());
      Files.delete(file);
      FileComments missing = service.inspect("中文.java", false);
      assertTrue(missing.missing());
      assertEquals(moved.snapshot(), missing.snapshot());
      assertEquals(AnchorStatus.MISSING_FILE, missing.comments().getFirst().location().status());
      assertEquals(
          missing.snapshot().id(), missing.comments().getFirst().location().fileVersionId());
      Files.writeString(file, moved.snapshot().text());
      assertFalse(service.inspect("中文.java", false).missing());
    }
  }

  @Test
  void staleSnapshotRollsBackWithoutCreatingCommentsOrRevisions() throws Exception {
    Path file = root.resolve("代码.java");
    Files.writeString(file, "前\n目标");
    try (CommentService service = new CommentService(root)) {
      CommentView first = service.create("代码.java", 2, 2, "原评论", "甲");
      String previous = first.location().fileVersionId();
      Files.writeString(file, "新增\n前\n目标");
      CommentException conflict =
          assertThrows(
              CommentException.class, () -> service.create("代码.java", 2, 2, "错误行", "甲", previous));
      assertEquals("FILE_CONFLICT", conflict.code());
      assertNotEquals(previous, conflict.details().get("currentFileVersionId"));
      assertEquals(1, service.history(first.id()).size());
      FileComments current = service.inspect("代码.java", true);
      assertEquals(1, current.comments().size());
      assertEquals(3, current.comments().getFirst().location().startLine());
      CommentView second = service.create("代码.java", 3, 3, "新评论", "甲", current.snapshot().id());
      assertEquals("目标", second.location().selectedText());
    }
  }
}
