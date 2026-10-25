package io.github.dreamlike.llmcomment.desktop;

import io.github.dreamlike.llmcomment.core.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** 文件及数据库操作在单个后台队列中执行，界面显示同一快照中的源码和评论。 */
public class DesktopApplication extends Application {
  static DesktopOptions options;
  final BooleanProperty busy = new SimpleBooleanProperty();
  final SourceView source = new SourceView();
  final FileTreeView files = new FileTreeView(this::loadDirectory, this::openFile);
  final ListView<CommentView> comments = new ListView<>();
  final ListView<RevisionView> revisions = new ListView<>();
  final TextArea body = new TextArea();
  final TextField author = new TextField(System.getProperty("user.name", "reviewer"));
  final CheckBox includeDeleted = new CheckBox("显示已删除");
  final Button save = button("添加评论", "primary-button");
  final Button refresh = button("刷新", "tonal-button");
  final Button newComment = button("新评论", "text-button");
  final Button delete = button("删除", "danger-button");
  final TabPane tabs = new TabPane();
  final Label status = label("准备打开工作区", "status-text");
  final Label fileTitle = label("选择一个文件", "file-title");
  final Label fileInfo = label("从左侧打开源码，选择行后添加评论", "muted");
  final Label workspaceTitle = label("工作区", "workspace-title");
  final Label workspacePath = label("", "muted");
  final Label directoryLabel = label("/", "directory-label");
  final Label count = label("0", "count-chip");
  final Label draftTitle = label("新增评论", "section-title");
  final Label draftRange = label("先选择代码行", "muted");
  final Label historyTitle = label("选择评论查看历史", "muted");
  final TextArea historyBody = new TextArea();
  final Button currentFile = button("返回当前文件", "tonal-button");
  final TextField filter = new TextField();
  final ProgressIndicator progress = new ProgressIndicator();
  final MenuButton scaleChoice = new MenuButton();
  final SplitPane columns = new SplitPane();
  WorkspaceModel model;
  FileComments current;
  CommentView selected;
  Stage stage;
  BorderPane root;
  ScaledPane viewport;
  private double scaleMode;
  boolean historical;
  boolean dirty;
  private boolean changing;
  private boolean closed;
  private long generation;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(Thread.ofPlatform().name("desktop-io").daemon().factory());

  @Override
  public void init() throws Exception {
    DesktopFonts.initialize();
  }

  @Override
  public void start(Stage window) throws Exception {
    DesktopInputMethods.initialize();
    stage = window;
    String iconUrl = getClass().getResource("app-icon.png").toExternalForm();
    for (int size : new int[] {16, 32, 48, 64, 128, 256}) {
      stage.getIcons().add(new Image(iconUrl, size, size, true, true));
    }
    root = new BorderPane();
    root.getStyleClass().addAll("root", "workbench");
    root.setTop(header());
    Node browser = fileBrowser(), commentPane = commentPanel();
    columns.getItems().addAll(browser, sourcePanel(), commentPane);
    SplitPane.setResizableWithParent(browser, false);
    SplitPane.setResizableWithParent(commentPane, false);
    columns.getStyleClass().add("columns");
    columns.setDividerPositions(0.21, 0.68);
    root.setCenter(columns);
    root.setBottom(footer());
    scaleMode = options.scale();
    viewport = new ScaledPane(root);
    updateScale();
    Rectangle2D available = Screen.getPrimary().getVisualBounds();
    Scene scene =
        new Scene(
            viewport,
            Math.min(1380 * viewport.getZoom(), available.getWidth() * 0.92),
            Math.min(900 * viewport.getZoom(), available.getHeight() * 0.90));
    scene.getStylesheets().add(getClass().getResource("material.css").toExternalForm());
    scene
        .getAccelerators()
        .put(new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN), () -> refresh.fire());
    scene
        .getAccelerators()
        .put(
            new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN), () -> save.fire());
    scene
        .getAccelerators()
        .put(
            new KeyCodeCombination(
                KeyCode.EQUALS, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_ANY),
            () -> stepScale(0.25));
    scene
        .getAccelerators()
        .put(
            new KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN),
            () -> stepScale(0.25));
    scene
        .getAccelerators()
        .put(
            new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN),
            () -> stepScale(-0.25));
    scene
        .getAccelerators()
        .put(
            new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN),
            () -> setScale(0));
    stage.setTitle("行间 · 本地代码评论");
    stage.setMinWidth(1050);
    stage.setMinHeight(720);
    stage.setScene(scene);
    DesktopInputMethods.install(scene);
    stage.xProperty().addListener((o, before, after) -> updateScale());
    stage.yProperty().addListener((o, before, after) -> updateScale());
    stage.widthProperty().addListener((o, before, after) -> updateScale());
    stage.heightProperty().addListener((o, before, after) -> updateScale());
    stage.outputScaleXProperty().addListener((o, before, after) -> updateScale());
    stage.setOnCloseRequest(
        event -> {
          if (busy.get()) {
            status.setText("正在完成操作，请稍后关闭窗口。");
            event.consume();
          } else if (!discardDraft()) {
            event.consume();
          }
        });
    stage.setMaximized(options.maximized());
    stage.show();
    bindActions();
    openInitialWorkspace();
  }

  /** 窗口与动作初始化完成后打开工作区，子类可选择自己的启动内容。 */
  protected void openInitialWorkspace() throws Exception {
    openWorkspace(
        Workspace.resolve(Path.of("."), options.workspace(), options.database()), options.file());
  }

  private Node header() {
    ImageView mark =
        new ImageView(new Image(getClass().getResource("app-icon.png").toExternalForm()));
    mark.setFitWidth(48);
    mark.setFitHeight(48);
    mark.setPreserveRatio(true);
    VBox brand =
        new VBox(2, label("行间", "brand-title"), label("LOCAL CODE COMMENTS", "brand-caption"));
    HBox identity = new HBox(12, mark, brand);
    identity.setAlignment(Pos.CENTER_LEFT);
    Button choose = button("打开工作区", "tonal-button");
    choose.disableProperty().bind(busy);
    choose.setOnAction(event -> chooseWorkspace());
    ToggleButton theme = new ToggleButton("深色");
    theme.getStyleClass().add("tonal-button");
    theme.setOnAction(
        event -> {
          if (theme.isSelected()) {
            root.getStyleClass().add("dark");
            viewport.getStyleClass().add("dark");
          } else {
            root.getStyleClass().remove("dark");
            viewport.getStyleClass().remove("dark");
          }
          theme.setText(theme.isSelected() ? "浅色" : "深色");
        });
    VBox project = new VBox(3, workspaceTitle, workspacePath);
    project.setMinWidth(0);
    project.setMaxWidth(320);
    workspaceTitle.setMinWidth(0);
    workspacePath.setMinWidth(0);
    workspacePath.setMaxWidth(320);
    scaleChoice.getStyleClass().add("tonal-button");
    scaleChoice.setTooltip(new Tooltip("调整界面大小 · Ctrl/Cmd + 加号、减号；0 恢复自动"));
    MenuItem automatic = new MenuItem("自动适配屏幕");
    automatic.setOnAction(event -> setScale(0));
    scaleChoice.getItems().add(automatic);
    for (double scale : new double[] {0.75, 1, 1.25, 1.5, 1.75, 2, 2.5, 3}) {
      MenuItem choice = new MenuItem(Math.round(scale * 100) + "%");
      choice.setOnAction(event -> setScale(scale));
      scaleChoice.getItems().add(choice);
    }
    HBox header = new HBox(14, identity, spacer(), project, choose, refresh, scaleChoice, theme);
    header.setAlignment(Pos.CENTER_LEFT);
    header.getStyleClass().add("app-header");
    return header;
  }

  void setScale(double scale) {
    scaleMode = scale;
    updateScale();
  }

  private void stepScale(double step) {
    setScale(Math.clamp(viewport.getZoom() + step, 0.75, 3));
  }

  private void updateScale() {
    if (viewport == null) return;
    Screen screen = Screen.getPrimary();
    if (Double.isFinite(stage.getX()) && Double.isFinite(stage.getY())) {
      double bestArea = -1;
      for (Screen candidate :
          Screen.getScreensForRectangle(
              stage.getX(),
              stage.getY(),
              Math.max(1, stage.getWidth()),
              Math.max(1, stage.getHeight()))) {
        Rectangle2D bounds = candidate.getBounds();
        double area =
            Math.max(
                    0,
                    Math.min(stage.getX() + stage.getWidth(), bounds.getMaxX())
                        - Math.max(stage.getX(), bounds.getMinX()))
                * Math.max(
                    0,
                    Math.min(stage.getY() + stage.getHeight(), bounds.getMaxY())
                        - Math.max(stage.getY(), bounds.getMinY()));
        if (area > bestArea) {
          bestArea = area;
          screen = candidate;
        }
      }
    }
    double zoom =
        scaleMode == 0
            ? UiScale.automatic(
                screen.getDpi(), Math.max(screen.getOutputScaleX(), screen.getOutputScaleY()))
            : scaleMode;
    viewport.setZoom(zoom);
    scaleChoice.setText((scaleMode == 0 ? "自动 · " : "") + Math.round(zoom * 100) + "%");
    for (MenuItem choice : scaleChoice.getItems()) {
      choice.setStyle("-fx-font-size: " + (14 * zoom) + "px;");
    }
  }

  private Node fileBrowser() {
    HBox heading = new HBox(8, label("文件", "section-title"), spacer());
    heading.setAlignment(Pos.CENTER_LEFT);
    filter.setPromptText("筛选已加载文件");
    filter.textProperty().addListener((o, before, after) -> files.setFilter(after));
    VBox browser =
        new VBox(14, heading, directoryLabel, filter, files, label("展开目录 · 双击打开文件", "muted"));
    browser.setMinWidth(210);
    browser.setPrefWidth(280);
    browser.setMaxWidth(400);
    browser.getStyleClass().add("file-browser");
    VBox.setVgrow(files, Priority.ALWAYS);
    return browser;
  }

  private Node sourcePanel() {
    Label readOnly = label("只读", "neutral-chip");
    HBox heading = new HBox(12, fileTitle, spacer(), readOnly);
    heading.setAlignment(Pos.CENTER_LEFT);
    currentFile.setVisible(false);
    currentFile.setManaged(false);
    currentFile.setOnAction(
        event -> {
          if (current != null) {
            renderSource(current);
            if (selected != null)
              current.comments().stream()
                  .filter(comment -> comment.id().equals(selected.id()))
                  .findFirst()
                  .ifPresent(this::highlight);
            updateActions();
          }
        });
    VBox title = new VBox(6, heading, fileInfo, currentFile);
    title.getStyleClass().add("source-header");
    Label selection = label("", "selection-label");
    selection.textProperty().bind(source.selectionTextProperty());
    Button copy = button("复制选中行", "text-button");
    copy.setOnAction(event -> source.copySelection());
    HBox bottom = new HBox(12, selection, spacer(), copy);
    bottom.setAlignment(Pos.CENTER_LEFT);
    bottom.getStyleClass().add("source-footer");
    BorderPane pane = new BorderPane(source, title, null, bottom, null);
    pane.setMinWidth(350);
    pane.getStyleClass().add("source-panel");
    return pane;
  }

  private Node commentPanel() {
    HBox heading = new HBox(10, label("评论", "section-title"), count, spacer());
    heading.setAlignment(Pos.CENTER_LEFT);
    comments.getStyleClass().add("comment-list");
    comments.setPlaceholder(label("让想法留在代码旁\n\n选择一行或一个范围，\n写下第一条评论。", "empty-state"));
    comments.setCellFactory(
        list ->
            new ListCell<>() {
              @Override
              protected void updateItem(CommentView comment, boolean empty) {
                super.updateItem(comment, empty);
                if (empty || comment == null) {
                  setGraphic(null);
                  setText(null);
                  return;
                }
                Location location = comment.location();
                Label position = label(range(location), "comment-range");
                Label state =
                    label(comment.deleted() ? "已删除" : state(location.status()), "state-chip");
                if (comment.deleted() || location.startLine() == null)
                  state.getStyleClass().add("warning-chip");
                HBox top = new HBox(8, position, spacer(), state);
                Label text = label(preview(comment.body(), 200), "comment-body");
                text.setWrapText(true);
                text.setMaxWidth(330);
                Label meta = label(comment.author() + "  ·  " + time(comment.updatedAt()), "muted");
                VBox card = new VBox(9, top, text, meta);
                card.getStyleClass().add("comment-card");
                setGraphic(card);
                setText(null);
              }
            });
    HBox draftHeading = new HBox(10, draftTitle, spacer(), newComment);
    draftHeading.setAlignment(Pos.CENTER_LEFT);
    body.setPromptText("写下需要关注的问题、建议或待办…");
    body.setWrapText(true);
    body.setPrefRowCount(5);
    body.setMinHeight(115);
    body.getStyleClass().add("comment-editor");
    author.setPromptText("作者");
    author.setMaxWidth(160);
    HBox authors = new HBox(10, label("作者", "muted"), author);
    authors.setAlignment(Pos.CENTER_LEFT);
    HBox actions = new HBox(8, save, spacer(), delete);
    actions.setAlignment(Pos.CENTER_LEFT);
    VBox editor = new VBox(10, draftHeading, draftRange, body, authors, actions);
    editor.getStyleClass().add("composer");
    VBox commentContent = new VBox(10, includeDeleted, comments, editor);
    VBox.setVgrow(comments, Priority.ALWAYS);
    Tab commentTab = new Tab("全部评论", commentContent);
    commentTab.setClosable(false);
    revisions.setPlaceholder(label("选择评论后可查看每次修改", "empty-state"));
    revisions.setCellFactory(
        list ->
            new ListCell<>() {
              @Override
              protected void updateItem(RevisionView revision, boolean empty) {
                super.updateItem(revision, empty);
                if (empty || revision == null) {
                  setGraphic(null);
                  setText(null);
                  return;
                }
                VBox item =
                    new VBox(
                        7,
                        label(
                            action(revision.action()) + "  ·  " + time(revision.createdAt()),
                            "revision-title"),
                        label(revision.author() + "  ·  " + range(revision.location()), "muted"));
                item.getStyleClass().add("revision-card");
                setGraphic(item);
                setText(null);
              }
            });
    historyBody.setEditable(false);
    historyBody.setWrapText(true);
    historyBody.setPromptText("选择一个历史版本，查看当时的正文与源码");
    historyBody.setPrefRowCount(8);
    VBox historyContent =
        new VBox(14, historyTitle, revisions, label("版本正文", "section-title"), historyBody);
    VBox.setVgrow(revisions, Priority.ALWAYS);
    Tab historyTab = new Tab("修改历史", historyContent);
    historyTab.setClosable(false);
    tabs.getTabs().addAll(commentTab, historyTab);
    VBox panel = new VBox(14, heading, tabs);
    panel.setMinWidth(360);
    panel.setPrefWidth(420);
    panel.setMaxWidth(560);
    panel.getStyleClass().add("comment-panel");
    VBox.setVgrow(tabs, Priority.ALWAYS);
    return panel;
  }

  private Node footer() {
    progress.setMaxSize(16, 16);
    progress.visibleProperty().bind(busy);
    HBox footer = new HBox(10, progress, status, spacer(), label("代码只读 · 评论修改保留历史", "muted"));
    footer.setAlignment(Pos.CENTER_LEFT);
    footer.getStyleClass().add("app-footer");
    return footer;
  }

  private void bindActions() {
    refresh.disableProperty().bind(busy);
    refresh.setOnAction(event -> refreshCurrent());
    includeDeleted.disableProperty().bind(busy);
    includeDeleted.setOnAction(event -> refreshCurrent());
    files.disableProperty().bind(busy);
    comments.disableProperty().bind(busy);
    revisions.disableProperty().bind(busy);
    source.disableProperty().bind(busy);
    busy.addListener((o, before, after) -> updateActions());
    source.selectionTextProperty().addListener((o, before, after) -> updateActions());
    source.onUserSelection(this::beginCommentFromSelection);
    source.onCommentChosen(
        comment -> {
          if (busy.get()) return;
          tabs.getSelectionModel().select(0);
          comments.getSelectionModel().select(comment);
          comments.scrollTo(comment);
        });
    body.textProperty().addListener((o, before, after) -> editorChanged());
    author.textProperty().addListener((o, before, after) -> editorChanged());
    newComment.setOnAction(event -> beginComment());
    save.setOnAction(event -> saveComment());
    delete.setOnAction(event -> deleteComment(false));
    comments
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, before, after) -> {
              if (!changing && after != null) {
                if (!discardDraft()) {
                  changing = true;
                  comments.getSelectionModel().select(before);
                  changing = false;
                } else selectComment(after);
              }
            });
    revisions
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, before, after) -> {
              if (!changing && after != null && !busy.get()) showRevision(after);
            });
    updateActions();
  }

  void openWorkspace(Workspace workspace, String initialFile) {
    if (busy.get() || !discardDraft()) return;
    runTask(
        "正在打开工作区…",
        () -> {
          WorkspaceModel next = new WorkspaceModel(workspace);
          try {
            next.children("");
            return next;
          } catch (Exception e) {
            next.close();
            throw e;
          }
        },
        opened -> {
          if (model != null) model.close();
          model = opened;
          workspaceTitle.setText(
              workspace.root().getFileName() == null
                  ? workspace.root().toString()
                  : workspace.root().getFileName().toString());
          workspacePath.setText(workspace.root().toString());
          workspacePath.setTooltip(new Tooltip(workspace.root().toString()));
          directoryLabel.setText("/");
          filter.clear();
          files.reset(workspaceTitle.getText());
          clearFile();
          if (initialFile != null) Platform.runLater(() -> openFile(initialFile));
        });
  }

  private void chooseWorkspace() {
    DirectoryChooser chooser = new DirectoryChooser();
    chooser.setTitle("选择代码工作区");
    if (model != null) chooser.setInitialDirectory(model.workspace().root().toFile());
    java.io.File chosen = chooser.showDialog(stage);
    if (chosen != null) openWorkspace(Workspace.resolve(Path.of("."), chosen.toPath(), null), null);
  }

  private void loadDirectory(String path, Consumer<List<WorkspaceModel.Entry>> completed) {
    WorkspaceModel requested = model;
    if (requested == null || closed) {
      completed.accept(null);
      return;
    }
    worker.submit(
        () -> {
          try {
            var children = requested.children(path);
            Platform.runLater(
                () -> {
                  if (!closed && model == requested) completed.accept(children);
                });
          } catch (Exception error) {
            Platform.runLater(
                () -> {
                  if (!closed && model == requested) {
                    completed.accept(null);
                    report(error);
                  }
                });
          }
        });
  }

  void openFile(String file) {
    if (model == null || busy.get() || !discardDraft()) return;
    boolean deleted = includeDeleted.isSelected();
    runTask(
        "正在读取源码与评论…", () -> model.load(file, deleted), loaded -> applyFile(loaded, null, false));
  }

  void refreshCurrent() {
    if (model == null || busy.get()) return;
    files.refresh();
    if (current == null) {
      return;
    }
    String file = current.snapshot().file();
    String identity = selected == null ? null : selected.id();
    boolean keepDraft = dirty;
    boolean deleted = includeDeleted.isSelected();
    runTask(
        "正在同步评论位置…",
        () -> model.load(file, deleted),
        loaded -> applyFile(loaded, identity, keepDraft));
  }

  private void applyFile(FileComments loaded, String identity, boolean keepDraft) {
    current = loaded;
    historical = false;
    changing = true;
    comments.setItems(FXCollections.observableArrayList(loaded.comments()));
    count.setText(Integer.toString(loaded.comments().size()));
    renderSource(loaded);
    CommentView match =
        identity == null
            ? null
            : loaded.comments().stream()
                .filter(comment -> comment.id().equals(identity))
                .findFirst()
                .orElse(null);
    comments.getSelectionModel().select(match);
    changing = false;
    if (!keepDraft) {
      if (match == null) clearDraft();
      else selectComment(match);
    } else if (match != null) {
      highlight(match);
    }
    updateActions();
  }

  private void renderSource(FileComments loaded) {
    historical = false;
    source.show(loaded.snapshot().text(), loaded.comments());
    fileTitle.setText(loaded.snapshot().file());
    fileTitle.setTooltip(new Tooltip(loaded.snapshot().file()));
    fileInfo.setText(loaded.missing() ? "文件已移除，显示最后一次保存的快照" : "选择代码行 · Shift 选择连续范围");
    currentFile.setVisible(false);
    currentFile.setManaged(false);
  }

  private void clearFile() {
    current = null;
    historical = false;
    source.show("", List.of());
    comments.getItems().clear();
    count.setText("0");
    fileTitle.setText("选择一个文件");
    fileInfo.setText("从左侧打开源码，选择行后添加评论");
    clearDraft();
  }

  void beginComment() {
    if (busy.get() || !discardDraft()) return;
    changing = true;
    comments.getSelectionModel().clearSelection();
    changing = false;
    clearDraft();
    tabs.getSelectionModel().select(0);
    body.requestFocus();
  }

  private void beginCommentFromSelection() {
    if (changing || busy.get() || historical || current == null || source.startLine() == 0) return;
    if (selected != null) {
      if (!discardDraft()) {
        highlight(selected);
        return;
      }
      changing = true;
      comments.getSelectionModel().clearSelection();
      changing = false;
      clearDraft();
    }
    tabs.getSelectionModel().select(0);
    // 焦点留在源码，用户可以继续 Shift 扩展范围；新评论草稿随范围选择保留。
    updateActions();
  }

  private void clearDraft() {
    changing = true;
    selected = null;
    body.clear();
    revisions.getItems().clear();
    historyBody.clear();
    historyTitle.setText("选择评论查看历史");
    changing = false;
    dirty = false;
    updateActions();
  }

  private void selectComment(CommentView comment) {
    selected = comment;
    dirty = false;
    changing = true;
    body.setText(comment.body());
    author.setText(comment.author());
    changing = false;
    if (historical && current != null) renderSource(current);
    highlight(comment);
    updateActions();
    runTask(
        "正在读取修改历史…",
        () -> model.history(comment.id()),
        history -> {
          revisions.setItems(FXCollections.observableArrayList(history.reversed()));
          historyBody.clear();
          historyTitle.setText(history.size() + " 个版本 · 最新在前");
        });
  }

  void saveComment() {
    if (save.isDisabled() || current == null) return;
    String text = body.getText(), writer = author.getText().strip();
    String file = current.snapshot().file(), version = current.snapshot().id();
    int start = source.startLine(), end = source.endLine();
    CommentView original = selected;
    boolean deleted = includeDeleted.isSelected();
    runTask(
        "正在保存评论…",
        () -> {
          CommentView saved =
              original == null
                  ? model.create(file, start, end, text, writer, version)
                  : model.update(original, text, writer);
          return new Mutation(saved, model.load(file, deleted));
        },
        result -> {
          dirty = false;
          applyFile(result.file(), result.comment().id(), false);
        });
  }

  void deleteComment(boolean confirmed) {
    if (selected == null || selected.deleted() || busy.get()) return;
    if (!confirmed) {
      Alert alert =
          new Alert(
              Alert.AlertType.CONFIRMATION,
              "删除后会保留正文和修改历史，可通过“显示已删除”查看。",
              ButtonType.CANCEL,
              ButtonType.OK);
      alert.initOwner(stage);
      alert.setHeaderText("删除这条评论？");
      if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
    }
    CommentView original = selected;
    String writer = author.getText().isBlank() ? original.author() : author.getText().strip();
    boolean deleted = includeDeleted.isSelected();
    runTask(
        "正在删除评论…",
        () -> {
          CommentView removed = model.delete(original, writer);
          return new Mutation(removed, model.load(original.file(), deleted));
        },
        result -> {
          dirty = false;
          applyFile(result.file(), result.comment().id(), false);
        });
  }

  private void showRevision(RevisionView revision) {
    runTask(
        "正在读取历史快照…",
        () -> model.snapshot(revision.location().fileVersionId()),
        snapshot -> {
          historical = true;
          source.show(snapshot.text(), List.of());
          if (revision.location().startLine() != null)
            source.highlight(revision.location().startLine(), revision.location().endLine());
          historyBody.setText(revision.body());
          fileTitle.setText(snapshot.file());
          fileInfo.setText(
              "历史快照 · " + action(revision.action()) + " · " + time(revision.createdAt()));
          currentFile.setVisible(true);
          currentFile.setManaged(true);
          updateActions();
        });
  }

  private boolean discardDraft() {
    if (!dirty) return true;
    Alert alert =
        new Alert(Alert.AlertType.CONFIRMATION, "当前正文尚未保存。", ButtonType.CANCEL, ButtonType.OK);
    alert.initOwner(stage);
    alert.setHeaderText("放弃未保存的评论？");
    return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
  }

  private void highlight(CommentView comment) {
    if (current != null
        && !current.missing()
        && comment.location().fileVersionId().equals(current.snapshot().id())
        && comment.location().startLine() != null) {
      source.highlight(comment.location().startLine(), comment.location().endLine());
    } else source.clearSelection();
  }

  private void editorChanged() {
    if (!changing)
      dirty =
          selected == null
              ? !body.getText().isEmpty()
              : !body.getText().equals(selected.body())
                  || !author.getText().strip().equals(selected.author());
    updateActions();
  }

  private void updateActions() {
    boolean editing = selected != null;
    draftTitle.setText(editing ? "编辑评论" : "新增评论");
    draftRange.setText(editing ? range(selected.location()) : source.selectionTextProperty().get());
    save.setText(editing ? "保存新版本" : "添加评论");
    boolean unavailable = busy.get() || current == null || historical;
    save.setDisable(
        unavailable
            || author.getText().isBlank()
            || (editing
                ? selected.deleted() || !dirty
                : current.missing() || source.startLine() == 0 || body.getText().isBlank()));
    newComment.setDisable(unavailable || !editing);
    delete.setDisable(unavailable || !editing || selected.deleted());
    body.setDisable(unavailable || editing && selected.deleted());
    author.setDisable(unavailable || editing && selected.deleted());
  }

  private <T> void runTask(String message, Callable<T> work, Consumer<T> success) {
    if (closed) return;
    long ticket = ++generation;
    busy.set(true);
    status.getStyleClass().remove("error-text");
    status.setText(message);
    worker.submit(
        () -> {
          try {
            T value = work.call();
            Platform.runLater(
                () -> {
                  if (closed || generation != ticket) return;
                  busy.set(false);
                  status.setText("已同步");
                  try {
                    success.accept(value);
                  } catch (Exception error) {
                    report(error);
                  }
                });
          } catch (Exception error) {
            Platform.runLater(
                () -> {
                  if (closed || generation != ticket) return;
                  busy.set(false);
                  report(error);
                });
          }
        });
  }

  protected void report(Exception error) {
    status.getStyleClass().add("error-text");
    String message = error.getMessage();
    if (error instanceof CommentException exception) {
      message =
          switch (exception.code()) {
            case "FILE_CONFLICT" -> "源码已经变化，请刷新文件并重新选择代码行。草稿已保留。";
            case "CONFLICT" -> "评论已有新版本。请先复制草稿，刷新并选择最新评论后重试。";
            default -> exception.getMessage();
          };
    }
    status.setText(message == null ? "操作失败，请重试" : message);
    status.setTooltip(new Tooltip(status.getText()));
  }

  @Override
  public void stop() {
    closed = true;
    generation++;
    worker.shutdownNow();
    if (model != null) model.close();
  }

  private record Mutation(CommentView comment, FileComments file) {}

  static Label label(String value, String style) {
    Label label = new Label(value);
    label.getStyleClass().add(style);
    return label;
  }

  static Button button(String value, String style) {
    Button button = new Button(value);
    button.getStyleClass().add(style);
    return button;
  }

  private static Region spacer() {
    Region region = new Region();
    HBox.setHgrow(region, Priority.ALWAYS);
    return region;
  }

  private static String range(Location location) {
    return location.startLine() == null
        ? "等待重新定位"
        : location.startLine().equals(location.endLine())
            ? "第 " + location.startLine() + " 行"
            : "第 " + location.startLine() + " 至 " + location.endLine() + " 行";
  }

  private static String state(AnchorStatus state) {
    return switch (state) {
      case ACTIVE -> "已定位";
      case MODIFIED -> "代码已修改";
      case ORPHANED -> "原文缺失";
      case AMBIGUOUS -> "位置有歧义";
      case MISSING_FILE -> "文件缺失";
    };
  }

  private static String action(String action) {
    return switch (action) {
      case "CREATE" -> "创建";
      case "UPDATE" -> "修改";
      case "DELETE" -> "删除";
      default -> action;
    };
  }

  private static String time(String time) {
    try {
      return java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
          .withZone(java.time.ZoneId.systemDefault())
          .format(java.time.Instant.parse(time));
    } catch (RuntimeException ignored) {
      return time;
    }
  }

  private static String preview(String text, int length) {
    if (text.isEmpty()) return "（空正文）";
    if (text.length() <= length) return text;
    int end = Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
    return text.substring(0, end) + "…";
  }
}
