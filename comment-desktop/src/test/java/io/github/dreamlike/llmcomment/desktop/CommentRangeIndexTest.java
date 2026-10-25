package io.github.dreamlike.llmcomment.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dreamlike.llmcomment.core.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommentRangeIndexTest {
  private CommentView range(String id, int start, int end, boolean deleted) {
    return new CommentView(
        id,
        "代码.java",
        id,
        id,
        "甲",
        "",
        "",
        deleted,
        new Location("v", 0, 1, start, end, AnchorStatus.ACTIVE, "字"));
  }

  @Test
  void returnsEveryIntersectingContainingIdenticalAndBoundaryRange() {
    var a = range("交叉", 2, 5, false);
    var b = range("包含", 1, 10, false);
    var c = range("相同", 2, 5, true);
    var d = range("共线", 5, 8, false);
    var index = new CommentRangeIndex(List.of(a, b, c, d));
    assertEquals(List.of(b), index.at(1));
    assertEquals(List.of(b, a, c), index.at(3));
    assertEquals(List.of(b, a, c, d), index.at(5));
    assertEquals(List.of(b, d), index.at(6));
    assertTrue(index.at(11).isEmpty());
  }

  @Test
  void hugeRangesDoNotExpandIntoIndividualLines() {
    var comment = range("大范围", 1, Integer.MAX_VALUE, false);
    assertEquals(List.of(comment), new CommentRangeIndex(List.of(comment)).at(Integer.MAX_VALUE));
  }
}
