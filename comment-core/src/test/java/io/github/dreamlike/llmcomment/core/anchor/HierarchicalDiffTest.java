package io.github.dreamlike.llmcomment.core.anchor;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dreamlike.llmcomment.core.AnchorStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HierarchicalDiffTest {
  @Test
  void sparseEditsInLargeCrLfFilePreserveInternalChangesAndUtf16Offsets() {
    StringBuilder old = new StringBuilder();
    for (int i = 0; i < 12_000; i++) old.append("第").append(i).append("行：中文😀目标内容\r\n");
    String previous = old.toString();
    String current =
        previous
            .replace("第1行：", "新增😀\r\n第1行：")
            .replace("第6000行：中文😀目标内容", "第6000行：中文😀目标新增内容")
            .replace("第11998行：", "第11998行：末尾修改");
    assertTrue(HierarchicalDiff.diff(previous, current).stream().noneMatch(edit -> edit.coarse));
    List<Anchor> anchors =
        List.of(
            AnchorTracker.capture(previous, 6001, 6001),
            AnchorTracker.capture(previous, 9001, 9001));
    List<Anchor> relocated = AnchorTracker.relocate(previous, current, anchors);
    assertEquals(AnchorStatus.MODIFIED, relocated.getFirst().status());
    assertEquals("第6000行：中文😀目标新增内容", relocated.getFirst().text());
    assertEquals(current.indexOf("第6000行："), relocated.getFirst().startOffset());
    assertEquals(AnchorStatus.ACTIVE, relocated.getLast().status());
    assertEquals(current.indexOf("第9000行："), relocated.getLast().startOffset());
  }

  @Test
  void repeatedLinesAndDeletedCopyRemainAmbiguousInLargeFile() {
    String previous = "头\n" + "重复😀\n".repeat(3000) + "尾";
    Anchor original = AnchorTracker.capture(previous, 1500, 1500);
    String current = "新增\n头\n" + "重复😀\n".repeat(2999) + "尾变更";
    Anchor moved = AnchorTracker.relocate(previous, current, List.of(original)).getFirst();
    assertEquals(AnchorStatus.AMBIGUOUS, moved.status());
    assertEquals(original.text(), moved.text());
  }

  @Test
  void entireRewriteCannotBindUnrelatedTextAndCanRecover() {
    String previous = "a".repeat(50_000) + "\n唯一😀原文\n" + "b".repeat(50_000);
    Anchor original = AnchorTracker.capture(previous, 2, 2);
    String rewritten = "x".repeat(110_000);
    Anchor missing = AnchorTracker.relocate(previous, rewritten, List.of(original)).getFirst();
    assertEquals(AnchorStatus.ORPHANED, missing.status());
    assertEquals(original.text(), missing.text());
    Anchor recovered = AnchorTracker.relocate(rewritten, previous, List.of(missing)).getFirst();
    assertEquals(AnchorStatus.ACTIVE, recovered.status());
    assertEquals(original.startOffset(), recovered.startOffset());
  }

  @Test
  void multiPatternIndexIncludesOverlapsSuffixesAndSharedPatterns() {
    List<Anchor> anchors = new ArrayList<>();
    for (String needle :
        List.of("a", "aa", "aaa", "ba", "😀", "aa", "aaaa", "baaa", "a😀", "aa😀", "😀aa", "无匹配"))
      anchors.add(new Anchor(0, needle.length(), AnchorStatus.ORPHANED, needle, "", ""));
    String source = "baaaa😀aa😀";
    OccurrenceIndex index = new OccurrenceIndex(source, anchors);
    OccurrenceIndex other = index.forSource("aaa😀");
    assertEquals(2, other.count("aa"));
    assertEquals(1, other.count("😀"));
    for (Anchor anchor : anchors) {
      List<Integer> expected = new ArrayList<>();
      for (int i = 0; (i = source.indexOf(anchor.text(), i)) >= 0; i++) expected.add(i);
      List<Integer> actual = new ArrayList<>();
      index.positions(anchor.text()).forEach(actual::add);
      assertEquals(expected.size(), index.count(anchor.text()), anchor.text());
      assertEquals(expected, actual, anchor.text());
    }
  }

  @Test
  void excessiveCandidatesAndLongPatternsKeepExactCountsWithoutUnboundedCache() {
    String source = "a".repeat(100_000);
    List<Anchor> anchors = new ArrayList<>();
    for (int i = 1; i <= 12; i++)
      anchors.add(new Anchor(0, i, AnchorStatus.ORPHANED, "a".repeat(i), "", ""));
    OccurrenceIndex index = new OccurrenceIndex(source, anchors);
    for (Anchor anchor : anchors) {
      assertEquals(source.length() - anchor.text().length() + 1, index.count(anchor.text()));
      int count = 0;
      for (int ignored : index.positions(anchor.text())) count++;
      assertEquals(index.count(anchor.text()), count);
    }
    List<Anchor> longAnchors = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      String text = "a".repeat(20_000) + i;
      longAnchors.add(new Anchor(0, text.length(), AnchorStatus.ORPHANED, text, "", ""));
    }
    OccurrenceIndex bounded = new OccurrenceIndex(source + "7", longAnchors);
    assertEquals(1, bounded.count(longAnchors.get(7).text()));
    assertEquals(0, bounded.count(longAnchors.getFirst().text()));
  }

  @Test
  void compactAutomatonMatchesSeededOracleAndSourcesStayIndependent() {
    Random random = new Random(0x53a91cL);
    List<String> alphabet = List.of("a", "b", "中", "文", "😀", "\r\n");
    for (int round = 0; round < 20; round++) {
      StringBuilder source = new StringBuilder();
      for (int i = 0; i < 30_000; i++) source.append(alphabet.get(random.nextInt(alphabet.size())));
      String first = source.toString();
      List<Anchor> anchors = new ArrayList<>();
      List<String> needles =
          new ArrayList<>(List.of("a", "aa", "ba", "aba", "😀", "中😀", "😀中", "中", "中a"));
      for (int i = 0; i < 30; i++) {
        int start = random.nextInt(first.length() - 20);
        needles.add(first.substring(start, start + 1 + random.nextInt(20)));
      }
      for (String text : needles)
        anchors.add(new Anchor(0, text.length(), AnchorStatus.ORPHANED, text, "", ""));
      OccurrenceIndex index = new OccurrenceIndex(first, anchors);
      String second = "😀中aba" + first + first;
      OccurrenceIndex shared = index.forSource(second);
      for (String needle : needles) {
        assertIndex(first, needle, index);
        assertIndex(second, needle, shared);
        assertIndex(first, needle, index);
      }
    }
  }

  private static void assertIndex(String source, String needle, OccurrenceIndex index) {
    List<Integer> expected = new ArrayList<>();
    for (int i = 0; (i = source.indexOf(needle, i)) >= 0; i++) expected.add(i);
    assertEquals(expected.size(), index.count(needle), needle);
    List<Integer> actual = new ArrayList<>();
    index.positions(needle).forEach(actual::add);
    assertEquals(expected, actual, needle);
  }

  @Test
  void contentChunksRealignAfterInsertionInOneLongLine() {
    StringBuilder old = new StringBuilder();
    for (int i = 0; i < 10_000; i++) old.append(i).append(":中文😀;");
    String previous = old.toString();
    String current =
        previous
            .replace("100:中文", "100:插入中文")
            .replace("5000:中文", "5000:中新增文")
            .replace("9900:中文", "9900:修改中文");
    int start = previous.indexOf("5000:"), end = previous.indexOf(';', start);
    Anchor original =
        new Anchor(
            start,
            end,
            AnchorStatus.ACTIVE,
            previous.substring(start, end),
            previous.substring(start - 64, start),
            previous.substring(end, end + 64));
    Anchor moved = AnchorTracker.relocate(previous, current, List.of(original)).getFirst();
    assertEquals(AnchorStatus.MODIFIED, moved.status());
    assertEquals("5000:中新增文😀", moved.text());
    assertEquals(current.indexOf("5000:"), moved.startOffset());
  }
}
