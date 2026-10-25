package io.github.dreamlike.llmcomment.core.anchor;

import com.github.difflib.DiffUtils;
import java.util.*;

/** 唯一行的递增配对把大文件切成独立的小差异；只有不能继续确认的块才降级。 */
final class HierarchicalDiff {
  private static final long CHARACTER_BUDGET = 4_000_000L;
  private static final int[] GEAR = gear();

  static final class Edit {
    final int start, removed, inserted;
    final boolean coarse;

    Edit(int start, int removed, int inserted, boolean coarse) {
      this.start = start;
      this.removed = removed;
      this.inserted = inserted;
      this.coarse = coarse;
    }
  }

  static List<Edit> diff(String old, String now) {
    List<Edit> edits = new ArrayList<>();
    ArrayDeque<Region> pending = new ArrayDeque<>();
    pending.push(new Region(0, old.length(), 0, now.length(), true, 0));
    while (!pending.isEmpty()) split(old, now, edits, pending);
    return edits;
  }

  private record Region(int a, int ae, int b, int be, boolean lines, int depth) {}

  private static void split(String old, String now, List<Edit> edits, ArrayDeque<Region> pending) {
    Region region = pending.pop();
    int a = region.a(), ae = region.ae(), b = region.b(), be = region.be();
    boolean lines = region.lines();
    int depth = region.depth();
    while (a < ae && b < be && old.charAt(a) == now.charAt(b)) {
      a++;
      b++;
    }
    while (a < ae && b < be && old.charAt(ae - 1) == now.charAt(be - 1)) {
      ae--;
      be--;
    }
    int n = ae - a, m = be - b;
    if (n == 0 && m == 0) return;
    if (n == 0 || m == 0) {
      edits.add(new Edit(a, n, m, false));
      return;
    }
    if ((long) n * m <= CHARACTER_BUDGET) {
      int origin = a;
      try {
        DiffUtils.diff(chars(old, a, ae), chars(now, b, be), new IterativeMyersDiff<>())
            .getDeltas()
            .stream()
            .sorted(Comparator.comparingInt(d -> d.getSource().getPosition()))
            .forEach(
                d ->
                    edits.add(
                        new Edit(
                            origin + d.getSource().getPosition(),
                            d.getSource().size(),
                            d.getTarget().size(),
                            false)));
      } catch (IterativeMyersDiff.BudgetExceeded exhausted) {
        // 未完成脚本尚未输出；保留完整粗块，交给上层原文身份校验与恢复。
        edits.add(new Edit(a, n, m, true));
      }
      return;
    }
    // 限制切块层数，极端重复数据仍返回明确的粗粒度差异供身份校验。
    if (depth >= 4) {
      edits.add(new Edit(a, n, m, true));
      return;
    }
    List<Token> left = tokens(old, a, ae, lines), right = tokens(now, b, be, lines);
    Map<String, Integer> oldUnique = unique(left), newUnique = unique(right);
    List<int[]> pairs = new ArrayList<>();
    for (int i = 0; i < left.size(); i++) {
      String text = left.get(i).text;
      Integer j = newUnique.get(text);
      if (oldUnique.get(text) == i && j != null && j >= 0) pairs.add(new int[] {i, j});
    }
    List<int[]> ordered = increasing(pairs);
    if (ordered.isEmpty()) {
      if (lines) pending.push(new Region(a, ae, b, be, false, depth + 1));
      else edits.add(new Edit(a, n, m, true));
      return;
    }
    List<Region> children = new ArrayList<>(ordered.size() + 1);
    int previousA = a, previousB = b;
    for (int[] pair : ordered) {
      Token x = left.get(pair[0]), y = right.get(pair[1]);
      // 再细分只需文本块，避免重复扫描相同层级。
      children.add(new Region(previousA, x.start, previousB, y.start, false, depth + 1));
      previousA = x.end;
      previousB = y.end;
    }
    children.add(new Region(previousA, ae, previousB, be, false, depth + 1));
    // 反向压栈，输出仍按原文件 offset 递增。
    for (int i = children.size() - 1; i >= 0; i--) pending.push(children.get(i));
  }

  private record Token(String text, int start, int end) {}

  private static List<Token> tokens(String text, int start, int end, boolean lines) {
    List<Token> result = new ArrayList<>();
    for (int i = start; i < end; ) {
      int from = i;
      if (lines) {
        while (i < end && text.charAt(i) != '\r' && text.charAt(i) != '\n') i++;
        if (i < end && text.charAt(i++) == '\r' && i < end && text.charAt(i) == '\n') i++;
      } else {
        // 内容决定边界；插入后仍能重新对齐，且比较始终验证完整文本。
        long hash = 0;
        do {
          hash = (hash << 1) + GEAR[text.charAt(i++)];
        } while (i < end && i - from < 256 && (i - from < 32 || (hash & 63) != 0));
      }
      result.add(new Token(text.substring(from, i), from, i));
    }
    return result;
  }

  private static int[] gear() {
    int[] values = new int[Character.MAX_VALUE + 1];
    for (int i = 0; i < values.length; i++) {
      int value = i + 0x9e3779b9;
      value = (value ^ (value >>> 16)) * 0x85ebca6b;
      value = (value ^ (value >>> 13)) * 0xc2b2ae35;
      values[i] = value ^ (value >>> 16);
    }
    return values;
  }

  private static Map<String, Integer> unique(List<Token> tokens) {
    Map<String, Integer> result = new HashMap<>();
    for (int i = 0; i < tokens.size(); i++) result.merge(tokens.get(i).text, i, (a, b) -> -1);
    return result;
  }

  private static List<int[]> increasing(List<int[]> pairs) {
    int[] tails = new int[pairs.size()], parents = new int[pairs.size()];
    int size = 0;
    for (int i = 0; i < pairs.size(); i++) {
      int lo = 0, hi = size;
      while (lo < hi) {
        int mid = (lo + hi) >>> 1;
        if (pairs.get(tails[mid])[1] < pairs.get(i)[1]) lo = mid + 1;
        else hi = mid;
      }
      parents[i] = lo == 0 ? -1 : tails[lo - 1];
      tails[lo] = i;
      if (lo == size) size++;
    }
    LinkedList<int[]> result = new LinkedList<>();
    for (int i = size == 0 ? -1 : tails[size - 1]; i >= 0; i = parents[i])
      result.addFirst(pairs.get(i));
    return result;
  }

  private static List<Character> chars(String text, int start, int end) {
    return new AbstractList<>() {
      public Character get(int index) {
        return text.charAt(start + Objects.checkIndex(index, size()));
      }

      public int size() {
        return end - start;
      }
    };
  }
}
