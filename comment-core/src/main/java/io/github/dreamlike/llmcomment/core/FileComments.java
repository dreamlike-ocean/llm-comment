package io.github.dreamlike.llmcomment.core;

import java.util.List;

/** 同一源码快照及其评论；缺失文件仍保留最后成功读取的快照。 */
public record FileComments(SnapshotView snapshot, boolean missing, List<CommentView> comments) {
  public FileComments {
    comments = List.copyOf(comments);
  }
}
