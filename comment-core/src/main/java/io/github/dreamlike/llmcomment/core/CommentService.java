package io.github.dreamlike.llmcomment.core;

import io.github.dreamlike.llmcomment.core.anchor.*;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 独立连接及 IMMEDIATE 事务保证跨线程、跨进程的版本更新串行化。 */
public final class CommentService implements AutoCloseable {
  private final Path workspace, database, defaultDatabaseDirectory;

  public CommentService(Path workspace) {
    this(workspace, Path.of(".llm-comments/comments.db"));
  }

  public CommentService(Path workspace, Path database) {
    try {
      this.workspace = workspace.toRealPath();
      Path absolute =
          (database.isAbsolute() ? database : this.workspace.resolve(database)).normalize();
      Files.createDirectories(absolute.getParent());
      this.database =
          Files.exists(absolute)
              ? absolute.toRealPath()
              : absolute.getParent().toRealPath().resolve(absolute.getFileName());
      Path defaultDirectory = this.workspace.resolve(".llm-comments");
      this.defaultDatabaseDirectory =
          Files.exists(defaultDirectory) ? defaultDirectory.toRealPath() : defaultDirectory;
      try (Connection c = connection();
          Statement s = c.createStatement()) {
        s.execute("PRAGMA journal_mode=WAL");
        s.execute(
            "CREATE TABLE IF NOT EXISTS snapshots(id TEXT PRIMARY KEY,file TEXT NOT NULL,hash TEXT"
                + " NOT NULL,text TEXT NOT NULL,created TEXT NOT NULL,UNIQUE(file,hash))");
        s.execute(
            "CREATE TABLE IF NOT EXISTS files(file TEXT PRIMARY KEY,snapshot TEXT NOT NULL"
                + " REFERENCES snapshots(id),missing INTEGER NOT NULL DEFAULT 0)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS comments(id TEXT PRIMARY KEY,file TEXT NOT NULL REFERENCES"
                + " files(file),revision TEXT,body TEXT NOT NULL,author TEXT NOT NULL,created TEXT"
                + " NOT NULL,updated TEXT NOT NULL,deleted INTEGER NOT NULL,start INTEGER NOT"
                + " NULL,end INTEGER NOT NULL,status TEXT NOT NULL,selected TEXT NOT"
                + " NULL,before_text TEXT NOT NULL,after_text TEXT NOT NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS revisions(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT"
                + " UNIQUE NOT NULL,comment_id TEXT NOT NULL REFERENCES comments(id),parent"
                + " TEXT,action TEXT NOT NULL,body TEXT NOT NULL,author TEXT NOT NULL,created TEXT"
                + " NOT NULL,snapshot TEXT NOT NULL REFERENCES snapshots(id),start INTEGER,end"
                + " INTEGER,start_line INTEGER,end_line INTEGER,status TEXT NOT NULL,selected TEXT"
                + " NOT NULL)");
        s.execute("CREATE INDEX IF NOT EXISTS comments_file_order ON comments(file,created,id)");
        s.execute("CREATE INDEX IF NOT EXISTS revisions_comment_seq ON revisions(comment_id,seq)");
      }
    } catch (Exception e) {
      throw failure(e);
    }
  }

  private Connection connection() throws SQLException {
    Connection c = DriverManager.getConnection("jdbc:sqlite:" + database);
    try (Statement s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys=ON");
      s.execute("PRAGMA busy_timeout=10000");
    } catch (SQLException e) {
      try {
        c.close();
      } catch (SQLException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
    return c;
  }

  @FunctionalInterface
  private interface Work<T> {
    T run(Connection c) throws Exception;
  }

  private <T> T transaction(Work<T> work) {
    return transaction(work, true);
  }

  private <T> T transaction(Work<T> work, boolean write) {
    try (Connection c = connection();
        Statement s = c.createStatement()) {
      s.execute(write ? "BEGIN IMMEDIATE" : "BEGIN");
      try {
        T result = work.run(c);
        s.execute("COMMIT");
        return result;
      } catch (Exception e) {
        try {
          s.execute("ROLLBACK");
        } catch (SQLException ignored) {
        }
        throw e;
      }
    } catch (Exception e) {
      throw failure(e);
    }
  }

  private static CommentException failure(Exception e) {
    if (e instanceof CommentException ce) return ce;
    return new CommentException(
        e instanceof IOException
            ? "FILE_READ_ERROR"
            : e instanceof IllegalArgumentException ? "INVALID_ARGUMENT" : "STORAGE_ERROR",
        e.getMessage() == null ? "操作失败" : e.getMessage(),
        e);
  }

  private String file(String name) throws IOException {
    if (name == null || name.isBlank()) throw new CommentException("INVALID_PATH", "文件路径不能为空");
    Path supplied = Path.of(name);
    if (supplied.isAbsolute()) throw new CommentException("INVALID_PATH", "必须使用工作区相对路径");
    Path path = workspace.resolve(supplied).normalize();
    if (!path.startsWith(workspace) || path.equals(workspace))
      throw new CommentException("INVALID_PATH", "文件超出工作区");
    if (path.startsWith(workspace.resolve(".llm-comments")) || databaseArtifact(path))
      throw new CommentException("INVALID_PATH", "不能评论数据库文件");
    Path probe = path;
    while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS))
      probe = probe.getParent();
    Path real = probe.toRealPath();
    Path resolved = real.resolve(probe.relativize(path)).normalize();
    if (!resolved.startsWith(workspace)
        || resolved.startsWith(defaultDatabaseDirectory)
        || databaseArtifact(resolved))
      throw new CommentException("INVALID_PATH", "不能评论工作区外或数据库目录中的文件");
    return workspace.relativize(resolved).toString().replace('\\', '/');
  }

  private boolean databaseArtifact(Path path) {
    return path.equals(database)
        || path.equals(Path.of(database + "-wal"))
        || path.equals(Path.of(database + "-shm"))
        || path.equals(Path.of(database + "-journal"));
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private static String now() {
    return Instant.now().toString();
  }

  private static void execute(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(sql)) {
      bind(p, args);
      p.executeUpdate();
    }
  }

  private static void bind(PreparedStatement p, Object... args) throws SQLException {
    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
  }

  private static Map<String, Object> row(Connection c, String sql, Object... args)
      throws SQLException {
    try (PreparedStatement p = c.prepareStatement(sql)) {
      bind(p, args);
      try (ResultSet r = p.executeQuery()) {
        if (!r.next()) return null;
        return row(r);
      }
    }
  }

  private static Map<String, Object> row(ResultSet r) throws SQLException {
    Map<String, Object> result = new HashMap<>();
    ResultSetMetaData metadata = r.getMetaData();
    for (int i = 1; i <= metadata.getColumnCount(); i++)
      result.put(metadata.getColumnLabel(i), r.getObject(i));
    return result;
  }

  // 仅在当前事务中共享快照及行索引，后续操作仍重新读取源码。
  private record FileState(SnapshotView snapshot, boolean missing, LineMap.Index lines) {
    FileState(SnapshotView snapshot, boolean missing) {
      this(snapshot, missing, LineMap.index(snapshot.text()));
    }
  }

  private static String str(Map<String, Object> r, String key) {
    return (String) r.get(key);
  }

  private static int num(Map<String, Object> r, String key) {
    return ((Number) r.get(key)).intValue();
  }

  private static Map<String, Object> required(Connection c, String id) throws SQLException {
    Map<String, Object> r = row(c, "SELECT * FROM comments WHERE id=?", id);
    if (r == null) throw new CommentException("NOT_FOUND", "评论不存在");
    return r;
  }

  private SnapshotView snapshot(Connection c, String id) throws SQLException {
    Map<String, Object> r = row(c, "SELECT * FROM snapshots WHERE id=?", id);
    if (r == null) throw new CommentException("NOT_FOUND", "快照不存在");
    return new SnapshotView(id, str(r, "file"), str(r, "hash"), str(r, "text"), str(r, "created"));
  }

  private SnapshotView saveSnapshot(Connection c, String file, String text) throws Exception {
    String hash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    Map<String, Object> old =
        row(c, "SELECT id FROM snapshots WHERE file=? AND hash=?", file, hash);
    if (old != null) return snapshot(c, str(old, "id"));
    SnapshotView s = new SnapshotView(id(), file, hash, text, now());
    execute(c, "INSERT INTO snapshots VALUES(?,?,?,?,?)", s.id(), file, hash, text, s.createdAt());
    return s;
  }

  private static Anchor anchor(Map<String, Object> r) {
    return new Anchor(
        num(r, "start"),
        num(r, "end"),
        AnchorStatus.valueOf(str(r, "status")),
        str(r, "selected"),
        str(r, "before_text"),
        str(r, "after_text"));
  }

  private FileState sync(Connection c, String file) throws Exception {
    // 每次读取先校验路径；缺失只改变可见状态，保留可恢复的锚点和旧快照。
    file(file);
    Map<String, Object> f = row(c, "SELECT * FROM files WHERE file=?", file);
    Path source = workspace.resolve(file);
    String text;
    try {
      text = Files.readString(source);
    } catch (NoSuchFileException e) {
      if (f == null) throw new CommentException("FILE_NOT_FOUND", "源文件不存在");
      if (num(f, "missing") == 0) execute(c, "UPDATE files SET missing=1 WHERE file=?", file);
      return new FileState(snapshot(c, str(f, "snapshot")), true);
    }
    SnapshotView previous = f == null ? null : snapshot(c, str(f, "snapshot"));
    if (previous != null && previous.text().equals(text)) {
      if (num(f, "missing") == 1) execute(c, "UPDATE files SET missing=0 WHERE file=?", file);
      return new FileState(previous, false);
    }
    SnapshotView next = saveSnapshot(c, file, text);
    if (f == null) {
      execute(c, "INSERT INTO files VALUES(?,?,0)", file, next.id());
      return new FileState(next, false);
    }
    if (!previous.id().equals(next.id())) {
      List<String> ids = new ArrayList<>();
      List<Anchor> anchors = new ArrayList<>();
      try (PreparedStatement p =
          c.prepareStatement("SELECT * FROM comments WHERE file=? ORDER BY id")) {
        p.setString(1, file);
        try (ResultSet r = p.executeQuery()) {
          while (r.next()) {
            ids.add(r.getString("id"));
            anchors.add(
                new Anchor(
                    r.getInt("start"),
                    r.getInt("end"),
                    AnchorStatus.valueOf(r.getString("status")),
                    r.getString("selected"),
                    r.getString("before_text"),
                    r.getString("after_text")));
          }
        }
      }
      List<Anchor> moved = AnchorTracker.relocate(previous.text(), text, anchors);
      try (PreparedStatement p = c.prepareStatement(
          "UPDATE comments SET start=?,end=?,status=?,selected=?,before_text=?,after_text=? WHERE id=?")) {
        for (int i = 0; i < ids.size(); i++) {
          Anchor a = moved.get(i);
          if (a.equals(anchors.get(i))) continue;
          bind(p, a.startOffset(), a.endOffset(), a.status().name(), a.text(), a.before(),
              a.after(), ids.get(i));
          p.addBatch();
        }
        p.executeBatch();
      }
    }
    execute(c, "UPDATE files SET snapshot=?,missing=0 WHERE file=?", next.id(), file);
    return new FileState(next, false);
  }

  private Location location(FileState state, Map<String, Object> r) {
    Anchor a = anchor(r);
    AnchorStatus status = state.missing() ? AnchorStatus.MISSING_FILE : a.status();
    boolean valid = status == AnchorStatus.ACTIVE || status == AnchorStatus.MODIFIED;
    return new Location(
        state.snapshot().id(),
        valid ? a.startOffset() : null,
        valid ? a.endOffset() : null,
        valid ? state.lines().lineAt(a.startOffset()) : null,
        valid ? state.lines().lineAt(Math.max(a.startOffset(), a.endOffset() - 1)) : null,
        status,
        a.text());
  }

  private CommentView view(FileState state, Map<String, Object> r) {
    return new CommentView(
        str(r, "id"),
        str(r, "file"),
        str(r, "revision"),
        str(r, "body"),
        str(r, "author"),
        str(r, "created"),
        str(r, "updated"),
        num(r, "deleted") == 1,
        location(state, r));
  }

  private void revision(
      Connection c, FileState state, Map<String, Object> r, String action, String body, String author)
      throws SQLException {
    Location l = location(state, r);
    String revision = id(), time = now();
    execute(
        c,
        "INSERT INTO"
            + " revisions(id,comment_id,parent,action,body,author,created,snapshot,start,end,start_line,end_line,status,selected)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        revision,
        str(r, "id"),
        r.get("revision"),
        action,
        body,
        author,
        time,
        l.fileVersionId(),
        l.startOffset(),
        l.endOffset(),
        l.startLine(),
        l.endLine(),
        l.status().name(),
        l.selectedText());
    execute(
        c,
        "UPDATE comments SET revision=?,body=?,author=?,updated=?,deleted=? WHERE id=?",
        revision,
        body,
        author,
        time,
        action.equals("DELETE") ? 1 : 0,
        str(r, "id"));
  }

  private static void content(String body, String author) {
    if (body == null || author == null || author.isBlank())
      throw new CommentException("INVALID_ARGUMENT", "正文和作者不能为空值，作者不得为空白");
  }

  public CommentView create(String file, int startLine, int endLine, String body, String author) {
    return transaction(
        c -> {
          content(body, author);
          String normalized = file(file);
          FileState state = sync(c, normalized);
          if (state.missing()) throw new CommentException("FILE_NOT_FOUND", "源文件不存在");
          Anchor a =
              AnchorTracker.capture(state.snapshot().text(), startLine, endLine);
          String id = id(), time = now();
          execute(
              c,
              "INSERT INTO comments VALUES(?,?,NULL,?,?,?,?,0,?,?,?,?,?,?)",
              id,
              normalized,
              body,
              author,
              time,
              time,
              a.startOffset(),
              a.endOffset(),
              a.status().name(),
              a.text(),
              a.before(),
              a.after());
          revision(c, state, required(c, id), "CREATE", body, author);
          return view(state, required(c, id));
        });
  }

  public CommentView get(String id) {
    return transaction(
        c -> {
          Map<String, Object> r = required(c, id);
          FileState state = sync(c, str(r, "file"));
          return view(state, required(c, id));
        });
  }

  public List<CommentView> list(String file, boolean includeDeleted) {
    return transaction(
        c -> {
          String normalized = file == null ? null : file(file);
          List<String> files = new ArrayList<>();
          if (normalized != null) files.add(normalized);
          else
            try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT file FROM files ORDER BY file")) {
              while (r.next()) files.add(r.getString(1));
            }
          List<CommentView> result = new ArrayList<>();
          try (PreparedStatement p =
              c.prepareStatement(
                  "SELECT * FROM comments WHERE file=? AND "
                      + (includeDeleted ? "1=1" : "deleted=0") + " ORDER BY created,id")) {
            // 每个文件生成结果后即可释放全文快照和行索引，避免累积整个工作区源码。
            for (String f : files) {
              FileState state = sync(c, f);
              p.setString(1, f);
              try (ResultSet r = p.executeQuery()) {
                while (r.next()) result.add(view(state, row(r)));
              }
            }
          }
          result.sort(Comparator.comparing(CommentView::createdAt).thenComparing(CommentView::id));
          return List.copyOf(result);
        });
  }

  public CommentView update(String id, String baseRevisionId, String body, String author) {
    content(body, author);
    return change(id, baseRevisionId, "UPDATE", body, author);
  }

  public CommentView delete(String id, String baseRevisionId, String author) {
    content("", author);
    return change(id, baseRevisionId, "DELETE", null, author);
  }

  private CommentView change(String id, String base, String action, String body, String author) {
    return transaction(
        c -> {
          Map<String, Object> r = required(c, id);
          FileState state = sync(c, str(r, "file"));
          r = required(c, id);
          if (!Objects.equals(base, str(r, "revision")))
            throw new CommentException(
                "CONFLICT", "评论已被其他操作修改", Map.of("currentRevisionId", str(r, "revision")));
          if (num(r, "deleted") == 1) throw new CommentException("DELETED", "评论已删除");
          revision(c, state, r, action, body == null ? str(r, "body") : body, author);
          return view(state, required(c, id));
        });
  }

  public List<RevisionView> history(String id) {
    return transaction(
        c -> {
          required(c, id);
          List<RevisionView> result = new ArrayList<>();
          try (PreparedStatement p =
              c.prepareStatement("SELECT * FROM revisions WHERE comment_id=? ORDER BY seq")) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) {
              while (r.next())
                result.add(
                    new RevisionView(
                        r.getString("id"),
                        id,
                        r.getString("parent"),
                        r.getString("action"),
                        r.getString("body"),
                        r.getString("author"),
                        r.getString("created"),
                        new Location(
                            r.getString("snapshot"),
                            (Integer) r.getObject("start"),
                            (Integer) r.getObject("end"),
                            (Integer) r.getObject("start_line"),
                            (Integer) r.getObject("end_line"),
                            AnchorStatus.valueOf(r.getString("status")),
                            r.getString("selected"))));
            }
          }
          return List.copyOf(result);
        }, false);
  }

  public SnapshotView snapshot(String fileVersionId) {
    return transaction(c -> snapshot(c, fileVersionId), false);
  }

  @Override
  public void close() {
    /* 连接按操作关闭，无共享句柄。 */
  }
}
