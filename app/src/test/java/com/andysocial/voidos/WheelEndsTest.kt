package com.andysocial.voidos

import com.andysocial.voidos.ui.wheelClamp
import com.andysocial.voidos.ui.wheelStart
import com.andysocial.voidos.ui.wheelStep
import org.junit.Assert.assertEquals
import org.junit.Test

/** The side wheels stop at their first and last icon: dragging further does nothing. */
class WheelEndsTest {
    @Test fun cannotDragPastLastOrFirstIcon() {
        for (n in listOf(2, 5, 7, 9)) {
            val first = wheelStart(n)
            val last = first + (n - 1) * wheelStep(n)
            assertEquals(last, wheelClamp(last + 1.2f, first, last), 1e-6f)
            assertEquals(first, wheelClamp(first - 1.2f, first, last), 1e-6f)
            val mid = (first + last) / 2f
            assertEquals(mid, wheelClamp(mid, first, last), 1e-6f)
        }
    }
}
