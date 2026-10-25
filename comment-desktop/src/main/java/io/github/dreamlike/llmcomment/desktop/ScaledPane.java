package io.github.dreamlike.llmcomment.desktop;

import java.util.Objects;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;

/** 用场景图变换整体缩放界面；视口不足时通过滚动保留完整的操作区域。 */
public final class ScaledPane extends ScrollPane {
  private final Region content;
  private final Scale scale = new Scale(1, 1, 0, 0);
  private final Rectangle contentClip = new Rectangle();
  private final double minimumWidth;
  private final double minimumHeight;
  private double zoom = 1;

  public ScaledPane(Region content) {
    this(content, 1050, 720);
  }

  public ScaledPane(Region content, double minimumWidth, double minimumHeight) {
    requireFxThread();
    if (!Double.isFinite(minimumWidth)
        || minimumWidth <= 0
        || !Double.isFinite(minimumHeight)
        || minimumHeight <= 0) {
      throw new IllegalArgumentException("界面最小宽高必须为有限正数");
    }
    this.content = Objects.requireNonNull(content);
    this.minimumWidth = minimumWidth;
    this.minimumHeight = minimumHeight;
    content.setManaged(false);
    // 根布局中子控件的绘制越界不应扩大整个界面的滚动范围。
    Group surface = new Group(content);
    surface.setAutoSizeChildren(false);
    surface.setClip(contentClip);
    surface.getTransforms().add(scale);
    Group group = new Group(surface);
    group.setAutoSizeChildren(false);
    setContent(group);
    setFitToWidth(false);
    setFitToHeight(false);
    setPannable(false);
    getStyleClass().add("scaled-pane");
    viewportBoundsProperty().addListener((observable, previous, current) -> requestLayout());
    content.resize(minimumWidth, minimumHeight);
    contentClip.setWidth(minimumWidth);
    contentClip.setHeight(minimumHeight);
  }

  public void setZoom(double zoom) {
    requireFxThread();
    if (!Double.isFinite(zoom) || zoom < 0.75 || zoom > 3) {
      throw new IllegalArgumentException("界面缩放必须在 0.75 至 3 之间");
    }
    if (this.zoom == zoom) {
      return;
    }
    this.zoom = zoom;
    scale.setX(zoom);
    scale.setY(zoom);
    requestLayout();
  }

  public double getZoom() {
    return zoom;
  }

  public Region content() {
    return content;
  }

  @Override
  protected void layoutChildren() {
    // 预留一个视口像素，并向下取整，避免缩放后浮点误差或像素吸附产生多余滚动条。
    double width =
        Math.max(minimumWidth, Math.floor(Math.max(0, getViewportBounds().getWidth() - 1) / zoom));
    double height =
        Math.max(
            minimumHeight, Math.floor(Math.max(0, getViewportBounds().getHeight() - 1) / zoom));
    contentClip.setWidth(width);
    contentClip.setHeight(height);
    content.resizeRelocate(0, 0, width, height);
    super.layoutChildren();
  }

  private static void requireFxThread() {
    if (!Platform.isFxApplicationThread()) {
      throw new IllegalStateException("界面缩放必须在 JavaFX 线程操作");
    }
  }
}
