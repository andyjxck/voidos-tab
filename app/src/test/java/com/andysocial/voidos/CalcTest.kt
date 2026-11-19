package com.andysocial.voidos

import com.andysocial.voidos.ui.Calc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The calculator's sums, worked out with exact decimals. */
class CalcTest {
    private fun f(s: String) = Calc.format(Calc.eval(s))

    @Test fun basics() {
        assertEquals("4", f("2+2"))
        assertEquals("14", f("2+3×4"))
        assertEquals("20", f("(2+3)×4"))
        assertEquals("2.5", f("5÷2"))
        assertEquals("-3", f("2−5"))
    }

    @Test fun decimalsStayExact() {
        // binary doubles give 0.30000000000000004 here
        assertEquals("0.3", f("0.1+0.2"))
        assertEquals("1.1", f("3.3÷3"))
    }

    @Test fun percentages() {
        assertEquals("0.5", f("50%"))
        assertEquals("20", f("200×10%"))
    }

    @Test fun groupingAndErrors() {
        assertEquals("1,234,567", f("1234567"))
        assertEquals("Error", f("5÷0"))
        assertNull(Calc.eval("2+"))
    }

    @Test fun keys() {
        assertEquals("", Calc.press("12+3", "AC"))
        assertEquals("12+", Calc.press("12+3", "⌫"))
        assertEquals("12+−3", Calc.press("12+3", "±"))
        assertEquals("(", Calc.press("", "( )"))
    }
}

/** When VoidOS's low battery warning shows. */
class LowBatteryTest {
    private fun step(was: Int, now: Int, charging: Boolean = false) = com.andysocial.voidos.ui.lowBatteryStep(was, now, charging)

    @Test fun warnsOnceAtEachStepOnTheWayDown() {
        assertEquals(20, step(21, 20))
        assertNull(step(20, 19))
        assertEquals(10, step(11, 10))
        assertEquals(5, step(6, 5))
        assertEquals(5, step(12, 4))
    }

    @Test fun neverWhileChargingOrUnknown() {
        assertNull(step(21, 20, charging = true))
        assertNull(step(-1, 5))
        assertNull(step(30, 25))
    }
}

/** The switcher's grid: every card fits on screen when it can. */
class SwitcherLayoutTest {
    @Test fun sixAppsFitWithoutScrollingInLandscape() {
        // landscape grid area on the tablet: about 757 x 300 dp
        val (cols, h) = com.andysocial.voidos.ui.switcherLayout(757f, 300f, 6)
        val rows = (6 + cols - 1) / cols
        org.junit.Assert.assertTrue("cols=$cols h=$h", rows * h + (rows - 1) * 20f <= 300f && h >= 136f)
        org.junit.Assert.assertTrue((757f - 20f * (cols - 1)) / cols >= 170f)
    }

    @Test fun portraitKeepsFullSizeCards() {
        val (cols, h) = com.andysocial.voidos.ui.switcherLayout(437f, 650f, 4)
        assertEquals(2, cols); assertEquals(190f, h, 0.01f)
    }

    @Test fun tooManyScrollsAtFullSize() {
        val (_, h) = com.andysocial.voidos.ui.switcherLayout(757f, 300f, 30)
        assertEquals(190f, h, 0.01f)
    }
}
