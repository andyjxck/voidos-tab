package com.andysocial.voidos

import androidx.compose.runtime.mutableStateOf

/**
 * 1.20.0: the one list of apps that are open right now. The app switcher and the right edge
 * drawer both show exactly this, so they can never disagree.
 *
 * The bug it fixes ("I closed all my apps and the app switcher brings them all back"): the
 * switcher used to list every app Android had seen at the front in the last two days, and
 * closing an app only took it off VoidOS's own list, so Android's records brought it straight
 * back. Now an app is open only when it last came to the front (in Android's records, or opened
 * from VoidOS) after all three of: the tablet started, it was last closed, and the last Close all.
 * So a closed app stays gone through reopening the switcher, going home and a restart, and
 * comes back only when it's opened again. VoidOS itself and any other home app never show.
 */
object OpenApps {
    /** The open apps, newest first. Refreshed off the main thread by [com.andysocial.voidos.ui.Switcher.recent]. */
    val list = mutableStateOf<List<String>>(emptyList())

    /** A close: it leaves the list at once, before the next refresh. */
    fun drop(pkg: String) { if (pkg in list.value) list.value = list.value - pkg }
    fun clear() { list.value = emptyList() }
    fun set(pkgs: List<String>) { if (pkgs != list.value) list.value = pkgs }

    /**
     * Pure: whether an app is open. [lastFront] is when Android last saw it at the front,
     * [opened] when VoidOS last opened it (either may be unknown).
     */
    fun isOpen(lastFront: Long?, opened: Long?, closedAt: Long?, clearedAt: Long, bootAt: Long): Boolean {
        val last = maxOf(lastFront ?: Long.MIN_VALUE, opened ?: Long.MIN_VALUE)
        if (last == Long.MIN_VALUE) return false
        return last > maxOf(closedAt ?: Long.MIN_VALUE, clearedAt, bootAt)
    }

    /**
     * Pure: the open apps, newest first. [system] is Android's last-at-the-front times,
     * [opened] VoidOS's own, [launchable] the apps that can be opened, [excluded] home apps
     * (VoidOS included) and the system UI.
     */
    fun pick(
        system: Map<String, Long>, opened: Map<String, Long>, launchable: Set<String>, excluded: Set<String>,
        closed: Map<String, Long>, clearedAt: Long, bootAt: Long,
    ): List<String> =
        (system.keys + opened.keys)
            .filter { it !in excluded && it in launchable }
            .filter { isOpen(system[it], opened[it], closed[it], clearedAt, bootAt) }
            .sortedByDescending { maxOf(system[it] ?: Long.MIN_VALUE, opened[it] ?: Long.MIN_VALUE) }
}
