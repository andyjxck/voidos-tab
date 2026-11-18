package com.andysocial.voidos

import androidx.compose.ui.geometry.Offset
import com.andysocial.voidos.ui.FaceBox
import com.andysocial.voidos.ui.FaceTarget
import com.andysocial.voidos.ui.Lasso
import com.andysocial.voidos.ui.groupDelta
import com.andysocial.voidos.ui.lassoPick
import org.junit.Assert.assertEquals
import org.junit.Test

/** Picking several widgets with the double-tap box and moving them together. */
class GroupMoveTest {
    private fun t(id: Long, cx: Float, cy: Float) = FaceTarget(id, FaceBox(cx, cy, 100f, 60f, 0f), false, 10f, 500f, 10f, 500f)

    @Test fun boxPicksWhatsInside() {
        val ts = listOf(t(1, 100f, 100f), t(2, 300f, 120f), t(3, 800f, 500f))
        // drawn either way round, it picks by each widget's middle
        assertEquals(setOf(1L, 2L), lassoPick(Lasso(Offset(350f, 200f), Offset(50f, 50f)), ts))
        assertEquals(emptySet<Long>(), lassoPick(Lasso(Offset(400f, 400f), Offset(420f, 420f)), ts))
    }

    @Test fun groupMovesFreelyInTheMiddle() {
        val boxes = listOf(FaceBox(300f, 300f, 100f, 60f, 0f), FaceBox(500f, 300f, 100f, 60f, 0f))
        assertEquals(Offset(40f, -25f), groupDelta(boxes, Offset(40f, -25f), 1000f, 700f))
    }

    @Test fun groupStopsTogetherAtTheEdge() {
        // the left one can only hang a quarter over the left edge: centre down to 25
        val boxes = listOf(FaceBox(100f, 300f, 100f, 60f, 0f), FaceBox(400f, 300f, 100f, 60f, 0f))
        val d = groupDelta(boxes, Offset(-500f, 0f), 1000f, 700f)
        assertEquals(-75f, d.x, 0.01f)
        assertEquals(0f, d.y, 0.01f)
    }
}
