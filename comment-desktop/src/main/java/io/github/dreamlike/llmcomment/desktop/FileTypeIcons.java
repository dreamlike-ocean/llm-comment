package io.github.dreamlike.llmcomment.desktop;

import java.util.Locale;
import javafx.scene.layout.Region;

/** 文件名优先于扩展名；图形从固定版本的 Material Icon Theme SVG 资源加载。 */
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

  static String resourceName(Kind kind) {
    String name =
        switch (kind) {
          case SHELL -> "console";
          case CONFIG -> "settings";
          case ARCHIVE -> "zip";
          case FILE -> "document";
          default -> kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
        };
    return "file-icons/" + name + ".svg";
  }

  static Region create(Kind kind) {
    Region icon = SvgFileIcon.load(resourceName(kind));
    icon.getStyleClass().add("file-type-icon");
    icon.setMouseTransparent(true);
    icon.setAccessibleText(kind.name());
    return icon;
  }
}
