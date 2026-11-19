package com.andysocial.voidos

import android.os.UserManager
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guest mode and Kids Space are out of VoidOS, and Android's own ways back in stay closed. */
class GuestKidsGoneTest {
    @Test fun androidGuestAndUserSwitchingAreBlocked() {
        assertTrue(UserManager.DISALLOW_ADD_USER in Policy.noGuestRestrictions)
        assertTrue(UserManager.DISALLOW_USER_SWITCH in Policy.noGuestRestrictions)
    }

    @Test fun kidsSpaceAppIsKeptHidden() {
        assertTrue("com.google.android.apps.kids.home" in Policy.removedKidsApps)
    }

    @Test fun noGuestOrKidsEntryLeftInTheApp() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).firstOrNull { it.isDirectory } ?: return
        val res = listOf(File("src/main/res"), File("app/src/main/res")).firstOrNull { it.isDirectory }
        val words = Regex("""guest ?mode|kids ?space|GuestGate|GuestAndKids|store\.guest""", RegexOption.IGNORE_CASE)
        val hits = (src.walk() + (res?.walk() ?: emptySequence()))
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .flatMap { f -> f.readLines().withIndex().filter { (_, l) -> words.containsMatchIn(l) && !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*") }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" } }
            .toList()
        assertTrue("Guest/Kids still in the app:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
