package io.github.dreamlike.llmcomment.core.anchor;

import io.github.dreamlike.llmcomment.core.AnchorStatus;

/** 失效时保留原文和上下文，以便在后续快照恢复定位。 */
public record Anchor(
    int startOffset,
    int endOffset,
    AnchorStatus status,
    String text,
    String before,
    String after) {}
