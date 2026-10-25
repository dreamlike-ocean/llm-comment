package io.github.dreamlike.llmcomment.core;

public record SnapshotView(String id, String file, String sha256, String text, String createdAt) {}
