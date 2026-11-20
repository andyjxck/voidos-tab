package com.andysocial.voidos

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * 1.20.0: holding power opens VoidOS's power menu and nothing else. Android's white
 * "Power off / Restart" dialog is never drawn at all.
 *
 * How (Android 10, PhoneWindowManager.powerLongPress): Android's setting for a long press on
 * power (Settings.Global "power_button_long_press") is switched from 1, Android's dialog, to
 * 5, "the assistant". On a hold Android then (1) marks the press as handled, so letting go
 * doesn't send the screen to sleep, (2) tells every app with reason "assist", which
 * VoidKeysService takes as the signal to open VoidOS's menu straight away, and (3) asks the
 * system UI to open the assistant, which VoidOS makes this invisible screen, so Google's
 * assistant never opens instead. A short press is untouched: it still sleeps and wakes.
 *
 * Always a way out:
 *  - If VoidOS crashes, Android restarts its accessibility service by itself, and the next hold
 *    opens the menu again. If the service isn't running when this screen opens, it puts
 *    Android's own dialog back at once, so the next hold shows that.
 *  - Turning "VoidOS power menu" off, or turning the service off, puts Android's back.
 *  - The system zip's watchdog puts Android's back if VoidOS or its service is ever missing,
 *    and removing the zip in Magisk puts it back too.
 */
object PowerHold {
    const val SETTING = "power_button_long_press"
    const val STOCK = 1
    const val VOIDOS = 5
    const val ASSISTANT = "com.andysocial.voidos/com.andysocial.voidos.PowerHoldActivity"
    /** The reason Android gives when it hands a hold to "the assistant". */
    const val REASON = "assist"

    /** Pure: the root commands that hand a long press on power to VoidOS. */
    fun takeOverCommands(): List<String> = listOf(
        "settings put global $SETTING $VOIDOS",
        "settings put secure assistant $ASSISTANT",
    )

    /** Pure: the root commands that give it back to Android. [prevAssistant] is what the assistant was before. */
    fun giveBackCommands(prevAssistant: String?): List<String> = listOf(
        "settings put global $SETTING $STOCK",
        if (prevAssistant.isNullOrBlank() || prevAssistant == "null" || prevAssistant == ASSISTANT) "settings delete secure assistant"
        else "settings put secure assistant $prevAssistant",
    )

    /**
     * Pure: whether a "close system dialogs" signal is a power hold VoidOS should answer.
     * [lastAt] stops the signal and this screen opening the menu twice for one hold.
     */
    fun isHold(reason: String?, voidPower: Boolean, now: Long, lastAt: Long): Boolean =
        voidPower && reason == REASON && now - lastAt > 800L
}

/** The "assistant" Android opens on a hold: it opens VoidOS's power menu, if it isn't up already, and goes. */
class PowerHoldActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = VoidKeysService.instance
        if (svc != null) {
            svc.main.post { svc.onPowerHold() }
        } else {
            // VoidOS's service isn't running: Android's own dialog comes back for the next hold
            Thread { Su.run(PowerHold.giveBackCommands(null).joinToString("; ")) }.start()
            Toast.makeText(this, "Hold power again for the power menu", Toast.LENGTH_SHORT).show()
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
