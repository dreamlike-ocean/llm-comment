package io.github.dreamlike.llmcomment.core;

public record CommentView(
    String id,
    String file,
    String currentRevisionId,
    String body,
    String author,
    String createdAt,
    String updatedAt,
    boolean deleted,
    Location location) {}
