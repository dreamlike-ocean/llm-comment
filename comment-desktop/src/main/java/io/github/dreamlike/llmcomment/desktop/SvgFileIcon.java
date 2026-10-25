package io.github.dreamlike.llmcomment.desktop;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Affine;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 仅加载随程序提供的 path/linearGradient SVG 子集，不加载外部资源或执行脚本。 */
final class SvgFileIcon extends Region {
  private static final Map<String, Drawing> CACHE = new ConcurrentHashMap<>();
  private static final Pattern TRANSFORM = Pattern.compile("(translate|scale)\\s*\\(([^)]*)\\)");
  private final Drawing drawing;
  private final Group shapes = new Group();

  private SvgFileIcon(Drawing drawing) {
    this.drawing = drawing;
    for (PathData data : drawing.paths()) {
      SVGPath path = new SVGPath();
      path.setContent(data.content());
      path.setFill(data.fill());
      shapes.getChildren().add(path);
    }
    shapes.setManaged(false);
    getChildren().add(shapes);
    setMinSize(USE_PREF_SIZE, USE_PREF_SIZE);
    setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
    setPrefSize(16, 16);
  }

  static Region load(String resource) {
    return new SvgFileIcon(CACHE.computeIfAbsent(resource, SvgFileIcon::read));
  }

  private static Drawing read(String resource) {
    try (InputStream input = SvgFileIcon.class.getResourceAsStream(resource)) {
      if (input == null) throw new IllegalArgumentException("缺少文件图标资源：" + resource);
      // 固定使用 JDK 实现，避免服务发现；禁止 DTD、实体及外部 schema。
      var factory = DocumentBuilderFactory.newDefaultInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      Element svg = factory.newDocumentBuilder().parse(input).getDocumentElement();
      if (!svg.getLocalName().equals("svg")) throw new IllegalArgumentException("图标根元素不是 SVG");
      double[] box = numbers(svg.getAttribute("viewBox"));
      if (box.length != 4 || box[2] <= 0 || box[3] <= 0)
        throw new IllegalArgumentException("图标 viewBox 无效");
      Map<String, Paint> gradients = new HashMap<>();
      for (Element child : children(svg)) {
        if (child.getLocalName().equals("defs")) {
          for (Element gradient : children(child)) {
            if (!gradient.getLocalName().equals("linearGradient"))
              throw new IllegalArgumentException("图标包含不支持的定义");
            gradients.put(gradient.getAttribute("id"), gradient(gradient));
          }
        }
      }
      List<PathData> paths = new ArrayList<>();
      for (Element child : children(svg)) {
        switch (child.getLocalName()) {
          case "defs" -> {}
          case "path" -> {
            String fill =
                child.hasAttribute("fill")
                    ? child.getAttribute("fill")
                    : svg.hasAttribute("fill") ? svg.getAttribute("fill") : "#000000";
            Paint paint;
            if (fill.startsWith("url(#") && fill.endsWith(")")) {
              paint = gradients.get(fill.substring(5, fill.length() - 1));
              if (paint == null) throw new IllegalArgumentException("图标引用了未知渐变");
            } else paint = fill.equals("none") ? Color.TRANSPARENT : Color.web(fill);
            paths.add(new PathData(child.getAttribute("d"), paint));
          }
          default -> throw new IllegalArgumentException("图标包含不支持的元素：" + child.getLocalName());
        }
      }
      if (paths.isEmpty()) throw new IllegalArgumentException("图标没有路径");
      return new Drawing(box[0], box[1], box[2], box[3], List.copyOf(paths));
    } catch (Exception error) {
      throw new IllegalStateException("文件图标加载失败：" + resource, error);
    }
  }

  private static Paint gradient(Element element) {
    boolean proportional = !element.getAttribute("gradientUnits").equals("userSpaceOnUse");
    Point2D start = new Point2D(number(element, "x1", 0), number(element, "y1", 0));
    Point2D end = new Point2D(number(element, "x2", 1), number(element, "y2", 0));
    String transform = element.getAttribute("gradientTransform");
    if (!transform.isEmpty()) {
      Affine affine = new Affine();
      var matcher = TRANSFORM.matcher(transform);
      int previous = 0;
      while (matcher.find()) {
        if (!transform.substring(previous, matcher.start()).isBlank())
          throw new IllegalArgumentException("不支持的渐变变换");
        double[] values = numbers(matcher.group(2));
        if (values.length < 1 || values.length > 2) throw new IllegalArgumentException("渐变变换参数无效");
        if (matcher.group(1).equals("translate"))
          affine.appendTranslation(values[0], values.length == 2 ? values[1] : 0);
        else affine.appendScale(values[0], values.length == 2 ? values[1] : values[0]);
        previous = matcher.end();
      }
      if (!transform.substring(previous).isBlank()) throw new IllegalArgumentException("不支持的渐变变换");
      start = affine.transform(start);
      end = affine.transform(end);
    }
    List<Stop> stops = new ArrayList<>();
    for (Element stop : children(element)) {
      if (!stop.getLocalName().equals("stop")) throw new IllegalArgumentException("渐变节点无效");
      stops.add(new Stop(number(stop, "offset", 0), Color.web(stop.getAttribute("stop-color"))));
    }
    return new LinearGradient(
        start.getX(),
        start.getY(),
        end.getX(),
        end.getY(),
        proportional,
        CycleMethod.NO_CYCLE,
        stops);
  }

  private static List<Element> children(Element parent) {
    List<Element> children = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element element) children.add(element);
    return children;
  }

  private static double number(Element element, String attribute, double fallback) {
    String value = element.getAttribute(attribute);
    if (value.isEmpty()) return fallback;
    return value.endsWith("%")
        ? Double.parseDouble(value.substring(0, value.length() - 1)) / 100
        : Double.parseDouble(value);
  }

  private static double[] numbers(String value) {
    String[] parts = value.strip().split("[\\s,]+");
    double[] result = new double[parts.length];
    for (int i = 0; i < parts.length; i++) result[i] = Double.parseDouble(parts[i]);
    return result;
  }

  @Override
  protected void layoutChildren() {
    double scale = Math.min(getWidth() / drawing.width(), getHeight() / drawing.height());
    shapes
        .getTransforms()
        .setAll(
            new Affine(
                scale,
                0,
                (getWidth() - drawing.width() * scale) / 2 - drawing.x() * scale,
                0,
                scale,
                (getHeight() - drawing.height() * scale) / 2 - drawing.y() * scale));
  }

  private record PathData(String content, Paint fill) {}

  private record Drawing(double x, double y, double width, double height, List<PathData> paths) {}
}
