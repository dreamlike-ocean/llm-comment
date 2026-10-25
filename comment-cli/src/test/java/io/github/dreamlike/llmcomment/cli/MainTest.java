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
    return runAt(workspace, stdin, all.toArray(String[]::new));
  }

  private Result runAt(Path cwd, String stdin, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
    int code =
        Main.run(
            args,
            cwd,
            new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Result(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void positionalAndNamedFilesUseTheSameRules() throws Exception {
    Files.writeString(workspace.resolve("pom.xml"), "<project>中文</project>\n");
    Result created = run("", "create", "--file", "pom.xml", "--lines", "1", "--body", "检查依赖");
    assertEquals(0, created.code(), created.err());
    for (String[] args :
        List.of(
            new String[] {"list", "pom.xml"},
            new String[] {"list", "--file", "pom.xml"},
            new String[] {"list", "pom.xml", "--file", "./pom.xml"},
            new String[] {"list", workspace.resolve("pom.xml").toString()})) {
      Result listed = run("", args);
      assertEquals(0, listed.code(), listed.err());
      assertEquals(created.json().path("id"), listed.json().get(0).path("id"));
      assertEquals("pom.xml", listed.json().get(0).path("file").asText());
    }
    Result conflicting = run("", "list", "pom.xml", "--file", "another.xml");
    assertEquals(2, conflicting.code());
    assertTrue(conflicting.json().path("message").asText().contains("不同文件"));
    Result missing = run("", "create", "--lines", "1", "--body", "缺少文件");
    assertEquals(2, missing.code());
    assertTrue(missing.json().path("message").asText().contains("create FILE"));
  }

  @Test
  void workspaceInspectionDoesNotCreateStorage() throws Exception {
    Result result = runAt(workspace, "", "workspace");
    assertEquals(0, result.code(), result.err());
    assertEquals(workspace.toRealPath().toString(), result.json().path("workspace").asText());
    assertFalse(result.json().path("databaseExists").asBoolean());
    assertFalse(Files.exists(workspace.resolve(".llm-comments")));
    assertEquals(0, runAt(workspace, "", "--help").code());
    assertFalse(Files.exists(workspace.resolve(".llm-comments")));
  }

  @Test
  void everyCommandDiscoversTheSameWorkspaceFromSubdirectories() throws Exception {
    Files.writeString(workspace.resolve("示例.java"), "目标代码\n");
    Result created = runAt(workspace, "", "create", "示例.java", "--lines", "1", "--body", "初稿");
    assertEquals(0, created.code(), created.err());
    String id = created.json().path("id").asText();
    String revision = created.json().path("currentRevisionId").asText();
    Path nested = Files.createDirectories(workspace.resolve("src/子目录"));
    assertEquals(
        workspace.toRealPath().toString(),
        runAt(nested, "", "workspace").json().path("workspace").asText());
    assertEquals(id, runAt(nested, "", "get", id).json().path("id").asText());
    assertEquals(1, runAt(nested, "", "list", "示例.java").json().size());
    Result updated = runAt(nested, "", "update", id, "--base-revision", revision, "--body", "修订");
    assertEquals(0, updated.code(), updated.err());
    assertEquals(2, runAt(nested, "", "history", id).json().size());
    String snapshot = created.json().path("location").path("fileVersionId").asText();
    assertEquals("目标代码\n", runAt(nested, "", "snapshot", snapshot).json().path("text").asText());
    Result deleted =
        runAt(
            nested,
            "",
            "delete",
            id,
            "--base-revision",
            updated.json().path("currentRevisionId").asText());
    assertEquals(0, deleted.code(), deleted.err());
    assertEquals(0, runAt(nested, "", "list").json().size());
    assertFalse(Files.exists(nested.resolve(".llm-comments")));
  }

  @Test
  void explicitWorkspaceAndAllRelativePathsUseOneBaseDirectory() throws Exception {
    Path project = Files.createDirectories(workspace.resolve("项目"));
    Path caller = Files.createDirectories(workspace.resolve("调用目录"));
    Files.writeString(project.resolve("源.java"), "正确源码\n");
    Files.writeString(project.resolve("正文.txt"), "项目中的正文\n");
    Files.writeString(caller.resolve("正文.txt"), "错误的正文");
    Result created =
        runAt(
            caller,
            "",
            "create",
            "源.java",
            "--lines",
            "1",
            "--body-file",
            "正文.txt",
            "--workspace",
            "../项目",
            "--database",
            "store/评论.db");
    assertEquals(0, created.code(), created.err());
    assertEquals("项目中的正文\n", created.json().path("body").asText());
    assertTrue(Files.isRegularFile(project.resolve("store/评论.db")));
    assertFalse(Files.exists(caller.resolve("store")));
    Result listed =
        runAt(caller, "", "list", "源.java", "--workspace", "../项目", "--database", "store/评论.db");
    assertEquals(0, listed.code(), listed.err());
    assertEquals(1, listed.json().size());
    Result inspect =
        runAt(caller, "", "--workspace", "../项目", "workspace", "--database", "store/评论.db");
    assertTrue(inspect.json().path("databaseExists").asBoolean());
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
