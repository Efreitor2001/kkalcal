package ru.dietdiary.offline

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatRatingBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.yandex.mobile.ads.common.AdBindingResult
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.nativeads.MediaView
import com.yandex.mobile.ads.nativeads.NativeAd
import com.yandex.mobile.ads.nativeads.NativeAdAssets
import com.yandex.mobile.ads.nativeads.NativeAdEventListener
import com.yandex.mobile.ads.nativeads.NativeAdLoadListener
import com.yandex.mobile.ads.nativeads.NativeAdLoader
import com.yandex.mobile.ads.nativeads.NativeAdOptions
import com.yandex.mobile.ads.nativeads.NativeAdView
import com.yandex.mobile.ads.nativeads.NativeAdViewBinder
import com.yandex.mobile.ads.nativeads.Rating
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class AdPlacement {
    DAY, PRODUCTS, DIARY, STATS, MORE, ACHIEVEMENTS, CLOUD;

    internal val unitId: String get() = when (this) {
        DAY -> BuildConfig.YANDEX_NATIVE_DAY_ID
        PRODUCTS -> BuildConfig.YANDEX_NATIVE_PRODUCTS_ID
        DIARY -> BuildConfig.YANDEX_NATIVE_DIARY_ID
        STATS -> BuildConfig.YANDEX_NATIVE_STATS_ID
        MORE -> BuildConfig.YANDEX_NATIVE_MORE_ID
        ACHIEVEMENTS -> BuildConfig.YANDEX_NATIVE_ACHIEVEMENTS_ID
        CLOUD -> BuildConfig.YANDEX_NATIVE_CLOUD_ID
    }
}

/** One card at the page end. The one-dp empty host stays measurable in a lazy list. */
@Composable
fun AdCard(placement: AdPlacement, modifier: Modifier = Modifier) {
    val testing = NativeAdsTestHooks.configuration
    val id = if (testing != null) "test-" + placement.name else configuredAdId(placement.unitId) ?: return
    val activity = LocalContext.current.adActivity() ?: return
    if (activity.isDestroyed || activity.isFinishing || activity !is LifecycleOwner) return
    val colors = MaterialTheme.colorScheme
    val palette = NativePalette(colors.surfaceContainerLow.toArgb(), colors.onSurface.toArgb(),
        colors.onSurfaceVariant.toArgb(), colors.primary.toArgb(), colors.onPrimary.toArgb(), colors.outlineVariant.toArgb())
    key(activity, placement, id) {
        AndroidView(
            factory = { NativeAdHost(activity, placement, NativeAdSessions.create(activity, id), palette) },
            update = { it.updatePalette(palette) },
            modifier = modifier.fillMaxWidth(),
            onReset = null,
            onRelease = { it.dispose() },
        )
    }
}

internal data class NativePalette(val surface: Int, val text: Int, val muted: Int,
    val action: Int, val onAction: Int, val outline: Int)

internal interface NativeAdCreative {
    /** Called once in the owning host; later navigation requests a new creative. */
    fun bind(activity: Activity, width: Int, palette: NativePalette, onClicked: () -> Unit, onImpression: () -> Unit): View?
    fun updatePalette(palette: NativePalette)
    fun dispose()
}

internal interface NativeAdTransport {
    fun whenReady(context: Context, callback: () -> Unit): () -> Unit
    fun load(activity: Activity, unitId: String, onLoaded: (NativeAdCreative) -> Unit, onFailed: (Int) -> Unit): () -> Unit
}

internal interface NativeAdScheduler {
    fun nowMillis(): Long
    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit
}

internal const val NATIVE_AD_LOAD_TIMEOUT_MILLIS = 20_000L

/** Instrumentation replaces transport/time only, keeping the real host, session and lifecycle. */
internal object NativeAdsTestHooks {
    internal data class Configuration(val transport: NativeAdTransport, val scheduler: NativeAdScheduler)
    internal var configuration: Configuration? = null
        private set

    fun install(transport: NativeAdTransport, scheduler: NativeAdScheduler): AutoCloseable {
        check(BuildConfig.DEBUG && Looper.myLooper() == Looper.getMainLooper())
        NativeAdSessions.clear()
        NativeRequestPace.clear()
        val installed = Configuration(transport, scheduler)
        configuration = installed
        return AutoCloseable {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (configuration === installed) {
                NativeAdSessions.clear()
                NativeRequestPace.clear()
                configuration = null
            }
        }
    }

    fun networkChanged(available: Boolean) {
        check(configuration != null && Looper.myLooper() == Looper.getMainLooper())
        NativeAdSessions.networkChanged(available)
    }
}

private object NativeScheduler : NativeAdScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun nowMillis() = SystemClock.elapsedRealtime()
    override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
        val task = Runnable(action)
        handler.postDelayed(task, delayMillis)
        return { handler.removeCallbacks(task); Unit }
    }
}

private object SdkNativeTransport : NativeAdTransport {
    override fun whenReady(context: Context, callback: () -> Unit) = AdSdk.whenReady(context, callback)
    override fun load(activity: Activity, unitId: String, onLoaded: (NativeAdCreative) -> Unit, onFailed: (Int) -> Unit): () -> Unit {
        YandexAds.adVolumeController.setMuted(true)
        val loader = NativeAdLoader(activity)
        loader.loadAd(AdRequest.Builder(unitId).build(),
            NativeAdOptions.Builder().setShouldLoadImagesAutomatically(true).build(),
            object : NativeAdLoadListener {
                override fun onAdLoaded(nativeAd: NativeAd) = onAdMain { onLoaded(SdkNativeCreative(nativeAd, loader)) }
                override fun onAdFailedToLoad(error: AdRequestError) = onAdMain { onFailed(error.code) }
            })
        return { adSafely("cancel native load") { loader.cancelLoading() }; Unit }
    }
}

/** Keep the loader and bound card alive only for the host that requested this creative. */
private class SdkNativeCreative(private val ad: NativeAd, private val loader: NativeAdLoader) : NativeAdCreative {
    private var card: NativeCard? = null
    override fun bind(activity: Activity, width: Int, palette: NativePalette, onClicked: () -> Unit, onImpression: () -> Unit): View? {
        check(card == null)
        val next = NativeCard(activity, ad.adAssets, palette)
        if (next.hasVideo && width - activity.nativeDp(20) < activity.nativeDp(300)) {
            Log.i("DietDiaryAds", "Native video needs wider container")
            return null
        }
        if (ad.bindNativeAd(next.binder) !is AdBindingResult.Success) {
            Log.i("DietDiaryAds", "Native binding unavailable")
            return null
        }
        ad.setNativeAdEventListener(object : NativeAdEventListener {
            override fun onAdClicked() = onAdMain(onClicked)
            override fun onImpression(data: ImpressionData?) = onAdMain(onImpression)
        })
        card = next
        return next.view
    }
    override fun updatePalette(palette: NativePalette) { card?.applyPalette(palette) }
    override fun dispose() {
        adSafely("release native listener") { ad.setNativeAdEventListener(null) }
        adSafely("release native loader") { loader.cancelLoading() }
        card?.view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        card = null
    }
}

/** Track only live hosts for test cleanup; no creative survives a host's final release. */
private object NativeAdSessions {
    private val sessions = mutableSetOf<NativeAdSession>()
    fun create(activity: Activity, unitId: String): NativeAdSession {
        val testing = NativeAdsTestHooks.configuration
        return NativeAdSession(activity, unitId, testing?.transport ?: SdkNativeTransport,
            testing?.scheduler ?: NativeScheduler, if (testing != null) true else null,
            { sessions.remove(it) }).also { sessions.add(it) }
    }
    fun networkChanged(available: Boolean) { sessions.toList().forEach { it.networkChanged(available) } }
    fun clear() {
        val old = sessions.toList()
        sessions.clear()
        old.forEach { it.dispose() }
    }
}

private class NativeAdSession(
    private val activity: Activity,
    private val unitId: String,
    private val transport: NativeAdTransport,
    private val scheduler: NativeAdScheduler,
    private var networkAvailable: Boolean?,
    private val onDisposed: (NativeAdSession) -> Unit,
) {
    private var owner: NativeAdHost? = null
    private var disposed = false
    private var foreground = false
    private var generation = 0L
    private var loading = false
    private var terminalFailure = false
    private var cancelReady: (() -> Unit)? = null
    private var cancelLoad: (() -> Unit)? = null
    private var cancelDeferred: (() -> Unit)? = null
    private var cancelWatchdog: (() -> Unit)? = null
    private var notBefore = 0L
    private val retry = NativeAdRetryPolicy().apply { start(networkAvailable) }
    private var creative: NativeAdCreative? = null
    private var boundView: View? = null

    fun attach(host: NativeAdHost) {
        if (disposed) return
        owner?.takeIf { it !== host }?.unmount()
        val returning = owner == null
        owner = host
        if (returning) retry.start(networkAvailable)
        if (creative != null) render() else ensureLoad()
    }

    fun detach(host: NativeAdHost) {
        if (owner !== host) return
        host.unmount()
        owner = null
        cancelPending()
        discardCreative()
    }

    fun setForeground(value: Boolean) {
        if (disposed) return
        foreground = value
        if (!value) {
            cancelPending()
            discardCreative()
        }
        else {
            retry.start(networkAvailable)
            notBefore = 0L
            ensureLoad()
        }
    }

    fun networkChanged(value: Boolean) {
        networkAvailable = value
        if (retry.networkChanged(value) && !terminalFailure && creative == null && !loading) {
            notBefore = 0L
            cancelDeferred?.invoke(); cancelDeferred = null
            ensureLoad()
        }
    }

    fun render() {
        val host = owner ?: return
        val item = creative ?: return
        if (disposed || !foreground || !host.isAttachedToWindow || host.width <= 0) return
        if (boundView == null) {
            var view: View? = null
            val success = adSafely("bind native ad") {
                view = item.bind(activity, host.width, host.palette,
                    { if (!disposed && foreground && owner != null && creative === item) DietAds.suppressNextEntry() },
                    { if (!disposed && foreground && owner != null && creative === item) Log.i("DietDiaryAds", "Native impression") })
            }
            if (!success || view == null) {
                discardCreative()
                failed(-1)
                return
            }
            boundView = view
            Log.i("DietDiaryAds", "Native ad bound")
        }
        adSafely("style native ad") { item.updatePalette(host.palette) }
        boundView?.let(host::mount)
    }

    fun contentFailed(host: NativeAdHost) {
        if (owner !== host || creative == null) return
        discardCreative()
        failed(-1)
    }

    private fun ensureLoad() {
        if (disposed || !foreground || owner == null || creative != null || loading || terminalFailure || cancelDeferred != null) return
        val delay = (notBefore - scheduler.nowMillis()).coerceAtLeast(0)
        if (delay > 0) { defer(delay); return }
        loading = true
        val session = ++generation
        armWatchdog(session, "SDK readiness")
        if (!adSafely("prepare native ad") {
            val unsubscribe = transport.whenReady(activity.applicationContext) {
                onAdMain {
                    if (!current(session)) return@onAdMain
                    cancelWatchdog?.invoke(); cancelWatchdog = null
                    val paceDelay = NativeRequestPace.reserveOrDelay(unitId, scheduler.nowMillis())
                    if (paceDelay > 0) {
                        loading = false
                        notBefore = scheduler.nowMillis() + paceDelay
                        if (owner != null) defer(paceDelay)
                    } else request(session)
                }
            }
            if (current(session) && loading) cancelReady = unsubscribe else unsubscribe()
        }) {
            cancelPending()
            failed(5)
        }
    }

    private fun request(session: Long) {
        if (!current(session)) return
        armWatchdog(session, "load")
        if (!adSafely("load native ad") {
            val cancellation = transport.load(activity, unitId,
                { item -> onAdMain {
                    if (!current(session)) {
                        adSafely("release stale native ad") { item.dispose() }
                        return@onAdMain
                    }
                    loading = false
                    cancelWatchdog?.invoke(); cancelWatchdog = null
                    cancelLoad = null
                    cancelReady?.invoke(); cancelReady = null
                    creative = item
                    notBefore = 0L
                    render()
                } },
                { code -> onAdMain {
                    if (!current(session)) return@onAdMain
                    loading = false
                    cancelWatchdog?.invoke(); cancelWatchdog = null
                    cancelLoad = null
                    cancelReady?.invoke(); cancelReady = null
                    Log.i("DietDiaryAds", "Native ad unavailable code=$code")
                    failed(code)
                } })
            if (current(session) && loading) cancelLoad = cancellation
        }) {
            cancelPending()
            failed(5)
        }
    }

    private fun current(session: Long) = !disposed && foreground && generation == session && loading &&
        !activity.isFinishing && !activity.isDestroyed

    private fun failed(code: Int) {
        // SDK invalid/system errors must not be retried. Negative codes are local timeout/layout failures.
        if (code !in setOf(3, 4, -1, -2)) { terminalFailure = true; return }
        val delay = retry.afterFailure() ?: return
        notBefore = scheduler.nowMillis() + delay
        if (owner != null && foreground) defer(delay)
    }

    private fun defer(delay: Long) {
        cancelDeferred?.invoke()
        cancelDeferred = scheduler.schedule(delay) {
            cancelDeferred = null
            ensureLoad()
        }
    }

    private fun armWatchdog(session: Long, stage: String) {
        cancelWatchdog?.invoke()
        cancelWatchdog = scheduler.schedule(NATIVE_AD_LOAD_TIMEOUT_MILLIS) {
            if (current(session)) {
                Log.i("DietDiaryAds", "Native $stage timeout")
                cancelPending()
                failed(-2)
            }
        }
    }

    private fun discardCreative() {
        owner?.unmount()
        boundView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        boundView = null
        creative?.let { adSafely("release native creative") { it.dispose() } }
        creative = null
    }

    private fun cancelPending() {
        generation++
        loading = false
        cancelWatchdog?.invoke(); cancelWatchdog = null
        cancelDeferred?.invoke(); cancelDeferred = null
        cancelReady?.invoke(); cancelReady = null
        cancelLoad?.invoke(); cancelLoad = null
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        cancelPending()
        discardCreative()
        owner = null
        onDisposed(this)
    }
}

private class NativeAdHost(
    activity: Activity,
    placement: AdPlacement,
    private val session: NativeAdSession,
    var palette: NativePalette,
) : FrameLayout(activity) {
    private var disposed = false
    private val lifecycle = (activity as LifecycleOwner).lifecycle
    private val connectivity = activity.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                session.setForeground(true)
                if (isAttachedToWindow) observeNetwork()
            }
            Lifecycle.Event.ON_STOP -> { stopNetwork(); session.setForeground(false) }
            Lifecycle.Event.ON_DESTROY -> dispose()
            else -> Unit
        }
    }
    init {
        tag = "native-ad-" + placement.name
        minimumHeight = context.nativeDp(1)
        layoutParams = ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lifecycle.addObserver(observer)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!disposed) {
            observeNetwork()
            session.attach(this)
        }
    }
    override fun onDetachedFromWindow() { stopNetwork(); session.detach(this); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); session.render() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!adSafely("measure native ad") { super.onMeasure(widthMeasureSpec, heightMeasureSpec) }) {
            session.contentFailed(this)
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), minimumHeight)
        }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!adSafely("layout native ad") { super.onLayout(changed, left, top, right, bottom) }) session.contentFailed(this)
    }
    fun mount(view: View) {
        if (view.parent !== this) {
            (view.parent as? ViewGroup)?.removeView(view)
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }
    fun unmount() { adSafely("detach native view") { removeAllViews() } }
    fun updatePalette(value: NativePalette) { palette = value; if (!disposed) session.render() }
    private fun observeNetwork() {
        if (NativeAdsTestHooks.configuration != null || networkCallback != null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val manager = connectivity ?: return
        runCatching {
            session.networkChanged(manager.getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onAdMain {
                if (networkCallback === this && active()) session.networkChanged(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
            override fun onLost(network: Network) = onAdMain { if (networkCallback === this && active()) session.networkChanged(false) }
        }
        if (adSafely("observe native ad connection") { manager.registerDefaultNetworkCallback(callback) }) networkCallback = callback
    }
    private fun active() = !disposed && isAttachedToWindow && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    private fun stopNetwork() {
        networkCallback?.let { callback -> adSafely("stop native connection observer") { connectivity?.unregisterNetworkCallback(callback) } }
        networkCallback = null
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        stopNetwork()
        session.dispose()
        lifecycle.removeObserver(observer)
    }
}

/** Shared placement request pacing is independent of the lifetime of a Compose host. */
private object NativeRequestPace {
    private val lastRequest = mutableMapOf<String, Long>()
    fun reserveOrDelay(unitId: String, now: Long): Long {
        val previous = lastRequest[unitId]
        val delay = if (previous == null || now < previous) 0L else (30_000L - (now - previous)).coerceAtLeast(0L)
        if (delay == 0L) lastRequest[unitId] = now
        return delay
    }
    fun clear() { lastRequest.clear() }
}

/** All assets belong to this NativeAdView; the SDK supplies text, images and click handling. */
private class NativeCard(context: Context, assets: NativeAdAssets, palette: NativePalette) {
    val view = NativeAdView(context)
    private val label = text(context, 11f).apply { text = "РЕКЛАМА"; letterSpacing = .08f }
    private val sponsored = text(context, 12f)
    private val age = text(context, 12f)
    private val title = text(context, 18f).apply { setTypeface(typeface, Typeface.BOLD) }
    private val body = text(context, 14f)
    private val domain = text(context, 12f)
    private val price = text(context, 14f)
    private val reviews = text(context, 12f)
    private val warning = text(context, 13f).apply { gravity = Gravity.CENTER_VERTICAL }
    private val action = text(context, 15f).apply {
        gravity = Gravity.CENTER
        minimumHeight = context.nativeDp(48)
        setPadding(context.nativeDp(16), context.nativeDp(10), context.nativeDp(16), context.nativeDp(10))
        setTypeface(typeface, Typeface.BOLD)
    }
    private val icon = image(context)
    private val favicon = image(context)
    private val feedback = image(context).apply {
        setImageResource(android.R.drawable.ic_menu_more)
        setPadding(context.nativeDp(8), context.nativeDp(8), context.nativeDp(8), context.nativeDp(8))
        contentDescription = "Информация о рекламе"
    }
    private val rating = NativeRating(context).apply { numStars = 5; stepSize = .5f; setIsIndicator(true) }
    private val media = MediaView(context)
    val hasVideo = assets.media?.hasVideo == true
    val binder: NativeAdViewBinder

    init {
        val content = NativeAssetColumn(context, warning, media, assets.warning?.minimumRequiredArea ?: 0f,
            assets.media?.aspectRatio ?: 0f, hasVideo).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.nativeDp(10), context.nativeDp(12), context.nativeDp(10), context.nativeDp(12))
        }
        view.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(label, rowParams(context, top = 0))
        val disclosure = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        disclosure.addView(sponsored, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        disclosure.addView(age, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        disclosure.addView(feedback, LinearLayout.LayoutParams(context.nativeDp(40), context.nativeDp(40)))
        content.addView(disclosure, rowParams(context, top = 0))
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(icon, LinearLayout.LayoutParams(context.nativeDp(56), context.nativeDp(56)).apply { marginEnd = context.nativeDp(10) })
        heading.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(heading, rowParams(context))
        content.addView(body, rowParams(context))
        val source = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        source.addView(favicon, LinearLayout.LayoutParams(context.nativeDp(24), context.nativeDp(24)).apply { marginEnd = context.nativeDp(6) })
        source.addView(domain, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(source, rowParams(context))
        content.addView(price, rowParams(context))
        content.addView(rating, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(reviews, rowParams(context, top = 2))
        content.addView(media, rowParams(context))
        content.addView(action, rowParams(context))
        content.addView(warning, rowParams(context))
        listOf(sponsored to assets.sponsored, age to assets.age, title to assets.title, body to assets.body,
            domain to assets.domain, price to assets.price, reviews to assets.reviewCount,
            action to assets.callToAction, warning to assets.warning?.value).forEach { (target, value) ->
            target.visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        icon.visibility = if (assets.icon == null) View.GONE else View.VISIBLE
        favicon.visibility = if (assets.favicon == null) View.GONE else View.VISIBLE
        feedback.visibility = if (assets.isFeedbackAvailable) View.VISIBLE else View.GONE
        rating.visibility = if (assets.rating == null) View.GONE else View.VISIBLE
        media.visibility = if (assets.media == null && assets.image == null) View.GONE else View.VISIBLE
        binder = NativeAdViewBinder.Builder(view).setAgeView(age).setBodyView(body).setCallToActionView(action)
            .setDomainView(domain).setFaviconView(favicon).setFeedbackView(feedback).setIconView(icon)
            .setMediaView(media).setPriceView(price).setRatingView(rating).setReviewCountView(reviews)
            .setSponsoredView(sponsored).setTitleView(title).setWarningView(warning).build()
        applyPalette(palette)
    }

    fun applyPalette(palette: NativePalette) {
        view.background = rounded(view.context, palette.surface, 22, palette.outline)
        listOf(title, body, price).forEach { it.setTextColor(palette.text) }
        listOf(label, sponsored, age, domain, reviews, warning).forEach { it.setTextColor(palette.muted) }
        feedback.imageTintList = ColorStateList.valueOf(palette.muted)
        rating.progressTintList = ColorStateList.valueOf(palette.action)
        rating.secondaryProgressTintList = ColorStateList.valueOf(palette.outline)
        action.background = rounded(view.context, palette.action, 14)
        action.setTextColor(palette.onAction)
    }
}

/** Text remains untruncated; warning area grows with the complete creative, including font scale. */
private class NativeAssetColumn(context: Context, private val warning: TextView, private val media: MediaView,
    warningArea: Float, private val mediaRatio: Float, private val video: Boolean) : LinearLayout(context) {
    private val warningFraction = (if (warningArea > 1f) warningArea / 100f else warningArea)
        .takeIf { it.isFinite() }?.coerceIn(.10f, .90f) ?: .10f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (media.visibility != View.GONE && availableWidth > 0) {
            val ratio = mediaRatio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
            val height = (availableWidth / ratio).roundToInt().coerceAtLeast(if (video) context.nativeDp(160) else 1)
            media.layoutParams.height = height
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (warning.visibility != View.GONE && warning.measuredWidth > 0) {
            val otherHeight = measuredHeight - warning.measuredHeight
            val denominator = warning.measuredWidth - warningFraction * measuredWidth
            if (denominator > 0) {
                val requiredHeight = ceil(warningFraction * measuredWidth * otherHeight / denominator).toInt()
                if (warning.minimumHeight != requiredHeight) {
                    warning.minimumHeight = requiredHeight
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }
            }
        }
    }
}

private class NativeRating(context: Context) : AppCompatRatingBar(context, null, android.R.attr.ratingBarStyleSmall), Rating
private fun Context.nativeDp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
private fun text(context: Context, size: Float) = TextView(context).apply { textSize = size; includeFontPadding = false }
private fun image(context: Context) = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = true }
private fun rowParams(context: Context, top: Int = 8) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    .apply { topMargin = context.nativeDp(top) }
private fun rounded(context: Context, color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
    setColor(color); cornerRadius = context.nativeDp(radius).toFloat()
    if (stroke != null) setStroke(context.nativeDp(1), stroke)
}
