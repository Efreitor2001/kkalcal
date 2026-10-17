package ru.dietdiary.offline

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class PaymentAvailability(val available: Boolean, val mode: String)
internal class PaymentProblem(val messageResource: Int) : IOException()

/**
 * The application calls only its own HTTPS backend. The backend signs Robokassa orders,
 * accepts authenticated ResultURL notifications and issues offline entitlement receipts.
 * Network payloads, recovery tokens and payment form values are intentionally never logged.
 */
internal class Payments private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("payment-device", Context.MODE_PRIVATE)
    private val operation = Mutex()
    private val configuration = Mutex()
    private var damagedPending = false
    private var pending by mutableStateOf<PendingPayment?>(readPending())
    var availability by mutableStateOf<PaymentAvailability?>(null)
        private set
    var checkingAvailability by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var messageResource by mutableStateOf(R.string.payment_idle)
        private set
    var availabilityMessage by mutableStateOf(R.string.payment_not_configured)
        private set

    val hasPending: Boolean get() = pending != null || damagedPending
    val invoiceId: String? get() = pending?.invoiceId
    val canBuy: Boolean get() = BuildConfig.PAYMENT_ENABLED && availability?.available == true &&
        !checkingAvailability && !busy && !damagedPending && !PaymentEntitlements.adFree
    private val origin = BuildConfig.PAYMENT_API_BASE_URL
    private val receiptVerifier by lazy { PaymentReceiptVerifier(BuildConfig.PAYMENT_PUBLIC_KEY_BASE64) }
    private val configured: Boolean get() = validPaymentOrigin(origin) &&
        receiptVerifier.configured

    private fun readPending(): PendingPayment? = try {
        preferences.getString("pending", null)?.let { PendingPayment.decode(it) }
    } catch (_: Exception) {
        // An unreadable recovery record must never silently turn into a second paid order.
        damagedPending = true
        null
    }

    private fun savePending(value: PendingPayment?) {
        val edit = preferences.edit()
        if (value == null) edit.remove("pending") else edit.putString("pending", value.encode())
        if (!edit.commit()) throw PaymentProblem(R.string.payment_save_failed)
        pending = value
    }

    suspend fun refreshAvailability() = configuration.withLock {
        if (!BuildConfig.PAYMENT_ENABLED || !configured) {
            availability = null
            availabilityMessage = R.string.payment_not_configured
            return@withLock
        }
        checkingAvailability = true
        // Do not leave a stale purchase button visible while location/store admission is unknown.
        availability = null
        try {
            val result = withContext(Dispatchers.IO) {
                PaymentHttp.request("$origin/v1/config?store=${BuildConfig.STORE_ID}")
            }
            val allowed = result.get("available") as? Boolean ?: throw PaymentProblem(R.string.payment_invalid_response)
            validateProduct(result)
            val mode = result.getString("mode")
            requireMode(mode)
            if (allowed && result.opt("publicKeySha256") != receiptVerifier.publicKeySha256)
                throw PaymentProblem(R.string.payment_invalid_response)
            val enabled = allowed && (mode == "live" || BuildConfig.DEBUG)
            availability = PaymentAvailability(enabled, mode)
            availabilityMessage = when {
                allowed && mode == "test" && !BuildConfig.DEBUG -> R.string.payment_not_configured
                !enabled -> R.string.payment_unavailable
                else -> R.string.payment_idle
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) {
            availability = null
            availabilityMessage = (problem as? PaymentProblem)?.messageResource ?: R.string.payment_availability_failed
        } finally { checkingAvailability = false }
    }

    /** Persist idempotency and its secret recovery token before the first outgoing request. */
    suspend fun prepareCheckout(): PendingPayment? {
        if (PaymentEntitlements.adFree || !operation.tryLock()) return null
        busy = true
        messageResource = R.string.payment_preparing
        try {
            refreshAvailability()
            if (availability?.available != true) {
                messageResource = availabilityMessage
                return null
            }
            if (PaymentEntitlements.adFree) { messageResource = R.string.payment_active; return null }
            if (damagedPending) throw PaymentProblem(R.string.payment_recovery_damaged)
            if (AppStore.get(context).isReadOnly) throw PaymentProblem(R.string.core_write_locked)
            // A developer's unfinished sandbox checkout must not block the later live rollout.
            // A real-money pending order is never silently discarded.
            if (pending?.mode == "test" && availability?.mode == "live") {
                withContext(Dispatchers.IO) { savePending(null) }
            }
            val previous = pending ?: withContext(Dispatchers.IO) {
                PendingPayment.create(BuildConfig.STORE_ID, origin).also(::savePending)
            }
            requireSameBackend(previous)
            val response = withContext(Dispatchers.IO) {
                if (previous.invoiceId != null) getOrder(previous) else createOrder(previous)
            }
            val next = applyResponse(previous, response)
            if (next == null) return null
            if (PaymentEntitlements.adFree) { messageResource = R.string.payment_active; return null }
            if (next.mode != "live" && !BuildConfig.DEBUG) throw PaymentProblem(R.string.payment_not_configured)
            if (next.checkoutUrl == null || next.successUrl == null || next.failUrl == null)
                throw PaymentProblem(R.string.payment_unavailable)
            messageResource = R.string.payment_waiting
            return next
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { messageResource = failureMessage(problem); return null }
        finally { busy = false; operation.unlock() }
    }

    /** Return/cancel are only hints to query the server; neither event grants the purchase. */
    suspend fun checkPending(poll: Boolean = false, pendingMessage: Int = R.string.payment_waiting) {
        if (!hasPending || !operation.tryLock()) return
        busy = true
        messageResource = R.string.payment_checking
        try {
            if (damagedPending) throw PaymentProblem(R.string.payment_recovery_damaged)
            if (!configured) throw PaymentProblem(R.string.payment_not_configured)
            repeat(if (poll) 6 else 1) { index ->
                val previous = pending ?: return
                requireSameBackend(previous)
                val response = withContext(Dispatchers.IO) {
                    // If a process died before the first response, replay the SAME order creation.
                    if (previous.invoiceId == null) createOrder(previous) else getOrder(previous)
                }
                if (applyResponse(previous, response) == null) return
                messageResource = pendingMessage
                if (poll && index < 5) delay(2_000)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { messageResource = failureMessage(problem) }
        finally { busy = false; operation.unlock() }
    }

    fun checkoutCouldNotOpen() { messageResource = R.string.payment_open_failed }

    private fun requireSameBackend(value: PendingPayment) {
        if (value.apiOrigin != origin) throw PaymentProblem(R.string.payment_backend_changed)
    }

    private fun createOrder(value: PendingPayment): JSONObject = PaymentHttp.request(
        "$origin/v1/orders", method = "POST", body = value.requestBody())

    private fun getOrder(value: PendingPayment): JSONObject = PaymentHttp.request(
        "$origin/v1/orders/${value.invoiceId}", token = value.clientToken)

    private suspend fun applyResponse(previous: PendingPayment, response: JSONObject): PendingPayment? {
        validateProduct(response, productRequired = false)
        val invoice = response.get("invoiceId") as? String ?: throw PaymentProblem(R.string.payment_invalid_response)
        if (!invoice.matches(Regex("[1-9][0-9]{0,18}")) ||
            (previous.invoiceId != null && previous.invoiceId != invoice))
            throw PaymentProblem(R.string.payment_invalid_response)
        val mode = response.getString("mode")
        requireMode(mode)
        if (previous.mode != null && previous.mode != mode) throw PaymentProblem(R.string.payment_invalid_response)
        when (response.getString("status")) {
            "paid" -> {
                val receipt = response.opt("receipt") as? String
                val proof = PaymentEntitlements.verify(receipt)
                if (mode != "live" || proof == null || proof.invoiceId != invoice)
                    throw PaymentProblem(R.string.payment_invalid_receipt)
                withContext(Dispatchers.IO) {
                    // The proof must be durable in the diary BEFORE discarding recovery credentials.
                    AppStore.get(context).saveAdFreeReceipt(receipt!!)
                    savePending(null)
                }
                messageResource = R.string.payment_active
                // This uses the existing user's automatic-sync setting and connection.
                runCatching { CloudSync.get(context).onAppStart() }
                return null
            }
            "test_paid" -> {
                if (mode != "test") throw PaymentProblem(R.string.payment_invalid_response)
                withContext(Dispatchers.IO) { savePending(null) }
                messageResource = R.string.payment_test_complete
                return null
            }
            "pending" -> {
                val checkoutAllowed = response.opt("checkoutAvailable") == true
                fun optional(key: String): String? = if (checkoutAllowed && response.has(key))
                    response.get(key) as? String ?: throw PaymentProblem(R.string.payment_invalid_response)
                else null
                val checkout = optional("checkoutUrl")
                val postParams = optional("postParams")
                val success = optional("successUrl")
                val fail = optional("failUrl")
                if (!checkoutAllowed) {
                    availability = PaymentAvailability(false, mode)
                    availabilityMessage = R.string.payment_unavailable
                }
                if (checkout != null && !validCheckoutUrl(checkout)) throw PaymentProblem(R.string.payment_invalid_response)
                if (postParams != null && (postParams.length > 32_768 || postParams.any { it.isISOControl() }))
                    throw PaymentProblem(R.string.payment_invalid_response)
                if (success != null && success != "$origin/robokassa/success") throw PaymentProblem(R.string.payment_invalid_response)
                if (fail != null && fail != "$origin/robokassa/fail") throw PaymentProblem(R.string.payment_invalid_response)
                val next = previous.copy(invoiceId = invoice, mode = mode, checkoutUrl = checkout,
                    postParams = postParams, successUrl = success, failUrl = fail)
                withContext(Dispatchers.IO) { savePending(next) }
                return next
            }
            else -> throw PaymentProblem(R.string.payment_invalid_response)
        }
    }

    private fun validateProduct(value: JSONObject, productRequired: Boolean = true) {
        if (value.opt("amount") != "149.00" || value.opt("currency") != "RUB" ||
            (productRequired && value.opt("product") != "ad_free_lifetime"))
            throw PaymentProblem(R.string.payment_invalid_response)
    }

    private fun requireMode(value: String) {
        if (value !in setOf("test", "live")) throw PaymentProblem(R.string.payment_invalid_response)
    }

    private fun failureMessage(problem: Exception): Int =
        (problem as? PaymentProblem)?.messageResource ?: if (hasPending)
            R.string.payment_check_failed else R.string.payment_connection_failed

    companion object {
        @Volatile private var instance: Payments? = null
        fun get(context: Context): Payments = instance ?: synchronized(this) {
            instance ?: Payments(context.applicationContext).also { instance = it }
        }
    }
}

private object PaymentHttp {
    fun request(address: String, method: String = "GET", token: String? = null, body: String? = null): JSONObject {
        val url = URL(address)
        require(url.protocol == "https" && url.userInfo == null)
        val connection = url.openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            body?.toByteArray(Charsets.UTF_8)?.let { bytes ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw PaymentProblem(when (status) {
                403, 503 -> R.string.payment_unavailable
                429 -> R.string.payment_try_later
                else -> R.string.payment_check_failed
            })
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 65_536) throw PaymentProblem(R.string.payment_invalid_response)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
