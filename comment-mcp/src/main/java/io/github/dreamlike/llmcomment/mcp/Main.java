package io.github.dreamlike.llmcomment.mcp;

import io.github.dreamlike.llmcomment.core.CommentException;
import io.github.dreamlike.llmcomment.core.CommentService;
import io.github.dreamlike.llmcomment.core.Workspace;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class Main {
  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.in, System.out, System.err));
  }

  public static int run(
      String[] args, InputStream input, OutputStream output, OutputStream errors) {
    return run(args, input, output, errors, Path.of("."));
  }

  static int run(
      String[] args,
      InputStream input,
      OutputStream output,
      OutputStream errors,
      Path workingDirectory) {
    var err = new PrintWriter(new OutputStreamWriter(errors, StandardCharsets.UTF_8), true);
    Path workspace = null;
    Path database = null;
    Set<String> seen = new HashSet<>();
    try {
      for (int i = 0; i < args.length; i++) {
        String option = args[i];
        if ("--help".equals(option) || "--version".equals(option)) {
          var out = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
          out.println(
              "--version".equals(option)
                  ? "llm-comment-mcp 0.1.0-SNAPSHOT"
                  : "用法：llm-comment-mcp [--workspace PATH] [--database PATH]\n"
                        + "通过stdio提供MCP工具；省略工作区时向上寻找最近的.llm-comments目录，否则采用启动目录。\n"
                        + "--workspace相对启动目录；工具文件及--database相对工作区根。数据库默认位于.llm-comments/comments.db。");
          return 0;
        }
        if (!Set.of("--workspace", "--database").contains(option)
            || !seen.add(option)
            || ++i == args.length
            || args[i].startsWith("--")
            || args[i].isBlank()) {
          err.println("无效或重复参数：" + option);
          return 2;
        }
        if ("--workspace".equals(option)) workspace = Path.of(args[i]);
        else database = Path.of(args[i]);
      }
      try (var service =
          new CommentService(Workspace.resolve(workingDirectory, workspace, database))) {
        new McpServer(service).run(input, output);
      }
      return 0;
    } catch (CommentException exception) {
      err.println("MCP服务失败：" + exception.getMessage());
      return exception.code().startsWith("INVALID") ? 2 : 1;
    } catch (InvalidPathException exception) {
      err.println("无效路径参数：" + exception.getMessage());
      return 2;
    } catch (Exception exception) {
      err.println("MCP服务失败：" + exception.getMessage());
      return 1;
    }
  }
}
