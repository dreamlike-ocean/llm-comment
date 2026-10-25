package io.github.dreamlike.llmcomment.desktop;

import io.github.dreamlike.llmcomment.core.AnchorStatus;
import io.github.dreamlike.llmcomment.core.CommentView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 按当前起始行聚合评论标记；范围长度不影响空间与查询开销。 */
final class CommentStartIndex {
  private final Map<Integer, List<CommentView>> starts;

  CommentStartIndex(List<CommentView> comments) {
    var grouped = new HashMap<Integer, List<CommentView>>();
    for (var comment : comments) {
      var location = comment.location();
      if (location == null
          || location.startLine() == null
          || location.endLine() == null
          || location.startLine() < 1
          || location.endLine() < location.startLine()
          || (location.status() != AnchorStatus.ACTIVE
              && location.status() != AnchorStatus.MODIFIED)) continue;
      grouped.computeIfAbsent(location.startLine(), ignored -> new ArrayList<>()).add(comment);
    }
    grouped.replaceAll((line, values) -> List.copyOf(values));
    starts = Map.copyOf(grouped);
  }

  List<CommentView> startingAt(int line) {
    return starts.getOrDefault(line, List.of());
  }
}
