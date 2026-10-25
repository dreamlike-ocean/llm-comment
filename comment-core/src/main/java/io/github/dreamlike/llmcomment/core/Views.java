package io.github.dreamlike.llmcomment.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** 将公开结果转为 JSON 兼容的值，供不同入口共享字段定义。 */
public final class Views {
  private Views() {}

  public static Map<String, Object> toMap(CommentView value) {
    return fields(
        "id",
        value.id(),
        "file",
        value.file(),
        "currentRevisionId",
        value.currentRevisionId(),
        "body",
        value.body(),
        "author",
        value.author(),
        "createdAt",
        value.createdAt(),
        "updatedAt",
        value.updatedAt(),
        "deleted",
        value.deleted(),
        "location",
        toMap(value.location()));
  }

  public static Map<String, Object> toMap(RevisionView value) {
    return fields(
        "id",
        value.id(),
        "commentId",
        value.commentId(),
        "parentRevisionId",
        value.parentRevisionId(),
        "action",
        value.action(),
        "body",
        value.body(),
        "author",
        value.author(),
        "createdAt",
        value.createdAt(),
        "location",
        toMap(value.location()));
  }

  public static Map<String, Object> toMap(SnapshotView value) {
    return fields(
        "id",
        value.id(),
        "file",
        value.file(),
        "sha256",
        value.sha256(),
        "text",
        value.text(),
        "createdAt",
        value.createdAt());
  }

  public static Map<String, Object> toMap(Location value) {
    if (value == null) return null;
    return fields(
        "fileVersionId",
        value.fileVersionId(),
        "startOffset",
        value.startOffset(),
        "endOffset",
        value.endOffset(),
        "startLine",
        value.startLine(),
        "endLine",
        value.endLine(),
        "status",
        value.status().name(),
        "selectedText",
        value.selectedText());
  }

  private static Map<String, Object> fields(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
    return result;
  }
}
