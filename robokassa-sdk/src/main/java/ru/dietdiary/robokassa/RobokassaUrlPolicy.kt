/*
 * Local hardening for the MIT-licensed Robokassa payment-window adapter.
 */
package ru.dietdiary.robokassa

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Deliberately independent of android.net.Uri, so URL security rules can be unit tested. */
internal object RobokassaUrlPolicy {
    private val bankScheme = Regex("bank[0-9]{12}")
    private val bankSchemes = setOf(
        "sberpay", "sbolpay", "tinkoffbank", "tinkoff",
        "alfabank", "alfa-payment", "vtb-online", "gazprombank", "raiffeisenonline"
    )
    private val credentialFields = setOf(
        "password", "password1", "password2", "password3",
        "password#1", "password#2", "password#3",
        "merchantpass1", "merchantpass2", "merchantpass3",
        "pass1", "pass2", "pass3"
    )

    fun isCheckoutUrl(value: String): Boolean {
        if (value.length > 16_384) return false
        val uri = httpsUri(value) ?: return false
        return uri.host.equals("auth.robokassa.ru", ignoreCase = true) &&
            uri.rawPath == "/Merchant/Index.aspx"
    }

    fun isRedirectUrl(value: String): Boolean =
        value.length <= 4_096 && httpsUri(value) != null

    // Issuer pages may legitimately use hash/fragment navigation. Initial checkout
    // and configured return URLs remain stricter.
    fun isHttpsUrl(value: String): Boolean = httpsUri(value, allowFragment = true) != null

    fun matchesRedirect(value: String, expected: String): Boolean {
        val uri = httpsUri(value) ?: return false
        val target = httpsUri(expected) ?: return false
        if (!uri.host.equals(target.host, ignoreCase = true) ||
            (uri.rawPath.ifEmpty { "/" }) != (target.rawPath.ifEmpty { "/" })
        ) return false
        // Keep any configured nonce/query selectors, while permitting Robokassa's extra fields.
        val targetQuery = target.rawQuery ?: return true
        val actualFields = uri.rawQuery?.split('&')?.toSet() ?: return false
        return targetQuery.split('&').all { it in actualFields }
    }

    fun isProviderStateUrl(value: String): Boolean {
        val uri = httpsUri(value) ?: return false
        return uri.host.equals("auth.robokassa.ru", ignoreCase = true) &&
            uri.rawPath.startsWith("/Merchant/State/")
    }

    fun isSbpUrl(value: String): Boolean =
        httpsUri(value)?.host.equals("qr.nspk.ru", ignoreCase = true)

    fun isTrustedBankLaunchOrigin(value: String?): Boolean {
        val host = value?.let { httpsUri(it, allowFragment = true) }
            ?.host?.lowercase(Locale.ROOT) ?: return false
        return host == "robokassa.ru" || host.endsWith(".robokassa.ru") || host == "qr.nspk.ru"
    }

    /** Only banking deep links; the outer intent:// envelope is handled by the activity. */
    fun isBankTarget(value: String): Boolean {
        val uri = parse(value) ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (uri.isOpaque || uri.rawUserInfo != null) return false
        return if (bankScheme.matches(scheme)) {
            uri.host.equals("qr.nspk.ru", ignoreCase = true)
        } else {
            scheme in bankSchemes && !uri.host.isNullOrBlank()
        }
    }

    /** Fail closed if a caller accidentally passes common merchant-secret fields. */
    fun hasNoMerchantCredentials(encodedFields: String): Boolean = try {
        encodedFields.split('&').all { field ->
            val key = URLDecoder.decode(field.substringBefore('='), "UTF-8")
                .lowercase(Locale.ROOT)
            key !in credentialFields
        }
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun httpsUri(value: String, allowFragment: Boolean = false): URI? {
        val uri = parse(value) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.isOpaque || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443) || (!allowFragment && uri.rawFragment != null)
        ) return null
        return uri
    }

    private fun parse(value: String): URI? = try {
        URI(value)
    } catch (_: Exception) {
        null
    }
}
