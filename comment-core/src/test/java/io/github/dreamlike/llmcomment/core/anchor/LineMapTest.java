package io.github.dreamlike.llmcomment.core.anchor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class LineMapTest {
  @Test
  void indexedLocationsMatchScanningAtEveryUtf16Offset() {
    Random random = new Random(481);
    for (int sample = 0; sample < 100; sample++) {
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < sample * 3; i++)
        text.append(switch (random.nextInt(6)) {
          case 0 -> "\r\n";
          case 1 -> "\r";
          case 2 -> "\n";
          case 3 -> "😀";
          case 4 -> "中";
          default -> "abc";
        });
      String value = text.toString();
      var index = LineMap.index(value);
      assertEquals(LineMap.lineCount(value), index.lineCount());
      for (int offset = 0; offset <= value.length(); offset++)
        assertEquals(LineMap.lineAt(value, offset), index.lineAt(offset), "offset=" + offset);
      assertThrows(IllegalArgumentException.class, () -> index.lineAt(-1));
      assertThrows(IllegalArgumentException.class, () -> index.lineAt(value.length() + 1));
    }
    assertThrows(NullPointerException.class, () -> LineMap.index(null));
  }
}
