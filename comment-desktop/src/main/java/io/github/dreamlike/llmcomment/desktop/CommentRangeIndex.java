package io.github.dreamlike.llmcomment.desktop;

import io.github.dreamlike.llmcomment.core.AnchorStatus;
import io.github.dreamlike.llmcomment.core.CommentView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 按闭区间查询覆盖某行的评论；空间只随评论数量增长。 */
final class CommentRangeIndex {
  private final Node root;

  CommentRangeIndex(List<CommentView> comments) {
    var ranges =
        comments.stream()
            .filter(
                comment -> {
                  var location = comment.location();
                  return location != null
                      && location.startLine() != null
                      && location.endLine() != null
                      && (location.status() == AnchorStatus.ACTIVE
                          || location.status() == AnchorStatus.MODIFIED);
                })
            .sorted(Comparator.comparingInt(comment -> comment.location().startLine()))
            .toList();
    root = build(ranges, 0, ranges.size());
  }

  List<CommentView> at(int line) {
    var result = new ArrayList<CommentView>();
    collect(root, line, result);
    return List.copyOf(result);
  }

  private static Node build(List<CommentView> values, int from, int to) {
    if (from == to) return null;
    int middle = (from + to) >>> 1;
    return new Node(values.get(middle), build(values, from, middle), build(values, middle + 1, to));
  }

  private static void collect(Node node, int line, List<CommentView> result) {
    if (node == null || node.maxEnd < line) return;
    collect(node.left, line, result);
    var location = node.comment.location();
    if (location.startLine() > line) return;
    if (location.endLine() >= line) result.add(node.comment);
    collect(node.right, line, result);
  }

  private static final class Node {
    final CommentView comment;
    final Node left;
    final Node right;
    final int maxEnd;

    Node(CommentView comment, Node left, Node right) {
      this.comment = comment;
      this.left = left;
      this.right = right;
      maxEnd =
          Math.max(
              comment.location().endLine(),
              Math.max(left == null ? 0 : left.maxEnd, right == null ? 0 : right.maxEnd));
    }
  }
}
