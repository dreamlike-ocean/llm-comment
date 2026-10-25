package io.github.dreamlike.llmcomment.core.anchor;

import static org.junit.jupiter.api.Assertions.*;

import com.github.difflib.DiffUtils;
import com.github.difflib.algorithm.myers.MyersDiffWithLinearSpace;
import com.github.difflib.patch.Patch;
import io.github.dreamlike.llmcomment.core.AnchorStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IterativeMyersDiffTest {
  @TempDir Path temporary;

  @Test
  void seededUtf16ScriptsMatchOriginalAndReconstructTarget() throws Exception {
    Random random = new Random(0x834791L);
    String alphabet = "ab中文\r\n😀";
    for (int round = 0; round < 3000; round++) {
      StringBuilder old = new StringBuilder(), now = new StringBuilder();
      for (int i = random.nextInt(100); i > 0; i--)
        old.append(alphabet.charAt(random.nextInt(alphabet.length())));
      for (int i = random.nextInt(100); i > 0; i--)
        now.append(alphabet.charAt(random.nextInt(alphabet.length())));
      List<Character> source = chars(old.toString()), target = chars(now.toString());
      Patch<Character> expected = DiffUtils.diff(source, target, new MyersDiffWithLinearSpace<>());
      Patch<Character> actual = DiffUtils.diff(source, target, new IterativeMyersDiff<>());
      assertEquals(expected.getDeltas(), actual.getDeltas(), "轮次 " + round);
      assertEquals(target, actual.applyTo(source));
      assertHierarchicalReconstruction(old.toString(), now.toString());
    }
  }

  @Test
  void skewedChineseInputsUseBoundedCoarseFallback() {
    for (int length : new int[] {10_000, 1_000_000}) {
      String large = "中文".repeat(length / 2);
      for (boolean reverse : new boolean[] {false, true}) {
        String old = reverse ? large : "甲", now = reverse ? "乙" : large;
        var edits = HierarchicalDiff.diff(old, now);
        assertEquals(1, edits.size());
        assertTrue(edits.getFirst().coarse);
        assertHierarchicalReconstruction(old, now);
      }
    }
  }

  @Test
  void budgetFallbackRecoversUniqueTextAndKeepsAmbiguity() {
    String padding = "中文".repeat(250_000);
    Anchor original = AnchorTracker.capture("甲", 1, 1);
    String unique = padding + "甲" + padding;
    Anchor recovered = AnchorTracker.relocate("甲", unique, List.of(original)).getFirst();
    assertEquals(AnchorStatus.ACTIVE, recovered.status());
    assertEquals(padding.length(), recovered.startOffset());
    assertEquals("甲", unique.substring(recovered.startOffset(), recovered.endOffset()));
    assertEquals(
        AnchorStatus.AMBIGUOUS,
        AnchorTracker.relocate("甲", padding + "甲甲" + padding, List.of(original))
            .getFirst()
            .status());
    assertEquals(
        AnchorStatus.ORPHANED,
        AnchorTracker.relocate("甲", padding + padding, List.of(original)).getFirst().status());
  }

  @Test
  void complexInputsRunOnSmallJvmStack() throws Exception {
    Path output = temporary.resolve("small-stack.log");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xss256k",
                "-Xmx128m",
                "-cp",
                System.getProperty("java.class.path"),
                SmallStackProbe.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "小栈子进程超时");
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally {
      process.destroyForcibly();
    }
  }

  public static class SmallStackProbe {
    public static void main(String[] args) throws Exception {
      // 偏斜输入检查计算量预算；小堆也约束前沿数组及工作栈的占用。
      assertHierarchicalReconstruction("甲", "中文".repeat(500_000));
      assertHierarchicalReconstruction("a", "b".repeat(6_000));
      assertHierarchicalReconstruction("a".repeat(6_000), "b");
      assertHierarchicalReconstruction("ab".repeat(999) + "x", "ba".repeat(999) + "y");
      assertHierarchicalReconstruction("a".repeat(1999), "b".repeat(1999));
      StringBuilder source = new StringBuilder();
      for (int i = 0; i < 20_000; i++) source.append(i).append(":中文😀\r\n");
      String old = source.toString();
      String now = old.replace("00:", "00:改").replace("999:", "999:新增😀");
      assertHierarchicalReconstruction(old, now);
      System.out.println("非递归 diff 小栈检查通过：-Xss256k -Xmx128m");
    }
  }

  private static void assertHierarchicalReconstruction(String old, String now) {
    StringBuilder result = new StringBuilder();
    int previousOld = 0, previousNew = 0;
    for (HierarchicalDiff.Edit edit : HierarchicalDiff.diff(old, now)) {
      assertTrue(edit.start >= previousOld);
      int unchanged = edit.start - previousOld;
      result.append(old, previousOld, edit.start);
      previousNew += unchanged;
      result.append(now, previousNew, previousNew + edit.inserted);
      previousOld = edit.start + edit.removed;
      previousNew += edit.inserted;
    }
    result.append(old, previousOld, old.length());
    assertEquals(now, result.toString());
  }

  static List<Character> chars(String value) {
    return new AbstractList<>() {
      public Character get(int i) {
        return value.charAt(i);
      }

      public int size() {
        return value.length();
      }
    };
  }
}
