package io.github.dreamlike.llmcomment.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommentServiceTest {
  @TempDir Path workspace;

  private void source(String text) throws Exception {
    Files.writeString(workspace.resolve("代码.java"), text);
  }

  @Test
  void crudHistoryAndSnapshotsAreIndependentOfGit() throws Exception {
    String original = "第一行\r\n第二行\r\n第三行";
    source(original);
    try (CommentService s = new CommentService(workspace)) {
      CommentView c = s.create("代码.java", 2, 2, "检查", "甲");
      assertEquals("第二行", c.location().selectedText());
      String version = c.location().fileVersionId();
      CommentView updated = s.update(c.id(), c.currentRevisionId(), "已检查", "乙");
      assertEquals(c.id(), updated.id());
      assertEquals(c.currentRevisionId(), s.history(c.id()).get(1).parentRevisionId());
      assertEquals(original, s.snapshot(version).text());
      assertEquals(original, Files.readString(workspace.resolve("代码.java")));
      CommentException conflict =
          assertThrows(
              CommentException.class, () -> s.update(c.id(), c.currentRevisionId(), "旧", "甲"));
      assertEquals("CONFLICT", conflict.code());
      assertEquals(updated.currentRevisionId(), conflict.details().get("currentRevisionId"));
      CommentView deleted = s.delete(c.id(), updated.currentRevisionId(), "甲");
      assertTrue(deleted.deleted());
      assertTrue(s.list(null, false).isEmpty());
      assertEquals(1, s.list(null, true).size());
      assertEquals(3, s.history(c.id()).size());
      assertEquals("CREATE", s.history(c.id()).getFirst().action());
    }
  }

  @Test
  void migrationMissingAndRestorationPreserveHistory() throws Exception {
    source("前\n目标\n后");
    try (CommentService s = new CommentService(workspace)) {
      CommentView first = s.create("代码.java", 2, 2, "一", "甲");
      CommentView second = s.create("代码.java", 3, 3, "二", "乙");
      source("新增\n前\n目标\n后");
      CommentView shifted = s.get(first.id());
      assertEquals(3, shifted.location().startLine());
      assertEquals(4, s.get(second.id()).location().startLine());
      assertEquals(first.currentRevisionId(), shifted.currentRevisionId());
      assertEquals(2, s.history(first.id()).getFirst().location().startLine());
      Files.delete(workspace.resolve("代码.java"));
      assertEquals(AnchorStatus.MISSING_FILE, s.get(first.id()).location().status());
      assertNull(s.get(first.id()).location().startOffset());
      source("新增\n前\n目标\n后");
      assertEquals(AnchorStatus.ACTIVE, s.get(first.id()).location().status());
      assertEquals(3, s.get(first.id()).location().startLine());
    }
  }

  @Test
  void decodingFailureRollsBackAndPathsAreProtected() throws Exception {
    source("原文");
    try (CommentService s = new CommentService(workspace)) {
      CommentView c = s.create("代码.java", 1, 1, "正文", "甲");
      Files.write(workspace.resolve("代码.java"), new byte[] {(byte) 0xc3, 0x28});
      assertEquals(
          "FILE_READ_ERROR", assertThrows(CommentException.class, () -> s.get(c.id())).code());
      source("原文");
      assertEquals(c.location().fileVersionId(), s.get(c.id()).location().fileVersionId());
      assertEquals(1, s.history(c.id()).size());
      assertEquals(
          "INVALID_PATH",
          assertThrows(CommentException.class, () -> s.create("../escape", 1, 1, "正文", "甲"))
              .code());
      assertEquals(
          "INVALID_PATH",
          assertThrows(
                  CommentException.class,
                  () -> s.create(".llm-comments/comments.db", 1, 1, "正文", "甲"))
              .code());
      Path outside = Files.createTempFile("comment-outside", ".txt");
      try {
        Files.createSymbolicLink(workspace.resolve("link"), outside);
        assertEquals(
            "INVALID_PATH",
            assertThrows(CommentException.class, () -> s.create("link", 1, 1, "正文", "甲")).code());
      } finally {
        Files.delete(outside);
      }
    }
  }

  @Test
  void independentConnectionsResolveConcurrentRevisionConflict() throws Exception {
    source("文字");
    try (CommentService first = new CommentService(workspace);
        CommentService second = new CommentService(workspace);
        ExecutorService pool = Executors.newFixedThreadPool(2)) {
      CommentView c = first.create("代码.java", 1, 1, "初始", "甲");
      CountDownLatch gate = new CountDownLatch(1);
      Callable<String> a =
          () -> {
            gate.await();
            try {
              first.update(c.id(), c.currentRevisionId(), "一", "甲");
              return "ok";
            } catch (CommentException e) {
              return e.code();
            }
          };
      Callable<String> b =
          () -> {
            gate.await();
            try {
              second.update(c.id(), c.currentRevisionId(), "二", "乙");
              return "ok";
            } catch (CommentException e) {
              return e.code();
            }
          };
      Future<String> fa = pool.submit(a), fb = pool.submit(b);
      gate.countDown();
      assertEquals(java.util.Set.of("ok", "CONFLICT"), java.util.Set.of(fa.get(), fb.get()));
      assertEquals(2, first.history(c.id()).size());
    }
  }

  @Test
  void separateJvmWritersCheckBaseInsideTransaction() throws Exception {
    source("进程并发");
    try (CommentService service = new CommentService(workspace)) {
      CommentView c = service.create("代码.java", 1, 1, "原始", "甲");
      String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
      String cp = System.getProperty("java.class.path");
      Process first =
          new ProcessBuilder(
                  javaExecutable,
                  "--enable-native-access=ALL-UNNAMED",
                  "-cp",
                  cp,
                  WriterProcess.class.getName(),
                  workspace.toString(),
                  c.id(),
                  c.currentRevisionId())
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      Process second =
          new ProcessBuilder(
                  javaExecutable,
                  "--enable-native-access=ALL-UNNAMED",
                  "-cp",
                  cp,
                  WriterProcess.class.getName(),
                  workspace.toString(),
                  c.id(),
                  c.currentRevisionId())
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      assertTrue(first.waitFor(20, TimeUnit.SECONDS));
      assertTrue(second.waitFor(20, TimeUnit.SECONDS));
      assertEquals(0, first.exitValue());
      assertEquals(0, second.exitValue());
      assertEquals(
          java.util.Set.of("ok", "CONFLICT"),
          java.util.Set.of(
              new String(first.getInputStream().readAllBytes()).strip(),
              new String(second.getInputStream().readAllBytes()).strip()));
      assertEquals(2, service.history(c.id()).size());
    }
  }

  @Test
  void longFileCommentsShareSnapshotAndKeepIndependentRanges() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= 4000; i++) text.append("中文😀第").append(i).append("行\r\n");
    source(text.toString());
    try (CommentService service = new CommentService(workspace)) {
      var created = new java.util.ArrayList<CommentView>();
      for (int i = 1; i <= 40; i++)
        created.add(service.create("代码.java", i * 97, i * 97, "评论" + i, "甲"));
      assertEquals(created, service.list("代码.java", false));
      source("新增\r\n" + text);
      var moved = service.list(null, false);
      String version = moved.getFirst().location().fileVersionId();
      for (int i = 0; i < moved.size(); i++) {
        assertEquals(created.get(i).id(), moved.get(i).id());
        assertEquals(created.get(i).location().startLine() + 1, moved.get(i).location().startLine());
        assertEquals(created.get(i).location().selectedText(), moved.get(i).location().selectedText());
        assertEquals(version, moved.get(i).location().fileVersionId());
        assertEquals(1, service.history(moved.get(i).id()).size());
      }
      Files.delete(workspace.resolve("代码.java"));
      assertTrue(service.list(null, false).stream()
          .allMatch(c -> c.location().status() == AnchorStatus.MISSING_FILE));
      source("新增\r\n" + text);
      assertEquals(moved, service.list(null, false));
    }
  }

  @Test
  void workspaceListOrdersAcrossFilesAndPreservesMissingLocations() throws Exception {
    source("中文目标");
    Files.writeString(workspace.resolve("a.java"), "另一个文件");
    try (CommentService service = new CommentService(workspace)) {
      CommentView first = service.create("代码.java", 1, 1, "第一", "甲");
      CommentView second = service.create("a.java", 1, 1, "第二", "甲");
      CommentView third = service.create("代码.java", 1, 1, "第三", "甲");
      CommentView deleted = service.delete(second.id(), second.currentRevisionId(), "甲");
      // 固定相同时间以覆盖跨文件 id 排序；最后一个评论设为更早时间。
      try (var connection = java.sql.DriverManager.getConnection(
              "jdbc:sqlite:" + workspace.resolve(".llm-comments/comments.db"));
          var statement = connection.prepareStatement("UPDATE comments SET created=? WHERE id=?")) {
        for (CommentView comment : java.util.List.of(first, second, third)) {
          statement.setString(1, comment.id().equals(third.id()) ? "2020-01-01T00:00:00Z" : "2021-01-01T00:00:00Z");
          statement.setString(2, comment.id());
          statement.executeUpdate();
        }
      }
      Files.delete(workspace.resolve("代码.java"));
      var all = service.list(null, true);
      var expected = new java.util.ArrayList<>(java.util.List.of(first.id(), second.id()));
      expected.sort(String::compareTo);
      expected.addFirst(third.id());
      assertEquals(expected, all.stream().map(CommentView::id).toList());
      for (CommentView comment : all) {
        if (comment.file().equals("代码.java")) {
          assertEquals(AnchorStatus.MISSING_FILE, comment.location().status());
          assertNull(comment.location().startLine());
          assertEquals(first.location().fileVersionId(), comment.location().fileVersionId());
        } else {
          assertEquals(AnchorStatus.ACTIVE, comment.location().status());
          assertTrue(comment.deleted());
          assertEquals(deleted.currentRevisionId(), comment.currentRevisionId());
        }
      }
      assertEquals(java.util.List.of(third.id(), first.id()),
          service.list(null, false).stream().map(CommentView::id).toList());
      assertEquals(java.util.List.of(third.id(), first.id()),
          service.list("代码.java", false).stream().map(CommentView::id).toList());
    }
  }

  @Test
  void unchangedReadsDoNotWriteAndSameTimestampEditsAreDetected() throws Exception {
    source("前\n目标\n后");
    Path source = workspace.resolve("代码.java");
    try (CommentService service = new CommentService(workspace)) {
      CommentView comment = service.create("代码.java", 2, 2, "正文", "甲");
      try (var connection = java.sql.DriverManager.getConnection(
              "jdbc:sqlite:" + workspace.resolve(".llm-comments/comments.db"));
          var statement = connection.createStatement()) {
        int version;
        try (var result = statement.executeQuery("PRAGMA data_version")) {
          assertTrue(result.next());
          version = result.getInt(1);
        }
        assertEquals(comment, service.get(comment.id()));
        assertEquals(java.util.List.of(comment), service.list(null, false));
        try (var result = statement.executeQuery("PRAGMA data_version")) {
          assertTrue(result.next());
          assertEquals(version, result.getInt(1));
        }
      }
      var timestamp = Files.getLastModifiedTime(source);
      source("新\n前\n目标\n后");
      Files.setLastModifiedTime(source, timestamp);
      assertEquals(3, service.get(comment.id()).location().startLine());
    }
  }

  @Test
  void immutableHistoryReadsDoNotWaitForAnotherWriter() throws Exception {
    source("原文");
    try (CommentService service = new CommentService(workspace)) {
      CommentView comment = service.create("代码.java", 1, 1, "正文", "甲");
      var history = service.history(comment.id());
      try (var connection = java.sql.DriverManager.getConnection(
              "jdbc:sqlite:" + workspace.resolve(".llm-comments/comments.db"));
          var statement = connection.createStatement()) {
        statement.execute("BEGIN IMMEDIATE");
        try {
          assertEquals(history, service.history(comment.id()));
          assertEquals("原文", service.snapshot(comment.location().fileVersionId()).text());
        } finally {
          statement.execute("ROLLBACK");
        }
      }
    }
  }

  public static class WriterProcess {
    public static void main(String[] args) {
      try (CommentService service = new CommentService(Path.of(args[0]))) {
        try {
          service.update(args[1], args[2], "进程修改", "进程");
          System.out.println("ok");
        } catch (CommentException e) {
          System.out.println(e.code());
        }
      }
    }
  }

  @Test
  void customDatabaseInWorkspaceDoesNotExcludeSourceSiblings() throws Exception {
    source("源码");
    try (CommentService service = new CommentService(workspace, workspace.resolve("state.db"))) {
      CommentView c = service.create("代码.java", 1, 1, "正文", "甲");
      assertEquals("源码", c.location().selectedText());
      for (String artifact :
          java.util.List.of("state.db", "state.db-wal", "state.db-shm", "state.db-journal")) {
        assertEquals(
            "INVALID_PATH",
            assertThrows(CommentException.class, () -> service.create(artifact, 1, 1, "正文", "甲"))
                .code());
      }
      Files.createSymbolicLink(workspace.resolve("db-alias"), workspace.resolve("state.db"));
      assertEquals(
          "INVALID_PATH",
          assertThrows(CommentException.class, () -> service.create("db-alias", 1, 1, "正文", "甲"))
              .code());
    }
  }

  @Test
  void historicalRevisionsRemainReadableWhenCurrentSourceIsUnreadable() throws Exception {
    source("原文");
    try (CommentService service = new CommentService(workspace)) {
      CommentView c = service.create("代码.java", 1, 1, "正文", "甲");
      var history = service.history(c.id());
      Files.write(workspace.resolve("代码.java"), new byte[] {(byte) 0xc3, 0x28});
      assertEquals(
          "FILE_READ_ERROR",
          assertThrows(CommentException.class, () -> service.get(c.id())).code());
      assertEquals(history, service.history(c.id()));
      assertEquals("原文", service.snapshot(history.getFirst().location().fileVersionId()).text());
    }
  }

  @Test
  void relativeDatabaseIsResolvedAgainstRelativeWorkspace() throws Exception {
    source("原文");
    Path relativeWorkspace = Path.of("").toAbsolutePath().relativize(workspace);
    try (CommentService service =
        new CommentService(relativeWorkspace, Path.of("store/state.db"))) {
      CommentView comment = service.create("代码.java", 1, 1, "", "甲");
      assertEquals("", comment.body());
      assertTrue(Files.isRegularFile(workspace.resolve("store/state.db")));
      assertEquals("", service.update(comment.id(), comment.currentRevisionId(), "", "乙").body());
    }
    try (CommentService service = new CommentService(relativeWorkspace)) {
      service.create("代码.java", 1, 1, "默认", "甲");
      assertTrue(Files.isRegularFile(workspace.resolve(".llm-comments/comments.db")));
    }
  }

  @Test
  void emptyFileHasOneEmptyLine() throws Exception {
    source("");
    try (CommentService s = new CommentService(workspace)) {
      CommentView c = s.create("代码.java", 1, 1, "空文件", "甲");
      assertEquals(0, c.location().startOffset());
      assertEquals(0, c.location().endOffset());
      assertThrows(CommentException.class, () -> s.create("代码.java", 2, 2, "越界", "甲"));
    }
  }
}
