package io.github.dreamlike.llmcomment.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {
  @TempDir Path temporary;

  @Test
  void discoveryUsesNearestMarkerAndOtherwiseWorkingDirectory() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("中文项目"));
    Path nested = Files.createDirectories(root.resolve("模块/源码"));
    assertEquals(nested.toRealPath(), Workspace.resolve(nested, null, null).root());
    assertFalse(Files.exists(nested.resolve(".llm-comments")));
    Files.createDirectory(root.resolve(".llm-comments"));
    assertEquals(root.toRealPath(), Workspace.resolve(nested, null, null).root());
    Path module = nested.getParent();
    Files.createDirectory(module.resolve(".llm-comments"));
    assertEquals(module.toRealPath(), Workspace.resolve(nested, null, null).root());
  }

  @Test
  void explicitRootAndDatabaseAreResolvedWithoutCreatingAnything() throws Exception {
    Files.createDirectory(temporary.resolve(".llm-comments"));
    Path cwd = Files.createDirectories(temporary.resolve("nested/目录"));
    Workspace workspace = Workspace.resolve(cwd, Path.of(".."), Path.of("数据/state.db"));
    assertEquals(cwd.getParent().toRealPath(), workspace.root());
    assertEquals(workspace.root().resolve("数据/state.db"), workspace.database());
    assertFalse(Files.exists(workspace.database().getParent()));
    Path absoluteDatabase = temporary.resolve("outside/state.db");
    assertEquals(absoluteDatabase, Workspace.resolve(cwd, null, absoluteDatabase).database());
    assertEquals(
        temporary.resolve(".llm-comments/comments.db"),
        Workspace.resolve(cwd, null, null).database());
    assertFalse(Files.exists(absoluteDatabase.getParent()));
  }

  @Test
  void directoriesAreValidatedAndSymlinkWorkingDirectoryIsCanonical() throws Exception {
    Path file = Files.writeString(temporary.resolve("file"), "text");
    assertThrows(CommentException.class, () -> Workspace.resolve(file, null, null));
    assertThrows(CommentException.class, () -> Workspace.resolve(temporary, file, null));
    assertThrows(CommentException.class, () -> new CommentService(file));
    Path directory = Files.createDirectory(temporary.resolve("directory"));
    Path link = Files.createSymbolicLink(temporary.resolve("link"), directory);
    assertEquals(directory.toRealPath(), Workspace.resolve(link, null, null).root());
  }

  @Test
  void sourcePathsAreRootRelativeAndServiceChecksRealBoundaries() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("root"));
    Workspace workspace = Workspace.resolve(root, null, null);
    Path source = Files.writeString(root.resolve("中文.java"), "源码");
    assertEquals("中文.java", workspace.sourcePath(source.toString()));
    assertEquals("中文.java", workspace.sourcePath("目录/../中文.java"));
    assertEquals(source, workspace.resolvePath("中文.java"));
    assertThrows(CommentException.class, () -> workspace.sourcePath("../escape"));
    assertThrows(CommentException.class, () -> workspace.sourcePath(" "));
    Path outside = Files.writeString(temporary.resolve("outside.java"), "外部");
    Files.createSymbolicLink(root.resolve("escape.java"), outside);
    try (CommentService service = new CommentService(workspace)) {
      assertEquals("中文.java", service.create(source.toString(), 1, 1, "评论", "作者").file());
      assertEquals(
          "INVALID_PATH",
          assertThrows(
                  CommentException.class,
                  () -> service.create(outside.toString(), 1, 1, "评论", "作者"))
              .code());
      assertEquals(
          "INVALID_PATH",
          assertThrows(
                  CommentException.class, () -> service.create("escape.java", 1, 1, "评论", "作者"))
              .code());
    }
  }
}
