package ru.dietdiary.offline

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

/** One native card at the end of the page. Its empty host measures zero until binding succeeds. */
@Composable
fun AdCard(placement: AdPlacement, modifier: Modifier = Modifier) {
    val id = configuredAdId(placement.unitId) ?: return
    val activity = LocalContext.current.adActivity() ?: return
    val colors = MaterialTheme.colorScheme
    val palette = NativePalette(colors.surfaceContainerLow.toArgb(), colors.onSurface.toArgb(),
        colors.onSurfaceVariant.toArgb(), colors.primary.toArgb(), colors.onPrimary.toArgb(), colors.outlineVariant.toArgb())
    key(activity, id) {
        AndroidView(
            factory = { NativeAdHost(activity, id, palette) },
            update = { it.updatePalette(palette) },
            modifier = modifier.fillMaxWidth(),
            onReset = null,
            onRelease = { it.dispose() },
        )
    }
}

private data class NativePalette(val surface: Int, val text: Int, val muted: Int,
    val action: Int, val onAction: Int, val outline: Int)

/** All SDK work is on main. A stopped/recycled screen cannot accept an old load callback. */
private class NativeAdHost(
    private val activity: Activity,
    private val unitId: String,
    private var palette: NativePalette,
) : FrameLayout(activity) {
    private val lifecycle = (activity as? LifecycleOwner)?.lifecycle
    private var disposed = false
    private var started = false
    private var generation = 0L
    private var unsubscribe: (() -> Unit)? = null
    private var deferredLoad: Runnable? = null
    private var loader: NativeAdLoader? = null
    private var ad: NativeAd? = null
    private var card: NativeCard? = null
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> dispose()
            else -> Unit
        }
    }

    init {
        layoutParams = ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lifecycle?.addObserver(observer)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!adSafely("measure native ad") { super.onMeasure(widthMeasureSpec, heightMeasureSpec) }) {
            clearContent()
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 0)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!adSafely("layout native ad") { super.onLayout(changed, left, top, right, bottom) }) clearContent()
    }

    private fun start() {
        if (disposed || started || !isAttachedToWindow || activity.isFinishing || activity.isDestroyed ||
            lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != true) return
        started = true
        val session = ++generation
        unsubscribe = AdSdk.whenReady(activity.applicationContext) {
            if (current(session)) requestWhenAllowed(session)
        }
    }

    private fun current(session: Long) = !disposed && started && generation == session &&
        isAttachedToWindow && !activity.isFinishing && !activity.isDestroyed

    private fun requestWhenAllowed(session: Long) {
        if (!current(session)) return
        val delay = NativeRequestPace.reserveOrDelay(unitId)
        if (delay > 0) {
            val task = Runnable { deferredLoad = null; requestWhenAllowed(session) }
            deferredLoad = task
            postDelayed(task, delay)
        } else load(session)
    }

    private fun load(session: Long) {
        if (!adSafely("load native ad") {
            // SDK-only volume control: never change the user's device/media volume.
            YandexAds.adVolumeController.setMuted(true)
            val requestLoader = NativeAdLoader(activity)
            loader = requestLoader
            requestLoader.loadAd(AdRequest.Builder(unitId).build(),
                NativeAdOptions.Builder().setShouldLoadImagesAutomatically(true).build(),
                object : NativeAdLoadListener {
                    override fun onAdLoaded(nativeAd: NativeAd) = onAdMain {
                        if (!current(session) || loader !== requestLoader) {
                            release(nativeAd)
                            return@onAdMain
                        }
                        if (!adSafely("bind native ad") { show(nativeAd, session) }) {
                            release(nativeAd)
                            clearContent()
                        }
                    }

                    override fun onAdFailedToLoad(error: AdRequestError) = onAdMain {
                        if (current(session) && loader === requestLoader) {
                            clearContent()
                            Log.i("DietDiaryAds", "Native ad unavailable code=${error.code}")
                        }
                        // No request loop on failure. A later screen start may try again.
                    }
                })
        }) {
            loader?.let { adSafely("cancel native load") { it.cancelLoading() } }
            loader = null
            clearContent()
        }
    }

    private fun show(nativeAd: NativeAd, session: Long) {
        clearContent()
        val next = NativeCard(activity, nativeAd.adAssets, palette)
        // Video requires >=300dp of unobscured media width; narrow windows cannot provide it.
        if (next.hasVideo && width > 0 && width - context.nativeDp(20) < context.nativeDp(300)) {
            release(nativeAd)
            return
        }
        if (nativeAd.bindNativeAd(next.binder) !is AdBindingResult.Success) {
            release(nativeAd)
            Log.i("DietDiaryAds", "Native binding unavailable")
            return
        }
        nativeAd.setNativeAdEventListener(object : NativeAdEventListener {
            override fun onAdClicked() = onAdMain {
                if (current(session) && ad === nativeAd) DietAds.suppressNextEntry()
            }
            override fun onImpression(data: ImpressionData?) = onAdMain {
                if (current(session) && ad === nativeAd) Log.i("DietDiaryAds", "Native impression")
            }
        })
        ad = nativeAd
        card = next
        addView(next.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        Log.i("DietDiaryAds", "Native ad bound")
    }

    fun updatePalette(value: NativePalette) {
        palette = value
        card?.let { adSafely("style native ad") { it.applyPalette(value) } }
    }

    private fun release(value: NativeAd) {
        adSafely("release native listener") { value.setNativeAdEventListener(null) }
    }

    private fun clearContent() {
        val old = ad
        ad = null
        card = null
        if (old != null) release(old)
        // NativeAd has no public destroy/unbind in SDK8. Detach media and drop strong references.
        adSafely("detach native view") { removeAllViews() }
        requestLayout()
    }

    private fun stop() {
        started = false
        generation++
        deferredLoad?.let(::removeCallbacks); deferredLoad = null
        unsubscribe?.invoke(); unsubscribe = null
        val oldLoader = loader
        loader = null
        if (oldLoader != null) adSafely("cancel native load") { oldLoader.cancelLoading() }
        clearContent()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        stop()
        lifecycle?.removeObserver(observer)
    }
}

/** Main-thread, placement-scoped pacing also survives lazy-list disposal and rapid tab changes. */
private object NativeRequestPace {
    private val lastRequest = mutableMapOf<String, Long>()
    fun reserveOrDelay(unitId: String): Long {
        val now = SystemClock.elapsedRealtime()
        val previous = lastRequest[unitId]
        val delay = if (previous == null) 0L else (30_000L - (now - previous)).coerceAtLeast(0L)
        if (delay == 0L) lastRequest[unitId] = now
        return delay
    }
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
