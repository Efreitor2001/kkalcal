package ru.dietdiary.offline

/** Advertising frequency state belongs to this installation, never to the food diary or sync. */
internal data class AppOpenHistory(
    val hasLaunched: Boolean = false,
    val lastShownAtMillis: Long = 0L,
    val pendingId: String = "",
    val pendingAtMillis: Long = 0L
)

internal interface AppOpenHistoryStore {
    /** Null means unreadable state: advertising must fail closed. */
    fun read(): AppOpenHistory?
    /** Must durably persist before returning true. */
    fun write(value: AppOpenHistory): Boolean
}

/**
 * Entry opportunities last at most five seconds behind an explicit startup panel.
 * A durable reservation precedes show(). Only an SDK shown/impression callback records a show.
 * An uncertain reservation (process died / write failed after show) fails closed on later runs.
 */
internal class AppOpenPolicy(
    private val store: AppOpenHistoryStore,
    private val wallTime: () -> Long,
    private val uptime: () -> Long,
    private val nextId: () -> String
) {
    private var coldStartHandled = false
    private var opportunity = false
    private var startedAt = 0L
    private var activeReservation: String? = null
    private var wasShown = false

    fun beginColdStart(restored: Boolean, launcherIntent: Boolean): Boolean {
        if (coldStartHandled) return false
        coldStartHandled = true
        val history = store.read() ?: return false
        if (!history.hasLaunched) {
            store.write(history.copy(hasLaunched = true))
            return false
        }
        if (restored || !launcherIntent || !intervalAllows(history, wallTime())) return false
        startedAt = uptime()
        opportunity = true
        return true
    }

    /** Only a real eligible entry opens a window; loading itself never opens one. */
    fun beginWarmStart(
        backgroundMillis: Long,
        safeScreen: Boolean,
        externalReturn: Boolean
    ): Boolean {
        cancelOpportunity()
        if (!coldStartHandled || backgroundMillis < MIN_BACKGROUND_MILLIS || !safeScreen || externalReturn) return false
        val history = store.read() ?: return false
        if (!intervalAllows(history, wallTime())) return false
        startedAt = uptime()
        opportunity = true
        return true
    }

    fun canPreload(): Boolean = store.read()?.let { intervalAllows(it, wallTime()) } == true

    fun canLoad(): Boolean = remainingStartupMillis() > 0L

    fun remainingStartupMillis(): Long {
        val elapsed = uptime() - startedAt
        if (!opportunity || elapsed < 0L || elapsed >= STARTUP_WINDOW_MILLIS) {
            opportunity = false
            return 0L
        }
        return STARTUP_WINDOW_MILLIS - elapsed
    }

    /** initialScreen means the startup panel is visible, never the interactive diary. */
    fun reserveShow(adReady: Boolean, activityResumed: Boolean, initialScreen: Boolean): String? {
        if (!canLoad() || !adReady || !activityResumed || !initialScreen) return null
        val history = store.read() ?: run { cancelOpportunity(); return null }
        val now = wallTime()
        if (!intervalAllows(history, now)) { cancelOpportunity(); return null }
        opportunity = false
        val id = nextId().takeIf { it.isNotBlank() } ?: return null
        if (!store.write(history.copy(pendingId = id, pendingAtMillis = now))) return null
        activeReservation = id
        wasShown = false
        return id
    }

    fun cancelOpportunity() { opportunity = false }

    fun onShown(id: String) {
        if (activeReservation != id || wasShown) return
        wasShown = true
        val history = store.read() ?: return
        if (history.pendingId != id) return
        // A wall-clock rollback between show() and its callback cannot shorten the cooldown.
        val shownAt = maxOf(wallTime(), history.pendingAtMillis, history.lastShownAtMillis)
        store.write(history.copy(lastShownAtMillis = shownAt, pendingId = "", pendingAtMillis = 0L))
    }

    fun onFailedToShow(id: String) {
        if (activeReservation != id || wasShown) return
        val history = store.read() ?: return
        if (history.pendingId == id) store.write(history.copy(pendingId = "", pendingAtMillis = 0L))
        activeReservation = null
    }

    companion object {
        const val IMPRESSION_INTERVAL_MILLIS = 60L * 60L * 1000L
        const val STARTUP_WINDOW_MILLIS = 5000L
        const val MIN_BACKGROUND_MILLIS = 30_000L
        const val CACHE_TTL_MILLIS = 4L * 60L * 60L * 1000L

        fun cacheIsFresh(loadedAtUptime: Long, nowUptime: Long): Boolean =
            loadedAtUptime >= 0L && nowUptime >= loadedAtUptime && nowUptime - loadedAtUptime < CACHE_TTL_MILLIS

        fun intervalAllows(history: AppOpenHistory, now: Long): Boolean {
            if (!history.hasLaunched || now <= 0L || history.pendingId.isNotEmpty() || history.pendingAtMillis != 0L) return false
            val previous = history.lastShownAtMillis
            if (previous < 0L || now < previous) return false
            return previous == 0L || now - previous >= IMPRESSION_INTERVAL_MILLIS
        }
    }
}
