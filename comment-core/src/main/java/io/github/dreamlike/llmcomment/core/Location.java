package io.github.dreamlike.llmcomment.core;

/** offset 为 UTF-16 半开范围；行号从 1 开始。失效位置的 offset 和行号为 null。 */
public record Location(
    String fileVersionId,
    Integer startOffset,
    Integer endOffset,
    Integer startLine,
    Integer endLine,
    AnchorStatus status,
    String selectedText) {}
