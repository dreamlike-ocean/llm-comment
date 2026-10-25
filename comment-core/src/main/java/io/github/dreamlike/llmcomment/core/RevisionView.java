package io.github.dreamlike.llmcomment.core;

/** action 为 CREATE、UPDATE 或 DELETE，location 指向生成该版本时保存的快照。 */
public record RevisionView(
    String id,
    String commentId,
    String parentRevisionId,
    String action,
    String body,
    String author,
    String createdAt,
    Location location) {}
