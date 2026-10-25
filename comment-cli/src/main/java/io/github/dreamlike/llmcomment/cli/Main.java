package io.github.dreamlike.llmcomment.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dreamlike.llmcomment.core.CommentException;
import io.github.dreamlike.llmcomment.core.CommentService;
import io.github.dreamlike.llmcomment.core.Views;
import io.github.dreamlike.llmcomment.core.Workspace;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 无头命令行入口；结果写标准输出，错误写标准错误。 */
public final class Main {
  private static final ObjectMapper JSON = new ObjectMapper();

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.in, System.out, System.err));
  }

  public static int run(String[] args, InputStream input, PrintStream out, PrintStream err) {
    return run(args, Path.of("."), input, out, err);
  }

  static int run(
      String[] args, Path workingDirectory, InputStream input, PrintStream out, PrintStream err) {
    try {
      Arguments a = parse(args);
      if (a.command.equals("help")) {
        write(
            out,
            Map.of(
                "usage",
                "llm-comment [--workspace PATH] [--database PATH] COMMAND",
                "commands",
                List.of(
                    usage("create"),
                    "get ID",
                    usage("list"),
                    "update ID --base-revision ID (--body TEXT | --body-file PATH|-) [--author"
                        + " TEXT]",
                    "delete ID --base-revision ID [--author TEXT]",
                    "history ID",
                    "snapshot ID",
                    "workspace"),
                "paths",
                "所有命令均可省略 --workspace：向上查找最近的 .llm-comments 目录，找不到时使用当前目录。"
                    + "所有相对文件路径以工作区为基准；workspace 命令显示实际路径。",
                "database",
                "默认 .llm-comments/comments.db；--database PATH 覆盖本次调用的数据库路径。"));
        return 0;
      }
      if (a.command.equals("version")) {
        write(out, Map.of("version", "0.1.0-SNAPSHOT"));
        return 0;
      }
      Workspace workspace =
          Workspace.resolve(
              workingDirectory,
              pathOption(a.options, "--workspace"),
              pathOption(a.options, "--database"));
      if (a.command.equals("workspace")) {
        write(
            out,
            Map.of(
                "workspace", workspace.root().toString(),
                "database", workspace.database().toString(),
                "databaseExists", Files.isRegularFile(workspace.database())));
        return 0;
      }
      String file = sourceFile(a, workspace);
      String body = a.options.get("--body");
      if (a.options.containsKey("--body-file")) {
        String path = a.options.get("--body-file");
        body =
            path.equals("-")
                ? StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(input.readAllBytes()))
                    .toString()
                : Files.readString(workspace.resolvePath(path), StandardCharsets.UTF_8);
      }
      try (CommentService service = new CommentService(workspace)) {
        String author = a.options.getOrDefault("--author", "anonymous");
        Object result =
            switch (a.command) {
              case "create" -> {
                String[] lines = a.options.get("--lines").split(":", -1);
                int start = line(lines[0]);
                int end = lines.length == 1 ? start : line(lines[1]);
                if (end < start) throw invalid("结束行不能小于起始行");
                yield Views.toMap(service.create(file, start, end, body, author));
              }
              case "get" -> Views.toMap(service.get(a.argument));
              case "list" ->
                  service.list(file, a.options.containsKey("--include-deleted")).stream()
                      .map(Views::toMap)
                      .toList();
              case "update" ->
                  Views.toMap(
                      service.update(a.argument, a.options.get("--base-revision"), body, author));
              case "delete" ->
                  Views.toMap(service.delete(a.argument, a.options.get("--base-revision"), author));
              case "history" -> service.history(a.argument).stream().map(Views::toMap).toList();
              case "snapshot" -> Views.toMap(service.snapshot(a.argument));
              default -> throw invalid("未知命令");
            };
        write(out, result);
      }
      return 0;
    } catch (CommentException e) {
      error(err, e.code(), e.getMessage(), e.details());
      String code = e.code().toUpperCase(Locale.ROOT);
      if (code.contains("NOT_FOUND")) return 3;
      if (code.contains("CONFLICT") || code.equals("DELETED")) return 4;
      if (code.contains("INVALID") || code.contains("ARGUMENT") || code.contains("RANGE")) return 2;
      return 1;
    } catch (IllegalArgumentException e) {
      error(err, "INVALID_ARGUMENT", e.getMessage(), Map.of());
      return 2;
    } catch (Exception e) {
      error(
          err,
          "IO_ERROR",
          Objects.toString(e.getMessage(), e.getClass().getSimpleName()),
          Map.of());
      return 1;
    }
  }

  private static int line(String value) {
    try {
      int n = Integer.parseInt(value);
      if (n > 0) return n;
    } catch (NumberFormatException ignored) {
    }
    throw invalid("行号必须是正整数");
  }

  private static void write(PrintStream out, Object value) throws IOException {
    out.println(JSON.writeValueAsString(value));
  }

  private static void error(
      PrintStream err, String code, String message, Map<String, Object> details) {
    try {
      write(
          err, Map.of("code", code, "message", Objects.toString(message, ""), "details", details));
    } catch (IOException ignored) {
      err.println("{\"code\":\"SERIALIZATION_ERROR\",\"message\":\"错误序列化失败\",\"details\":{}}");
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static Path pathOption(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null) return null;
    if (value.isBlank()) throw invalid("路径不能为空：" + name);
    return Path.of(value);
  }

  private static String sourceFile(Arguments a, Workspace workspace) {
    if (!a.command.equals("create") && !a.command.equals("list")) return null;
    String positional = a.argument == null ? null : workspace.sourcePath(a.argument);
    String option = a.options.get("--file");
    if (option != null) option = workspace.sourcePath(option);
    if (positional != null && option != null && !positional.equals(option))
      throw invalid("FILE 与 --file 指向不同文件，请只指定一个文件");
    return positional == null ? option : positional;
  }

  private static String usage(String command) {
    return switch (command) {
      case "create" ->
          "create FILE --lines N[:M] (--body TEXT | --body-file PATH|-) [--author TEXT]"
              + "（FILE 也可写成 --file FILE）";
      case "list" -> "list [FILE | --file FILE] [--include-deleted]";
      case "update" -> "update ID --base-revision ID (--body TEXT | --body-file PATH|-)";
      case "delete" -> "delete ID --base-revision ID";
      case "get", "history", "snapshot" -> command + " ID";
      default -> command;
    };
  }

  private record Arguments(String command, String argument, Map<String, String> options) {}

  private static Arguments parse(String[] args) {
    Map<String, String> options = new HashMap<>();
    List<String> positional = new ArrayList<>();
    Set<String> valued =
        Set.of(
            "--workspace",
            "--database",
            "--lines",
            "--body",
            "--body-file",
            "--author",
            "--file",
            "--base-revision");
    boolean literal = false;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (!literal && arg.equals("--")) {
        literal = true;
        continue;
      }
      if (!literal && arg.startsWith("--")) {
        if (arg.equals("--help") || arg.equals("--version")) {
          positional.add(arg.substring(2));
          continue;
        }
        if (options.containsKey(arg)) throw invalid("重复参数：" + arg);
        if (arg.equals("--include-deleted")) {
          options.put(arg, "true");
          continue;
        }
        if (!valued.contains(arg)) throw invalid("未知参数：" + arg);
        if (++i == args.length || args[i].startsWith("--")) throw invalid("参数缺少值：" + arg);
        options.put(arg, args[i]);
      } else positional.add(arg);
    }
    if (positional.isEmpty()) throw invalid("缺少命令，请使用 --help");
    String command = positional.getFirst();
    Set<String> allowed = new HashSet<>(Set.of("--workspace", "--database"));
    Set<String> required = new HashSet<>();
    int minCount = 2, maxCount = 2;
    switch (command) {
      case "help", "version", "workspace" -> {
        minCount = maxCount = 1;
      }
      case "create" -> {
        minCount = 1;
        allowed.addAll(Set.of("--file", "--lines", "--body", "--body-file", "--author"));
        required.add("--lines");
      }
      case "update" -> {
        allowed.addAll(Set.of("--base-revision", "--body", "--body-file", "--author"));
        required.add("--base-revision");
      }
      case "delete" -> {
        allowed.addAll(Set.of("--base-revision", "--author"));
        required.add("--base-revision");
      }
      case "list" -> {
        minCount = 1;
        allowed.addAll(Set.of("--file", "--include-deleted"));
      }
      case "get", "history", "snapshot" -> {}
      default -> throw invalid("未知命令：" + command);
    }
    if (positional.size() < minCount || positional.size() > maxCount)
      throw invalid("参数数量错误，用法：" + usage(command));
    for (String key : options.keySet())
      if (!allowed.contains(key)) throw invalid("当前命令不接受参数：" + key);
    for (String key : required) if (!options.containsKey(key)) throw invalid("缺少参数：" + key);
    if (command.equals("create") || command.equals("update")) {
      if (options.containsKey("--body") == options.containsKey("--body-file"))
        throw invalid("必须且只能指定 --body 或 --body-file");
    }
    if (command.equals("create") && positional.size() == 1 && !options.containsKey("--file"))
      throw invalid("缺少文件，用法：" + usage(command));
    if (options.containsKey("--lines") && !options.get("--lines").matches("[0-9]+(:[0-9]+)?"))
      throw invalid("行范围格式必须为 N 或 N:N");
    return new Arguments(command, positional.size() == 2 ? positional.get(1) : null, options);
  }
}
