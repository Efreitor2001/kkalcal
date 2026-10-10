package ru.dietdiary.offline

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Real navigation, editors, AndroidView hosts and lifecycle; only the SDK transport and time
 * are controlled. No live ad requests or clicks, and no files from the user's diary are used.
 */
@RunWith(AndroidJUnit4::class)
class NativeAdsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fixtureRoot: File
    private lateinit var store: AppStore
    private lateinit var hooks: AutoCloseable
    private val transport = ControlledTransport()
    private val scheduler = ManualScheduler()

    @Before fun prepare() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        fixtureRoot = File(application.cacheDir, "native-ad-ui-${UUID.randomUUID()}")
        val files = File(fixtureRoot, "files").apply { check(mkdirs()) }
        store = AppStore(object : ContextWrapper(application) {
            override fun getFilesDir(): File = files
        })
        assertFalse(store.isReadOnly)
        store.saveProduct(Product(FOOD_ID, FOOD_NAME, "Мои продукты", Macros(200.0, 10.0, 8.0, 22.0)))
        compose.runOnUiThread {
            hooks = NativeAdsTestHooks.install(transport, scheduler)
            NativeAdsTestHooks.networkChanged(true)
        }
        compose.setContent { DietDiaryTheme { DietDiaryApp(store) } }
    }

    @After fun cleanUp() {
        compose.runOnUiThread { if (::hooks.isInitialized) hooks.close() }
        if (::fixtureRoot.isInitialized) fixtureRoot.deleteRecursively()
    }

    @Test fun repeatedTabsLoadAndDisplayNewCards() {
        showFooter(AdPlacement.DAY)
        val day = deliver(AdPlacement.DAY, "day-loaded")
        assertCardVisible(AdPlacement.DAY, day)

        compose.onNodeWithTag("tab_4").performClick()
        showFooter(AdPlacement.MORE)
        val more = deliver(AdPlacement.MORE, "more-loaded")
        assertCardVisible(AdPlacement.MORE, more)

        repeat(2) { index ->
            compose.onNodeWithTag("tab_0").performClick()
            awaitFreshRequest(AdPlacement.DAY, index + 2)
            assertCardVisible(AdPlacement.DAY, deliver(AdPlacement.DAY, "day-return-$index", index + 2))
            compose.onNodeWithTag("tab_4").performClick()
            awaitFreshRequest(AdPlacement.MORE, index + 2)
            assertCardVisible(AdPlacement.MORE, deliver(AdPlacement.MORE, "more-return-$index", index + 2))
        }

        compose.runOnIdle {
            assertEquals(3, transport.requestCount(AdPlacement.DAY))
            assertEquals(3, transport.requestCount(AdPlacement.MORE))
            assertEquals(1, day.disposeCount)
            assertEquals(1, more.disposeCount)
        }
    }

    @Test fun savingFoodThroughPickerRestoresDayCardAfterItsListChanges() {
        showFooter(AdPlacement.DAY)
        val day = deliver(AdPlacement.DAY, "day-before-food")
        assertCardVisible(AdPlacement.DAY, day)

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("+  Добавить еду"))
        compose.onNodeWithTag("add_food").performClick()
        compose.onNodeWithTag("product_search").performTextReplacement(FOOD_NAME)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("food_$FOOD_ID").performClick()
        compose.onNodeWithTag("portion_grams").performScrollTo().performTextReplacement("125")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("editor_save").performScrollTo().performClick()

        compose.runOnIdle {
            val entry = store.data.entries.single()
            assertEquals(FOOD_ID, entry.productId)
            assertEquals(125.0, entry.grams, 0.000001)
            assertEquals(250.0, entry.total.kcal, 0.000001)
        }
        // The empty-day panel has been replaced with a meal header and an actual food row.
        awaitFreshRequest(AdPlacement.DAY, 2)
        val afterSave = deliver(AdPlacement.DAY, "day-after-food", 2)
        assertCardVisible(AdPlacement.DAY, afterSave)
        compose.runOnIdle {
            assertEquals(2, transport.requestCount(AdPlacement.DAY))
            assertEquals(1, day.disposeCount)
        }
    }

    @Test fun lateSuccessFromReleasedPageCannotReplaceTheNewCard() {
        showFooter(AdPlacement.DAY)
        awaitRequests(AdPlacement.DAY, 1)
        compose.onNodeWithTag("tab_4").performClick()
        // An SDK callback can already be queued when cancelLoading is called.
        val late = TestCreative("Controlled native ad: stale-off-page")
        compose.runOnIdle { transport.deliverLate(AdPlacement.DAY, 1, late) }
        compose.onNodeWithTag("tab_0").performClick()
        awaitFreshRequest(AdPlacement.DAY, 2)
        val day = deliver(AdPlacement.DAY, "day-after-return", 2)
        assertCardVisible(AdPlacement.DAY, day)
        compose.runOnIdle {
            assertEquals(0, late.bindCount)
            assertEquals(1, late.disposeCount)
            assertEquals(2, transport.requestCount(AdPlacement.DAY))
            assertEquals(1, day.bindCount)
            assertEquals(0, day.disposeCount)
        }
    }

    @Test fun backgroundDuringLoadCancelsOldCallbackAndLoadsOnReturn() {
        compose.onNodeWithTag("tab_4").performClick()
        showFooter(AdPlacement.MORE)
        awaitRequests(AdPlacement.MORE, 1)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val late = TestCreative("Controlled native ad: stale-background")
        compose.runOnIdle { transport.deliverLate(AdPlacement.MORE, 1, late) }
        awaitFreshRequest(AdPlacement.MORE, 2)
        assertCardVisible(AdPlacement.MORE, deliver(AdPlacement.MORE, "more-after-background", 2))
        compose.runOnIdle {
            assertEquals(0, late.bindCount)
            assertEquals(1, late.disposeCount)
        }
    }

    @Test fun stalledLoadTimesOutAndRetriesWhileRemainingOnTheSamePage() {
        compose.onNodeWithTag("tab_4").performClick()
        showFooter(AdPlacement.MORE)
        awaitRequests(AdPlacement.MORE, 1)
        compose.runOnIdle { scheduler.advanceBy(20_000L) }
        compose.runOnIdle {
            assertTrue(transport.wasCancelled(AdPlacement.MORE, 1))
            scheduler.advanceBy(30_000L)
        }
        awaitRequests(AdPlacement.MORE, 2)
        val late = TestCreative("Controlled native ad: stale-timeout")
        compose.runOnIdle { transport.deliverLate(AdPlacement.MORE, 1, late) }
        compose.runOnIdle { scheduler.advanceBy(20_000L + 60_000L) }
        awaitRequests(AdPlacement.MORE, 3)
        val loaded = deliver(AdPlacement.MORE, "more-third-attempt", 3)
        assertCardVisible(AdPlacement.MORE, loaded)
        compose.runOnIdle {
            assertEquals(0, late.bindCount)
            assertEquals(1, late.disposeCount)
            scheduler.advanceBy(120_000L)
            assertEquals(3, transport.requestCount(AdPlacement.MORE))
        }
    }

    @Test fun failedCardRecoversAfterNetworkReturnsWithoutChangingPage() {
        compose.onNodeWithTag("tab_4").performClick()
        showFooter(AdPlacement.MORE)
        awaitRequests(AdPlacement.MORE, 1)
        compose.runOnIdle {
            transport.fail(AdPlacement.MORE, 3)
            scheduler.advanceBy(30_000L)
            transport.fail(AdPlacement.MORE, 3)
            scheduler.advanceBy(60_000L)
            transport.fail(AdPlacement.MORE, 3)
            scheduler.advanceBy(120_000L)
            assertEquals("Retries must stop after three failed attempts", 3, transport.requestCount(AdPlacement.MORE))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(host(AdPlacement.MORE)?.childCount == 0)
            NativeAdsTestHooks.networkChanged(false)
            NativeAdsTestHooks.networkChanged(true)
        }
        awaitRequests(AdPlacement.MORE, 4)
        val recovered = deliver(AdPlacement.MORE, "more-recovered", requestNumber = 4)
        assertCardVisible(AdPlacement.MORE, recovered)
        compose.runOnIdle { scheduler.advanceBy(120_000L) }
        assertCardVisible(AdPlacement.MORE, recovered)
        compose.runOnIdle { assertEquals(4, transport.requestCount(AdPlacement.MORE)) }
    }

    @Test fun configurationErrorDoesNotCauseRepeatedRequests() {
        compose.onNodeWithTag("tab_4").performClick()
        showFooter(AdPlacement.MORE)
        awaitRequests(AdPlacement.MORE, 1)
        compose.runOnIdle {
            transport.fail(AdPlacement.MORE, 5)
            scheduler.advanceBy(5 * 60_000L)
            NativeAdsTestHooks.networkChanged(false)
            NativeAdsTestHooks.networkChanged(true)
            scheduler.advanceBy(5 * 60_000L)
            assertEquals(1, transport.requestCount(AdPlacement.MORE))
            assertTrue(host(AdPlacement.MORE)?.childCount == 0)
        }
    }

    @Test fun sdkReadinessTimeoutRetriesWithoutConsumingTheSubsequentLoadBudget() {
        compose.runOnIdle { transport.holdReady = true }
        compose.onNodeWithTag("tab_4").performClick()
        showFooter(AdPlacement.MORE)
        compose.runOnIdle {
            assertEquals(1, transport.heldReadyCount)
            scheduler.advanceBy(20_000L)
            assertTrue(transport.readyWasCancelled(1))
            scheduler.advanceBy(30_000L)
            assertEquals(2, transport.heldReadyCount)
            transport.deliverReady(1) // queued callback from the expired subscription
            assertEquals(0, transport.requestCount(AdPlacement.MORE))

            scheduler.advanceBy(15_000L)
            transport.deliverReady(2)
            assertEquals(1, transport.requestCount(AdPlacement.MORE))
            scheduler.advanceBy(19_000L)
            assertFalse("SDK startup must not consume the separate ad-load budget",
                transport.wasCancelled(AdPlacement.MORE, 1))
        }
        assertCardVisible(AdPlacement.MORE, deliver(AdPlacement.MORE, "more-after-sdk-ready"))
    }

    private fun showFooter(placement: AdPlacement) {
        when (placement) {
            AdPlacement.DAY -> compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(DAY_FOOTNOTE))
            AdPlacement.MORE -> compose.onNodeWithText("Дневник диеты · ${BuildConfig.VERSION_NAME}").performScrollTo()
            else -> error("This regression covers the reported DAY and MORE placements")
        }
        // Both screens put the ad after the explanatory text. This also brings an initially
        // empty lazy footer into composition, without depending on list item indexes.
        compose.onRoot().performTouchInput { swipeUp(startY = height * .75f, endY = height * .25f) }
        compose.waitForIdle()
    }

    private fun awaitRequests(placement: AdPlacement, count: Int) {
        compose.waitUntil(timeoutMillis = 5_000L) {
            var actual = 0
            compose.runOnUiThread { actual = transport.requestCount(placement) }
            actual >= count
        }
    }

    private fun awaitFreshRequest(placement: AdPlacement, count: Int) {
        showFooter(placement)
        compose.runOnIdle {
            // Full page navigation intentionally creates a fresh ad. Respect the minimum
            // interval between requests without sleeping or expiring an already active load.
            if (transport.requestCount(placement) < count) scheduler.advanceBy(30_000L)
        }
        awaitRequests(placement, count)
    }

    private fun deliver(placement: AdPlacement, label: String, requestNumber: Int = 1): TestCreative {
        awaitRequests(placement, requestNumber)
        val creative = TestCreative("Controlled native ad: $label")
        compose.runOnIdle { transport.succeed(placement, creative) }
        compose.waitForIdle()
        return creative
    }

    private fun assertCardVisible(placement: AdPlacement, creative: TestCreative) {
        showFooter(placement)
        compose.runOnIdle {
            val container = requireNotNull(host(placement)) { "Missing real native host for $placement" }
            val view = requireNotNull(creative.view) { "Creative was not bound for $placement" }
            assertEquals("Exactly one creative must be attached", 1, container.childCount)
            assertSame("The expected creative must occupy the actual host", view, container.getChildAt(0))
            assertEquals(creative.label, view.text.toString())
            val visible = Rect()
            assertTrue("$placement creative must be visible after scrolling", view.isShown &&
                view.getGlobalVisibleRect(visible) && visible.height() > 0 && visible.width() > 0)
        }
    }

    private fun host(placement: AdPlacement): ViewGroup? =
        compose.activity.window.decorView.findViewWithTag("native-ad-${placement.name}")

    private class ControlledTransport : NativeAdTransport {
        private data class ReadySubscription(val callback: () -> Unit, var cancelled: Boolean = false)
        private data class Request(
            val unitId: String,
            val onLoaded: (NativeAdCreative) -> Unit,
            val onFailed: (Int) -> Unit,
            var cancelled: Boolean = false,
            var completed: Boolean = false,
        )
        private val requests = mutableListOf<Request>()
        private val heldReady = mutableListOf<ReadySubscription>()
        var holdReady = false
        val heldReadyCount get() = heldReady.size

        override fun whenReady(context: Context, callback: () -> Unit): () -> Unit {
            if (holdReady) {
                val subscription = ReadySubscription(callback)
                heldReady += subscription
                return { subscription.cancelled = true }
            }
            callback()
            return {}
        }

        fun readyWasCancelled(number: Int) = heldReady[number - 1].cancelled
        fun deliverReady(number: Int) { heldReady[number - 1].callback() }

        override fun load(activity: Activity, unitId: String, onLoaded: (NativeAdCreative) -> Unit,
            onFailed: (Int) -> Unit): () -> Unit {
            val request = Request(unitId, onLoaded, onFailed)
            requests += request
            return { request.cancelled = true }
        }

        fun requestCount(placement: AdPlacement) = requests.count { it.unitId == "test-${placement.name}" }

        fun succeed(placement: AdPlacement, creative: NativeAdCreative) {
            pending(placement).also { it.completed = true }.onLoaded(creative)
        }

        fun fail(placement: AdPlacement, code: Int) {
            pending(placement).also { it.completed = true }.onFailed(code)
        }

        fun deliverLate(placement: AdPlacement, number: Int, creative: NativeAdCreative) {
            request(placement, number).also { it.completed = true }.onLoaded(creative)
        }

        fun wasCancelled(placement: AdPlacement, number: Int) = request(placement, number).cancelled

        private fun request(placement: AdPlacement, number: Int) =
            requests.filter { it.unitId == "test-${placement.name}" }[number - 1]

        private fun pending(placement: AdPlacement): Request = requests.lastOrNull {
            it.unitId == "test-${placement.name}" && !it.completed && !it.cancelled
        } ?: error("No active request for $placement")
    }

    private class TestCreative(val label: String) : NativeAdCreative {
        var view: TextView? = null
            private set
        var bindCount = 0
            private set
        var disposeCount = 0
            private set

        override fun bind(activity: Activity, width: Int, palette: NativePalette,
            onClicked: () -> Unit, onImpression: () -> Unit): View {
            bindCount++
            return TextView(activity).apply {
                text = label
                textSize = 16f
                gravity = Gravity.CENTER
                minHeight = (80 * resources.displayMetrics.density).toInt()
                setPadding(12, 12, 12, 12)
                setBackgroundColor(palette.surface)
                setTextColor(palette.text)
                view = this
            }
        }

        override fun updatePalette(palette: NativePalette) {
            view?.setBackgroundColor(palette.surface)
            view?.setTextColor(palette.text)
        }

        override fun dispose() { disposeCount++ }
    }

    /** A clock/queue only: all request, lifetime, pacing and retry decisions stay in production. */
    private class ManualScheduler : NativeAdScheduler {
        private data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private var now = 1_000_000L
        private val tasks = mutableListOf<Task>()

        override fun nowMillis() = now

        override fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delayMillis.coerceAtLeast(0), action)
            tasks += task
            return { task.cancelled = true }
        }

        fun advanceBy(millis: Long) {
            val target = now + millis
            var executed = 0
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                check(executed++ < 100) { "Unbounded native-ad scheduling loop" }
                tasks.remove(next)
                now = next.at
                next.action()
            }
            tasks.removeAll { it.cancelled }
            now = target
        }
    }

    private companion object {
        const val FOOD_ID = "native-ui-food"
        const val FOOD_NAME = "Продукт для проверки карточки"
        const val DAY_FOOTNOTE = "Вес и КБЖУ продукта должны относиться к одному состоянию: сырому, сухому или готовому."
    }
}
