package io.github.dreamlike.llmcomment.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainWorkspaceTest {
  @TempDir Path root;

  @Test
  void startupDiscoversAncestorAndExplicitRootWins() throws Exception {
    Files.createDirectory(root.resolve(".llm-comments"));
    Path nested = Files.createDirectories(root.resolve("模块/src"));
    assertEquals(0, run(nested));
    assertTrue(Files.isRegularFile(root.resolve(".llm-comments/comments.db")));
    assertFalse(Files.exists(nested.resolve(".llm-comments")));
    assertEquals(0, run(nested, "--workspace", "..", "--database", "state/custom.db"));
    assertTrue(Files.isRegularFile(nested.getParent().resolve("state/custom.db")));
    assertFalse(Files.exists(nested.resolve("state")));
  }

  @Test
  void startupFallsBackToCwdAndHelpDoesNotCreateDatabase() throws Exception {
    Path nested = Files.createDirectories(root.resolve("中文/src"));
    assertEquals(0, run(nested, "--help"));
    assertFalse(Files.exists(nested.resolve(".llm-comments")));
    assertEquals(0, run(nested));
    assertTrue(Files.isRegularFile(nested.resolve(".llm-comments/comments.db")));
    assertEquals(2, run(nested, "--workspace"));
    assertEquals(2, run(nested, "--workspace", "\u0000"));
    assertEquals(2, run(nested, "--workspace", "--database"));
    assertEquals(2, run(nested, "--workspace", " "));
    assertEquals(2, run(nested, "--database", ""));
    Path file = Files.writeString(nested.resolve("file.txt"), "普通文件");
    assertEquals(2, run(nested, "--workspace", file.toString()));
  }

  private int run(Path cwd, String... args) {
    var errors = new ByteArrayOutputStream();
    int exit =
        Main.run(
            args, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), errors, cwd);
    if (exit == 0) assertEquals("", errors.toString(StandardCharsets.UTF_8));
    return exit;
  }
}
