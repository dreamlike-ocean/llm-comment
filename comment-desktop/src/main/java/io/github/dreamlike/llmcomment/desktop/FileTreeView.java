package io.github.dreamlike.llmcomment.desktop;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;

/** 目录仅在展开时异步读取一层；筛选只检查已经加载的节点。 */
final class FileTreeView extends TreeView<WorkspaceModel.Entry> {
  private final BiConsumer<String, Consumer<List<WorkspaceModel.Entry>>> loader;
  private final Consumer<String> openFile;
  private long generation;
  private String filter = "";

  FileTreeView(
      BiConsumer<String, Consumer<List<WorkspaceModel.Entry>>> loader, Consumer<String> openFile) {
    this.loader = Objects.requireNonNull(loader);
    this.openFile = Objects.requireNonNull(openFile);
    getStyleClass().add("file-tree");
    setShowRoot(false);
    setCellFactory(
        ignored ->
            new TreeCell<>() {
              @Override
              protected void updateItem(WorkspaceModel.Entry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(
                    empty || entry == null ? null : (entry.directory() ? "▣ " : "") + entry.name());
                setGraphic(null);
              }
            });
    setOnMouseClicked(
        event -> {
          if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
            TreeItem<WorkspaceModel.Entry> item = getSelectionModel().getSelectedItem();
            // 目录的双击展开和折叠交给标准 TreeCell 行为，避免切换两次。
            if (item != null && item.getValue() != null && !item.getValue().directory())
              openFile.accept(item.getValue().path());
          }
        });
    setOnKeyPressed(
        event -> {
          if (event.getCode() == KeyCode.ENTER) {
            activateSelected();
            event.consume();
          }
        });
  }

  /** 新工作区使用全新根节点，旧工作区的异步结果无法写回。 */
  void reset(String rootLabel) {
    generation++;
    Branch root = new Branch(new WorkspaceModel.Entry("", rootLabel, true));
    setRoot(root);
    root.setExpanded(true);
  }

  void activateSelected() {
    TreeItem<WorkspaceModel.Entry> item = getSelectionModel().getSelectedItem();
    if (item == null || item.getValue() == null) return;
    if (item.getValue().directory()) item.setExpanded(!item.isExpanded());
    else openFile.accept(item.getValue().path());
  }

  String selectedDirectory() {
    TreeItem<WorkspaceModel.Entry> item = getSelectionModel().getSelectedItem();
    if (item == null || item.getValue() == null) return "";
    String path = item.getValue().path();
    if (item.getValue().directory()) return path;
    int separator = path.lastIndexOf('/');
    return separator < 0 ? "" : path.substring(0, separator);
  }

  /** 仅重读根目录及已经展开的目录；保留相同路径节点的展开状态。 */
  @Override
  public void refresh() {
    super.refresh();
    if (!(getRoot() instanceof Branch root)) return;
    ArrayDeque<Branch> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      Branch branch = pending.removeFirst();
      if (branch == root || branch.isExpanded()) {
        pending.addAll(branch.entries.stream().filter(it -> it.getValue().directory()).toList());
        load(branch);
      }
    }
  }

  void setFilter(String text) {
    filter = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    rebuildVisible();
  }

  private void load(Branch branch) {
    long workspaceRequest = generation;
    long request = ++branch.request;
    branch.loading = true;
    loader.accept(
        branch.getValue().path(),
        entries -> {
          Runnable apply =
              () -> {
                if (workspaceRequest != generation || request != branch.request) return;
                branch.loading = false;
                // null 表示读取失败，保留已有内容，下次展开或刷新时重试。
                if (entries == null) return;
                Map<String, Branch> old = new HashMap<>();
                for (Branch child : branch.entries) old.put(child.getValue().path(), child);
                List<Branch> children = new ArrayList<>();
                for (WorkspaceModel.Entry entry : entries) {
                  Branch child = old.get(entry.path());
                  if (child == null || child.getValue().directory() != entry.directory())
                    child = new Branch(entry);
                  else child.setValue(entry);
                  children.add(child);
                }
                branch.entries = children;
                branch.loaded = true;
                rebuildVisible();
              };
          if (Platform.isFxApplicationThread()) apply.run();
          else Platform.runLater(apply);
        });
  }

  private void rebuildVisible() {
    if (!(getRoot() instanceof Branch root)) return;
    ArrayList<Branch> nodes = new ArrayList<>();
    nodes.add(root);
    for (int i = 0; i < nodes.size(); i++) nodes.addAll(nodes.get(i).entries);
    Set<Branch> matches = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int i = nodes.size() - 1; i >= 0; i--) {
      Branch branch = nodes.get(i);
      List<TreeItem<WorkspaceModel.Entry>> visible = new ArrayList<>();
      for (Branch child : branch.entries)
        if (filter.isEmpty() || matches.contains(child)) visible.add(child);
      if (branch.getValue().directory() && !branch.loaded) visible.add(new TreeItem<>());
      branch.getChildren().setAll(visible);
      if (branch.getValue().name().toLowerCase(Locale.ROOT).contains(filter) || !visible.isEmpty())
        matches.add(branch);
    }
  }

  private final class Branch extends TreeItem<WorkspaceModel.Entry> {
    private List<Branch> entries = List.of();
    private boolean loaded;
    private boolean loading;
    private long request;

    private Branch(WorkspaceModel.Entry entry) {
      super(entry);
      if (entry.directory()) {
        getChildren().add(new TreeItem<>());
        expandedProperty()
            .addListener(
                (observable, before, expanded) -> {
                  if (expanded && !loaded && !loading) load(this);
                });
      }
    }
  }
}
