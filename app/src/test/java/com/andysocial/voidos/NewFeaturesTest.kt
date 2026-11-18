package com.andysocial.voidos

import androidx.compose.ui.graphics.Color
import com.andysocial.voidos.ui.GlassIcons
import com.andysocial.voidos.ui.eventText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the 1.14 features: Wi-Fi Direct sharing, casting, battery use, icons, calendar. */
class NewFeaturesTest {
    @Test fun wifiQrEscapes() {
        assertEquals("WIFI:T:WPA;S:DIRECT-ab VoidOS;P:pa\\;ss\\:1;;", Share.wifiQr("DIRECT-ab VoidOS", "pa;ss:1"))
    }

    @Test fun receivedNamesAreSafe() {
        assertEquals("passwd", Share.safeName("../../etc/passwd"))
        assertEquals("photo_1_.jpg", Share.safeName("photo<1>.jpg"))
        assertEquals("file", Share.safeName("..."))
        assertTrue(Share.safeName("a".repeat(300)).length <= 120)
    }

    @Test fun sizesReadNaturally() {
        assertEquals("512 B", Share.size(512))
        assertEquals("2 KB", Share.size(2048))
        assertEquals("1.5 MB", Share.size(1_572_864))
    }

    @Test fun castParsesHelperOutput() {
        val st = Cast.parse(Su.Out(0, "STATE\t3\t2\taa:bb\nTV\taa:bb\tLiving room\t1\t1\nTV\tcc:dd\t\t0\t0\n"))
        assertEquals(3, st.feature); assertEquals(2, st.active); assertEquals("aa:bb", st.activeAddress)
        assertEquals(listOf("Living room", "cc:dd"), st.tvs.map { it.name })
        assertTrue(st.supported); assertNull(st.error)
        val noRoot = Cast.parse(Su.Out(-1, ""))
        assertTrue(noRoot.error!!.contains("root"))
        assertEquals("SecurityException: nope", Cast.parse(Su.Out(1, "ERR\tSecurityException: nope")).error)
    }

    @Test fun batteryUseReadsTheEstimate() {
        val dump = """
            Statistics since last charge:
              Estimated power use (mAh):
                Capacity: 5000, Computed drain: 812, actual drain: 700-900
                Screen: 320
                Uid u0a123: 145.6 ( cpu=100 )
                Uid 1000: 80.1
                Uid u0a77: 0.01
                Uid u0a9: 12.5
            
              All partial wake locks:
        """.trimIndent()
        val got = Extras.parseUse(dump)
        assertEquals(listOf(10123 to 145.6, 10009 to 12.5), got)
        assertTrue(Extras.parseUse("nothing here").isEmpty())
    }

    @Test fun iconTintPrefersAColourfulBackground() {
        val red = 0xFFE53935.toInt(); val white = 0xFFFFFFFF.toInt(); val green = 0xFF1DB954.toInt()
        assertEquals(Color(red), GlassIcons.chooseTint(red, green))
        // a white background says nothing, so the symbol's colour wins
        assertEquals(Color(green), GlassIcons.chooseTint(white, green))
        // nothing colourful at all: a neutral grey drop
        assertEquals(Color(0xFF7A7F8A.toInt()), GlassIcons.chooseTint(white, null))
    }

    @Test fun nextEventLine() {
        val now = java.util.Calendar.getInstance().apply { set(2026, 10, 18, 9, 0, 0) }.timeInMillis
        val today = now + 3 * 3_600_000L
        assertEquals("Today 12:00  ·  Dentist", eventText(Extras.Event("Dentist", today, false), true, now))
        assertEquals("Tomorrow  ·  Birthday", eventText(Extras.Event("Birthday", now + 86_400_000L, true), true, now))
    }
}
