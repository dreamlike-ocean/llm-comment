package io.github.dreamlike.llmcomment.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dreamlike.llmcomment.core.CommentService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpServerTest {
  @TempDir Path workspace;
  private final ObjectMapper json = new ObjectMapper();

  private JsonNode request(McpServer server, String method, Object params) throws Exception {
    return server.handle(
        json.writeValueAsString(
            Map.of("jsonrpc", "2.0", "id", "test", "method", method, "params", params)));
  }

  private void initialize(McpServer server) throws Exception {
    var result =
        request(
            server,
            "initialize",
            Map.of(
                "protocolVersion",
                "2025-11-25",
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "test", "version", "1")));
    assertEquals("2025-11-25", result.path("result").path("protocolVersion").textValue());
    assertNull(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
  }

  private JsonNode call(McpServer server, String name, Map<String, Object> args) throws Exception {
    return request(server, "tools/call", Map.of("name", name, "arguments", args));
  }

  @Test
  void lifecycleHistoryAndFileChanges() throws Exception {
    Files.writeString(workspace.resolve("Demo.java"), "first\r\n中文目标\r\nlast\r\n");
    try (var service = new CommentService(workspace)) {
      var server = new McpServer(service);
      assertEquals(
          -32002, request(server, "tools/list", Map.of()).path("error").path("code").intValue());
      initialize(server);
      assertEquals(7, request(server, "tools/list", Map.of()).path("result").path("tools").size());
      var created =
          call(
              server,
              "comment_create",
              Map.of("file", "Demo.java", "startLine", 2, "body", "检查并发\n第二行"));
      assertFalse(created.path("result").path("isError").booleanValue(), created.toString());
      var comment = created.path("result").path("structuredContent");
      String id = comment.path("id").textValue();
      String revision = comment.path("currentRevisionId").textValue();
      String snapshot = comment.path("location").path("fileVersionId").textValue();
      Files.writeString(workspace.resolve("Demo.java"), "new\r\nfirst\r\n中文目标\r\nlast\r\n");
      var current =
          call(server, "comment_get", Map.of("id", id)).path("result").path("structuredContent");
      assertEquals(3, current.path("location").path("startLine").intValue());
      assertEquals(revision, current.path("currentRevisionId").textValue());
      assertEquals(
          1,
          call(server, "comment_history", Map.of("id", id))
              .path("result")
              .path("structuredContent")
              .path("revisions")
              .size());
      var updated =
          call(
              server,
              "comment_update",
              Map.of("id", id, "baseRevisionId", revision, "body", "需要测试"));
      assertFalse(updated.path("result").path("isError").booleanValue());
      var conflict =
          call(
              server,
              "comment_update",
              Map.of("id", id, "baseRevisionId", revision, "body", "旧内容"));
      assertTrue(conflict.path("result").path("isError").booleanValue());
      assertEquals(
          "CONFLICT", conflict.path("result").path("structuredContent").path("code").textValue());
      var old =
          call(server, "comment_snapshot", Map.of("fileVersionId", snapshot))
              .path("result")
              .path("structuredContent");
      assertEquals("first\r\n中文目标\r\nlast\r\n", old.path("text").textValue());
      var deletion =
          call(
              server,
              "comment_delete",
              Map.of(
                  "id",
                  id,
                  "baseRevisionId",
                  updated
                      .path("result")
                      .path("structuredContent")
                      .path("currentRevisionId")
                      .textValue()));
      assertTrue(deletion.path("result").path("structuredContent").path("deleted").booleanValue());
    }
  }

  @Test
  void protocolErrorsAndNotificationSilence() throws Exception {
    try (var service = new CommentService(workspace)) {
      var server = new McpServer(service);
      assertEquals(-32700, server.handle("{").path("error").path("code").intValue());
      assertEquals(-32600, server.handle("[]").path("error").path("code").intValue());
      assertEquals(
          -32600,
          server
              .handle("{\"jsonrpc\":2.0,\"id\":1,\"method\":\"ping\"}")
              .path("error")
              .path("code")
              .intValue());
      assertEquals(
          -32600,
          server
              .handle("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}")
              .path("error")
              .path("code")
              .intValue());
      assertNull(server.handle("{\"jsonrpc\":\"2.0\",\"method\":\"unknown\"}"));
      initialize(server);
      assertEquals(
          -32601, request(server, "unknown", Map.of()).path("error").path("code").intValue());
      assertEquals(-32602, call(server, "unknown", Map.of()).path("error").path("code").intValue());
      assertEquals(
          -32602,
          call(server, "comment_create", Map.of("file", "a", "startLine", 1.5, "body", "x"))
              .path("error")
              .path("code")
              .intValue());
      assertEquals(
          -32602,
          call(server, "comment_list", Map.of("unused", true))
              .path("error")
              .path("code")
              .intValue());
      assertEquals(
          -32602,
          call(server, "comment_list", Map.of("includeDeleted", "false"))
              .path("error")
              .path("code")
              .intValue());
      assertEquals(
          "NOT_FOUND",
          call(server, "comment_get", Map.of("id", "missing"))
              .path("result")
              .path("structuredContent")
              .path("code")
              .textValue());
    }
  }

  @Test
  void stdioKeepsOneJsonResponsePerRequest() throws Exception {
    String input =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n";
    var output = new ByteArrayOutputStream();
    var errors = new ByteArrayOutputStream();
    assertEquals(
        0,
        Main.run(
            new String[] {"--workspace", workspace.toString()},
            new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
            output,
            errors));
    var lines = output.toString(StandardCharsets.UTF_8).lines().toList();
    assertEquals(2, lines.size());
    assertEquals(1, json.readTree(lines.getFirst()).path("id").intValue());
    assertEquals(2, json.readTree(lines.getLast()).path("id").intValue());
    assertEquals("", errors.toString(StandardCharsets.UTF_8));
  }

  @Test
  void malformedUtf8FailsWithoutReplacingTheInput() {
    var output = new ByteArrayOutputStream();
    var errors = new ByteArrayOutputStream();
    assertEquals(
        1,
        Main.run(
            new String[] {"--workspace", workspace.toString()},
            new ByteArrayInputStream(new byte[] {'{', '"', (byte) 0xFF, '"', '}', '\n'}),
            output,
            errors));
    assertEquals("", output.toString(StandardCharsets.UTF_8));
    assertFalse(errors.toString(StandardCharsets.UTF_8).isEmpty());
  }

  @Test
  void malformedInitializedNotificationDoesNotReadyTheConnection() throws Exception {
    try (var service = new CommentService(workspace)) {
      var server = new McpServer(service);
      request(
          server,
          "initialize",
          Map.of(
              "protocolVersion",
              "2025-11-25",
              "capabilities",
              Map.of(),
              "clientInfo",
              Map.of("name", "test", "version", "1")));
      assertNull(
          server.handle(
              "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":[]}"));
      assertEquals(
          -32002, request(server, "tools/list", Map.of()).path("error").path("code").intValue());
    }
  }
}
