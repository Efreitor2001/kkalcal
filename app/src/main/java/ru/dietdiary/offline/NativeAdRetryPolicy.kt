package ru.dietdiary.offline

/** A visible host gets two delayed retries, not an endless no-fill request loop. */
internal class NativeAdRetryPolicy {
    private var retries = 0
    private var networkAvailable: Boolean? = null

    fun start(available: Boolean?) {
        retries = 0
        networkAvailable = available
    }

    fun afterFailure(): Long? = when (retries) {
        0 -> { retries++; 30_000L }
        1 -> { retries++; 60_000L }
        else -> null
    }

    /** Initial/repeated online notifications must not defeat the retry limit. */
    fun networkChanged(available: Boolean): Boolean {
        val recovered = networkAvailable == false && available
        networkAvailable = available
        if (recovered) retries = 0
        return recovered
    }
}
