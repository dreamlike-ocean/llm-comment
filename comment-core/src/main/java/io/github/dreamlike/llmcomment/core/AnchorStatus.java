package io.github.dreamlike.llmcomment.core;

/** 当前范围的定位结果；MODIFIED 表示范围仍可定位，但内容发生了变化。 */
public enum AnchorStatus {
  ACTIVE,
  MODIFIED,
  ORPHANED,
  AMBIGUOUS,
  MISSING_FILE
}
