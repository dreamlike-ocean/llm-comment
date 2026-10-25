package io.github.dreamlike.llmcomment.core.anchor;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dreamlike.llmcomment.core.AnchorStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnchorTrackerTest {
  private Anchor move(String old, String now, Anchor anchor) {
    return AnchorTracker.relocate(old, now, List.of(anchor)).getFirst();
  }

  @Test
  void capturesUtf16AndAllLineTerminators() {
    String source = "😀中文\r\n\r下一行\n末尾\n";
    Anchor a = AnchorTracker.capture(source, 1, 3);
    assertEquals("😀中文\r\n\r下一行", a.text());
    assertEquals(0, a.startOffset());
    assertEquals(a.text().length(), a.endOffset());
    assertEquals(5, LineMap.lineCount(source));
    assertEquals(1, LineMap.lineAt(source, 5)); // CRLF 中间仍属于前一行
    assertEquals(2, LineMap.lineAt(source, 6));
    assertEquals("", AnchorTracker.capture(source, 2, 2).text());
    assertEquals("", AnchorTracker.capture(source, 5, 5).text());
    assertEquals(1, LineMap.lineCount(""));
  }

  @Test
  void validatesArguments() {
    assertThrows(IllegalArgumentException.class, () -> AnchorTracker.capture("a", 0, 1));
    assertThrows(IllegalArgumentException.class, () -> AnchorTracker.capture("a", 2, 2));
    assertThrows(IllegalArgumentException.class, () -> AnchorTracker.capture("a", 1, 0));
    assertThrows(IllegalArgumentException.class, () -> LineMap.lineAt("a", 2));
    assertThrows(IllegalArgumentException.class, () -> LineMap.lineAt("a", -1));
    assertThrows(NullPointerException.class, () -> AnchorTracker.capture(null, 1, 1));
  }

  @Test
  void tracksIndependentEditsBeforeAndAfterRange() {
    String old = "前言\n目标😀\n末尾";
    Anchor a = AnchorTracker.capture(old, 2, 2);
    String now = "新增\n前言变化\n目标😀\n末尾扩展";
    Anchor b = move(old, now, a);
    assertEquals(AnchorStatus.ACTIVE, b.status());
    assertEquals(now.indexOf("目标"), b.startOffset());
    Anchor c = move(now, "目标😀\n末尾扩展", b);
    assertEquals(0, c.startOffset());
    assertEquals("目标😀", c.text());
  }

  @Test
  void excludesBothBoundaryInsertionsButIncludesInternalChanges() {
    String old = "aaa\n目标文字\nzzz";
    Anchor a = AnchorTracker.capture(old, 2, 2);
    Anchor b = move(old, "aaa\n左目标文字右\nzzz", a);
    assertEquals("目标文字", b.text());
    assertEquals(AnchorStatus.ACTIVE, b.status());
    Anchor c = move(old, "aaa\n目标新增文字\nzzz", a);
    assertEquals("目标新增文字", c.text());
    assertEquals(AnchorStatus.MODIFIED, c.status());
    Anchor d = move("aaa\n目标新增文字\nzzz", "aaa\n目标文字\nzzz", c);
    assertEquals("目标文字", d.text());
    assertEquals(AnchorStatus.MODIFIED, d.status());
  }

  @Test
  void replacementBeginningAtRightBoundaryDoesNotExpandTheRange() {
    String old = "a\nTARGET\nzzz";
    Anchor original = AnchorTracker.capture(old, 2, 2);
    Anchor moved = move(old, "a\nTARGETrightzzz", original);
    assertEquals(AnchorStatus.ACTIVE, moved.status());
    assertEquals("TARGET", moved.text());
    assertEquals(original.endOffset(), moved.endOffset());
  }

  @Test
  void deletionRetainsTextAndCanRecoverLater() {
    String old = "before\n唯一目标\nafter";
    Anchor a = AnchorTracker.capture(old, 2, 2);
    String deleted = "before\n\nafter";
    Anchor b = move(old, deleted, a);
    assertEquals(AnchorStatus.ORPHANED, b.status());
    assertEquals("唯一目标", b.text());
    Anchor c = move(deleted, old, b);
    assertEquals(AnchorStatus.ACTIVE, c.status());
    assertEquals(a.startOffset(), c.startOffset());
  }

  @Test
  void recoversMovedBlockAndRejectsUnresolvedDuplicateRecovery() {
    String old = "开头\n唯一代码块\n结束";
    Anchor a = AnchorTracker.capture(old, 2, 2);
    String now = "唯一代码块\n开头\n结束";
    Anchor b = move(old, now, a);
    assertEquals(0, b.startOffset());
    assertEquals("唯一代码块", b.text());
    Anchor missing = new Anchor(0, 3, AnchorStatus.ORPHANED, "abc", "", "");
    assertEquals(AnchorStatus.AMBIGUOUS, move("", "abc abc", missing).status());
    Anchor contextual = new Anchor(0, 3, AnchorStatus.ORPHANED, "abc", "left:", ":right");
    Anchor recovered = move("", "abc; left:abc:right; abc", contextual);
    assertEquals(10, recovered.startOffset());
  }

  @Test
  void removingOneDuplicateDoesNotTransferItsIdentityToTheRemainingCopy() {
    String old = "相同代码\n相同代码\n";
    Anchor first = AnchorTracker.capture(old, 1, 1);
    Anchor result = move(old, "相同代码\n", first);
    assertEquals(AnchorStatus.AMBIGUOUS, result.status());
    assertEquals(first.text(), result.text());
  }

  @Test
  void replacementAndDeletionInsideTheRangeHaveDifferentOutcomes() {
    String old = "head\nabcdef\ntail";
    Anchor a = AnchorTracker.capture(old, 2, 2);
    Anchor shortened = move(old, "head\nabef\ntail", a);
    assertEquals("abef", shortened.text());
    assertEquals(AnchorStatus.MODIFIED, shortened.status());
    Anchor replaced = move(old, "head\nxyz\ntail", a);
    assertEquals("xyz", replaced.text());
    assertEquals(AnchorStatus.MODIFIED, replaced.status());
  }

  @Test
  void multipleAnchorsShareOneDiffAndUnchangedSnapshotsRecoverLostAnchors() {
    String old = "first\nsecond\nthird";
    List<Anchor> changed =
        AnchorTracker.relocate(
            old,
            "x\nfirst\nsecond!\nthird",
            List.of(AnchorTracker.capture(old, 1, 1), AnchorTracker.capture(old, 3, 3)));
    assertEquals(2, changed.get(0).startOffset());
    assertEquals("third", changed.get(1).text());
    Anchor lost = new Anchor(0, 4, AnchorStatus.MISSING_FILE, "唯一目标", "", "");
    assertEquals(AnchorStatus.ACTIVE, move("唯一目标", "唯一目标", lost).status());
  }
}
