package ru.dietdiary.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAdRetryPolicyTest {
    @Test fun noFillCannotProduceAnEndlessRequestLoop() {
        val policy = NativeAdRetryPolicy().apply { start(true) }
        assertEquals(30_000L, policy.afterFailure())
        assertEquals(60_000L, policy.afterFailure())
        repeat(20) { assertNull(policy.afterFailure()) }
    }

    @Test fun internetRecoveryRestoresExhaustedRetryBudgetWithoutLeavingTheScreen() {
        val policy = NativeAdRetryPolicy().apply { start(false) }
        repeat(3) { policy.afterFailure() }
        assertTrue(policy.networkChanged(true))
        assertEquals(30_000L, policy.afterFailure())
        assertEquals(60_000L, policy.afterFailure())
        assertNull(policy.afterFailure())
    }

    @Test fun repeatedOnlineCapabilitiesDoNotResetNoFillBudget() {
        val policy = NativeAdRetryPolicy().apply { start(true) }
        assertEquals(30_000L, policy.afterFailure())
        assertFalse(policy.networkChanged(true))
        assertEquals(60_000L, policy.afterFailure())
        repeat(20) {
            assertFalse(policy.networkChanged(true))
            assertNull(policy.afterFailure())
        }
    }

    @Test fun firstUnknownNetworkNotificationDoesNotCreateASecondInitialRequest() {
        val policy = NativeAdRetryPolicy().apply { start(null) }
        assertFalse(policy.networkChanged(true))
        assertEquals(30_000L, policy.afterFailure())
        assertFalse(policy.networkChanged(false))
        assertFalse(policy.networkChanged(false))
        assertTrue(policy.networkChanged(true))
        assertFalse(policy.networkChanged(true))
    }

    @Test fun laterScreenStartGetsItsOwnBoundedAttempts() {
        val policy = NativeAdRetryPolicy().apply { start(true) }
        repeat(3) { policy.afterFailure() }
        policy.start(true)
        assertFalse(policy.networkChanged(true))
        assertEquals(30_000L, policy.afterFailure())
        assertEquals(60_000L, policy.afterFailure())
        assertNull(policy.afterFailure())
    }
}
