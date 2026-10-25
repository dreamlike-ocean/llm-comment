package io.github.dreamlike.llmcomment.core.anchor;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dreamlike.llmcomment.core.AnchorStatus;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AnchorTrackerRandomizedTest {
  @Test
  void seededIndependentEditsPreserveBothUniqueCommentRanges() {
    Random random = new Random(0x6c6c6dL);
    for (int sample = 0; sample < 100; sample++) {
      String left = noise(random), middle = noise(random), right = noise(random);
      String first = "【唯一目标甲😀】", second = "【唯一目标乙中文】";
      String previous = left + "\n" + first + "\n" + middle + "\n" + second + "\n" + right;
      List<Anchor> anchors =
          List.of(
              AnchorTracker.capture(
                  previous,
                  LineMap.lineAt(previous, previous.indexOf(first)),
                  LineMap.lineAt(previous, previous.indexOf(first))),
              AnchorTracker.capture(
                  previous,
                  LineMap.lineAt(previous, previous.indexOf(second)),
                  LineMap.lineAt(previous, previous.indexOf(second))));
      for (int step = 0; step < 12; step++) {
        left = edit(random, left);
        middle = edit(random, middle);
        right = edit(random, right);
        String current = left + "\n" + first + "\n" + middle + "\n" + second + "\n" + right;
        anchors = AnchorTracker.relocate(previous, current, anchors);
        for (int i = 0; i < 2; i++) {
          String selected = i == 0 ? first : second;
          Anchor a = anchors.get(i);
          String diagnostic = "sample=" + sample + ",step=" + step + ",anchor=" + i;
          assertEquals(AnchorStatus.ACTIVE, a.status(), diagnostic);
          assertEquals(selected, a.text(), diagnostic);
          assertEquals(current.indexOf(selected), a.startOffset(), diagnostic);
          assertEquals(current.indexOf(selected) + selected.length(), a.endOffset(), diagnostic);
        }
        previous = current;
      }
    }
  }

  @Test
  void unrelatedEditsDoNotWashAwayDuplicateAmbiguity() {
    String old = "same\nsame\n";
    Anchor first = AnchorTracker.capture(old, 1, 1);
    Anchor ambiguous = AnchorTracker.relocate(old, "same\n", List.of(first)).getFirst();
    Anchor stillAmbiguous =
        AnchorTracker.relocate("same\n", "same\nunrelated", List.of(ambiguous)).getFirst();
    assertEquals(AnchorStatus.AMBIGUOUS, stillAmbiguous.status());
    Anchor absent =
        AnchorTracker.relocate("same\nunrelated", "unrelated", List.of(stillAmbiguous)).getFirst();
    assertEquals(AnchorStatus.AMBIGUOUS, absent.status());
    Anchor restored = AnchorTracker.relocate("unrelated", old, List.of(absent)).getFirst();
    assertEquals(AnchorStatus.ACTIVE, restored.status());
    assertEquals(0, restored.startOffset());
  }

  @Test
  void wholeFileRewriteCannotAssignAnUnrelatedReplacementRange() {
    String old = "head\nTARGET\ntail";
    Anchor a = AnchorTracker.capture(old, 2, 2);
    for (String current : List.of("xxxxxxxxxxxxx", "totally unrelated new file")) {
      Anchor changed = AnchorTracker.relocate(old, current, List.of(a)).getFirst();
      assertEquals(AnchorStatus.ORPHANED, changed.status(), current);
      assertEquals("TARGET", changed.text());
    }
  }

  @Test
  void equalDuplicateCountsKeepUntouchedDiffRangesDuringContextEdits() {
    String old = "left-old\nsame\nmiddle-old\nsame\nright-old";
    List<Anchor> original =
        List.of(AnchorTracker.capture(old, 2, 2), AnchorTracker.capture(old, 4, 4));
    String current = "left-new\nsame\nmiddle-new\nsame\nright-new";
    List<Anchor> relocated = AnchorTracker.relocate(old, current, original);
    assertEquals(current.indexOf("same"), relocated.get(0).startOffset());
    assertEquals(current.lastIndexOf("same"), relocated.get(1).startOffset());
    assertTrue(relocated.stream().allMatch(a -> a.status() == AnchorStatus.ACTIVE));
  }

  @Test
  void emptyLinesFollowOffsetsButCannotBeRecoveredBySearchingEmptyText() {
    String old = "a\n\nb";
    Anchor empty = AnchorTracker.capture(old, 2, 2);
    Anchor shifted = AnchorTracker.relocate(old, "prefix\na\n\nb", List.of(empty)).getFirst();
    assertEquals(AnchorStatus.ACTIVE, shifted.status());
    assertEquals(9, shifted.startOffset());
    Anchor deleted = AnchorTracker.relocate(old, "a\nb", List.of(empty)).getFirst();
    assertEquals(AnchorStatus.ORPHANED, deleted.status());
    assertEquals(
        AnchorStatus.ORPHANED,
        AnchorTracker.relocate("a\nb", old, List.of(deleted)).getFirst().status());
    assertEquals("\n", AnchorTracker.capture("\n\n", 1, 2).text());
  }

  @Test
  void storedContextNeverSplitsUtf16SurrogatePairs() {
    String source = "😀" + "a".repeat(62) + "\nTARGET\n" + "b".repeat(62) + "😀";
    Anchor anchor = AnchorTracker.capture(source, 2, 2);
    assertEquals("😀", anchor.before().substring(0, 2));
    assertTrue(anchor.after().endsWith("😀"));
  }

  @Test
  void boundedDiffFallsBackConservativelyForLargeRewrites() {
    String old = "a".repeat(20_000), current = "b".repeat(20_000);
    Anchor a = AnchorTracker.capture(old, 1, 1);
    assertEquals(
        AnchorStatus.ORPHANED,
        AnchorTracker.relocate(old, current, List.of(a)).getFirst().status());
    String preserved = "UNIQUE_TARGET";
    old = "a".repeat(10_000) + "\n" + preserved + "\n" + "c".repeat(10_000);
    current = "b".repeat(10_000) + "\n" + preserved + "\n" + "d".repeat(10_000);
    Anchor recovered =
        AnchorTracker.relocate(old, current, List.of(AnchorTracker.capture(old, 2, 2))).getFirst();
    assertEquals(AnchorStatus.ACTIVE, recovered.status());
    assertEquals(current.indexOf(preserved), recovered.startOffset());
  }

  @Test
  void largePrefixInsertionAndDeletionStillMapExactly() {
    String old = "prefix\nTARGET\nend";
    Anchor original = AnchorTracker.capture(old, 2, 2);
    String insertion = "x".repeat(50_000) + "\n";
    String current = insertion + old;
    Anchor shifted = AnchorTracker.relocate(old, current, List.of(original)).getFirst();
    assertEquals(AnchorStatus.ACTIVE, shifted.status());
    assertEquals(original.startOffset() + insertion.length(), shifted.startOffset());
    Anchor restored = AnchorTracker.relocate(current, old, List.of(shifted)).getFirst();
    assertEquals(AnchorStatus.ACTIVE, restored.status());
    assertEquals(original.startOffset(), restored.startOffset());
  }

  private static String noise(Random random) {
    StringBuilder s = new StringBuilder();
    for (int i = 0, n = random.nextInt(20); i < n; i++) s.append((char) ('a' + random.nextInt(8)));
    return s.toString();
  }

  private static String edit(Random random, String input) {
    int start = random.nextInt(input.length() + 1);
    int end = start + random.nextInt(input.length() - start + 1);
    return input.substring(0, start) + noise(random) + input.substring(end);
  }
}
