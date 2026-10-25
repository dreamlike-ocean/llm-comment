package io.github.dreamlike.llmcomment.core.anchor;

import java.util.*;

/** 多模式计数不展开全部匹配；快照共享紧凑自动机，计数与有界候选缓存按需建立。 */
final class OccurrenceIndex {
  private static final int PATTERN_BUDGET = 131_072;
  private static final int POSITION_BUDGET = 65_536;
  // 小文件中 JDK 的向量化查找成本更低，先按文件长度和模式数估算查找量。
  private static final long AUTOMATON_SEARCH_THRESHOLD = 1_000_000L;

  private static final class Match {
    int count = -1;
    List<Integer> positions;
  }

  private static final class Patterns {
    final Map<String, Integer> ids = new HashMap<>();
    final String[] texts;
    final int totalLength;
    final LiteralSearch[] searches;
    int cachedFailureUnits;
    final boolean useAutomaton;
    Automaton automaton;

    Patterns(List<Anchor> anchors) {
      long length = 0;
      for (Anchor anchor : anchors) {
        Objects.requireNonNull(anchor, "anchor");
        String text = anchor.text();
        if (!text.isEmpty() && !ids.containsKey(text)) {
          ids.put(text, ids.size());
          length += text.length();
        }
      }
      texts = new String[ids.size()];
      searches = new LiteralSearch[ids.size()];
      ids.forEach((text, id) -> texts[id] = text);
      useAutomaton = ids.size() > 8 && length <= PATTERN_BUDGET;
      totalLength = useAutomaton ? (int) length : 0;
    }

    LiteralSearch search(int id) {
      if (searches[id] == null) {
        String text = texts[id];
        LiteralSearch search =
            new LiteralSearch(text, text.length() <= PATTERN_BUDGET - cachedFailureUnits);
        if (search.cachedFailure()) cachedFailureUnits += text.length();
        searches[id] = search;
      }
      return searches[id];
    }

    Automaton automaton() {
      if (automaton == null) automaton = new Automaton(texts, totalLength);
      return automaton;
    }
  }

  /** 节点以整数编号连接；根节点直接按 UTF-16 单元索引，其余通常只有一个子节点。 */
  private static final class Automaton {
    final int[] firstChild, nextSibling, fail, terminal, breadthFirst;
    final char[] labels;
    final int[] rootChildren = new int[Character.MAX_VALUE + 1];
    int nodes = 1;

    Automaton(String[] patterns, int totalLength) {
      int capacity = totalLength + 1;
      firstChild = new int[capacity];
      nextSibling = new int[capacity];
      fail = new int[capacity];
      terminal = new int[capacity];
      breadthFirst = new int[capacity];
      labels = new char[capacity];
      for (int id = 0; id < patterns.length; id++) {
        String pattern = patterns[id];
        int node = 0;
        for (int i = 0; i < pattern.length(); i++) {
          char c = pattern.charAt(i);
          int child = child(node, c);
          if (child == 0) {
            child = nodes++;
            labels[child] = c;
            nextSibling[child] = firstChild[node];
            firstChild[node] = child;
            if (node == 0) rootChildren[c] = child;
          }
          node = child;
        }
        terminal[node] = id + 1;
      }
      int size = 0;
      for (int child = firstChild[0]; child != 0; child = nextSibling[child])
        breadthFirst[size++] = child;
      for (int i = 0; i < size; i++) {
        int node = breadthFirst[i];
        for (int child = firstChild[node]; child != 0; child = nextSibling[child]) {
          char c = labels[child];
          int fallback = fail[node], candidate = child(fallback, c);
          while (fallback != 0 && candidate == 0) {
            fallback = fail[fallback];
            candidate = child(fallback, c);
          }
          fail[child] = candidate;
          breadthFirst[size++] = child;
        }
      }
    }

    private int child(int node, char c) {
      if (node == 0) return rootChildren[c];
      int child = firstChild[node];
      if (child == 0 || labels[child] == c) return child;
      for (child = nextSibling[child]; child != 0; child = nextSibling[child])
        if (labels[child] == c) return child;
      return 0;
    }

    int[] count(String source, int patternCount) {
      int[] visits = new int[nodes];
      int node = 0;
      for (int i = 0; i < source.length(); i++) {
        char c = source.charAt(i);
        int child = child(node, c);
        while (node != 0 && child == 0) {
          node = fail[node];
          child = child(node, c);
        }
        node = child;
        visits[node]++;
      }
      int[] result = new int[patternCount];
      // 沿失败树汇总访问次数，不枚举高重复数据的全部匹配位置。
      for (int i = nodes - 2; i >= 0; i--) {
        int visited = breadthFirst[i];
        if (terminal[visited] != 0) result[terminal[visited] - 1] = visits[visited];
        visits[fail[visited]] += visits[visited];
      }
      return result;
    }
  }

  private final Patterns patterns;
  private final Match[] matches;
  private final String source;
  private int[] counts;
  private int cachedPositions;

  OccurrenceIndex(String source, List<Anchor> anchors) {
    this(source, new Patterns(anchors));
  }

  private OccurrenceIndex(String source, Patterns patterns) {
    this.source = source;
    this.patterns = patterns;
    matches = new Match[patterns.texts.length];
  }

  OccurrenceIndex forSource(String otherSource) {
    return new OccurrenceIndex(otherSource, patterns);
  }

  int count(String text) {
    Integer id = patterns.ids.get(text);
    if (id == null) return 0;
    if (patterns.useAutomaton
        && (long) source.length() * patterns.texts.length > AUTOMATON_SEARCH_THRESHOLD) {
      if (counts == null) counts = patterns.automaton().count(source, patterns.texts.length);
      return counts[id];
    }
    Match match = match(id);
    if (match.count < 0) scan(id, match);
    return match.count;
  }

  Iterable<Integer> positions(String text) {
    Integer id = patterns.ids.get(text);
    if (id == null) return List.of();
    Match match = match(id);
    if (match.positions == null) {
      int count = count(text);
      if (count == 0) match.positions = List.of();
      else if (count <= POSITION_BUDGET - cachedPositions) scan(id, match);
    }
    if (match.positions != null) return match.positions;
    // 缓存满时仍逐个返回所有候选，不截断计数或歧义判断。
    LiteralSearch search = patterns.search(id);
    return () -> search.positions(source);
  }

  private Match match(int id) {
    if (matches[id] == null) matches[id] = new Match();
    return matches[id];
  }

  private void scan(int id, Match match) {
    int count = 0;
    List<Integer> found = new ArrayList<>();
    PrimitiveIterator.OfInt positions = patterns.search(id).positions(source);
    while (positions.hasNext()) {
      int pos = positions.nextInt();
      count++;
      if (found != null) {
        if (count <= POSITION_BUDGET - cachedPositions) found.add(pos);
        else found = null;
      }
    }
    match.count = count;
    match.positions = found;
    if (found != null) cachedPositions += found.size();
  }
}
