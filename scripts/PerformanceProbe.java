import io.github.dreamlike.llmcomment.core.*;
import io.github.dreamlike.llmcomment.core.anchor.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 固定输入的独立 JVM 基准；构造、写文件、建库和结果校验均不计入调用耗时。 */
public final class PerformanceProbe {
  static volatile long checksum;
  static final int COUNT = 1000;
  static final String BASE = source();
  static final String CHINESE = chineseSource();
  static final String EMOJI = emojiSource();

  static String emojiSource() {
    var text = new StringBuilder();
    for (int i = 0; i < 8192; i++) text.append(chineseLine(marker(i, true) + "：", "😀🌟🚀"));
    return text.toString();
  }

  static String chineseDigits(int value) {
    String decimal = "%05d".formatted(value);
    var result = new StringBuilder();
    for (int i = 0; i < decimal.length(); i++)
      result.append("零一二三四五六七八九".charAt(decimal.charAt(i) - '0'));
    return result.toString();
  }

  static String marker(int line, boolean chinese) {
    return chinese ? "第" + chineseDigits(line) + "行" : "row_%05d".formatted(line);
  }

  static String chineseLine(String prefix, String body) {
    return prefix + body.repeat(128).substring(0, 126 - prefix.length()) + "\r\n";
  }

  static String chineseSource() {
    var text = new StringBuilder();
    for (int i = 0; i < 8192; i++)
      text.append(chineseLine(marker(i, true), "春风吹过青山绿水程序逐行记录评论位置文件快照保存历史版本"));
    return text.toString();
  }

  static String source() {
    var b = new StringBuilder();
    for (int i = 0; i < 8192; i++)
      b.append("row_%05d 中文😀 ".formatted(i))
          .append("abcdefghijklmnopqrstuvwxyz0123456789".repeat(3))
          .append("\r\n");
    return b.toString();
  }

  static List<Anchor> anchors(String text) {
    var out = new ArrayList<Anchor>();
    for (int i = 0; i < COUNT; i++) out.add(AnchorTracker.capture(text, 2 + i * 8, 2 + i * 8));
    return out;
  }

  static long allocated() {
    var bean = ManagementFactory.getThreadMXBean();
    if (bean instanceof com.sun.management.ThreadMXBean b && b.isThreadAllocatedMemorySupported()) {
      if (!b.isThreadAllocatedMemoryEnabled()) b.setThreadAllocatedMemoryEnabled(true);
      return b.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
    return -1;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3)
      throw new IllegalArgumentException("用法：PerformanceProbe case warmup samples");
    long setupStart = System.nanoTime();
    boolean chinese = args[0].contains("-chinese-");
    boolean emoji = args[0].contains("-emoji-");
    String name = args[0].replace("-chinese-", "-").replace("-emoji-", "-");
    String source = emoji ? EMOJI : chinese ? CHINESE : BASE;
    int warm = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
    if (warm < 0 || samples < 1) throw new IllegalArgumentException("warmup 不得为负，samples 必须大于零");
    if (!Set.of(
            "anchor-single",
            "anchor-single-unchanged",
            "anchor-small-many",
            "anchor-prefix",
            "anchor-wide",
            "anchor-wide-internal",
            "anchor-repeat",
            "anchor-rewrite",
            "anchor-nearmiss",
            "service-unchanged",
            "service-changed",
            "service-get")
        .contains(name)) throw new IllegalArgumentException("未知 case: " + name);
    String previous = source, current = (chinese ? "前置新增内容\r\n" : "// prefix 中文\r\n") + source;
    if (name.equals("anchor-wide")) {
      var b = new StringBuilder(source);
      for (int i = 7800; i >= 200; i -= 400) {
        int p = source.indexOf(marker(i, chinese));
        b.insert(p, chinese ? "多处新增内容\r\n" : "// small edit\r\n");
      }
      current = b.toString();
    } else if (name.equals("anchor-wide-internal")) {
      var b = new StringBuilder(source);
      for (int i = 950; i >= 0; i -= 50) {
        int p = source.indexOf(marker(1 + i * 8, chinese)) + 16;
        b.insert(p, chinese ? "修改内容" : "changed");
      }
      current = b.toString();
    } else if (name.equals("anchor-repeat")) {
      previous =
          chinese
              ? chineseLine("", "重复文本中文评论范围定位").repeat(8192)
              : "same repeated 中文\r\n".repeat(8192);
      current = (chinese ? "前置新增内容\r\n" : "// prefix\r\n") + previous;
    } else if (name.equals("anchor-rewrite"))
      current =
          chinese
              ? chineseLine("", "海洋星辰森林雨雪大地城市道路").repeat(8192)
              : "fully rewritten different content\n".repeat(32000);
    if (name.startsWith("anchor-single") || name.equals("anchor-small-many")) {
      previous = source.substring(0, source.indexOf(marker(100, chinese)));
      current = (chinese ? "前置新增内容\r\n" : "// small change\r\n") + previous;
    }
    if (name.equals("anchor-single-unchanged")) current = previous;
    if (name.equals("anchor-nearmiss")) previous = current = "甲".repeat(100000) + "乙";
    var original =
        name.startsWith("anchor-single")
            ? List.of(AnchorTracker.capture(previous, 50, 50))
            : name.equals("anchor-small-many") || name.equals("anchor-nearmiss")
                ? List.<Anchor>of()
                : anchors(previous);
    if (name.equals("anchor-small-many")) {
      var small = new ArrayList<Anchor>();
      for (int i = 0; i < 20; i++) small.add(AnchorTracker.capture(previous, 2 + i * 4, 2 + i * 4));
      original = List.copyOf(small);
    }
    if (name.equals("anchor-nearmiss"))
      original =
          List.of(new Anchor(0, 1001, AnchorStatus.ORPHANED, "甲".repeat(1000) + "丙", "", ""));
    Path directory = null;
    CommentService service = null;
    String id = null;
    if (name.startsWith("service-")) {
      directory = Files.createTempDirectory("comment-bench-");
      Files.writeString(directory.resolve("code.txt"), source);
      service = new CommentService(directory);
      var first = service.create("code.txt", 2, 2, "body", "benchmark");
      id = first.id();
      // 建库仅为调用提供真实、完整的评论/版本/快照关系，不纳入计时。
      try (var c =
          DriverManager.getConnection(
              "jdbc:sqlite:" + directory.resolve(".llm-comments/comments.db"))) {
        c.setAutoCommit(false);
        try (var p =
                c.prepareStatement(
                    "INSERT INTO comments SELECT"
                        + " ?,file,?,body,author,created,updated,deleted,?,?,status,?,?,? FROM"
                        + " comments WHERE id=?");
            var r =
                c.prepareStatement(
                    "INSERT INTO"
                        + " revisions(id,comment_id,parent,action,body,author,created,snapshot,start,end,start_line,end_line,status,selected)"
                        + " SELECT ?,?,parent,action,body,author,created,snapshot,?,?,?,?,status,?"
                        + " FROM revisions WHERE id=?")) {
          for (int i = 1; i < COUNT; i++) {
            String ci = "comment-" + i, ri = "revision-" + i;
            var anchor = original.get(i);
            p.setString(1, ci);
            p.setString(2, ri);
            p.setInt(3, anchor.startOffset());
            p.setInt(4, anchor.endOffset());
            p.setString(5, anchor.text());
            p.setString(6, anchor.before());
            p.setString(7, anchor.after());
            p.setString(8, id);
            p.addBatch();
            r.setString(1, ri);
            r.setString(2, ci);
            r.setInt(3, anchor.startOffset());
            r.setInt(4, anchor.endOffset());
            r.setInt(5, 2 + i * 8);
            r.setInt(6, 2 + i * 8);
            r.setString(7, anchor.text());
            r.setString(8, first.currentRevisionId());
            r.addBatch();
          }
          p.executeBatch();
          r.executeBatch();
        }
        c.commit();
      }
    }
    String input =
        "{\"previousUtf16Units\":"
            + previous.length()
            + ",\"previousUtf8Bytes\":"
            + previous.getBytes(StandardCharsets.UTF_8).length
            + ",\"currentUtf16Units\":"
            + current.length()
            + ",\"currentUtf8Bytes\":"
            + current.getBytes(StandardCharsets.UTF_8).length
            + ",\"commentCount\":"
            + original.size()
            + "}";
    long setupNanos = System.nanoTime() - setupStart;
    var states = new TreeMap<String, Integer>();
    try {
      for (int i = -warm; i < samples; i++) {
        boolean inserted = (i & 1) == 0;
        if (name.equals("service-changed"))
          Files.writeString(directory.resolve("code.txt"), inserted ? current : source);
        long a = allocated(), start = System.nanoTime();
        List<Anchor> moved = null;
        List<CommentView> views = null;
        if (service == null) moved = AnchorTracker.relocate(previous, current, original);
        else if (name.equals("service-get")) views = List.of(service.get(id));
        else views = service.list("code.txt", false);
        long nanos = System.nanoTime() - start, bytes = allocated();
        states.clear();
        if (moved != null) {
          if (moved.size() != original.size()) throw new AssertionError("评论数改变");
          for (int j = 0; j < moved.size(); j++) {
            var x = moved.get(j);
            states.merge(x.status().name(), 1, Integer::sum);
            checksum += x.startOffset() + x.endOffset() + x.text().hashCode();
            if ((name.equals("anchor-prefix")
                    || name.startsWith("anchor-single")
                    || name.equals("anchor-small-many"))
                && (x.status() != AnchorStatus.ACTIVE
                    || x.startOffset()
                        != original.get(j).startOffset() + current.length() - previous.length()))
              throw new AssertionError("前置插入定位错误");
            if ((x.status() == AnchorStatus.ACTIVE || x.status() == AnchorStatus.MODIFIED)
                && !current.substring(x.startOffset(), x.endOffset()).equals(x.text()))
              throw new AssertionError("原文不匹配");
          }
        } else {
          if (views.size() != (name.equals("service-get") ? 1 : COUNT))
            throw new AssertionError("评论数改变");
          for (var x : views) {
            states.merge(x.location().status().name(), 1, Integer::sum);
            checksum += x.location().selectedText().hashCode();
            if (x.location().status() != AnchorStatus.ACTIVE) throw new AssertionError("列表状态异常");
            if (x.location().startLine()
                != 2
                    + (x.id().startsWith("comment-")
                        ? Integer.parseInt(x.id().substring(8)) * 8
                        : 0)
                    + (name.equals("service-changed") && inserted ? 1 : 0))
              throw new AssertionError("列表行号异常");
          }
        }
        if (i >= 0)
          System.out.println(
              "{\"setupNanos\":"
                  + setupNanos
                  + ",\"input\":"
                  + input
                  + ",\"sample\":"
                  + i
                  + ",\"nanos\":"
                  + nanos
                  + ",\"allocatedBytes\":"
                  + (a < 0 ? -1 : bytes - a)
                  + ",\"checksum\":"
                  + checksum
                  + ",\"states\":\""
                  + states
                  + "\"}");
      }
    } finally {
      if (service != null) service.close();
      if (directory != null)
        try (var paths = Files.walk(directory)) {
          for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
  }
}
