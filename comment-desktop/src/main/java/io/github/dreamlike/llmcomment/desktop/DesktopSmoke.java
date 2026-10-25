package io.github.dreamlike.llmcomment.desktop;

import io.github.dreamlike.llmcomment.core.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

/** 在临时工作区操作实际控件，供 JVM 和 Native Image 使用相同的界面集成检查。 */
final class DesktopSmoke {
  private DesktopSmoke() {}

  static void start(DesktopApplication app, Path screenshot) {
    Thread.ofPlatform()
        .name("desktop-smoke")
        .daemon()
        .start(
            () -> {
              Path temporary = null;
              try {
                temporary = Files.createTempDirectory("llm-comment-desktop-");
                Path workspace = temporary;
                Path source = workspace.resolve("评论示例.java");
                String original =
                    """
                    package example;

                    public class 问候 {
                        // 中文与 emoji 😀，位置按 UTF-16 计算
                        public String hello(String name) {
                            return "你好，" + name;
                        }
                    }
                    """
                        .replace("\n", "\r\n");
                Files.writeString(source, original);
                Files.writeString(workspace.resolve("README.md"), "# 行间\n本地源码评论示例\n");
                Files.createDirectories(workspace.resolve("src"));
                fx(
                    () -> {
                      app.openWorkspace(Workspace.resolve(workspace, workspace, null), null);
                      return null;
                    });
                idle(app);
                fx(
                    () -> {
                      app.openFile(source.getFileName().toString());
                      return null;
                    });
                idle(app);
                require(
                    fx(() -> app.current != null && app.current.snapshot().text().equals(original)),
                    "源码快照读取失败");
                fx(
                    () -> {
                      app.source.highlight(4, 6);
                      app.body.setText("这里需要处理空名字。\n保留中文、emoji 😀 和多行范围，后续修改可查历史。");
                      app.author.setText("reviewer");
                      require(!app.save.isDisabled(), "创建按钮未启用");
                      app.save.fire();
                      return null;
                    });
                idle(app);
                CommentView created = fx(() -> app.current.comments().getFirst());
                require(
                    created.location().startLine() == 4 && created.location().endLine() == 6,
                    "创建范围错误");
                require(Files.readString(source).equals(original), "界面操作改变源码");
                Path other = workspace.resolve("另一个文件.java");
                Files.writeString(other, original);
                fx(
                    () -> {
                      app.openFile(other.getFileName().toString());
                      return null;
                    });
                idle(app);
                require(
                    fx(() -> app.source.startLine() == 0 && app.selected == null), "新文件继承了旧评论的选区");
                fx(
                    () -> {
                      app.openFile(source.getFileName().toString());
                      return null;
                    });
                idle(app);
                fx(
                    () -> {
                      app.comments.getSelectionModel().selectFirst();
                      return null;
                    });
                idle(app);
                String moved = "// 外部编辑器插入的说明\r\n" + original;
                Files.writeString(source, moved);
                fx(
                    () -> {
                      app.refresh.fire();
                      return null;
                    });
                idle(app);
                require(
                    fx(() -> app.current.comments().getFirst().location().startLine() == 5),
                    "外部编辑后位置未平移");
                fx(
                    () -> {
                      app.author.setText("另一位审阅者");
                      require(app.dirty && !app.save.isDisabled(), "仅修改作者时无法保存");
                      app.body.setText("增加空值检查，并为中文名字补充测试。\n这条修改生成了新的评论版本。");
                      app.save.fire();
                      return null;
                    });
                idle(app);
                require(fx(() -> app.revisions.getItems().size() == 2), "修改历史节点数错误");
                require(
                    !fx(() -> app.selected.currentRevisionId()).equals(created.currentRevisionId()),
                    "正文版本未改变");
                require(fx(() -> app.selected.author().equals("另一位审阅者")), "新版本没有保存作者");
                fx(
                    () -> {
                      app.tabs.getSelectionModel().select(1);
                      app.revisions.getSelectionModel().selectLast();
                      return null;
                    });
                idle(app);
                require(
                    fx(
                        () ->
                            app.historical
                                && app.historyBody.getText().equals(created.body())
                                && app.source
                                    .selectedText()
                                    .equals(created.location().selectedText())),
                    "历史正文或源码展示错误");
                fx(
                    () -> {
                      app.currentFile.fire();
                      app.tabs.getSelectionModel().select(0);
                      return null;
                    });
                if (screenshot != null) {
                  fx(
                      () -> {
                        app.root.applyCss();
                        app.root.layout();
                        PngSnapshot.write(app.root.snapshot(null, null), screenshot);
                        return null;
                      });
                }
                // 新评论使用可见快照的版本；外部改动后保存应拒绝并保留草稿。
                fx(
                    () -> {
                      app.newComment.fire();
                      app.source.selectLine(2);
                      app.body.setText("源码变化后应保留的草稿");
                      return null;
                    });
                Files.writeString(source, "// 再次插入\r\n" + moved);
                fx(
                    () -> {
                      app.save.fire();
                      return null;
                    });
                idle(app);
                require(fx(() -> app.status.getText().contains("源码已经变化")), "旧快照创建未报告冲突");
                require(fx(() -> app.body.getText().equals("源码变化后应保留的草稿")), "冲突丢失草稿");
                require(
                    app.model.load(source.getFileName().toString(), false).comments().size() == 1,
                    "冲突创建了评论");
                fx(
                    () -> {
                      app.body.clear();
                      app.comments.getSelectionModel().selectFirst();
                      return null;
                    });
                idle(app);
                fx(
                    () -> {
                      app.deleteComment(true);
                      return null;
                    });
                idle(app);
                require(fx(() -> app.current.comments().isEmpty()), "删除后仍显示评论");
                fx(
                    () -> {
                      app.includeDeleted.fire();
                      return null;
                    });
                idle(app);
                require(
                    fx(
                        () ->
                            app.current.comments().size() == 1
                                && app.current.comments().getFirst().deleted()),
                    "删除历史丢失");
                require(app.model.history(created.id()).size() == 3, "删除未追加历史");
                require(Files.readString(source).equals("// 再次插入\r\n" + moved), "评论操作改变源码");
                System.out.println(
                    "Desktop smoke passed: workspace, Chinese/CRLF/emoji, range, relocation,"
                        + " history, conflict, deletion.");
              } catch (Throwable error) {
                DesktopApplication.exitCode = 1;
                error.printStackTrace(System.err);
              } finally {
                Platform.runLater(Platform::exit);
                if (temporary != null) {
                  try (var paths = Files.walk(temporary)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                      Files.deleteIfExists(path);
                  } catch (Exception ignored) {
                  }
                }
              }
            });
  }

  private static void idle(DesktopApplication app) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < end) {
      if (fx(() -> !app.busy.get())) {
        // 等待当前 UI 回调及其后续读取历史任务完成。
        if (fx(() -> !app.busy.get())) return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("桌面操作超时：" + fx(() -> app.status.getText()));
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    FutureTask<T> task = new FutureTask<>(action);
    Platform.runLater(task);
    return task.get(30, TimeUnit.SECONDS);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
