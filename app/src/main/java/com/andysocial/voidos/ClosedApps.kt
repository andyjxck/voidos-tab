package com.andysocial.voidos

/**
 * The closed-apps record behind the switcher, saved as "pkg=time,pkg=time". Pure, so it's tested
 * on its own.
 */
object ClosedApps {
    /** How far back the switcher reads Android's usage records. Older closes can't matter. */
    const val WINDOW_MS = 2 * 86_400_000L

    fun parse(s: String?): Map<String, Long> =
        s.orEmpty().split(',').mapNotNull { part ->
            val pkg = part.substringBefore('=', "").trim()
            val at = part.substringAfter('=', "").trim().toLongOrNull()
            if (pkg.isEmpty() || at == null) null else pkg to at
        }.toMap()

    fun format(m: Map<String, Long>): String = m.entries.joinToString(",") { "${it.key}=${it.value}" }

    /**
     * Drops closes from before the usage window (with a day's margin): every usage record the
     * switcher can still see is newer than them, so they no longer hide anything.
     */
    fun prune(m: Map<String, Long>, now: Long): Map<String, Long> =
        m.filterValues { it >= now - WINDOW_MS - 86_400_000L }

    /**
     * Pure: whether an app Android last saw at the front at [lastFront] is still open as far as
     * the switcher goes: it came to the front after you last closed it and after the last Close all.
     */
    fun shownFromSystem(pkg: String, lastFront: Long, closed: Map<String, Long>, clearedAt: Long): Boolean =
        lastFront > maxOf(closed[pkg] ?: Long.MIN_VALUE, clearedAt)
}
