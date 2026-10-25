package io.github.dreamlike.llmcomment.desktop;

import io.github.dreamlike.llmcomment.core.AnchorStatus;
import io.github.dreamlike.llmcomment.core.CommentView;
import io.github.dreamlike.llmcomment.core.Location;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Stage;

/** 独立 GUI 性能探针；数据构造不计时，不访问工作区、数据库或系统按键。 */
public final class SourcePerformanceProbe {
  private SourcePerformanceProbe() {}

  public static void main(String[] args) throws Exception {
    int count = args.length == 0 ? 100_000 : Integer.parseInt(args[0]);
    if (count < 10) throw new IllegalArgumentException("样本至少需要 10 行");
    // 同一字符样本用于优化前后；包含空行、中文、组合字符、emoji 和 CRLF。
    StringBuilder input = new StringBuilder(count * 40);
    String[] lines = {"", "中文繁體，e\u0301，𠮷，👩🏽‍💻", "public int value = 12345; // code"};
    for (int i = 0; i < count - 1; i++) input.append(lines[i % lines.length]).append("\r\n");
    String text = input.toString();
    boolean dense = args.length > 1 && args[1].equals("dense");
    String body = "评论正文\n".repeat(40_000);
    var comments = new ArrayList<CommentView>();
    for (int i = 0; i < (dense ? 32 : 0); i++) {
      int start = i < 8 ? 1 : 1 + (i - 8) * count / 48;
      int end = i < 8 ? count : Math.min(count, start + count / 2);
      comments.add(
          new CommentView(
              "c" + i,
              "样本.java",
              "r" + i,
              body,
              "作者",
              "",
              "",
              false,
              new Location("v", 0, text.length(), start, end, AnchorStatus.ACTIVE, "")));
    }
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          error.printStackTrace();
          System.exit(1);
        });
    Platform.startup(() -> {});
    Platform.runLater(
        () -> {
          try {
            DesktopFonts.initialize();
            run(count, text, comments);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
  }

  private static void run(int count, String text, List<CommentView> comments) {
    var source = new SourceView();
    var stage = new Stage();
    var scene = new Scene(source, 1280, 900);
    scene.getStylesheets().add(SourceView.class.getResource("material.css").toExternalForm());
    stage.setScene(scene);
    stage.show();
    long began = System.nanoTime();
    source.show(text, comments);
    layout(source);
    double initialize = millis(System.nanoTime() - began);
    @SuppressWarnings("unchecked")
    var list = (ListView<SourceLines.Line>) source.getCenter();
    if (list.getItems().size() != count || list.getFixedCellSize() > 0) {
      throw new IllegalStateException("行数或动态行高错误");
    }
    var jumps = new ArrayList<Double>();
    int[] targets = {count - 1, count / 2, 0, count * 3 / 4, count / 4, count - 2, 1, count / 3};
    // 逐次 pulse 发出跳转，避免单个 FX 任务合并所有请求。
    new AnimationTimer() {
      private int step = -20;
      private long previous;
      private final List<Double> frames = new ArrayList<>();
      private final List<Double> scrolls = new ArrayList<>();
      private int initialVisible;

      @Override
      public void handle(long now) {
        if (step >= targets.length && previous != 0) frames.add(millis(now - previous));
        previous = now;
        if (step < 0) {
          step++;
          return;
        }
        if (step < targets.length) {
          long started = System.nanoTime();
          list.scrollTo(targets[step]);
          layout(source);
          jumps.add(millis(System.nanoTime() - started));
          requireVisible(list, targets[step]);
        } else if (step < targets.length + 180) {
          if (step == targets.length) initialVisible = firstVisible(list);
          long started = System.nanoTime();
          list.lookup(".virtual-flow")
              .fireEvent(
                  new ScrollEvent(
                      ScrollEvent.SCROLL,
                      400,
                      400,
                      400,
                      400,
                      false,
                      false,
                      false,
                      false,
                      false,
                      false,
                      0,
                      -64,
                      0,
                      -64,
                      ScrollEvent.HorizontalTextScrollUnits.NONE,
                      0,
                      ScrollEvent.VerticalTextScrollUnits.NONE,
                      0,
                      0,
                      null));
          layout(source);
          scrolls.add(millis(System.nanoTime() - started));
        } else {
          stop();
          if (firstVisible(list) <= initialVisible + 100)
            throw new IllegalStateException("连续滚动未实际移动至少 100 行");
          // 确认末尾空行和混排选区没有被显示优化改变。
          source.highlight(1, 3);
          int end = -2;
          for (int line = 0; line < 3; line++) end = text.indexOf("\r\n", end + 2);
          if (!source.selectedText().equals(text.substring(0, end)))
            throw new IllegalStateException("性能检查改变了选区原文");
          layout(source);
          if (!comments.isEmpty()) {
            var cell =
                list.lookupAll(".source-line").stream()
                    .filter(
                        node -> node instanceof ListCell<?> candidate && candidate.getIndex() == 0)
                    .map(node -> (ListCell<?>) node)
                    .findFirst()
                    .orElseThrow();
            var marker = (Label) cell.lookup(".line-marker");
            var tooltip = marker.getTooltip();
            tooltip.show(marker, 20, 20);
            if (tooltip.getText().lines().count() != source.commentsAtLine(1).size()
                || !tooltip.getText().contains("作者")
                || tooltip.getText().length() > 2000)
              throw new IllegalStateException("悬停说明未保留所有评论及摘要");
            tooltip.hide();
          }
          System.gc();
          long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
          System.out.printf(
              java.util.Locale.ROOT,
              "lines=%d init_ms=%.3f jump_p95_ms=%.3f jump_max_ms=%.3f scroll_p95_ms=%.3f"
                  + " scroll_max_ms=%.3f frame_p95_ms=%.3f frame_max_ms=%.3f heap_mib=%.1f"
                  + " cells=%d%n",
              count,
              initialize,
              percentile(jumps, .95),
              percentile(jumps, 1),
              percentile(scrolls, .95),
              percentile(scrolls, 1),
              percentile(frames, .95),
              percentile(frames, 1),
              used / 1048576.0,
              list.lookupAll(".source-line").size());
          stage.close();
          Platform.exit();
          return;
        }
        step++;
      }
    }.start();
  }

  private static void layout(SourceView source) {
    source.applyCss();
    source.layout();
    ((ListView<?>) source.getCenter()).layout();
  }

  private static int firstVisible(ListView<?> list) {
    return list.lookupAll(".source-line").stream()
        .filter(node -> node instanceof ListCell<?> cell && !cell.isEmpty())
        .mapToInt(node -> ((ListCell<?>) node).getIndex())
        .min()
        .orElseThrow();
  }

  private static void requireVisible(ListView<?> list, int index) {
    if (list.lookupAll(".source-line").stream()
        .noneMatch(
            node ->
                node instanceof ListCell<?> cell && !cell.isEmpty() && cell.getIndex() == index))
      throw new IllegalStateException("跳转未呈现目标行：" + index);
  }

  private static double percentile(List<Double> data, double fraction) {
    double[] values = data.stream().mapToDouble(Double::doubleValue).toArray();
    Arrays.sort(values);
    return values[Math.min(values.length - 1, (int) Math.ceil(values.length * fraction) - 1)];
  }

  private static double millis(long nanos) {
    return nanos / 1_000_000.0;
  }
}
