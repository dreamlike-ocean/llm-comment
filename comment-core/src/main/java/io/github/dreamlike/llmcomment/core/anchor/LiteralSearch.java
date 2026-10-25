package io.github.dreamlike.llmcomment.core.anchor;

import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;

/** UTF-16 原文查找：普通文本沿用 JDK，高重复长前缀用 KMP，完整保留重叠匹配。 */
final class LiteralSearch {
  static final int MIN_KMP_LENGTH = 256;
  static final int REPEATED_PREFIX = 64;
  private final String pattern;
  private final boolean kmp;
  private final int[] cachedFailure;

  LiteralSearch(String pattern, boolean cacheFailure) {
    this.pattern = pattern;
    // 最大 border >=64 等价于首64单元在模式内再次出现；普通长文本无需分配前缀表。
    kmp =
        pattern.length() >= MIN_KMP_LENGTH
            && pattern.indexOf(pattern.substring(0, REPEATED_PREFIX), 1) >= 0;
    cachedFailure = kmp && cacheFailure ? failure(pattern) : null;
  }

  boolean cachedFailure() {
    return cachedFailure != null;
  }

  PrimitiveIterator.OfInt positions(String source) {
    if (!kmp)
      return new PrimitiveIterator.OfInt() {
        private int next = source.indexOf(pattern);

        public boolean hasNext() {
          return next >= 0;
        }

        public int nextInt() {
          if (next < 0) throw new NoSuchElementException();
          int result = next;
          next = source.indexOf(pattern, next + 1);
          return result;
        }
      };
    int[] failure = cachedFailure == null ? failure(pattern) : cachedFailure;
    return new PrimitiveIterator.OfInt() {
      private int offset, matched;
      private int next = advance();

      private int advance() {
        while (offset < source.length()) {
          char c = source.charAt(offset++);
          while (matched > 0 && c != pattern.charAt(matched)) matched = failure[matched - 1];
          if (c == pattern.charAt(matched)) matched++;
          if (matched == pattern.length()) {
            int result = offset - matched;
            matched = failure[matched - 1];
            return result;
          }
        }
        return -1;
      }

      public boolean hasNext() {
        return next >= 0;
      }

      public int nextInt() {
        if (next < 0) throw new NoSuchElementException();
        int result = next;
        next = advance();
        return result;
      }
    };
  }

  private static int[] failure(String pattern) {
    int[] failure = new int[pattern.length()];
    for (int i = 1, matched = 0; i < pattern.length(); i++) {
      while (matched > 0 && pattern.charAt(i) != pattern.charAt(matched))
        matched = failure[matched - 1];
      if (pattern.charAt(i) == pattern.charAt(matched)) matched++;
      failure[i] = matched;
    }
    return failure;
  }
}
