package io.github.dreamlike.llmcomment.desktop;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dreamlike.llmcomment.core.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommentStartIndexTest {
  private CommentView range(String id, int start, int end, boolean deleted) {
    return range(id, new Location("v", 0, 1, start, end, AnchorStatus.ACTIVE, "字"), deleted);
  }

  private CommentView range(String id, Location location, boolean deleted) {
    return new CommentView(id, "代码.java", id, id, "甲", "", "", deleted, location);
  }

  @Test
  void marksOnlyStartsOfIntersectingContainingIdenticalAndBoundaryRanges() {
    var a = range("交叉", 2, 5, false);
    var b = range("包含", 1, 10, false);
    var c = range("相同", 2, 5, true);
    var d = range("共线", 5, 8, false);
    var single = range("单行", 7, 7, false);
    var index = new CommentStartIndex(List.of(a, b, c, d, single));
    assertEquals(List.of(b), index.startingAt(1));
    assertEquals(List.of(a, c), index.startingAt(2));
    assertTrue(index.startingAt(3).isEmpty());
    assertEquals(List.of(d), index.startingAt(5));
    assertTrue(index.startingAt(6).isEmpty());
    assertEquals(List.of(single), index.startingAt(7));
    assertTrue(index.startingAt(8).isEmpty());
    assertTrue(index.startingAt(10).isEmpty());
    assertTrue(index.startingAt(11).isEmpty());
  }

  @Test
  void hugeRangesDoNotExpandIntoIndividualLines() {
    var comment = range("大范围", 1, Integer.MAX_VALUE, false);
    var index = new CommentStartIndex(List.of(comment));
    assertEquals(List.of(comment), index.startingAt(1));
    assertTrue(index.startingAt(Integer.MAX_VALUE).isEmpty());
  }

  @Test
  void refreshUsesRelocatedStartsAndIgnoresUnlocatableComments() {
    var old = new CommentStartIndex(List.of(range("平移", 1, 10, false)));
    var moved = range("平移", 4, 13, false);
    var modified = range("内部修改", new Location("v", 0, 1, 6, 8, AnchorStatus.MODIFIED, "字"), false);
    var comments = new ArrayList<>(List.of(moved, modified));
    for (var status :
        List.of(AnchorStatus.ORPHANED, AnchorStatus.AMBIGUOUS, AnchorStatus.MISSING_FILE)) {
      comments.add(range(status.name(), new Location("v", 0, 1, 1, 10, status, "字"), false));
    }
    comments.add(range("未知位置", null, false));
    comments.add(
        range("无行号", new Location("v", null, null, null, null, AnchorStatus.ACTIVE, ""), false));
    var refreshed = new CommentStartIndex(comments);
    assertEquals(1, old.startingAt(1).size());
    assertTrue(refreshed.startingAt(1).isEmpty());
    assertEquals(List.of(moved), refreshed.startingAt(4));
    assertEquals(List.of(modified), refreshed.startingAt(6));
  }
}
