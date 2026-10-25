package io.github.dreamlike.llmcomment.desktop;

import io.github.dreamlike.llmcomment.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 不依赖界面线程的工作区操作；源码读取和版本校验统一交给 core。 */
public final class WorkspaceModel implements AutoCloseable {
  private static final Set<String> EXCLUDED = Set.of(".git", ".idea", "target", ".llm-comments");
  private final Workspace workspace;
  private final CommentService service;
  private final Path database;

  public WorkspaceModel(Workspace workspace) {
    this.workspace = Objects.requireNonNull(workspace);
    this.service = new CommentService(workspace);
    try {
      this.database = workspace.database().toRealPath();
    } catch (IOException e) {
      throw new CommentException("FILE_READ_ERROR", "无法读取数据库路径", e);
    }
  }

  public Workspace workspace() {
    return workspace;
  }

  public record Entry(String path, String name, boolean directory) {}

  /** 只枚举一层，目录符号链接不进入树；文件链接仍遵循 core 的真实路径约束。 */
  public List<Entry> children(String directory) {
    Path root = workspace.root();
    Path path = directory.isEmpty() ? root : workspace.resolvePath(directory);
    try {
      if (!path.startsWith(root)
          || hidden(path)
          || hasDirectoryLink(path)
          || !path.toRealPath().startsWith(root)
          || !Files.isDirectory(path)) throw new CommentException("INVALID_PATH", "目录不在可浏览工作区中");
      List<Entry> result = new ArrayList<>();
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
        for (Path entry : entries) {
          if (hidden(entry)) continue;
          Path real;
          try {
            real = entry.toRealPath();
          } catch (NoSuchFileException e) {
            continue;
          }
          if (!real.startsWith(root) || hidden(real)) continue;
          boolean isDirectory = Files.isDirectory(entry);
          if (isDirectory && Files.isSymbolicLink(entry)) continue;
          if (!isDirectory && !Files.isRegularFile(entry)) continue;
          result.add(
              new Entry(
                  root.relativize(entry).toString().replace('\\', '/'),
                  entry.getFileName().toString(),
                  isDirectory));
        }
      }
      result.sort(Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name));
      return List.copyOf(result);
    } catch (IOException e) {
      throw new CommentException("FILE_READ_ERROR", "无法读取工作区目录", e);
    }
  }

  private boolean hasDirectoryLink(Path path) {
    for (Path part = path; !part.equals(workspace.root()); part = part.getParent()) {
      if (Files.isSymbolicLink(part)) return true;
    }
    return false;
  }

  private boolean hidden(Path path) {
    for (Path part : workspace.root().relativize(path)) {
      if (EXCLUDED.contains(part.toString())) return true;
    }
    return path.equals(database)
        || path.equals(Path.of(database + "-wal"))
        || path.equals(Path.of(database + "-shm"))
        || path.equals(Path.of(database + "-journal"));
  }

  public FileComments load(String file, boolean includeDeleted) {
    return service.inspect(file, includeDeleted);
  }

  public CommentView create(
      String file, int start, int end, String body, String author, String version) {
    return service.create(file, start, end, body, author, version);
  }

  public CommentView update(CommentView original, String body, String author) {
    return service.update(original.id(), original.currentRevisionId(), body, author);
  }

  public CommentView delete(CommentView original, String author) {
    return service.delete(original.id(), original.currentRevisionId(), author);
  }

  public List<RevisionView> history(String id) {
    return service.history(id);
  }

  public SnapshotView snapshot(String version) {
    return service.snapshot(version);
  }

  @Override
  public void close() {
    service.close();
  }
}
