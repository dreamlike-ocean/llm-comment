package io.github.dreamlike.llmcomment.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 只读解析工作区及路径；解析过程不会创建目录或打开数据库。 */
public final class Workspace {
  private final Path root;
  private final Path database;

  private Workspace(Path root, Path database) {
    this.root = root;
    this.database = database;
  }

  public Path root() {
    return root;
  }

  public Path database() {
    return database;
  }

  /** 显式根相对启动目录；省略时寻找最近包含 .llm-comments 目录的祖先。 */
  public static Workspace resolve(
      Path workingDirectory, Path workspaceOverride, Path databaseOverride) {
    Path cwd = directory(workingDirectory);
    Path root = cwd;
    if (workspaceOverride != null) {
      root = directory(cwd.resolve(workspaceOverride));
    } else {
      for (Path candidate = cwd; candidate != null; candidate = candidate.getParent()) {
        if (Files.isDirectory(candidate.resolve(".llm-comments"))) {
          root = candidate;
          break;
        }
      }
    }
    Path database =
        databaseOverride == null ? Path.of(".llm-comments/comments.db") : databaseOverride;
    return new Workspace(root, root.resolve(database).normalize());
  }

  static Path directory(Path path) {
    try {
      Path real = path.toRealPath();
      if (!Files.isDirectory(real)) throw new CommentException("INVALID_PATH", "工作区必须是目录：" + path);
      return real;
    } catch (IOException e) {
      throw new CommentException("FILE_READ_ERROR", "无法读取工作区：" + path, e);
    }
  }

  /** 相对路径始终基于工作区；此方法不访问目标文件。 */
  public Path resolvePath(String name) {
    if (name == null || name.isBlank()) throw new CommentException("INVALID_PATH", "文件路径不能为空");
    return root.resolve(Path.of(name)).normalize();
  }

  /** 返回工作区相对路径；实际符号链接及数据库保护由 CommentService 校验。 */
  public String sourcePath(String name) {
    Path path = resolvePath(name);
    if (!path.startsWith(root) || path.equals(root))
      throw new CommentException("INVALID_PATH", "文件超出工作区");
    return root.relativize(path).toString().replace('\\', '/');
  }
}
