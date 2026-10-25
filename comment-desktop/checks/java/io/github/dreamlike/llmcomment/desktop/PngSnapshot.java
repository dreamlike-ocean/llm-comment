package io.github.dreamlike.llmcomment.desktop;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javafx.scene.image.WritableImage;

/** 保存当前 JavaFX 场景截图，PNG 编码使用 JDK 的压缩和校验 API。 */
final class PngSnapshot {
  private PngSnapshot() {}

  static void write(WritableImage image, Path destination) throws IOException {
    int width = (int) image.getWidth(), height = (int) image.getHeight();
    ByteArrayOutputStream pixels = new ByteArrayOutputStream();
    try (DeflaterOutputStream compressed = new DeflaterOutputStream(pixels)) {
      var reader = image.getPixelReader();
      byte[] row = new byte[width * 4 + 1];
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          int argb = reader.getArgb(x, y), at = x * 4 + 1;
          row[at] = (byte) (argb >>> 16);
          row[at + 1] = (byte) (argb >>> 8);
          row[at + 2] = (byte) argb;
          row[at + 3] = (byte) (argb >>> 24);
        }
        compressed.write(row);
      }
    }
    Path absolute = destination.toAbsolutePath();
    Files.createDirectories(absolute.getParent());
    try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(absolute))) {
      out.writeLong(0x89504e470d0a1a0aL);
      ByteArrayOutputStream header = new ByteArrayOutputStream();
      try (DataOutputStream fields = new DataOutputStream(header)) {
        fields.writeInt(width);
        fields.writeInt(height);
        fields.write(new byte[] {8, 6, 0, 0, 0});
      }
      chunk(out, "IHDR", header.toByteArray());
      chunk(out, "IDAT", pixels.toByteArray());
      chunk(out, "IEND", new byte[0]);
    }
  }

  private static void chunk(DataOutputStream out, String name, byte[] data) throws IOException {
    byte[] type = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    CRC32 crc = new CRC32();
    crc.update(type);
    crc.update(data);
    out.writeInt(data.length);
    out.write(type);
    out.write(data);
    out.writeInt((int) crc.getValue());
  }
}
