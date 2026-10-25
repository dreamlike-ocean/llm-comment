package io.github.dreamlike.llmcomment.desktop;

/** 整个桌面界面的缩放计算，不依赖 JavaFX 初始化。 */
public final class UiScale {
  private UiScale() {}

  /** 系统已有输出缩放时不重复补偿；否则按四分之一档补偿物理 DPI。 */
  public static double automatic(double dpi, double outputScale) {
    if (!Double.isFinite(dpi)
        || dpi <= 0
        || !Double.isFinite(outputScale)
        || outputScale <= 0
        || outputScale > 1.01) {
      return 1;
    }
    return Math.max(1, Math.min(2.5, Math.round(dpi / 96 * 4) / 4.0));
  }

  /** auto 返回 0；显式比例接受小数或百分数，范围为 75% 至 300%。 */
  public static double parse(String value) {
    if (value == null) {
      throw invalid();
    }
    String text = value.strip();
    if (text.equalsIgnoreCase("auto")) {
      return 0;
    }
    try {
      boolean percentage = text.endsWith("%");
      double zoom =
          Double.parseDouble(percentage ? text.substring(0, text.length() - 1).strip() : text);
      if (percentage) {
        zoom /= 100;
      }
      if (!Double.isFinite(zoom) || zoom < 0.75 || zoom > 3) {
        throw invalid();
      }
      return zoom;
    } catch (NumberFormatException ex) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("界面缩放必须为 auto、0.75 至 3 的比例或 75% 至 300% 的百分数");
  }
}
