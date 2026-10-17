/*
 * Derived from Robokassa Android SDK, Copyright (c) 2024 Robokassa.
 * Licensed under MIT; see LICENSE.txt and UPSTREAM.md.
 */
package ru.dietdiary.robokassa

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * Payment-window adapter. All parameters must already be signed by the merchant backend.
 *
 * No outcome from this window proves payment. The caller must independently fetch the
 * pending order/entitlement from its backend after every return, including cancellation.
 */
object RobokassaCheckout {
    enum class Outcome { RETURNED, CANCELLED, ERROR }

    /**
     * Open an HTTPS Robokassa payment URL, or POST an already encoded and signed body.
     * Do not supply merchant credentials. The two return URLs must be absolute HTTPS URLs.
     * Invalid configuration fails before a payment activity is started.
     */
    fun createIntent(
        context: Context,
        checkoutUrl: String,
        successUrl: String,
        failUrl: String,
        postParams: String? = null
    ): Intent {
        require(RobokassaUrlPolicy.isCheckoutUrl(checkoutUrl)) { "Invalid Robokassa checkout URL" }
        require(RobokassaUrlPolicy.isRedirectUrl(successUrl)) { "Invalid success redirect URL" }
        require(RobokassaUrlPolicy.isRedirectUrl(failUrl)) { "Invalid failure redirect URL" }
        require(RobokassaUrlPolicy.hasNoMerchantCredentials(checkoutUrl.substringAfter('?', ""))) {
            "Merchant credentials must never be passed to checkout"
        }
        if (postParams != null) {
            require(postParams.isNotBlank() && postParams.length <= MAX_POST_LENGTH) {
                "Invalid signed checkout body"
            }
            require(!checkoutUrl.contains('?')) { "POST checkout URL must not contain a query" }
            require(RobokassaUrlPolicy.hasNoMerchantCredentials(postParams)) {
                "Merchant credentials must never be passed to checkout"
            }
        }
        return Intent(context, RobokassaCheckoutActivity::class.java).apply {
            putExtra(EXTRA_URL, checkoutUrl)
            putExtra(EXTRA_SUCCESS_URL, successUrl)
            putExtra(EXTRA_FAIL_URL, failUrl)
            if (postParams != null) putExtra(EXTRA_POST_PARAMS, postParams)
        }
    }

    fun readOutcome(resultCode: Int, data: Intent?): Outcome = when {
        resultCode == Activity.RESULT_OK &&
            data?.getStringExtra(EXTRA_OUTCOME) == Outcome.RETURNED.name -> Outcome.RETURNED
        resultCode == Activity.RESULT_CANCELED -> Outcome.CANCELLED
        else -> Outcome.ERROR
    }

    internal const val EXTRA_URL = "ru.dietdiary.robokassa.checkout_url"
    internal const val EXTRA_SUCCESS_URL = "ru.dietdiary.robokassa.success_url"
    internal const val EXTRA_FAIL_URL = "ru.dietdiary.robokassa.fail_url"
    internal const val EXTRA_POST_PARAMS = "ru.dietdiary.robokassa.post_params"
    internal const val EXTRA_OUTCOME = "ru.dietdiary.robokassa.outcome"
    private const val MAX_POST_LENGTH = 65_536
}
