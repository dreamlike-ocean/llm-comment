package com.sun.javafx.font;

import java.util.ArrayList;
import java.util.List;

/** 在首次文字测量前补入应用字体。Gluon 21 静态 SDK 的 FontConfigManager 为包可见， 通过同包适配器兼容它与 Maven OpenJFX 的公开版本。 */
public final class EmbeddedFontFallbacks {
  private EmbeddedFontFallbacks() {}

  public record EmbeddedFont(String family, String fullName, String file) {}

  public static void install(List<EmbeddedFont> loaded) {
    for (String family : List.of("sans", "serif", "monospace")) {
      for (boolean bold : new boolean[] {false, true}) {
        for (boolean italic : new boolean[] {false, true}) {
          var config = FontConfigManager.getFontConfigFont(family, bold, italic);
          List<FontConfigManager.FontConfigFont> fonts = new ArrayList<>();
          if (config.firstFont != null) fonts.add(config.firstFont);
          for (EmbeddedFont entry : loaded) {
            var font = new FontConfigManager.FontConfigFont();
            font.familyName = entry.family();
            font.fullName = entry.fullName();
            font.fontFile = entry.file();
            fonts.add(font);
          }
          if (config.allFonts != null) {
            for (var font : config.allFonts) {
              if (font != config.firstFont && !"Noto Color Emoji".equals(font.familyName))
                fonts.add(font);
            }
          }
          config.allFonts = fonts.toArray(FontConfigManager.FontConfigFont[]::new);
        }
      }
    }
  }
}
