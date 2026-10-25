package io.github.dreamlike.llmcomment.desktop;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

record DesktopOptions(
    Path workspace, Path database, String file, boolean help, boolean smoke, Path screenshot) {
  static DesktopOptions parse(String[] args) {
    Path workspace = null, database = null, screenshot = null;
    String file = null;
    boolean help = false, smoke = false;
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < args.length; i++) {
      String option = args[i];
      if (!seen.add(option)) throw new IllegalArgumentException("重复参数：" + option);
      if (option.equals("--help")) {
        help = true;
        continue;
      }
      if (option.equals("--smoke-test")) {
        smoke = true;
        continue;
      }
      if (!Set.of("--workspace", "--database", "--file", "--screenshot").contains(option))
        throw new IllegalArgumentException("未知参数：" + option);
      if (++i == args.length || args[i].startsWith("--") || args[i].isBlank())
        throw new IllegalArgumentException("参数缺少值：" + option);
      switch (option) {
        case "--workspace" -> workspace = Path.of(args[i]);
        case "--database" -> database = Path.of(args[i]);
        case "--file" -> file = args[i];
        case "--screenshot" -> screenshot = Path.of(args[i]);
      }
    }
    return new DesktopOptions(workspace, database, file, help, smoke, screenshot);
  }
}
