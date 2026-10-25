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
                    // 简体：中文显示正常；繁體：臺灣、香港。
                    // 生僻字：𠮷 𠀀 龘；标点：「」『』，。！？
                    // 符号：→ ≤ ≥ ✓；组合：é；emoji：😀 🚀 🧑🏽‍💻 👩‍💻 ❤️
                    """
                        .replace("\n", "\r\n");
                Files.writeString(source, original);
                Files.writeString(workspace.resolve("README.md"), "# 行间\n本地源码评论示例\n");
                Files.createDirectories(workspace.resolve("src/main/java"));
                Files.writeString(workspace.resolve("src/main/java/示例.java"), original);
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
                      app.body.setText("这里需要处理空名字。\n简体繁體𠮷𠀀；é；😀 🚀 🧑🏽‍💻 👩‍💻 ❤️\n后续修改可查历史。");
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
                      app.body.setText(
                          "增加空值检查，并为中文名字补充测试。\n简体繁體𠮷𠀀；é；😀 🚀 🧑🏽‍💻 👩‍💻 ❤️\n这条修改生成了新的评论版本。");
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
                fx(
                    () -> {
                      String selected = app.source.selectedText();
                      double automatic = app.viewport.getZoom();
                      app.setScale(2);
                      app.stage.getScene().getRoot().applyCss();
                      app.stage.getScene().getRoot().layout();
                      var codeLabel = (javafx.scene.control.Label) app.source.lookup(".code-text");
                      require(codeLabel != null, "源码字形测试未找到可见行");
                      FileTreeCheck.verify();
                      require(
                          app.stage.getIcons().size() >= 3
                              && app.stage.getIcons().stream()
                                  .noneMatch(javafx.scene.image.Image::isError),
                          "应用图标加载失败");
                      FontRenderingCheck.verify(codeLabel.getFont(), app.body.getFont());
                      SourceLayoutCheck.verify(app.source);
                      require(
                          app.viewport.getZoom() == 2 && app.source.selectedText().equals(selected),
                          "缩放改变了源码选区");
                      require(
                          app.root.localToScene(1, 0).getX() - app.root.localToScene(0, 0).getX()
                              == 2,
                          "界面未整体缩放");
                      app.setScale(DesktopApplication.options.scale());
                      require(app.viewport.getZoom() == automatic, "未恢复指定缩放");
                      return null;
                    });
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
                verifyInteractions(app);
                if (screenshot != null) {
                  fx(
                      () -> {
                        app.stage.getScene().getRoot().applyCss();
                        app.stage.getScene().getRoot().layout();
                        require(
                            app.columns.getItems().getFirst().getLayoutBounds().getWidth() <= 401,
                            "目录栏随窗口过度扩张");
                        var visible = app.viewport.getViewportBounds();
                        var content = app.viewport.getContent().getLayoutBounds();
                        double zoom = app.viewport.getZoom();
                        if (visible.getWidth() >= 1050 * zoom
                            && visible.getHeight() >= 720 * zoom) {
                          require(
                              content.getWidth() <= visible.getWidth()
                                  && content.getHeight() <= visible.getHeight(),
                              "正常尺寸产生额外滚动范围");
                        }
                        System.out.printf(
                            "Desktop layout: %.0fx%.0f, scale=%.0f%%, sidebar=%.0f,"
                                + " content=%.0fx%.0f%n",
                            app.stage.getScene().getWidth(),
                            app.stage.getScene().getHeight(),
                            zoom * 100,
                            app.columns.getItems().getFirst().getLayoutBounds().getWidth(),
                            content.getWidth(),
                            content.getHeight());
                        PngSnapshot.write(app.stage.getScene().snapshot(null), screenshot);
                        return null;
                      });
                }
                require(DesktopApplication.exitCode == 0, "界面运行期间发生异常");
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

  private static void verifyInteractions(DesktopApplication app) throws Exception {
    fx(
        () -> {
          app.includeDeleted.fire();
          return null;
        });
    idle(app);
    int[][] ranges = {{5, 7}, {6, 9}, {5, 7}, {7, 7}, {9, 10}};
    String[] bodies = {
      "检查问候方法的输入边界", "交叉范围：中文、emoji 😀 与返回值", "同一范围的另一条建议", "包含范围：这一行需要测试", "边界共线：覆盖相邻代码"
    };
    java.util.List<CommentView> added = new java.util.ArrayList<>();
    for (int i = 0; i < ranges.length; i++) {
      final int index = i;
      fx(
          () -> {
            SourceInteractionCheck.clickLine(app.source, ranges[index][0], false);
            require(app.selected == null && app.body.getText().isEmpty(), "重新选代码未切到新增评论");
            SourceInteractionCheck.clickLine(app.source, ranges[index][1], true);
            require(
                app.source.startLine() == ranges[index][0]
                    && app.source.endLine() == ranges[index][1],
                "Shift 选择范围错误");
            require(
                app.tabs.getSelectionModel().getSelectedIndex() == 0
                    && app.draftTitle.getText().equals("新增评论"),
                "新增评论界面未显示");
            app.body.setText(bodies[index]);
            app.save.fire();
            return null;
          });
      idle(app);
      CommentView created = fx(() -> app.selected);
      added.add(created);
      require(fx(() -> app.current.comments().size()) == i + 1, "新选区覆盖了已有评论");
      for (int previous = 0; previous < added.size(); previous++) {
        var expected = added.get(previous);
        require(app.model.history(expected.id()).size() == 1, "新增交叉评论修改了其他评论历史");
      }
    }
    fx(
        () -> {
          require(app.source.commentsAtLine(7).size() == 4, "交叉、包含或同范围评论丢失");
          require(app.source.commentsAtLine(9).size() == 2, "共线边界没有同时包含两条评论");
          SourceInteractionCheck.verifyMarkers(app.source, app.current.comments());
          var menu = SourceInteractionCheck.clickMarker(app.source, 7);
          require(menu != null && menu.getItems().size() == 4, "行标记菜单没有列出全部评论");
          menu.getItems().getFirst().fire();
          menu.hide();
          return null;
        });
    idle(app);
    require(
        fx(() -> app.selected != null && app.draftTitle.getText().equals("编辑评论")), "标记选择评论误切到新增");
    fx(
        () -> {
          int line = app.selected.location().startLine();
          SourceInteractionCheck.clickLine(app.source, line, false);
          require(app.selected == null, "同范围重新点击未进入新增评论");
          app.body.setText("新评论草稿应保留");
          SourceInteractionCheck.clickLine(app.source, line + 1, true);
          require(app.body.getText().equals("新评论草稿应保留"), "延长选区丢失新增草稿");
          app.body.clear();
          app.comments.getSelectionModel().select(added.get(1));
          return null;
        });
    idle(app);
    fx(
        () -> {
          app.body.setText("已有评论的未保存修改");
          Platform.runLater(
              () -> {
                var dialog =
                    javafx.stage.Window.getWindows().stream()
                        .filter(
                            window ->
                                window.isShowing()
                                    && window.getScene() != null
                                    && window.getScene().getRoot()
                                        instanceof javafx.scene.control.DialogPane)
                        .map(
                            window -> (javafx.scene.control.DialogPane) window.getScene().getRoot())
                        .findFirst()
                        .orElseThrow();
                ((javafx.scene.control.Button)
                        dialog.lookupButton(javafx.scene.control.ButtonType.CANCEL))
                    .fire();
              });
          SourceInteractionCheck.clickLine(app.source, 2, false);
          require(
              app.selected.id().equals(added.get(1).id())
                  && app.body.getText().equals("已有评论的未保存修改"),
              "取消切换丢失已有评论草稿");
          require(app.source.startLine() == 6 && app.source.endLine() == 9, "取消切换未恢复评论范围");
          app.body.setText(app.selected.body());
          return null;
        });
    // 实际目录树逐层展开，等待每一层的后台读取完成。
    for (String path : new String[] {"src", "src/main", "src/main/java"}) {
      long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      boolean expanded = false;
      while (System.nanoTime() < deadline) {
        expanded =
            fx(
                () -> {
                  var queue =
                      new java.util.ArrayDeque<
                          javafx.scene.control.TreeItem<WorkspaceModel.Entry>>();
                  queue.add(app.files.getRoot());
                  while (!queue.isEmpty()) {
                    var item = queue.removeFirst();
                    if (item.getValue() != null && item.getValue().path().equals(path)) {
                      item.setExpanded(true);
                      return item.getChildren().stream()
                          .anyMatch(child -> child.getValue() != null);
                    }
                    queue.addAll(item.getChildren());
                  }
                  return false;
                });
        if (expanded) break;
        Thread.sleep(20);
      }
      require(expanded, "实际目录树没有展开：" + path);
    }
    System.out.println("交互检查通过：目录树、自动新增、Shift 范围、交叉/包含/同范围/共线评论、标记菜单及草稿保护");
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
