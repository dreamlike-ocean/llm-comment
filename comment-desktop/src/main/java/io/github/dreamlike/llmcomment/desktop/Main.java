package io.github.dreamlike.llmcomment.desktop;

import javafx.application.Application;
import javafx.application.Platform;

/** 单独的启动类也允许通过普通 classpath 或可执行 JAR 启动 JavaFX。 */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    try {
      if (args.length > 0 && args[0].equals("--ime-test")) {
        ImeCheck.main(java.util.Arrays.copyOfRange(args, 1, args.length));
        return;
      }
      DesktopOptions options = DesktopOptions.parse(args);
      if (options.help()) {
        System.out.println(
            "用法：llm-comment-desktop [--workspace PATH] [--database PATH] [--file FILE]\n"
                + "                      [--scale auto|150%|1.5] [--maximized]\n"
                + "省略工作区时向上查找 .llm-comments，找不到时使用当前目录。\n"
                + "界面默认自动适配屏幕，可在右上角调整缩放。\n"
                + "源码只读；评论保存在工作区数据库中。");
        return;
      }
      DesktopApplication.options = options;
      if (options.smoke()) {
        // JavaFX 会把 pulse/事件中的异常交给线程处理器，不能让冒烟测试静默通过。
        Thread.setDefaultUncaughtExceptionHandler(
            (thread, error) -> {
              DesktopApplication.exitCode = 1;
              error.printStackTrace(System.err);
              Platform.exit();
            });
      }
      Application.launch(DesktopApplication.class, args);
      if (DesktopApplication.exitCode != 0) System.exit(DesktopApplication.exitCode);
    } catch (Exception e) {
      System.err.println("桌面端启动失败：" + e.getMessage());
      if (DesktopApplication.options != null) e.printStackTrace(System.err);
      System.exit(2);
    }
  }
}
