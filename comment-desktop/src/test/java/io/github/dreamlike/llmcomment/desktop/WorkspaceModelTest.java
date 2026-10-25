package io.github.dreamlike.llmcomment.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dreamlike.llmcomment.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceModelTest {
  @TempDir Path root;

  private Workspace workspace() {
    return Workspace.resolve(root, root, Path.of("metadata.db"));
  }

  @Test
  void childrenAreLazySortedAndProtectMetadataAndLinks() throws Exception {
    for (String name : List.of(".git", ".idea", "target", ".llm-comments", "目录")) {
      Files.createDirectories(root.resolve(name));
    }
    Files.writeString(root.resolve("代码.java"), "中文\r\n源码");
    Files.writeString(root.resolve("metadata.db-journal"), "");
    Path outside = Files.createTempDirectory("comment-outside-");
    try {
      Files.writeString(outside.resolve("secret"), "外部");
      Files.createSymbolicLink(root.resolve("越界"), outside.resolve("secret"));
      Files.createSymbolicLink(root.resolve("循环"), root);
      Files.createSymbolicLink(root.resolve("合法.java"), root.resolve("代码.java"));
      Files.createSymbolicLink(root.resolve("元数据"), root.resolve("metadata.db"));
      try (WorkspaceModel model = new WorkspaceModel(workspace())) {
        assertEquals(
            List.of("目录", "代码.java", "合法.java"),
            model.children("").stream().map(WorkspaceModel.Entry::name).toList());
        assertTrue(model.children("目录").isEmpty());
        assertThrows(CommentException.class, () -> model.children("循环"));
        assertThrows(CommentException.class, () -> model.children("../"));
        assertEquals("中文\r\n源码", model.load("合法.java", false).snapshot().text());
        Files.write(root.resolve("非法.java"), new byte[] {(byte) 0xff});
        assertEquals(
            "FILE_READ_ERROR",
            assertThrows(CommentException.class, () -> model.load("非法.java", false)).code());
      }
    } finally {
      Files.deleteIfExists(outside.resolve("secret"));
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void concurrentUpdatesCheckOriginalRevision() throws Exception {
    Files.writeString(root.resolve("代码.java"), "源码");
    try (WorkspaceModel first = new WorkspaceModel(workspace());
        WorkspaceModel second = new WorkspaceModel(workspace());
        ExecutorService pool = Executors.newFixedThreadPool(2)) {
      FileComments loaded = first.load("代码.java", false);
      CommentView original = first.create("代码.java", 1, 1, "原始", "甲", loaded.snapshot().id());
      CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
      List<Future<String>> results = new ArrayList<>();
      for (WorkspaceModel model : List.of(first, second)) {
        results.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  go.await();
                  try {
                    model.update(original, "修改", "乙");
                    return "OK";
                  } catch (CommentException e) {
                    return e.code();
                  }
                }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      go.countDown();
      List<String> outcomes = new ArrayList<>();
      for (Future<String> result : results) outcomes.add(result.get(15, TimeUnit.SECONDS));
      assertEquals(Set.of("OK", "CONFLICT"), new HashSet<>(outcomes));
      assertEquals(2, first.history(original.id()).size());
      CommentView updated = first.load("代码.java", false).comments().getFirst();
      assertTrue(first.delete(updated, "甲").deleted());
      assertEquals(3, first.history(original.id()).size());
      assertEquals("源码", first.snapshot(loaded.snapshot().id()).text());
    }
  }
}
