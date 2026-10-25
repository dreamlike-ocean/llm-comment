package io.github.dreamlike.llmcomment.desktop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UiScaleTest {
  @Test
  void compensatesUnscaledHighDpiScreensInQuarterSteps() {
    assertEquals(1.75, UiScale.automatic(163, 1));
    assertEquals(1, UiScale.automatic(72, 1));
    assertEquals(1, UiScale.automatic(107.9, 1));
    assertEquals(1.25, UiScale.automatic(108, 1));
    assertEquals(2.5, UiScale.automatic(480, 1));
    assertEquals(1.75, UiScale.automatic(163, 1.01));
  }

  @Test
  void avoidsDoubleCompensationAndRejectsInvalidScreenData() {
    assertEquals(1, UiScale.automatic(163, 1.011));
    assertEquals(1, UiScale.automatic(163, 2));
    for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
      assertEquals(1, UiScale.automatic(invalid, 1));
      assertEquals(1, UiScale.automatic(163, invalid));
    }
  }

  @Test
  void parsesAutomaticDecimalAndPercentageValues() {
    assertEquals(0, UiScale.parse(" Auto "));
    assertEquals(1, UiScale.parse("100%"));
    assertEquals(1.5, UiScale.parse("1.5"));
    assertEquals(0.75, UiScale.parse("75%"));
    assertEquals(3, UiScale.parse("300%"));
    assertEquals(0.75, UiScale.parse("0.75"));
    assertEquals(3, UiScale.parse("3"));
  }

  @Test
  void invalidOverridesExplainAcceptedValues() {
    for (String invalid :
        new String[] {
          null, "", "0", "74.9%", "301%", "3.01", "NaN", "Infinity", "-1", "auto%", "1.5x", "100%%"
        }) {
      var error = assertThrows(IllegalArgumentException.class, () -> UiScale.parse(invalid));
      assertTrue(error.getMessage().contains("界面缩放"));
    }
  }
}
