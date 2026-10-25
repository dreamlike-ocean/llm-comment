/*
 * Copyright 2021 java-diff-utils.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dreamlike.llmcomment.core.anchor;

import com.github.difflib.algorithm.Change;
import com.github.difflib.algorithm.DiffAlgorithmI;
import com.github.difflib.algorithm.DiffAlgorithmListener;
import com.github.difflib.patch.DeltaType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** 基于 java-diff-utils 4.15 的线性空间 Myers，使用显式工作栈避免输入相关的调用栈增长。 */
final class IterativeMyersDiff<T> implements DiffAlgorithmI<T> {

  // 乘积预算不能约束偏斜输入的 Myers 前沿；同时限制实际推进和比较次数。
  private static final long WORK_BUDGET = 32_000_000L;

  static final class BudgetExceeded extends RuntimeException {
    BudgetExceeded() {
      super("字符差异工作量超出预算", null, false, false);
    }
  }

  private final BiPredicate<T, T> equalizer;

  public IterativeMyersDiff() {
    equalizer = Object::equals;
  }

  public IterativeMyersDiff(final BiPredicate<T, T> equalizer) {
    Objects.requireNonNull(equalizer, "equalizer must not be null");
    this.equalizer = equalizer;
  }

  @Override
  public List<Change> computeDiff(List<T> source, List<T> target, DiffAlgorithmListener progress) {
    Objects.requireNonNull(source, "source list must not be null");
    Objects.requireNonNull(target, "target list must not be null");

    if (progress != null) {
      progress.diffStart();
    }

    long minimumDepth = (Math.abs((long) source.size() - target.size()) + 1) / 2;
    // 前沿相交至少需要走完这些层；先判断，避免极偏斜输入分配大数组。
    if (minimumDepth * (minimumDepth + 1) > WORK_BUDGET) throw new BudgetExceeded();
    DiffData data = new DiffData(source, target);

    int maxIdx = source.size() + target.size();

    buildScript(
        data,
        0,
        source.size(),
        0,
        target.size(),
        idx -> {
          if (progress != null) {
            progress.diffStep(idx, maxIdx);
          }
        });

    if (progress != null) {
      progress.diffEnd();
    }
    return data.script;
  }

  private void buildScript(
      DiffData data, int start1, int end1, int start2, int end2, Consumer<Integer> progress) {
    ArrayDeque<Region> pending = new ArrayDeque<>();
    pending.push(new Region(start1, end1, start2, end2));
    while (!pending.isEmpty()) {
      Region region = pending.pop();
      start1 = region.start1();
      end1 = region.end1();
      start2 = region.start2();
      end2 = region.end2();
      if (progress != null) {
        progress.accept((end1 - start1) / 2 + (end2 - start2) / 2);
      }
      final Snake middle = getMiddleSnake(data, start1, end1, start2, end2);
      if (middle == null
          || middle.start == end1 && middle.diag == end1 - end2
          || middle.end == start1 && middle.diag == start1 - start2) {
        data.step((long) end1 - start1 + end2 - start2);
        int i = start1;
        int j = start2;
        while (i < end1 || j < end2) {
          if (i < end1 && j < end2 && equalizer.test(data.source.get(i), data.target.get(j))) {
            ++i;
            ++j;
          } else {
            if (end1 - start1 > end2 - start2) {
              if (data.script.isEmpty()
                  || data.script.get(data.script.size() - 1).endOriginal != i
                  || data.script.get(data.script.size() - 1).deltaType != DeltaType.DELETE) {
                data.script.add(new Change(DeltaType.DELETE, i, i + 1, j, j));
              } else {
                data.script.set(
                    data.script.size() - 1,
                    data.script.get(data.script.size() - 1).withEndOriginal(i + 1));
              }
              ++i;
            } else {
              if (data.script.isEmpty()
                  || data.script.get(data.script.size() - 1).endRevised != j
                  || data.script.get(data.script.size() - 1).deltaType != DeltaType.INSERT) {
                data.script.add(new Change(DeltaType.INSERT, i, i, j, j + 1));
              } else {
                data.script.set(
                    data.script.size() - 1,
                    data.script.get(data.script.size() - 1).withEndRevised(j + 1));
              }
              ++j;
            }
          }
        }
      } else {
        // 后半段先入栈，保持原算法从左至右的合并顺序和平局选择。
        pending.push(new Region(middle.end, end1, middle.end - middle.diag, end2));
        pending.push(new Region(start1, middle.start, start2, middle.start - middle.diag));
      }
    }
  }

  private record Region(int start1, int end1, int start2, int end2) {}

  private Snake getMiddleSnake(DiffData data, int start1, int end1, int start2, int end2) {
    final int m = end1 - start1;
    final int n = end2 - start2;
    if (m == 0 || n == 0) {
      return null;
    }

    final int delta = m - n;
    final int sum = n + m;
    final int offset = (sum % 2 == 0 ? sum : sum + 1) / 2;
    data.vDown[1 + offset] = start1;
    data.vUp[1 + offset] = end1 + 1;

    for (int d = 0; d <= offset; ++d) {
      // 每层两个方向各 d+1 个前沿，每个前沿至多一次失败比较；批量扣除减少热循环开销。
      data.step(4L * (d + 1));
      // 正向前沿
      for (int k = -d; k <= d; k += 2) {
        // 推进一步

        final int i = k + offset;
        if (k == -d || k != d && data.vDown[i - 1] < data.vDown[i + 1]) {
          data.vDown[i] = data.vDown[i + 1];
        } else {
          data.vDown[i] = data.vDown[i - 1] + 1;
        }

        int x = data.vDown[i];
        int y = x - start1 + start2 - k;

        int initialX = x;
        while (x < end1 && y < end2 && equalizer.test(data.source.get(x), data.target.get(y))) {
          data.vDown[i] = ++x;
          ++y;
        }
        data.step(x - initialX);
        // 检查前沿相交
        if (delta % 2 != 0 && delta - d <= k && k <= delta + d) {
          if (data.vUp[i - delta] <= data.vDown[i]) {
            return buildSnake(data, data.vUp[i - delta], k + start1 - start2, end1, end2);
          }
        }
      }

      // 反向前沿
      for (int k = delta - d; k <= delta + d; k += 2) {
        // 推进一步
        final int i = k + offset - delta;
        if (k == delta - d || k != delta + d && data.vUp[i + 1] <= data.vUp[i - 1]) {
          data.vUp[i] = data.vUp[i + 1] - 1;
        } else {
          data.vUp[i] = data.vUp[i - 1];
        }

        int x = data.vUp[i] - 1;
        int y = x - start1 + start2 - k;
        int initialX = x;
        while (x >= start1
            && y >= start2
            && equalizer.test(data.source.get(x), data.target.get(y))) {
          data.vUp[i] = x--;
          y--;
        }
        data.step(initialX - x);
        // 检查前沿相交
        if (delta % 2 == 0 && -d <= k && k <= d) {
          if (data.vUp[i] <= data.vDown[i + delta]) {
            return buildSnake(data, data.vUp[i], k + start1 - start2, end1, end2);
          }
        }
      }
    }

    // Myers 路径应当总能相交。
    throw new IllegalStateException("could not find a diff path");
  }

  private Snake buildSnake(
      DiffData data, final int start, final int diag, final int end1, final int end2) {
    int end = start;
    while (end - diag < end2
        && end < end1
        && equalizer.test(data.source.get(end), data.target.get(end - diag))) {
      ++end;
    }
    data.step((long) end - start + 1);
    return new Snake(start, end, diag);
  }

  private class DiffData {
    private long remaining = WORK_BUDGET;

    void step(long work) {
      remaining -= work;
      if (remaining < 0) throw new BudgetExceeded();
    }

    final int size;
    final int[] vDown;
    final int[] vUp;
    final List<Change> script;
    final List<T> source;
    final List<T> target;

    public DiffData(List<T> source, List<T> target) {
      this.source = source;
      this.target = target;
      size = source.size() + target.size() + 2;
      vDown = new int[size];
      vUp = new int[size];
      script = new ArrayList<>();
    }
  }

  private class Snake {

    final int start;
    final int end;
    final int diag;

    public Snake(final int start, final int end, final int diag) {
      this.start = start;
      this.end = end;
      this.diag = diag;
    }
  }
}
