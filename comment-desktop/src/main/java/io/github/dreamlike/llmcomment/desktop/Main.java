package io.github.dreamlike.llmcomment.desktop;

import javafx.application.Application;

/** 单独的启动类也允许通过普通 classpath 或可执行 JAR 启动 JavaFX。 */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    try {
      DesktopOptions options = DesktopOptions.parse(args);
      if (options.help()) {
        System.out.println(
            "用法：llm-comment-desktop [--workspace PATH] [--database PATH] [--file FILE]\n"
                + "省略工作区时向上查找 .llm-comments，找不到时使用当前目录。\n"
                + "源码只读；评论保存在工作区数据库中。");
        return;
      }
      DesktopApplication.options = options;
      Application.launch(DesktopApplication.class, args);
      if (DesktopApplication.exitCode != 0) System.exit(DesktopApplication.exitCode);
    } catch (Exception e) {
      System.err.println("桌面端启动失败：" + e.getMessage());
      System.exit(2);
    }
  }
}
