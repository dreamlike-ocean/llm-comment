package io.github.dreamlike.llmcomment.desktop;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import javafx.application.Application;
import javafx.application.Platform;

/** 独立诊断入口；不解析用户工作区，也不会进入发布程序。 */
public final class DesktopCheckMain {
  static volatile int exitCode;
  static Path screenshot;

  private DesktopCheckMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("--ime-test")) {
      ImeCheck.main(Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    if (args.length > 0 && args[0].equals("--scroll")) {
      SourcePerformanceProbe.main(Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    var displayOptions = new ArrayList<String>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--smoke-test" -> {}
        case "--screenshot" -> {
          if (++i == args.length) throw new IllegalArgumentException("--screenshot 缺少路径");
          screenshot = Path.of(args[i]);
        }
        case "--scale" -> {
          displayOptions.add(args[i]);
          if (++i == args.length) throw new IllegalArgumentException("--scale 缺少值");
          displayOptions.add(args[i]);
        }
        case "--maximized" -> displayOptions.add(args[i]);
        default -> throw new IllegalArgumentException("未知检查参数：" + args[i]);
      }
    }
    DesktopApplication.options = DesktopOptions.parse(displayOptions.toArray(String[]::new));
    Thread.setDefaultUncaughtExceptionHandler(
        (thread, error) -> {
          exitCode = 1;
          error.printStackTrace(System.err);
          Platform.exit();
        });
    Application.launch(DesktopCheckApplication.class, args);
    if (exitCode != 0) System.exit(exitCode);
  }
}
