package io.github.dreamlike.llmcomment.desktop;

import com.sun.javafx.font.EmbeddedFontFallbacks;
import com.sun.javafx.font.EmbeddedFontFallbacks.EmbeddedFont;
import java.io.IOException;
import java.lang.foreign.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.text.Font;

/** 在首次布局前加载随应用分发的字体，供源码、编辑框和历史共用。 */
final class DesktopFonts {
  private static final String PREFIX = "/io/github/dreamlike/llmcomment/desktop/fonts/";

  private DesktopFonts() {}

  static void initialize() throws IOException {
    Path directory = Files.createTempDirectory("llm-comment-fonts-");
    directory.toFile().deleteOnExit();
    List<EmbeddedFont> loaded = new ArrayList<>();
    for (String name : List.of("NotoSansMonoCJKsc-Regular.otf", "NotoEmoji.ttf", "HanaMinB.ttf")) {
      Path file = directory.resolve(name);
      try (var input = DesktopFonts.class.getResourceAsStream(PREFIX + name)) {
        if (input == null) throw new IOException("缺少内置字体：" + name);
        Files.copy(input, file);
      }
      file.toFile().deleteOnExit();
      Font font = Font.loadFont(file.toUri().toString(), 16);
      if (font == null) throw new IOException("无法加载字体：" + name);
      loaded.add(new EmbeddedFont(font.getFamily(), font.getName(), file.toString()));
    }
    if (System.getProperty("os.name", "").startsWith("Linux")) {
      configureLinux();
      EmbeddedFontFallbacks.install(loaded);
    }
  }

  private static void configureLinux() {
    // JavaFX 21 的 FreeType 管线跳过位图字形。Pango 的 emoji 布局应选用
    // 已加载的轮廓字体；Fontconfig 规则仅作用于本进程，不写系统配置。
    try (Arena arena = Arena.ofConfined()) {
      var library = SymbolLookup.libraryLookup("libfontconfig.so.1", arena);
      var linker = Linker.nativeLinker();
      var current =
          linker.downcallHandle(
              library.find("FcConfigGetCurrent").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.ADDRESS));
      var parse =
          linker.downcallHandle(
              library.find("FcConfigParseAndLoadFromMemory").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_INT));
      var build =
          linker.downcallHandle(
              library.find("FcConfigBuildFonts").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
      MemorySegment config = (MemorySegment) current.invokeExact();
      if (config.equals(MemorySegment.NULL)) throw new IllegalStateException("Fontconfig 未初始化");
      MemorySegment xml =
          arena.allocateFrom(
              """
              <fontconfig><selectfont><rejectfont><pattern>
              <patelt name="family"><string>Noto Color Emoji</string></patelt>
              </pattern></rejectfont></selectfont></fontconfig>
              """);
      int parsed = (int) parse.invokeExact(config, xml, 1);
      int built = (int) build.invokeExact(config);
      if (parsed == 0 || built == 0) throw new IllegalStateException("字体回退规则加载失败");
    } catch (Throwable error) {
      throw new IllegalStateException("无法配置中文及 emoji 字体", error);
    }
  }
}
