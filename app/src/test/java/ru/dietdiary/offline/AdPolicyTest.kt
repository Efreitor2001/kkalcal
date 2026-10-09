package ru.dietdiary.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPolicyTest {
    private class MemoryStore(var value: AppOpenHistory? = AppOpenHistory(hasLaunched = true)) : AppOpenHistoryStore {
        var writesAllowed = true
        override fun read() = value
        override fun write(value: AppOpenHistory): Boolean {
            if (!writesAllowed) return false
            this.value = value
            return true
        }
    }
    private class Clock(var wall: Long = 1_800_000_000_000L, var uptime: Long = 100L)
    private fun policy(store: MemoryStore, clock: Clock = Clock()) =
        AppOpenPolicy(store, { clock.wall }, { clock.uptime }, { "test-reservation" })
    private fun AppOpenPolicy.begin() = beginColdStart(restored = false, launcherIntent = true)
    private fun AppOpenPolicy.reserve() = reserveShow(adReady = true, activityResumed = true, initialScreen = true)

    @Test fun firstInstallationLaunchIsAlwaysFreeAndPersisted() {
        val store = MemoryStore(AppOpenHistory())
        val first = policy(store)
        assertFalse(first.begin())
        assertNull(first.reserve())
        assertTrue(store.value!!.hasLaunched)
        assertEquals(0L, store.value!!.lastShownAtMillis)
        assertTrue(policy(store).begin())
    }

    @Test fun firstLaunchWriteFailureKeepsAdvertisingDisabled() {
        val store = MemoryStore(AppOpenHistory()).apply { writesAllowed = false }
        assertFalse(policy(store).begin())
        assertFalse(policy(store).begin())
        assertFalse(store.value!!.hasLaunched)
    }

    @Test fun coldOpportunityNotRepeatedAndRestoredOrDeepLinkSkipped() {
        val gate = policy(MemoryStore())
        assertTrue(gate.begin())
        gate.cancelOpportunity()
        assertFalse(gate.begin())
        assertFalse(policy(MemoryStore()).beginColdStart(restored = true, launcherIntent = true))
        assertFalse(policy(MemoryStore()).beginColdStart(restored = false, launcherIntent = false))
    }

    @Test fun unreadableHistoryFailsClosed() {
        assertFalse(policy(MemoryStore(null)).begin())
    }

    @Test fun reservationIsDurableBeforeShowAndIsNotAnActualImpression() {
        val store = MemoryStore()
        val clock = Clock()
        val gate = policy(store, clock)
        assertTrue(gate.begin())
        val token = gate.reserve()
        assertNotNull(token)
        assertEquals(token, store.value!!.pendingId)
        assertEquals(clock.wall, store.value!!.pendingAtMillis)
        assertEquals(0L, store.value!!.lastShownAtMillis)
        assertNull(gate.reserve())
        clock.wall += 125
        gate.onShown(token!!)
        assertEquals(clock.wall, store.value!!.lastShownAtMillis)
        assertEquals("", store.value!!.pendingId)
    }

    @Test fun failedReservationWritePreventsShow() {
        val store = MemoryStore().apply { writesAllowed = false }
        val gate = policy(store)
        assertTrue(gate.begin())
        assertNull(gate.reserve())
        assertEquals("", store.value!!.pendingId)
    }

    @Test fun actualShowStartsAnExactOneHourCooldown() {
        val store = MemoryStore()
        val clock = Clock()
        val gate = policy(store, clock)
        assertTrue(gate.begin())
        gate.onShown(gate.reserve()!!)
        val shownAt = clock.wall
        clock.wall = shownAt + 3_600_000L - 1
        assertFalse(policy(store, clock).begin())
        clock.wall += 1
        assertTrue(policy(store, clock).begin())
    }

    @Test fun callbacksDoNotShortenCooldownAfterClockRollback() {
        val store = MemoryStore()
        val clock = Clock()
        val gate = policy(store, clock)
        assertTrue(gate.begin())
        val token = gate.reserve()!!
        val reservedAt = clock.wall
        clock.wall -= 100_000
        gate.onShown(token)
        assertEquals(reservedAt, store.value!!.lastShownAtMillis)
        assertFalse(policy(store, clock).begin())
    }

    @Test fun diaryVisibilityTouchOrNavigationCancellationRejectsLateAd() {
        val gate = policy(MemoryStore())
        assertTrue(gate.begin())
        gate.cancelOpportunity()
        assertFalse(gate.canLoad())
        assertNull(gate.reserve())
    }

    @Test fun startupDeadlineIsExactlyFiveSecondsAndRejectsLateShow() {
        val clock = Clock()
        val gate = policy(MemoryStore(), clock)
        assertTrue(gate.begin())
        assertEquals(5_000L, gate.remainingStartupMillis())
        clock.uptime += 4_999L
        assertTrue(gate.canLoad())
        assertEquals(1L, gate.remainingStartupMillis())
        clock.uptime += 1L
        assertFalse(gate.canLoad())
        assertEquals(0L, gate.remainingStartupMillis())
        assertNull(gate.reserve())
    }

    @Test fun readinessForegroundAndInitialScreenAreAllRequired() {
        val gate = policy(MemoryStore())
        assertTrue(gate.begin())
        assertNull(gate.reserveShow(adReady = false, activityResumed = true, initialScreen = true))
        assertNull(gate.reserveShow(adReady = true, activityResumed = false, initialScreen = true))
        assertNull(gate.reserveShow(adReady = true, activityResumed = true, initialScreen = false))
        assertNotNull(gate.reserve())
    }

    @Test fun explicitShowFailureDoesNotRecordImpressionOrRetryInTheSameEntry() {
        val store = MemoryStore()
        val gate = policy(store)
        assertTrue(gate.begin())
        gate.onFailedToShow(gate.reserve()!!)
        assertEquals(0L, store.value!!.lastShownAtMillis)
        assertEquals("", store.value!!.pendingId)
        assertNull(gate.reserve())
        assertFalse(gate.begin())
        assertTrue(policy(store).begin())
    }

    @Test fun failureAfterShownCannotEraseTheActualShowOrDuplicateIt() {
        val store = MemoryStore()
        val clock = Clock()
        val gate = policy(store, clock)
        assertTrue(gate.begin())
        val token = gate.reserve()!!
        gate.onShown(token)
        val shownAt = clock.wall
        clock.wall += 1000
        gate.onShown(token)
        gate.onFailedToShow(token)
        assertEquals(shownAt, store.value!!.lastShownAtMillis)
    }

    @Test fun processDeathOrFailedShownWriteKeepsReservationFailClosed() {
        val store = MemoryStore()
        val clock = Clock()
        val gate = policy(store, clock)
        assertTrue(gate.begin())
        val token = gate.reserve()!!
        store.writesAllowed = false
        gate.onShown(token)
        clock.wall += AppOpenPolicy.IMPRESSION_INTERVAL_MILLIS * 2
        assertFalse(policy(store, clock).begin())
        assertEquals(token, store.value!!.pendingId)
    }

    @Test fun staleCallbacksCannotClearAnotherReservation() {
        val store = MemoryStore()
        val gate = policy(store)
        assertTrue(gate.begin())
        val token = gate.reserve()!!
        gate.onFailedToShow("stale")
        gate.onShown("stale")
        assertEquals(token, store.value!!.pendingId)
        assertEquals(0L, store.value!!.lastShownAtMillis)
    }

    @Test fun loadFailureNeverConsumesHourlyImpressionAllowance() {
        val store = MemoryStore()
        val gate = policy(store)
        assertTrue(gate.begin())
        gate.cancelOpportunity()
        assertEquals(AppOpenHistory(hasLaunched = true), store.value)
        assertTrue(policy(store).begin())
    }

    @Test fun warmOpportunityRequiresThirtySecondsInBackground() {
        val clock = Clock()
        val gate = policy(MemoryStore(), clock)
        gate.begin()
        gate.cancelOpportunity()
        clock.uptime += 30_000
        assertFalse(gate.beginWarmStart(29_999, safeScreen = true, externalReturn = false))
        assertNull(gate.reserve())
        assertTrue(gate.beginWarmStart(30_000, safeScreen = true, externalReturn = false))
        assertNotNull(gate.reserve())
    }

    @Test fun firstInstallationHasNoWarmOpportunityUntilInitialEntryWasRegistered() {
        val store = MemoryStore(AppOpenHistory())
        val gate = policy(store)
        assertFalse(gate.beginWarmStart(30_000, true, false))
        assertFalse(gate.begin())
        assertNull(gate.reserve())
        // A later foreground is a new entry, rather than a delayed first-start impression.
        assertTrue(gate.beginWarmStart(30_000, true, false))
    }

    @Test fun unknownFirstLaunchStateCannotBeBypassedByWarmEntry() {
        val store = MemoryStore(AppOpenHistory()).apply { writesAllowed = false }
        val gate = policy(store)
        assertFalse(gate.begin())
        assertFalse(gate.canPreload())
        assertFalse(gate.beginWarmStart(30_000, true, false))
    }

    @Test fun cacheExpiresAtExactlyFourHoursAndRejectsMonotonicClockRollback() {
        val loaded = 10_000L
        assertTrue(AppOpenPolicy.cacheIsFresh(loaded, loaded))
        assertTrue(AppOpenPolicy.cacheIsFresh(loaded, loaded + AppOpenPolicy.CACHE_TTL_MILLIS - 1))
        assertFalse(AppOpenPolicy.cacheIsFresh(loaded, loaded + AppOpenPolicy.CACHE_TTL_MILLIS))
        assertFalse(AppOpenPolicy.cacheIsFresh(loaded, loaded - 1))
        assertFalse(AppOpenPolicy.cacheIsFresh(-1, loaded))
    }

    @Test fun emptyCacheMayLoadInsideFiveSecondWindowButNeverAfterItsDeadline() {
        val clock = Clock()
        val gate = policy(MemoryStore(), clock)
        gate.begin(); gate.cancelOpportunity()
        assertTrue(gate.beginWarmStart(30_000, true, false))
        assertNull(gate.reserveShow(adReady = false, activityResumed = true, initialScreen = true))
        clock.uptime += 5_000L
        // A late network callback cannot replace the now-visible diary with an ad.
        assertNull(gate.reserveShow(adReady = true, activityResumed = true, initialScreen = true))
    }

    @Test fun loadedAdMayShowWithinWindowOnlyWhileStartupPanelIsVisible() {
        val clock = Clock()
        val gate = policy(MemoryStore(), clock)
        assertTrue(gate.begin())
        clock.uptime += 4_000L // SDK initialization and network share one budget.
        assertEquals(1_000L, gate.remainingStartupMillis())
        assertNull(gate.reserveShow(adReady = true, activityResumed = true, initialScreen = false))
        assertNotNull(gate.reserveShow(adReady = true, activityResumed = true, initialScreen = true))
    }

    @Test fun skipOrLoadFailureClosesWindowWithoutRecordingAnImpression() {
        val store = MemoryStore()
        val gate = policy(store)
        assertTrue(gate.begin())
        gate.cancelOpportunity()
        assertEquals(0L, gate.remainingStartupMillis())
        assertNull(gate.reserve())
        assertEquals(0L, store.value!!.lastShownAtMillis)
        assertEquals("", store.value!!.pendingId)
    }

    @Test fun editorAndExternalReturnsNeverCreateAShowWindow() {
        val gate = policy(MemoryStore())
        gate.begin(); gate.cancelOpportunity()
        assertFalse(gate.beginWarmStart(30_000, safeScreen = false, externalReturn = false))
        assertNull(gate.reserve())
        assertFalse(gate.beginWarmStart(30_000, safeScreen = true, externalReturn = true))
        assertNull(gate.reserve())
        assertFalse(gate.beginWarmStart(-1, safeScreen = true, externalReturn = false))
    }

    @Test fun diaryVisibilityOrInputCancelsWarmWindowEvenWithFreshCache() {
        val gate = policy(MemoryStore())
        gate.begin(); gate.cancelOpportunity()
        assertTrue(gate.beginWarmStart(30_000, true, false))
        gate.cancelOpportunity()
        assertNull(gate.reserve())
    }

    @Test fun repeatedForegroundCannotBypassActualImpressionCooldown() {
        val store = MemoryStore()
        val clock = Clock()
        val gate = policy(store, clock)
        gate.begin(); gate.cancelOpportunity()
        assertTrue(gate.beginWarmStart(30_000, true, false))
        gate.onShown(gate.reserve()!!)
        assertFalse(gate.canPreload())
        clock.wall += AppOpenPolicy.IMPRESSION_INTERVAL_MILLIS - 1
        assertFalse(gate.beginWarmStart(30_000, true, false))
        clock.wall += 1
        assertTrue(gate.canPreload())
        assertTrue(gate.beginWarmStart(30_000, true, false))
    }

    @Test fun laterHourInSameProcessRecordsTheNewImpressionAndIgnoresOldCallbacks() {
        val store = MemoryStore()
        val clock = Clock()
        var sequence = 0
        val gate = AppOpenPolicy(store, { clock.wall }, { clock.uptime }, { "entry-${++sequence}" })
        gate.begin(); gate.cancelOpportunity()
        assertTrue(gate.beginWarmStart(30_000, true, false))
        val first = gate.reserve()!!
        gate.onShown(first)
        clock.wall += AppOpenPolicy.IMPRESSION_INTERVAL_MILLIS
        assertTrue(gate.beginWarmStart(30_000, true, false))
        val second = gate.reserve()!!
        gate.onShown(first)
        assertEquals(second, store.value!!.pendingId)
        gate.onShown(second)
        assertEquals(clock.wall, store.value!!.lastShownAtMillis)
        assertEquals("", store.value!!.pendingId)
    }

    @Test fun failedShowAllowsAnotherValidEntryButDoesNotSpendHourlyAllowance() {
        val store = MemoryStore()
        val gate = policy(store)
        gate.begin(); gate.cancelOpportunity()
        assertTrue(gate.beginWarmStart(30_000, true, false))
        gate.onFailedToShow(gate.reserve()!!)
        assertEquals(0L, store.value!!.lastShownAtMillis)
        assertNull(gate.reserve())
        assertTrue(gate.beginWarmStart(30_000, true, false))
    }
}
