package io.github.dreamlike.llmcomment.desktop;

import java.util.Locale;
import javafx.scene.Group;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;

/** 文件名优先于扩展名；所有路径自行绘制，不读取源码或加载字体和图片。 */
final class FileTypeIcons {
  enum Kind {
    JAVA,
    KOTLIN,
    XML,
    MAVEN,
    JSON,
    MARKDOWN,
    YAML,
    JAVASCRIPT,
    TYPESCRIPT,
    PYTHON,
    RUST,
    GO,
    SHELL,
    GIT,
    CONFIG,
    IMAGE,
    ARCHIVE,
    FILE,
    FOLDER,
    FOLDER_OPEN
  }

  private FileTypeIcons() {}

  static Kind kind(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return switch (lower) {
      case "pom.xml" -> Kind.MAVEN;
      case ".gitignore", ".gitattributes", ".gitmodules" -> Kind.GIT;
      case "dockerfile", "makefile", ".editorconfig", ".env", "gradlew", "mvnw" -> Kind.CONFIG;
      case "cargo.toml", "cargo.lock" -> Kind.RUST;
      case "go.mod", "go.sum" -> Kind.GO;
      case "package.json", "package-lock.json", "tsconfig.json" -> Kind.JSON;
      default -> extension(lower);
    };
  }

  private static Kind extension(String name) {
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) return Kind.FILE;
    return switch (name.substring(dot + 1)) {
      case "java", "class" -> Kind.JAVA;
      case "kt", "kts" -> Kind.KOTLIN;
      case "xml", "html", "htm", "fxml", "svg" -> Kind.XML;
      case "json", "jsonc" -> Kind.JSON;
      case "md", "markdown" -> Kind.MARKDOWN;
      case "yaml", "yml" -> Kind.YAML;
      case "js", "jsx", "mjs", "cjs" -> Kind.JAVASCRIPT;
      case "ts", "tsx", "mts", "cts" -> Kind.TYPESCRIPT;
      case "py", "pyi", "pyw" -> Kind.PYTHON;
      case "rs" -> Kind.RUST;
      case "go" -> Kind.GO;
      case "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1" -> Kind.SHELL;
      case "properties", "toml", "ini", "conf", "config", "gradle", "css", "scss" -> Kind.CONFIG;
      case "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "avif" -> Kind.IMAGE;
      case "zip", "jar", "gz", "tgz", "bz2", "xz", "7z", "rar", "tar", "war" -> Kind.ARCHIVE;
      default -> Kind.FILE;
    };
  }

  static Region create(Kind kind) {
    return new Icon(kind);
  }

  /** 固定 16 单位画布按控件尺寸布局，父场景的 ScaledPane 继续统一缩放。 */
  private static final class Icon extends Region {
    private final Group drawing;

    private Icon(Kind kind) {
      getStyleClass().addAll("file-type-icon", "file-icon-" + kind.name().toLowerCase(Locale.ROOT));
      setMouseTransparent(true);
      setAccessibleText(kind.name());
      SVGPath outline =
          path(
              switch (kind) {
                case FOLDER -> "M1 4 L6 4 L8 6 L15 6 L15 14 L1 14 Z";
                case FOLDER_OPEN -> "M1 6 L1 4 L6 4 L8 6 L14 6 M1 7 L15 7 L13 14 L1 14 Z";
                default -> "M3 1 L10 1 L14 5 L14 15 L3 15 Z M10 1 L10 5 L14 5";
              });
      SVGPath mark =
          path(
              switch (kind) {
                case JAVA -> "M6 8 L6 11 Q8 14 10 11 L10 8 M10 8 Q14 8 10 11 M7 6 L8 4";
                case KOTLIN -> "M6 7 L6 12 M10 7 L6 10 L10 12";
                case MAVEN -> "M5 12 L5 7 L8 10 L11 7 L11 12";
                case XML -> "M6 7 L4 9 L6 11 M11 7 L13 9 L11 11 M9 7 L8 12";
                case JSON ->
                    "M6 6 L5 6 L5 8 L4 9 L5 10 L5 12 L6 12 M11 6 L12 6 L12 8 L13 9 L12 10 L12 12"
                        + " L11 12";
                case MARKDOWN -> "M5 12 L5 7 L7 9 L9 7 L9 12 M12 7 L12 12 L10 10 M12 12 L14 10";
                case YAML -> "M5 7 L8 10 L11 7 M8 10 L8 13";
                case JAVASCRIPT -> "M7 7 L7 11 Q7 14 5 12 M12 7 L10 7 L10 9 L12 10 L12 12 L9 12";
                case TYPESCRIPT ->
                    "M4 7 L9 7 M6.5 7 L6.5 12 M13 7 L11 7 L11 9 L13 10 L13 12 L10 12";
                case PYTHON -> "M5 12 L5 7 L9 7 Q12 9 9 10 L5 10";
                case RUST -> "M5 12 L5 7 L9 7 Q12 9 8 10 L11 12 M5 10 L8 10";
                case GO -> "M8 7 L5 7 L5 12 L9 12 L9 10 L7 10 M11 8 Q14 8 14 10 Q14 12 11 12 Z";
                case SHELL -> "M5 7 L8 9 L5 11 M9 12 L12 12";
                case GIT ->
                    "M6 6 L11 11 M6 6 L6 12 M5 5 L7 5 L7 7 L5 7 Z M5 11 L7 11 L7 13 L5 13 Z M10 10"
                        + " L12 10 L12 12 L10 12 Z";
                case CONFIG -> "M5 7 L12 7 M5 11 L12 11 M7 6 L7 8 M10 10 L10 12";
                case IMAGE -> "M4 12 L7 9 L9 11 L11 8 L13 12 Z M5 6 L6 6";
                case ARCHIVE -> "M8 2 L8 4 L10 4 L10 6 L8 6 L8 8 L10 8 L10 12 L8 12 L8 10";
                case FILE -> "M6 8 L11 8 M6 11 L11 11";
                case FOLDER, FOLDER_OPEN -> "";
              });
      drawing = new Group(outline, mark);
      getChildren().add(drawing);
      setMinSize(USE_PREF_SIZE, USE_PREF_SIZE);
      setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
      setPrefSize(16, 16);
    }

    @Override
    protected void layoutChildren() {
      double size = Math.min(getWidth(), getHeight());
      drawing.setScaleX(size / 16);
      drawing.setScaleY(size / 16);
      // Group 的缩放中心是边界中心，补偿后保持 16×16 画布原点。
      drawing.setLayoutX(
          (getWidth() - size) / 2 + drawing.getLayoutBounds().getCenterX() * (size / 16 - 1));
      drawing.setLayoutY(
          (getHeight() - size) / 2 + drawing.getLayoutBounds().getCenterY() * (size / 16 - 1));
    }
  }

  private static SVGPath path(String data) {
    SVGPath path = new SVGPath();
    path.setContent(data);
    path.getStyleClass().add("file-icon-path");
    return path;
  }
}
