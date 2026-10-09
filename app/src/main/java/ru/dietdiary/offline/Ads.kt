package ru.dietdiary.offline

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewTreeObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.yandex.mobile.ads.appopenad.AppOpenAd
import com.yandex.mobile.ads.appopenad.AppOpenAdEventListener
import com.yandex.mobile.ads.appopenad.AppOpenAdLoadListener
import com.yandex.mobile.ads.appopenad.AppOpenAdLoader
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds
import java.util.UUID

/**
 * Optional, installation-local advertising. No diary data, account identifiers or targeting
 * parameters enter this component. Blank IDs disable every SDK initialization/request.
 */
object DietAds {
    private var policy: AppOpenPolicy? = null
    private var entry: AppOpenEntry? = null
    private var cache: AppOpenCache? = null
    var waitingForStartup by mutableStateOf(false)
        private set

    /** Call once from Application.onCreate. Does not initialize the SDK or use the network. */
    fun install(application: Application) {
        if (policy != null) return
        adSafely("prepare local frequency state") {
            policy = AppOpenPolicy(
                PreferenceAdHistory(application), System::currentTimeMillis,
                SystemClock::elapsedRealtime, { UUID.randomUUID().toString() }
            )
        }
    }

    /** Call before setContent. Eligible entries may use an explicit startup panel for <= 5s. */
    fun attachColdStart(activity: Activity, restored: Boolean) {
        install(activity.application)
        val gate = policy ?: return
        val launcher = activity.intent?.action == Intent.ACTION_MAIN &&
            activity.intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true
        val coldEligible = gate.beginColdStart(restored, launcher)
        val id = configuredAdId(BuildConfig.YANDEX_APP_OPEN_ID) ?: run { gate.cancelOpportunity(); return }
        entry?.dispose()
        val adCache = cache ?: AppOpenCache(activity.application, id).also { cache = it }
        val next = AppOpenEntry(activity, gate, adCache, coldEligible,
            onWaitingChanged = { waitingForStartup = it },
            onClosed = { closed -> if (entry === closed) entry = null })
        entry = next
        next.start()
    }

    /** SideEffect from the root UI: true for editors, pickers, dialogs and auxiliary screens. */
    fun setEditing(editing: Boolean) { entry?.setEditing(editing) }

    /** Native dialogs and popup menus report their visibility independently of Compose editors. */
    fun setModalOpen(open: Boolean) { entry?.setModalOpen(open) }

    fun skipStartup() { entry?.skipStartup() }
    /** Root SideEffect inside the opaque startup panel. */
    fun startupPanelVisible() { entry?.startupPanelVisible() }
    /** Root SideEffect in the ordinary diary; a visible diary permanently closes this entry. */
    fun markDiaryVisible() { entry?.markDiaryVisible() }

    /** Call before Google, SAF or another external activity; banner clicks use this internally. */
    fun suppressNextEntry() { entry?.suppressNextEntry() }

    /** Forward MainActivity.onUserInteraction; no ad may follow the first touch of an entry. */
    fun onUserInteraction() { entry?.onUserInteraction() }
}

internal fun configuredAdId(raw: String): String? = raw.trim().takeIf {
    it.isNotEmpty() && (BuildConfig.DEBUG || !it.startsWith("demo-"))
}

internal fun Context.adActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.adActivity() else null
    else -> null
}

private val adMain = Handler(Looper.getMainLooper())
internal fun onAdMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else adMain.post(block)
}

/** No exception messages, ad payloads, placement IDs, URLs or personal data in our logs. */
internal inline fun adSafely(operation: String, block: () -> Unit): Boolean = try {
    block(); true
} catch (problem: Exception) {
    Log.w("DietDiaryAds", "$operation unavailable (${problem.javaClass.simpleName})"); false
} catch (problem: LinkageError) {
    Log.w("DietDiaryAds", "$operation unavailable (${problem.javaClass.simpleName})"); false
}

internal object AdSdk {
    private var ready = false
    private var attempted = false
    private var failed = false
    private var nextSubscription = 0L
    private val callbacks = linkedMapOf<Long, () -> Unit>()

    /** The returned unsubscribe must run when the requesting UI is disposed. */
    fun whenReady(context: Context, callback: () -> Unit): () -> Unit {
        if (ready) { adSafely("ready callback", callback); return {} }
        if (failed) return {}
        val subscription = ++nextSubscription
        callbacks[subscription] = callback
        if (!attempted) {
            attempted = true
            val started = adSafely("initialize advertising") {
                YandexAds.setLocationTracking(false)
                YandexAds.setUserConsent(false)
                YandexAds.setAppAdAnalyticsReporting(false)
                YandexAds.enableLogging(false)
                YandexAds.initialize(context.applicationContext) {
                    onAdMain {
                        if (!failed) {
                            ready = true
                            val pending = callbacks.values.toList()
                            callbacks.clear()
                            pending.forEach { adSafely("ready callback", it) }
                        }
                    }
                }
            }
            if (!started) { failed = true; callbacks.clear() }
        }
        return { callbacks.remove(subscription); Unit }
    }
}

private class PreferenceAdHistory(context: Context) : AppOpenHistoryStore {
    private val preferences = context.getSharedPreferences("diet_diary_ads", Context.MODE_PRIVATE)

    override fun read(): AppOpenHistory? = try {
        AppOpenHistory(
            preferences.getBoolean("has_launched", false),
            preferences.getLong("last_actual_show", 0L),
            preferences.getString("pending_id", "").orEmpty(),
            preferences.getLong("pending_at", 0L)
        )
    } catch (_: Exception) { null }

    override fun write(value: AppOpenHistory): Boolean = try {
        // Tiny synchronous commit is intentional: show() must never precede durable reservation.
        preferences.edit().putBoolean("has_launched", value.hasLaunched)
            .putLong("last_actual_show", value.lastShownAtMillis)
            .putString("pending_id", value.pendingId).putLong("pending_at", value.pendingAtMillis).commit()
    } catch (_: Exception) { false }
}

/** Application-only cache; its observers may display only within an existing startup gate. */
private class AppOpenCache(private val application: Application, private val unitId: String) {
    data class Ready(val ad: AppOpenAd, val loadedAt: Long)
    private var cached: Ready? = null
    private var loader: AppOpenAdLoader? = null
    private var nextSubscription = 0L
    private val readyObservers = linkedMapOf<Long, (Boolean) -> Unit>()
    private val expiry = Runnable { clear() }

    fun peek(): Ready? {
        val ready = cached ?: return null
        if (!AppOpenPolicy.cacheIsFresh(ready.loadedAt, SystemClock.elapsedRealtime())) { clear(); return null }
        return ready
    }

    fun take(expected: Ready): AppOpenAd? {
        if (peek() !== expected) return null
        cached = null
        adMain.removeCallbacks(expiry)
        return expected.ad
    }

    fun observeLoadResult(observer: (Boolean) -> Unit): () -> Unit {
        val subscription = ++nextSubscription
        readyObservers[subscription] = observer
        return { readyObservers.remove(subscription); Unit }
    }

    fun preload() {
        if (peek() != null || loader != null) return
        if (!adSafely("preload app-open ad") {
            val requestLoader = AppOpenAdLoader(application)
            loader = requestLoader
            requestLoader.loadAd(AdRequest.Builder(unitId).build(), object : AppOpenAdLoadListener {
                override fun onAdLoaded(appOpenAd: AppOpenAd) = onAdMain {
                    loader = null
                    clear()
                    cached = Ready(appOpenAd, SystemClock.elapsedRealtime())
                    Log.i("DietDiaryAds", "App-open preloaded")
                    adMain.postDelayed(expiry, AppOpenPolicy.CACHE_TTL_MILLIS)
                    notifyLoadResult(true)
                }
                override fun onAdFailedToLoad(adRequestError: AdRequestError) = onAdMain {
                    loader = null
                    Log.i("DietDiaryAds", "App-open preload unavailable code=${adRequestError.code}")
                    notifyLoadResult(false)
                    // No retry loop; the next eligible foreground can try once again.
                }
            })
        }) {
            loader?.let { adSafely("cancel app-open preload") { it.cancelLoading() } }
            loader = null
            notifyLoadResult(false)
        }
    }

    private fun notifyLoadResult(loaded: Boolean) {
        readyObservers.values.toList().forEach { callback -> adSafely("app-open load callback") { callback(loaded) } }
    }

    private fun clear() {
        adMain.removeCallbacks(expiry)
        cached?.let { adSafely("release cached app-open ad") { it.ad.setAdEventListener(null) } }
        cached = null
    }
}

private class AppOpenEntry(
    private val activity: Activity,
    private val policy: AppOpenPolicy,
    private val cache: AppOpenCache,
    private val coldEligible: Boolean,
    private val onWaitingChanged: (Boolean) -> Unit,
    private val onClosed: (AppOpenEntry) -> Unit
) {
    private val lifecycle = (activity as? LifecycleOwner)?.lifecycle
    private var disposed = false
    private var foreground = false
    private var resumed = false
    private var editing = true
    private var modalOpen = false
    private var showing = false
    private var frameSeen = false
    private var firstStart = true
    private var entryHadFocus = false
    private var waiting = false
    private var panelVisible = false
    private var backgroundAt: Long? = null
    private var departedSafe = false
    private var pausedWithKeyboard = false
    private var skipNextEntry = false
    private var attemptedPreload = false
    private var shownAd: AppOpenAd? = null
    private var unsubscribe: (() -> Unit)? = null
    private var unsubscribeReady: (() -> Unit)? = null
    private val timeout = Runnable { closeGate(); schedulePreload() }
    private val preload = Runnable {
        if (!disposed && foreground && resumed && frameSeen && !editing && !modalOpen && !showing && !waiting &&
            !attemptedPreload && policy.canPreload()) {
            attemptedPreload = true
            unsubscribe = AdSdk.whenReady(activity.applicationContext) {
                if (!disposed && foreground && resumed && !editing && !modalOpen && !showing) cache.preload()
            }
        }
    }
    private val drawListener = ViewTreeObserver.OnPreDrawListener {
        frameSeen = true
        tryShow()
        removeDrawListener()
        schedulePreload()
        true // The startup panel is ordinary UI, never a blocked Android rendering thread.
    }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (hasFocus) { entryHadFocus = true; tryShow() }
        else if (waiting && panelVisible && frameSeen && !showing && entryHadFocus) closeGate()
    }
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> onStart()
            Lifecycle.Event.ON_RESUME -> { resumed = true; tryShow(); schedulePreload() }
            Lifecycle.Event.ON_PAUSE -> {
                pausedWithKeyboard = keyboardVisible()
                resumed = false
                if (!showing) closeGate()
                adMain.removeCallbacks(preload)
            }
            Lifecycle.Event.ON_STOP -> {
                foreground = false
                backgroundAt = SystemClock.elapsedRealtime()
                departedSafe = !editing && !modalOpen && !pausedWithKeyboard && !keyboardVisible() && !showing && !activity.isChangingConfigurations
                if (!showing) closeGate()
                removeDrawListener()
                removeFocusListener()
                adMain.removeCallbacks(preload)
                unsubscribe?.invoke(); unsubscribe = null
            }
            Lifecycle.Event.ON_DESTROY -> dispose()
            else -> Unit
        }
    }

    fun start() {
        if (lifecycle == null || activity.isDestroyed || activity.isFinishing) { dispose(); return }
        if (coldEligible) openGate()
        lifecycle.addObserver(observer)
    }

    private fun onStart() {
        foreground = true
        frameSeen = false
        entryHadFocus = false
        if (firstStart) {
            firstStart = false
        } else {
            attemptedPreload = false
            closeGate()
            val wasExternal = skipNextEntry
            skipNextEntry = false
            val elapsed = backgroundAt?.let { SystemClock.elapsedRealtime() - it } ?: -1L
            if (policy.beginWarmStart(elapsed, departedSafe && !editing && !modalOpen && !keyboardVisible() && !showing, wasExternal)) openGate()
        }
        backgroundAt = null
        removeDrawListener()
        removeFocusListener()
        activity.window.decorView.viewTreeObserver.addOnPreDrawListener(drawListener)
        activity.window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
    }

    private fun openGate() {
        if (disposed || !policy.canLoad()) return
        waiting = true
        panelVisible = false
        Log.i("DietDiaryAds", "Startup wait started")
        onWaitingChanged(true)
        adMain.removeCallbacks(preload)
        adMain.postDelayed(timeout, policy.remainingStartupMillis())
        attemptedPreload = true
        unsubscribeReady = cache.observeLoadResult { loaded -> if (loaded) tryShow() else closeGate() }
        // Initialization/loading counts against the same five-second budget, not a new timer.
        unsubscribe = AdSdk.whenReady(activity.applicationContext) {
            if (!disposed && waiting && policy.canLoad()) cache.preload()
        }
    }

    fun setEditing(value: Boolean) {
        editing = value
        if (value) { closeGate(); adMain.removeCallbacks(preload) }
        else { tryShow(); schedulePreload() }
    }

    fun setModalOpen(value: Boolean) {
        modalOpen = value
        if (value) { closeGate(); adMain.removeCallbacks(preload) }
        else schedulePreload()
    }

    fun startupPanelVisible() {
        if (waiting) { panelVisible = true; tryShow() }
    }

    fun markDiaryVisible() {
        closeGate()
        schedulePreload()
    }

    fun skipStartup() { closeGate(); schedulePreload() }

    fun suppressNextEntry() { skipNextEntry = true; closeGate() }

    fun onUserInteraction() {
        closeGate()
        schedulePreload()
    }

    private fun schedulePreload() {
        adMain.removeCallbacks(preload)
        if (!disposed && foreground && resumed && frameSeen && !editing && !modalOpen && !showing && !waiting && !attemptedPreload) {
            adMain.postDelayed(preload, 2_000L)
        }
    }

    private fun tryShow() {
        if (showing) return
        if (!waiting || !policy.canLoad()) {
            if (waiting) closeGate()
            return
        }
        if (keyboardVisible()) { closeGate(); return }
        if (disposed || showing || !foreground || !resumed || editing || modalOpen || !panelVisible ||
            activity.isDestroyed || activity.isFinishing || !activity.window.decorView.hasWindowFocus()) return
        val ready = cache.peek() ?: return
        val token = policy.reserveShow(adReady = true, activityResumed = true, initialScreen = panelVisible) ?: return
        val ad = cache.take(ready)
        if (ad == null) { policy.onFailedToShow(token); closeGate(); return }
        showing = true
        shownAd = ad
        skipNextEntry = true
        unsubscribeReady?.invoke(); unsubscribeReady = null
        unsubscribe?.invoke(); unsubscribe = null
        val bound = adSafely("bind app-open callbacks") {
            ad.setAdEventListener(object : AppOpenAdEventListener {
                override fun onAdShown() = onAdMain {
                    policy.onShown(token)
                    closeGate()
                    Log.i("DietDiaryAds", "App-open shown")
                }
                override fun onAdImpression(impressionData: ImpressionData?) = onAdMain {
                    policy.onShown(token)
                    closeGate()
                }
                override fun onAdFailedToShow(adError: AdError) = onAdMain {
                    policy.onFailedToShow(token)
                    finishShow()
                }
                override fun onAdDismissed() = onAdMain {
                    Log.i("DietDiaryAds", "App-open dismissed")
                    finishShow()
                }
                override fun onAdClicked() = onAdMain { suppressNextEntry() }
            })
        }
        if (!bound) { policy.onFailedToShow(token); finishShow(); return }
        if (!adSafely("show app-open ad") { ad.show(activity) }) {
            // An exception might follow a partial show; leave the durable reservation closed.
            finishShow()
        }
    }

    private fun finishShow() {
        showing = false
        closeGate()
        val previous = shownAd
        shownAd = null
        if (previous != null) adSafely("release app-open callbacks") { previous.setAdEventListener(null) }
        schedulePreload()
    }

    private fun closeGate() {
        policy.cancelOpportunity()
        if (waiting) Log.i("DietDiaryAds", "Startup wait ended")
        waiting = false
        panelVisible = false
        onWaitingChanged(false)
        adMain.removeCallbacks(timeout)
        unsubscribeReady?.invoke(); unsubscribeReady = null
        unsubscribe?.invoke(); unsubscribe = null
    }

    private fun removeDrawListener() {
        val observer = activity.window.decorView.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(drawListener)
    }

    private fun removeFocusListener() {
        val observer = activity.window.decorView.viewTreeObserver
        if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
    }

    private fun keyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    fun dispose() {
        if (disposed) return
        disposed = true
        closeGate()
        adMain.removeCallbacks(preload)
        removeDrawListener()
        removeFocusListener()
        lifecycle?.removeObserver(observer)
        finishShow()
        onClosed(this)
    }
}
