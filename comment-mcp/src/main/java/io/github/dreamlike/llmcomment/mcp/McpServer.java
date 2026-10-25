package io.github.dreamlike.llmcomment.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dreamlike.llmcomment.core.CommentException;
import io.github.dreamlike.llmcomment.core.CommentService;
import io.github.dreamlike.llmcomment.core.Views;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** MCP stdio：每行一条 JSON-RPC 消息，按初始化、就绪、EOF 的顺序处理。 */
public final class McpServer {
  public static final String PROTOCOL_VERSION = "2025-11-25";
  private static final Set<String> SUPPORTED_VERSIONS =
      Set.of(PROTOCOL_VERSION, "2025-06-18", "2025-03-26");
  private final ObjectMapper json =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final CommentService service;
  private boolean initialized;
  private boolean ready;
  private String protocolVersion = PROTOCOL_VERSION;

  public McpServer(CommentService service) {
    this.service = Objects.requireNonNull(service);
  }

  public void run(InputStream input, OutputStream output) throws IOException {
    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
    var reader = new BufferedReader(new InputStreamReader(input, decoder));
    var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
      JsonNode response = handle(line);
      if (response != null) {
        writer.write(json.writeValueAsString(response));
        writer.newLine();
        writer.flush();
      }
    }
  }

  JsonNode handle(String line) {
    JsonNode request;
    try {
      request = json.readTree(line);
    } catch (JsonProcessingException exception) {
      return error(null, -32700, "JSON 解析失败");
    }
    if (request == null
        || !request.isObject()
        || !request.path("jsonrpc").isTextual()
        || !"2.0".equals(request.path("jsonrpc").textValue())
        || !request.path("method").isTextual()) return error(null, -32600, "请求必须是 JSON-RPC 2.0 对象");
    boolean hasId = request.has("id");
    JsonNode id = request.get("id");
    if (hasId && (id.isNull() || !(id.isTextual() || id.isIntegralNumber())))
      return error(null, -32600, "请求 id 必须是字符串或整数");
    String method = request.path("method").textValue();
    if (!hasId) {
      if ("notifications/initialized".equals(method)
          && initialized
          && (!request.has("params") || request.path("params").isObject())) ready = true;
      return null;
    }
    try {
      JsonNode params = request.get("params");
      if (params != null && !params.isObject()) throw new InvalidParams("params 必须是对象");
      if (params == null) params = json.createObjectNode();
      if ("ping".equals(method)) return result(id, Map.of());
      if ("initialize".equals(method)) {
        if (initialized) return error(id, -32600, "连接已初始化");
        String requestedVersion = string(params, "protocolVersion", true);
        if (!params.path("capabilities").isObject() || !params.path("clientInfo").isObject()) {
          throw new InvalidParams("initialize 需要 capabilities 和 clientInfo 对象");
        }
        string(params.path("clientInfo"), "name", true);
        string(params.path("clientInfo"), "version", true);
        protocolVersion =
            SUPPORTED_VERSIONS.contains(requestedVersion) ? requestedVersion : PROTOCOL_VERSION;
        initialized = true;
        return result(
            id,
            Map.of(
                "protocolVersion",
                protocolVersion,
                "capabilities",
                Map.of("tools", Map.of("listChanged", false)),
                "serverInfo",
                Map.of("name", "llm-comment", "version", "0.1.0-SNAPSHOT"),
                "instructions",
                "相对文件路径以服务启动时确定的工作区为基准，也接受工作区内的绝对路径。"
                    + "行号从1开始且包含首尾行；查询自动同步位置，修改必须提供最新baseRevisionId。"));
      }
      if (!ready) return error(id, -32002, "请先完成 initialize 和 notifications/initialized");
      return switch (method) {
        case "tools/list" -> {
          if (params.hasNonNull("cursor")) throw new InvalidParams("工具列表不使用游标");
          yield result(id, Map.of("tools", definitions()));
        }
        case "tools/call" -> result(id, call(params));
        default -> error(id, -32601, "未知方法：" + method);
      };
    } catch (InvalidParams exception) {
      return error(id, -32602, exception.getMessage());
    } catch (RuntimeException exception) {
      return error(id, -32603, "服务处理失败：" + exception.getMessage());
    }
  }

  private Map<String, Object> call(JsonNode params) {
    String name = string(params, "name", true);
    JsonNode args = params.get("arguments");
    if (args == null) args = json.createObjectNode();
    if (!args.isObject()) throw new InvalidParams("arguments 必须是对象");
    Set<String> allowed =
        switch (name) {
          case "comment_create" -> Set.of("file", "startLine", "endLine", "body", "author");
          case "comment_get", "comment_history" -> Set.of("id");
          case "comment_list" -> Set.of("file", "includeDeleted");
          case "comment_update" -> Set.of("id", "baseRevisionId", "body", "author");
          case "comment_delete" -> Set.of("id", "baseRevisionId", "author");
          case "comment_snapshot" -> Set.of("fileVersionId");
          default -> throw new InvalidParams("未知工具：" + name);
        };
    args.fieldNames()
        .forEachRemaining(
            key -> {
              if (!allowed.contains(key)) throw new InvalidParams("未知参数：" + key);
            });
    try {
      Object data =
          switch (name) {
            case "comment_create" ->
                Views.toMap(
                    service.create(
                        string(args, "file", true),
                        integer(args, "startLine", null),
                        integer(args, "endLine", integer(args, "startLine", null)),
                        body(args),
                        author(args)));
            case "comment_get" -> Views.toMap(service.get(string(args, "id", true)));
            case "comment_list" ->
                Map.of(
                    "comments",
                    service
                        .list(string(args, "file", false), bool(args, "includeDeleted", false))
                        .stream()
                        .map(Views::toMap)
                        .toList());
            case "comment_update" ->
                Views.toMap(
                    service.update(
                        string(args, "id", true),
                        string(args, "baseRevisionId", true),
                        body(args),
                        author(args)));
            case "comment_delete" ->
                Views.toMap(
                    service.delete(
                        string(args, "id", true),
                        string(args, "baseRevisionId", true),
                        author(args)));
            case "comment_history" ->
                Map.of(
                    "revisions",
                    service.history(string(args, "id", true)).stream().map(Views::toMap).toList());
            case "comment_snapshot" ->
                Views.toMap(service.snapshot(string(args, "fileVersionId", true)));
            default -> throw new AssertionError(name);
          };
      return toolResult(data, false);
    } catch (CommentException exception) {
      return toolResult(
          Map.of(
              "code",
              exception.code(),
              "message",
              exception.getMessage(),
              "details",
              exception.details()),
          true);
    }
  }

  private Map<String, Object> toolResult(Object data, boolean failure) {
    try {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("content", List.of(Map.of("type", "text", "text", json.writeValueAsString(data))));
      if (!"2025-03-26".equals(protocolVersion)) result.put("structuredContent", data);
      result.put("isError", failure);
      return result;
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("结果序列化失败", exception);
    }
  }

  private JsonNode result(JsonNode id, Object payload) {
    ObjectNode result = json.createObjectNode();
    result.put("jsonrpc", "2.0");
    result.set("id", id);
    result.set("result", json.valueToTree(payload));
    return result;
  }

  private JsonNode error(JsonNode id, int code, String message) {
    ObjectNode result = json.createObjectNode();
    result.put("jsonrpc", "2.0");
    result.set("id", id);
    result.set("error", json.valueToTree(Map.of("code", code, "message", message)));
    return result;
  }

  private static String string(JsonNode node, String key, boolean required) {
    JsonNode value = node.get(key);
    if (value == null) {
      if (required) throw new InvalidParams("缺少参数：" + key);
      return null;
    }
    if (!value.isTextual() || value.textValue().isBlank())
      throw new InvalidParams(key + " 必须是非空字符串");
    return value.textValue();
  }

  private static String author(JsonNode args) {
    String author = string(args, "author", false);
    return author == null ? "anonymous" : author;
  }

  private static String body(JsonNode args) {
    JsonNode value = args.get("body");
    if (value == null || !value.isTextual()) throw new InvalidParams("body 必须是字符串");
    return value.textValue();
  }

  private static int integer(JsonNode node, String key, Integer fallback) {
    JsonNode value = node.get(key);
    if (value == null && fallback != null) return fallback;
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < 1) {
      throw new InvalidParams(key + " 必须是大于0的整数");
    }
    return value.intValue();
  }

  private static boolean bool(JsonNode node, String key, boolean fallback) {
    JsonNode value = node.get(key);
    if (value == null) return fallback;
    if (!value.isBoolean()) throw new InvalidParams(key + " 必须是布尔值");
    return value.booleanValue();
  }

  private static Map<String, Object> text(String description) {
    return Map.of("type", "string", "minLength", 1, "description", description);
  }

  private static Map<String, Object> line(String description) {
    return Map.of("type", "integer", "minimum", 1, "description", description);
  }

  private static Map<String, Object> tool(
      String name, String description, Map<String, Object> properties, String... required) {
    return Map.of(
        "name",
        name,
        "description",
        description,
        "inputSchema",
        Map.of(
            "type",
            "object",
            "properties",
            properties,
            "required",
            List.of(required),
            "additionalProperties",
            false));
  }

  private static List<Map<String, Object>> definitions() {
    return List.of(
        tool(
            "comment_create",
            "在当前文件的单行或连续多行创建评论，源码保持原样。",
            Map.of(
                "file",
                text("工作区相对路径或工作区内的绝对路径"),
                "startLine",
                line("起始行，包含，从1开始"),
                "endLine",
                line("结束行，包含，默认起始行"),
                "body",
                Map.of("type", "string", "description", "评论正文，可包含换行和Markdown"),
                "author",
                text("作者，默认anonymous")),
            "file",
            "startLine",
            "body"),
        tool("comment_get", "查询一条评论，自动迁移当前位置。删除的评论也可读取。", Map.of("id", text("commentId")), "id"),
        tool(
            "comment_list",
            "查询文件或工作区评论，自动同步定位，默认隐藏已删除评论。",
            Map.of(
                "file",
                text("工作区相对路径或工作区内的绝对路径，省略时列出整个工作区的评论"),
                "includeDeleted",
                Map.of("type", "boolean", "default", false))),
        tool(
            "comment_update",
            "追加一个评论版本；baseRevisionId必须是当前版本，否则返回冲突。",
            Map.of(
                "id",
                text("commentId"),
                "baseRevisionId",
                text("读取评论时的currentRevisionId"),
                "body",
                Map.of("type", "string", "description", "新的评论正文"),
                "author",
                text("作者，默认anonymous")),
            "id",
            "baseRevisionId",
            "body"),
        tool(
            "comment_delete",
            "追加删除版本并隐藏评论，历史及文件快照继续保留。",
            Map.of(
                "id",
                text("commentId"),
                "baseRevisionId",
                text("读取评论时的currentRevisionId"),
                "author",
                text("作者，默认anonymous")),
            "id",
            "baseRevisionId"),
        tool(
            "comment_history", "按顺序读取不可变评论历史，包括当时的范围和代码片段。", Map.of("id", text("commentId")), "id"),
        tool(
            "comment_snapshot",
            "读取历史评论所引用的完整文件快照。",
            Map.of("fileVersionId", text("location.fileVersionId")),
            "fileVersionId"));
  }

  private static final class InvalidParams extends RuntimeException {
    InvalidParams(String message) {
      super(message);
    }
  }
}
