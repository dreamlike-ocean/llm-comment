package io.github.dreamlike.llmcomment.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
  @TempDir Path workspace;
  private static final ObjectMapper JSON = new ObjectMapper();

  record Result(int code, String out, String err) {
    JsonNode json() throws IOException {
      return JSON.readTree(code == 0 ? out : err);
    }
  }

  private Result run(String stdin, String... args) {
    List<String> all = new ArrayList<>(List.of("--workspace", workspace.toString()));
    all.addAll(List.of(args));
    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    int code =
        Main.run(
            all.toArray(String[]::new),
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Result(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void lifecycleHistoryConflictAndRelocation() throws Exception {
    Path source = workspace.resolve("示例.java");
    Files.writeString(source, "第一行\r\n第二行\r\n第三行\r\n");
    Result created =
        run("", "create", "示例.java", "--lines", "2", "--body", "中文评论", "--author", "作者");
    assertEquals(0, created.code(), created.err());
    assertEquals("", created.err());
    JsonNode first = created.json();
    String id = first.path("id").asText(), revision = first.path("currentRevisionId").asText();
    assertFalse(id.isEmpty());
    assertEquals(2, first.path("location").path("startLine").asInt());
    Files.writeString(source, "插入行\r\n第一行\r\n第二行\r\n第三行\r\n");
    JsonNode moved = run("", "get", id).json();
    assertEquals(3, moved.path("location").path("startLine").asInt());
    assertEquals(revision, moved.path("currentRevisionId").asText());
    Result updated = run("", "update", id, "--base-revision", revision, "--body", "新正文");
    assertEquals(0, updated.code(), updated.err());
    Result conflict = run("", "update", id, "--base-revision", revision, "--body", "冲突");
    assertEquals(4, conflict.code());
    assertEquals("", conflict.out());
    assertTrue(conflict.json().has("details"));
    JsonNode history = run("", "history", id).json();
    assertEquals(2, history.size());
    String snapshotId = first.path("location").path("fileVersionId").asText();
    assertEquals(
        "第一行\r\n第二行\r\n第三行\r\n", run("", "snapshot", snapshotId).json().path("text").asText());
    Result deleted =
        run("", "delete", id, "--base-revision", updated.json().path("currentRevisionId").asText());
    assertEquals(0, deleted.code(), deleted.err());
    assertTrue(deleted.json().path("deleted").asBoolean());
    assertEquals(0, run("", "list").json().size());
    assertEquals(1, run("", "list", "--include-deleted", "--file", "示例.java").json().size());
    assertEquals("插入行\r\n第一行\r\n第二行\r\n第三行\r\n", Files.readString(source));
  }

  @Test
  void readsBodyFileAndStdin() throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "a\nb\n");
    Path body = workspace.resolve("body.txt");
    Files.writeString(body, "长正文\n第二段\n");
    Result file = run("", "create", "a.txt", "--lines", "1:2", "--body-file", body.toString());
    assertEquals(0, file.code(), file.err());
    assertEquals("长正文\n第二段\n", file.json().path("body").asText());
    Result stdin = run("标准输入\n正文", "create", "a.txt", "--lines", "1", "--body-file", "-");
    assertEquals(0, stdin.code(), stdin.err());
    assertEquals("标准输入\n正文", stdin.json().path("body").asText());
  }

  @Test
  void rejectsInvalidArgumentsWithoutWritingStdout() throws Exception {
    List<String[]> cases =
        List.of(
            new String[] {},
            new String[] {"unknown"},
            new String[] {"get"},
            new String[] {"get", "id", "extra"},
            new String[] {"list", "--body", "ignored"},
            new String[] {"create", "a", "--lines", "1", "--body", "x", "--body-file", "-"},
            new String[] {"create", "a", "--lines", "1:2:3", "--body", "x"},
            new String[] {"list", "--include-deleted", "--include-deleted"},
            new String[] {"list", "--file"},
            new String[] {"list", "--unknown"});
    for (String[] args : cases) {
      Result result = run("", args);
      assertEquals(2, result.code(), Arrays.toString(args));
      assertEquals("", result.out());
      assertEquals("INVALID_ARGUMENT", result.json().path("code").asText());
    }
    assertEquals(0, run("", "--help").code());
    assertEquals(0, run("", "--version").code());
    assertEquals(3, run("", "get", "missing").code());
  }

  @Test
  void invalidUtf8OnStdinDoesNotCreateAComment() throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "target\n");
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int code =
        Main.run(
            new String[] {
              "--workspace",
              workspace.toString(),
              "create",
              "a.txt",
              "--lines",
              "1",
              "--body-file",
              "-"
            },
            new ByteArrayInputStream(new byte[] {(byte) 0xFF}),
            new PrintStream(out),
            new PrintStream(err));
    assertEquals(1, code);
    assertEquals("", out.toString(StandardCharsets.UTF_8));
    assertEquals(
        "IO_ERROR", JSON.readTree(err.toString(StandardCharsets.UTF_8)).path("code").textValue());
    assertEquals(0, run("", "list").json().size());
  }
}
