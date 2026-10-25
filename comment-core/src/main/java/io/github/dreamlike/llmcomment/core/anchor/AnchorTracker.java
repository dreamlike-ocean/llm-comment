package io.github.dreamlike.llmcomment.core.anchor;

import io.github.dreamlike.llmcomment.core.AnchorStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 以共享快照的字符差异更新范围；失效范围保留原文及上下文供恢复使用。 */
public final class AnchorTracker {
  private static final int CONTEXT = 64;

  private AnchorTracker() {}

  public static Anchor capture(String text, int startLine, int endLine) {
    Objects.requireNonNull(text, "text");
    if (startLine < 1 || endLine < startLine || endLine > LineMap.lineCount(text))
      throw new IllegalArgumentException("行范围超出文件范围");
    int line = 1, start = startLine == 1 ? 0 : -1, end = text.length();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c != '\r' && c != '\n') continue;
      if (line == endLine) {
        end = i;
        break;
      }
      if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
      line++;
      if (line == startLine) start = i + 1;
    }
    return at(text, start, end, AnchorStatus.ACTIVE);
  }

  public static List<Anchor> relocate(String previous, String current, List<Anchor> anchors) {
    Objects.requireNonNull(previous, "previous");
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(anchors, "anchors");
    if (anchors.isEmpty()) return List.of();
    if (previous.equals(current) && anchors.stream().allMatch(AnchorTracker::valid))
      return List.copyOf(anchors);
    OccurrenceIndex currentMatches = new OccurrenceIndex(current, anchors);
    if (previous.equals(current))
      return anchors.stream().map(a -> valid(a) ? a : recover(current, a, currentMatches)).toList();
    OccurrenceIndex previousMatches = currentMatches.forSource(previous);
    List<HierarchicalDiff.Edit> rawEdits = HierarchicalDiff.diff(previous, current);
    // 线性空间 Myers 可能把一次替换拆成相邻的删除和插入，先合并再映射边界。
    List<HierarchicalDiff.Edit> edits = new ArrayList<>();
    for (HierarchicalDiff.Edit edit : rawEdits) {
      if (!edits.isEmpty()) {
        HierarchicalDiff.Edit last = edits.getLast();
        if (edit.start <= last.start + last.removed) {
          edits.set(
              edits.size() - 1,
              new HierarchicalDiff.Edit(
                  last.start,
                  Math.max(last.start + last.removed, edit.start + edit.removed) - last.start,
                  last.inserted + edit.inserted,
                  last.coarse || edit.coarse));
          continue;
        }
      }
      edits.add(edit);
    }
    List<Anchor> result = new ArrayList<>();
    for (Anchor a : anchors) {
      Objects.requireNonNull(a, "anchor");
      if (!valid(a)
          || a.startOffset() < 0
          || a.endOffset() > previous.length()
          || !previous.substring(a.startOffset(), a.endOffset()).equals(a.text())) {
        result.add(recover(current, a, currentMatches));
        continue;
      }
      // 相同原文有多份且其中一份消失时，diff 的相等片段不能证明身份。
      if (!a.text().isEmpty()
          && previousMatches.count(a.text()) > 1
          && currentMatches.count(a.text()) < previousMatches.count(a.text())
          && currentMatches.count(a.text()) > 0) {
        result.add(lost(a, AnchorStatus.AMBIGUOUS));
        continue;
      }
      if (edits.stream()
          .anyMatch(
              e ->
                  e.coarse
                      && (a.startOffset() < e.start + e.removed && a.endOffset() > e.start
                          || a.text().isEmpty()
                              && a.startOffset() >= e.start
                              && a.startOffset() < e.start + e.removed))) {
        result.add(recover(current, a, currentMatches));
        continue;
      }
      int start = map(a.startOffset(), true, edits), end = map(a.endOffset(), false, edits);
      if (end < start) end = start;
      String mapped = current.substring(start, end);
      if (!mapped.equals(a.text())) {
        Anchor recovered = recover(current, a, currentMatches);
        if (recovered.status() == AnchorStatus.ACTIVE
            || recovered.status() == AnchorStatus.AMBIGUOUS) {
          result.add(recovered);
          continue;
        }
      }
      boolean erasedWithSurroundings =
          edits.stream()
              .anyMatch(
                  e ->
                      e.start <= a.startOffset()
                          && e.start + e.removed >= a.endOffset()
                          && (e.start < a.startOffset() || e.start + e.removed > a.endOffset()));
      int preserved = a.endOffset() - a.startOffset();
      for (HierarchicalDiff.Edit e : edits)
        preserved -=
            Math.max(
                0,
                Math.min(a.endOffset(), e.start + e.removed) - Math.max(a.startOffset(), e.start));
      boolean noIdentity =
          !mapped.equals(a.text())
              && preserved * 2 < a.text().length()
              && contextScore(current, start, a, end) == 0;
      if (erasedWithSurroundings
          || noIdentity
          || (start == end && !a.text().isEmpty())
          || (a.text().isEmpty() && !emptyLineAt(current, start)))
        result.add(lost(a, AnchorStatus.ORPHANED));
      else
        result.add(
            at(current, start, end, mapped.equals(a.text()) ? a.status() : AnchorStatus.MODIFIED));
    }
    return List.copyOf(result);
  }

  private static boolean valid(Anchor a) {
    return a.status() == AnchorStatus.ACTIVE || a.status() == AnchorStatus.MODIFIED;
  }

  // 插入恰好在左端时向右移动，在右端时不扩张。替换内部端点映射到替换的对应边界。
  private static int map(int offset, boolean start, List<HierarchicalDiff.Edit> edits) {
    int shift = 0;
    for (HierarchicalDiff.Edit e : edits) {
      if (e.start > offset) break;
      if (e.start == offset && !start) return offset + shift;
      if (e.removed == 0) {
        if (e.start < offset || start) shift += e.inserted;
      } else if (offset >= e.start + e.removed) shift += e.inserted - e.removed;
      else return e.start + shift + (start ? 0 : e.inserted);
    }
    return offset + shift;
  }

  private static Anchor recover(String current, Anchor a, OccurrenceIndex matches) {
    if (a.text().isEmpty()) return lost(a, AnchorStatus.ORPHANED);
    int best = -1, bestScore = -1, ties = 0;
    for (int pos : matches.positions(a.text())) {
      int score = contextScore(current, pos, a);
      if (score > bestScore) {
        best = pos;
        bestScore = score;
        ties = 1;
      } else if (score == bestScore) ties++;
    }
    if (best < 0)
      return lost(
          a, a.status() == AnchorStatus.AMBIGUOUS ? AnchorStatus.AMBIGUOUS : AnchorStatus.ORPHANED);
    if (ties > 1) return lost(a, AnchorStatus.AMBIGUOUS);
    // 已经失去副本身份时，单个剩余候选本身不足以证明原来的副本恢复。
    if (a.status() == AnchorStatus.AMBIGUOUS
        && (a.before().isEmpty() && a.after().isEmpty()
            || bestScore != a.before().length() + a.after().length()))
      return lost(a, AnchorStatus.AMBIGUOUS);
    return at(current, best, best + a.text().length(), AnchorStatus.ACTIVE);
  }

  private static boolean emptyLineAt(String text, int pos) {
    return (pos == 0 || text.charAt(pos - 1) == '\n' || text.charAt(pos - 1) == '\r')
        && (pos == text.length() || text.charAt(pos) == '\n' || text.charAt(pos) == '\r');
  }

  private static int contextScore(String text, int pos, Anchor a) {
    return contextScore(text, pos, a, pos + a.text().length());
  }

  private static int contextScore(String text, int pos, Anchor a, int end) {
    int score = 0;
    for (int i = 1; i <= a.before().length() && pos - i >= 0; i++) {
      if (text.charAt(pos - i) != a.before().charAt(a.before().length() - i)) break;
      score++;
    }
    for (int i = 0; i < a.after().length() && end + i < text.length(); i++) {
      if (text.charAt(end + i) != a.after().charAt(i)) break;
      score++;
    }
    return score;
  }

  private static Anchor at(String text, int start, int end, AnchorStatus status) {
    int beforeStart = Math.max(0, start - CONTEXT),
        afterEnd = Math.min(text.length(), end + CONTEXT);
    // 上下文写入 UTF-8 SQLite 文本时不能切断代理对；公开 offset 仍按 UTF-16 计数。
    if (beforeStart > 0
        && Character.isLowSurrogate(text.charAt(beforeStart))
        && Character.isHighSurrogate(text.charAt(beforeStart - 1))) beforeStart--;
    if (afterEnd < text.length()
        && afterEnd > 0
        && Character.isHighSurrogate(text.charAt(afterEnd - 1))
        && Character.isLowSurrogate(text.charAt(afterEnd))) afterEnd++;
    return new Anchor(
        start,
        end,
        status,
        text.substring(start, end),
        text.substring(beforeStart, start),
        text.substring(end, afterEnd));
  }

  private static Anchor lost(Anchor a, AnchorStatus status) {
    return new Anchor(a.startOffset(), a.endOffset(), status, a.text(), a.before(), a.after());
  }
}
