package ru.dietdiary.offline

import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Device-local recovery credentials. This record never enters diary backups or Google Drive. */
internal data class PendingPayment(
    val idempotencyKey: String,
    val clientToken: String,
    val store: String,
    val apiOrigin: String,
    val invoiceId: String? = null,
    val checkoutUrl: String? = null,
    val postParams: String? = null,
    val successUrl: String? = null,
    val failUrl: String? = null,
    val mode: String? = null,
) {
    fun encode(): String = JSONObject().put("version", 1)
        .put("idempotencyKey", idempotencyKey).put("clientToken", clientToken)
        .put("store", store).put("apiOrigin", apiOrigin)
        .put("invoiceId", invoiceId).put("checkoutUrl", checkoutUrl).put("postParams", postParams)
        .put("successUrl", successUrl).put("failUrl", failUrl).put("mode", mode).toString()

    fun requestBody(): String = JSONObject().put("store", store)
        .put("idempotencyKey", idempotencyKey).put("clientToken", clientToken).toString()

    companion object {
        fun create(store: String, apiOrigin: String): PendingPayment {
            require(store in setOf("googlePlay", "rustore", "huawei") && validPaymentOrigin(apiOrigin))
            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return PendingPayment(UUID.randomUUID().toString(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(token), store, apiOrigin)
        }

        fun decode(text: String): PendingPayment {
            require(text.length <= 65_536)
            val json = JSONObject(text)
            require(json.get("version") == 1)
            fun optional(key: String): String? = if (json.isNull(key)) null else json.get(key) as? String
                ?: throw IllegalArgumentException("Invalid pending payment")
            val result = PendingPayment(json.getString("idempotencyKey"), json.getString("clientToken"),
                json.getString("store"), json.getString("apiOrigin"), optional("invoiceId"),
                optional("checkoutUrl"), optional("postParams"), optional("successUrl"), optional("failUrl"), optional("mode"))
            require(UUID.fromString(result.idempotencyKey).toString() == result.idempotencyKey)
            require(result.clientToken.matches(Regex("[A-Za-z0-9_-]{43}")))
            require(Base64.getUrlDecoder().decode(result.clientToken).size == 32)
            require(result.store in setOf("googlePlay", "rustore", "huawei"))
            require(validPaymentOrigin(result.apiOrigin))
            require(result.invoiceId == null || result.invoiceId.matches(Regex("[1-9][0-9]{0,18}")))
            require(result.mode == null || result.mode in setOf("test", "live"))
            require(result.postParams == null || result.postParams.length <= 32_768)
            return result
        }
    }
}

internal fun validPaymentOrigin(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.port == -1 && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false)

internal fun validCheckoutUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host == "auth.robokassa.ru" && uri.port == -1 &&
        uri.rawUserInfo == null && uri.rawPath == "/Merchant/Index.aspx" && uri.rawFragment == null
}.getOrDefault(false)
