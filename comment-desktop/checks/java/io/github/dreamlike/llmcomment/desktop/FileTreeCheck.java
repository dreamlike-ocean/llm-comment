package io.github.dreamlike.llmcomment.desktop;

import java.util.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;

/** 使用可控异步完成顺序验证目录树，不扫描或修改用户工作区。 */
final class FileTreeCheck {
  private FileTreeCheck() {}

  static void verify() {
    if (!Platform.isFxApplicationThread()) throw new IllegalStateException("目录树检查需要 JavaFX 线程");
    verifyIcons();
    List<Request> requests = new ArrayList<>();
    List<String> opened = new ArrayList<>();
    FileTreeView tree =
        new FileTreeView((path, done) -> requests.add(new Request(path, done)), opened::add);
    tree.reset("工作区");
    require(requests.size() == 1 && requests.getFirst().path().isEmpty(), "根目录必须只请求一次");
    requests.removeFirst().done().accept(List.of(directory("src"), file("README.md")));
    TreeItem<WorkspaceModel.Entry> src = tree.getRoot().getChildren().getFirst();
    require(requests.isEmpty(), "折叠目录不得提前读取");
    tree.getSelectionModel().select(src);
    tree.activateSelected();
    require(src.isExpanded() && requests.size() == 1, "Enter 必须展开目录并按层读取");
    require(requests.getFirst().path().equals("src"), "只请求所展开的目录");
    requests.removeFirst().done().accept(List.of(directory("src/main"), file("src/App.java")));
    require(requests.isEmpty(), "子目录不得递归加载");
    tree.getSelectionModel().select(src.getChildren().get(1));
    tree.activateSelected();
    require(opened.equals(List.of("src/App.java")), "文件必须调用打开回调");
    tree.setFilter("App.java");
    require(tree.getRoot().getChildren().contains(src), "筛选保留已加载匹配文件的父目录");
    require(requests.isEmpty(), "筛选不得读取目录");
    tree.setFilter("");
    tree.refresh();
    require(requests.size() == 2, "刷新只读取根和已经展开的分支");
    Request rootRefresh = requests.removeFirst();
    Request srcRefresh = requests.removeFirst();
    rootRefresh.done().accept(List.of(directory("src"), file("README.md")));
    srcRefresh.done().accept(List.of(directory("src/main"), file("src/App.java")));
    require(tree.getRoot().getChildren().getFirst() == src && src.isExpanded(), "刷新必须保留展开状态");
    tree.activateSelected();
    tree.reset("旧工作区");
    Request stale = requests.removeFirst();
    tree.reset("新工作区");
    Request current = requests.removeFirst();
    current.done().accept(List.of(file("new.java")));
    stale.done().accept(List.of(file("stale.java")));
    require(
        tree.getRoot().getChildren().getFirst().getValue().path().equals("new.java"),
        "过期工作区结果不得覆盖当前树");
    tree.getSelectionModel().select(tree.getRoot().getChildren().getFirst());
    tree.activateSelected();
    require(opened.getLast().equals("new.java"), "切换工作区后文件仍可打开");
    System.out.println("目录树检查通过：逐层加载、目录展开、文件打开、筛选、刷新及过期结果隔离");
  }

  private static void verifyIcons() {
    require(FileTypeIcons.kind("pom.xml") == FileTypeIcons.Kind.MAVEN, "精确文件名优先于 XML 扩展名");
    require(FileTypeIcons.kind("Cargo.toml") == FileTypeIcons.Kind.RUST, "精确文件名不区分大小写");
    require(FileTypeIcons.kind(".gitignore") == FileTypeIcons.Kind.GIT, "隐藏配置文件可识别");
    require(FileTypeIcons.kind("Example.JAVA") == FileTypeIcons.Kind.JAVA, "扩展名不区分大小写");
    require(FileTypeIcons.kind("中文.tsx") == FileTypeIcons.Kind.TYPESCRIPT, "中文文件名保留扩展名识别");
    require(FileTypeIcons.kind("README") == FileTypeIcons.Kind.FILE, "无扩展名回退通用文件");
    require(FileTypeIcons.kind("file.unknown") == FileTypeIcons.Kind.FILE, "未知扩展名回退通用文件");
    for (FileTypeIcons.Kind kind : FileTypeIcons.Kind.values()) {
      javafx.scene.layout.Region icon = FileTypeIcons.create(kind);
      require(icon != FileTypeIcons.create(kind), "每次创建独立图形节点");
      icon.resize(24, 24);
      icon.layout();
      require(icon.getChildrenUnmodifiable().size() == 1, "各类型必须从 SVG 资源生成独立矢量图形");
    }
  }

  private static WorkspaceModel.Entry directory(String path) {
    return new WorkspaceModel.Entry(path, path.substring(path.lastIndexOf('/') + 1), true);
  }

  private static WorkspaceModel.Entry file(String path) {
    return new WorkspaceModel.Entry(path, path.substring(path.lastIndexOf('/') + 1), false);
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private record Request(String path, Consumer<List<WorkspaceModel.Entry>> done) {}
}
