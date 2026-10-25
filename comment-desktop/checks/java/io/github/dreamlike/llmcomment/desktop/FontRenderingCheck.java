package io.github.dreamlike.llmcomment.desktop;

import com.sun.javafx.font.CompositeFontResource;
import com.sun.javafx.font.FontResource;
import com.sun.javafx.font.PGFont;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.scene.text.FontHelper;
import com.sun.javafx.text.TextRun;
import com.sun.javafx.tk.Toolkit;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/** 界面冒烟测试的字体诊断：内部 API 只用于核实真实回退字体及其光栅结果。 调用前须完成应用字体初始化；检查不修改系统字体配置或剪贴板。 */
final class FontRenderingCheck {
  private static final String SAMPLE = "中文简体繁體臺灣標點，。：「」𠮷𠀀龘😀🚀🧑🏽💻e\u0301→≤≥✓";
  private static final String[] LAYOUT_SAMPLES = {
    "🧑🏽‍💻",
    "👩‍💻",
    "❤️",
    "e\u0301",
    "中文繁體𠮷𠀀龘",
    "// 符号：→ ≤ ≥ ✓；组合：é；emoji：😀 🚀 🧑🏽‍💻 👩‍💻 ❤️"
  };

  private FontRenderingCheck() {}

  static void verify(Font sourceFont, Font bodyFont) {
    int glyphs = verifyFont(sourceFont);
    glyphs += verifyFont(new Font(sourceFont.getName(), 24));
    glyphs += verifyFont(bodyFont);
    glyphs += verifyFont(new Font(bodyFont.getName(), 24));
    System.out.println(
        "字体渲染检查通过：样本="
            + SAMPLE
            + "；有效字形="
            + glyphs
            + "；布局="
            + ((LAYOUT_SAMPLES.length + 3 + 21) * 4));
  }

  private static int verifyFont(Font suppliedFont) {
    // 同时验证控件原始字号和独立调用的 24px 诊断字号。
    Font font = suppliedFont;
    PGFont nativeFont = (PGFont) FontHelper.getNativeFont(font);
    FontResource resource = nativeFont.getFontResource();
    var mapper = resource.getGlyphMapper();
    int checked = 0;
    for (int point : SAMPLE.codePoints().toArray()) {
      int glyph = mapper.getGlyphCode(point);
      String context = "码点=U+%04X".formatted(point);
      requireGlyph(font, resource, glyph, context, !blank(point));
      checked++;
    }
    for (String sample : LAYOUT_SAMPLES) {
      var layout = Toolkit.getToolkit().getTextLayoutFactory().createLayout();
      layout.setContent(sample, nativeFont);
      int visibleGlyphs = 0;
      for (var run : layout.getRuns()) {
        for (int index = 0; index < run.getGlyphCount(); index++) {
          int glyph = run.getGlyphCode(index);
          if ((glyph & 0xFFFFFF) == 0xFFFF) {
            continue;
          }
          TextRun textRun = (TextRun) run;
          int relative = run.getCharOffset(index);
          int offset = textRun.getStart() + relative;
          offset = Math.clamp(offset, 0, sample.length() - 1);
          int point = sample.codePointAt(offset);
          requireGlyph(
              font,
              resource,
              glyph,
              "布局=" + sample + "，UTF-16位置=" + offset + "，码点=U+%04X".formatted(point),
              !blank(point));
          visibleGlyphs++;
          checked++;
        }
      }
      if (visibleGlyphs == 0
          || ((sample.equals("🧑🏽‍💻") || sample.equals("👩‍💻")) && visibleGlyphs != 1)) {
        throw new IllegalStateException(
            "字体=" + font.getName() + "，布局=" + sample + "，可见字形数=" + visibleGlyphs + "，应形成一个完整字形");
      }
    }
    verifyCaret(font, nativeFont, "🧑🏽‍💻 ABC", 8);
    verifyCaret(font, nativeFont, "אב x🧑🏽‍💻 ABC", 12);
    verifyCaret(font, nativeFont, "אב ABC", 3);
    checked += verifyWrapping(font);
    return checked;
  }

  private static void verifyCaret(Font font, PGFont nativeFont, String sample, int expectedA) {
    var layout = Toolkit.getToolkit().getTextLayoutFactory().createLayout();
    layout.setContent(sample, nativeFont);
    Text publicText = hitTarget(sample, font);
    int targetGlyph = nativeFont.getFontResource().getGlyphMapper().getGlyphCode('A');
    for (var run : layout.getRuns()) {
      for (int index = 0; index < run.getGlyphCount(); index++) {
        if (run.getGlyphCode(index) != targetGlyph) continue;
        // 从真实布局的 A 字形左缘命中，不用布局返回的字符索引构造期望值。
        float x = run.getLocation().x + run.getPosX(index) + 0.1f;
        float y = run.getLocation().y + run.getLineBounds().getHeight() / 2;
        // 公开 HitInfo 会完成不同 JavaFX 版本中可能延迟执行的插入位置计算。
        var hit = publicText.hitTest(new Point2D(x, y));
        if (hit.getCharIndex() != expectedA || hit.getInsertionIndex() != expectedA) {
          throw new IllegalStateException(
              "光标命中错误：字体="
                  + font.getName()
                  + "，字号="
                  + font.getSize()
                  + "，布局="
                  + sample
                  + "，期望 A 的 UTF-16位置="
                  + expectedA
                  + "，实际字符="
                  + hit.getCharIndex()
                  + "，插入位置="
                  + hit.getInsertionIndex());
        }
        return;
      }
    }
    throw new IllegalStateException("光标测试未找到 A 字形：字体=" + font.getName() + "，布局=" + sample);
  }

  private static int verifyWrapping(Font suppliedFont) {
    String sample = (LAYOUT_SAMPLES[5] + " 中文繁體𠮷𠀀龘 ").repeat(6) + " ENDZ";
    var layout = Toolkit.getToolkit().getTextLayoutFactory().createLayout();
    int checked = 0;
    Text publicText = hitTarget(sample, suppliedFont);
    // 复用同一布局，重现窄宽窗口切换和字体变更后的拆分、合并、再次塑形。
    for (double size : new double[] {suppliedFont.getSize(), 24, suppliedFont.getSize()}) {
      Font font = new Font(suppliedFont.getName(), size);
      PGFont nativeFont = (PGFont) FontHelper.getNativeFont(font);
      FontResource resource = nativeFont.getFontResource();
      layout.setContent(sample, nativeFont);
      publicText.setFont(font);
      for (float width : new float[] {140, 320, 64, 900, 200, 0, 180}) {
        layout.setWrapWidth(width);
        publicText.setWrappingWidth(width);
        var runs = layout.getRuns();
        if (width > 0 && layout.getLines().length <= 1) {
          throw new IllegalStateException("长文本没有换行：字体=" + font + "，宽度=" + width);
        }
        boolean foundEnd = false;
        int endGlyph = resource.getGlyphMapper().getGlyphCode('Z');
        int coveredEnd = 0;
        // 本样本从左到右排列；每次重排均应覆盖完整原文，不能留下被吞掉的尾段。
        for (var run : runs) {
          TextRun textRun = (TextRun) run;
          if (textRun.getStart() != coveredEnd || textRun.getEnd() <= coveredEnd) {
            throw new IllegalStateException(
                "换行范围不连续：字体="
                    + font
                    + "，宽度="
                    + width
                    + "，上次结束="
                    + coveredEnd
                    + "，当前范围="
                    + textRun.getStart()
                    + ".."
                    + textRun.getEnd());
          }
          coveredEnd = textRun.getEnd();
          for (int index = 0; index < run.getGlyphCount(); index++) {
            int glyph = run.getGlyphCode(index);
            if ((glyph & 0xFFFFFF) == 0xFFFF) continue;
            int offset = textRun.getStart() + run.getCharOffset(index);
            if (offset < textRun.getStart()
                || offset >= textRun.getEnd()
                || Character.isLowSurrogate(sample.charAt(offset))) {
              throw new IllegalStateException(
                  "换行字形索引无效：字体="
                      + font
                      + "，宽度="
                      + width
                      + "，UTF-16位置="
                      + offset
                      + "，当前范围="
                      + textRun.getStart()
                      + ".."
                      + textRun.getEnd());
            }
            int point = sample.codePointAt(offset);
            requireGlyph(
                font,
                resource,
                glyph,
                "换行宽度=" + width + "，UTF-16位置=" + offset + "，码点=U+%04X".formatted(point),
                !blank(point));
            checked++;
            if (glyph == endGlyph) {
              var hit =
                  publicText.hitTest(
                      new Point2D(
                          run.getLocation().x + run.getPosX(index) + 0.1f,
                          run.getLocation().y + run.getLineBounds().getHeight() / 2));
              if (hit.getCharIndex() != sample.length() - 1
                  || hit.getInsertionIndex() != sample.length() - 1) {
                throw new IllegalStateException(
                    "换行末尾光标错误：字体="
                        + font
                        + "，宽度="
                        + width
                        + "，期望="
                        + (sample.length() - 1)
                        + "，实际="
                        + hit.getCharIndex());
              }
              foundEnd = true;
            }
          }
        }
        if (coveredEnd != sample.length() || !foundEnd) {
          throw new IllegalStateException(
              "换行丢失文本尾部：字体="
                  + font
                  + "，宽度="
                  + width
                  + "，覆盖结束="
                  + coveredEnd
                  + "，原文长度="
                  + sample.length());
        }
      }
    }
    return checked;
  }

  private static Text hitTarget(String sample, Font font) {
    Text text = new Text(sample);
    text.setFont(font);
    // 与内部布局相同：坐标从行盒顶部开始，不引入 Text 默认基线偏移。
    text.setTextOrigin(VPos.TOP);
    return text;
  }

  private static boolean blank(int point) {
    return Character.isWhitespace(point)
        || Character.getType(point) == Character.FORMAT
        || (point >= 0xFE00 && point <= 0xFE0F)
        || (point >= 0xE0100 && point <= 0xE01EF);
  }

  private static void requireGlyph(
      Font font, FontResource resource, int code, String context, boolean requireInk) {
    FontResource actual =
        resource instanceof CompositeFontResource composite
            ? composite.getSlotResource(code >>> 24)
            : resource;
    int glyphCode = code & 0xFFFFFF;
    String details =
        "字体="
            + font.getName()
            + "，字号="
            + font.getSize()
            + "，"
            + context
            + "，实际回退="
            + actual.getFullName()
            + "，文件="
            + actual.getFileName()
            + "，glyph=0x"
            + Integer.toHexString(code);
    if (code == resource.getGlyphMapper().getMissingGlyphCode()
        || glyphCode == actual.getGlyphMapper().getMissingGlyphCode()) {
      throw new IllegalStateException("缺失字形：" + details);
    }
    if (!requireInk) {
      return;
    }
    var glyph =
        actual
            .getStrike((float) font.getSize(), BaseTransform.IDENTITY_TRANSFORM)
            .getGlyph(glyphCode);
    byte[] pixels = glyph.getPixelData();
    if (pixels != null && glyph.getWidth() > 0 && glyph.getHeight() > 0) {
      for (byte pixel : pixels) {
        if (pixel != 0) {
          return;
        }
      }
    }
    throw new IllegalStateException("字形无法生成有效像素：" + details);
  }
}
