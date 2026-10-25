package io.github.dreamlike.llmcomment.desktop;

import static org.junit.jupiter.api.Assertions.*;

import javafx.scene.Group;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.Test;

class FileTypeIconsTest {
  @Test
  void filenameTakesPriorityAndUnknownTypesFallBack() {
    assertEquals(FileTypeIcons.Kind.MAVEN, FileTypeIcons.kind("POM.XML"));
    assertEquals(FileTypeIcons.Kind.RUST, FileTypeIcons.kind("Cargo.toml"));
    assertEquals(FileTypeIcons.Kind.GIT, FileTypeIcons.kind(".gitignore"));
    assertEquals(FileTypeIcons.Kind.TYPESCRIPT, FileTypeIcons.kind("中文.tsx"));
    assertEquals(FileTypeIcons.Kind.FILE, FileTypeIcons.kind("unknown.filetype"));
    assertEquals(FileTypeIcons.Kind.FILE, FileTypeIcons.kind("无扩展名"));
  }

  @Test
  void everyVendoredResourceCreatesIndependentShapes() {
    for (FileTypeIcons.Kind kind : FileTypeIcons.Kind.values()) {
      Region first = FileTypeIcons.create(kind);
      Region second = FileTypeIcons.create(kind);
      Group drawing = drawing(first);
      assertFalse(drawing.getChildren().isEmpty(), kind.name());
      assertNotSame(first, second);
      assertNotSame(drawing, drawing(second));
      for (int i = 0; i < drawing.getChildren().size(); i++) {
        assertInstanceOf(SVGPath.class, drawing.getChildren().get(i));
        assertNotSame(drawing.getChildren().get(i), drawing(second).getChildren().get(i));
      }
    }
  }

  @Test
  void preservesUpstreamFillAndBothGradientCoordinateSystems() {
    SVGPath java =
        (SVGPath) drawing(FileTypeIcons.create(FileTypeIcons.Kind.JAVA)).getChildren().getFirst();
    assertEquals(Color.web("#f44336"), java.getFill());
    SVGPath kotlin =
        (SVGPath) drawing(FileTypeIcons.create(FileTypeIcons.Kind.KOTLIN)).getChildren().getFirst();
    LinearGradient absolute = assertInstanceOf(LinearGradient.class, kotlin.getFill());
    assertFalse(absolute.isProportional());
    assertEquals(1.306 + 1.725 * .89324, absolute.getStartX(), 1e-9);
    assertEquals(3, absolute.getStops().size());
    SVGPath maven =
        (SVGPath) drawing(FileTypeIcons.create(FileTypeIcons.Kind.MAVEN)).getChildren().getFirst();
    assertTrue(assertInstanceOf(LinearGradient.class, maven.getFill()).isProportional());
  }

  @Test
  void negativeViewBoxScalesIntoRequestedRegionWithoutChangingPath() {
    Region icon = FileTypeIcons.create(FileTypeIcons.Kind.JSON);
    Group drawing = drawing(icon);
    String original = ((SVGPath) drawing.getChildren().getFirst()).getContent();
    icon.resize(24, 24);
    icon.layout();
    var transform = drawing.getTransforms().getFirst();
    assertEquals(.025, transform.getMxx(), 1e-9);
    assertEquals(24, transform.getTy(), 1e-9);
    icon.resize(48, 48);
    icon.layout();
    assertEquals(.05, drawing.getTransforms().getFirst().getMxx(), 1e-9);
    assertEquals(original, ((SVGPath) drawing.getChildren().getFirst()).getContent());
  }

  private static Group drawing(Region icon) {
    return assertInstanceOf(Group.class, icon.getChildrenUnmodifiable().getFirst());
  }
}
