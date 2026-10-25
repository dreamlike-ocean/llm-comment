package io.github.dreamlike.llmcomment.core.anchor;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dreamlike.llmcomment.core.AnchorStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.PrimitiveIterator;
import org.junit.jupiter.api.Test;

class LiteralSearchTest {
  @Test
  void chineseNearMatchesAndOverlapsMatchUtf16Oracle() {
    String source = "甲".repeat(100_000) + "乙";
    verify(source, "甲".repeat(1000) + "丙", true);
    verify(source, "甲".repeat(1000), true);
    verify(source, "甲".repeat(1000), false);
  }

  @Test
  void emojiOffsetsAndSharedFallbackSourcesStayIndependent() {
    String pattern = "😀".repeat(500);
    String source = "😀".repeat(1500) + "乙";
    verify(source, pattern + "丙", true);
    verify(source, pattern, true);
    String mixed = "𠮷😀甲";
    verify("文" + mixed.repeat(120), mixed.repeat(100), true);
    verify("文" + mixed.repeat(120), mixed.repeat(100) + "乙", false);
    Anchor anchor = new Anchor(0, pattern.length(), AnchorStatus.ORPHANED, pattern, "", "");
    OccurrenceIndex first = new OccurrenceIndex(source, List.of(anchor));
    OccurrenceIndex second = first.forSource("甲" + pattern + "尾");
    assertEquals(1001, first.count(pattern));
    assertEquals(1, second.count(pattern));
    List<Integer> positions = new ArrayList<>();
    second.positions(pattern).forEach(positions::add);
    assertEquals(List.of(1), positions);
    assertEquals(1001, first.count(pattern));
  }

  @Test
  void ordinaryLongPatternUsesIntrinsicWithoutPrefixTable() {
    StringBuilder text = new StringBuilder();
    for (char c = '\u4000'; c < '\u4500'; c++) text.append(c);
    String pattern = text.toString();
    LiteralSearch search = new LiteralSearch(pattern, true);
    assertFalse(search.cachedFailure());
    verify("甲".repeat(1000) + pattern + "乙", pattern, true);
    assertFalse(new LiteralSearch("甲".repeat(255), true).cachedFailure());
    assertTrue(new LiteralSearch("甲".repeat(256), true).cachedFailure());
  }

  private static void verify(String source, String pattern, boolean cache) {
    List<Integer> expected = new ArrayList<>();
    for (int i = 0; (i = source.indexOf(pattern, i)) >= 0; i++) expected.add(i);
    List<Integer> actual = new ArrayList<>();
    PrimitiveIterator.OfInt positions = new LiteralSearch(pattern, cache).positions(source);
    while (positions.hasNext()) actual.add(positions.nextInt());
    assertEquals(expected, actual);
    Anchor anchor = new Anchor(0, pattern.length(), AnchorStatus.ORPHANED, pattern, "", "");
    OccurrenceIndex index = new OccurrenceIndex(source, List.of(anchor));
    assertEquals(expected.size(), index.count(pattern));
    actual.clear();
    index.positions(pattern).forEach(actual::add);
    assertEquals(expected, actual);
  }
}
