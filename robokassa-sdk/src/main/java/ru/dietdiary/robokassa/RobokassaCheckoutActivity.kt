/*
 * Adapted from Robokassa_Library/.../view/RobokassaActivity.kt
 * Copyright (c) 2024 Robokassa. MIT license; see LICENSE.txt.
 * Local adaptation removes credentials, local payment approval and persistent order storage.
 */
package ru.dietdiary.robokassa

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Non-exported, credential-free Robokassa checkout window.
 * Closing or returning only tells the host to ask its own backend about the order.
 */
class RobokassaCheckoutActivity : Activity() {
    private var webView: WebView? = null
    private var progress: ProgressBar? = null
    private var successUrl = ""
    private var failUrl = ""
    private var awaitingBankReturn = false
    private var pausedForBank = false
    private var completed = false
    private var lastMainFrameUrl: String? = null
    private var backCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never restore an old payment form or card-entry hierarchy after process death.
        // The host retains the pending order and must reconcile it with the server.
        if (savedInstanceState != null) {
            complete(RobokassaCheckout.Outcome.RETURNED)
            return
        }
        val checkoutUrl = intent.getStringExtra(RobokassaCheckout.EXTRA_URL).orEmpty()
        successUrl = intent.getStringExtra(RobokassaCheckout.EXTRA_SUCCESS_URL).orEmpty()
        failUrl = intent.getStringExtra(RobokassaCheckout.EXTRA_FAIL_URL).orEmpty()
        val postParams = intent.getStringExtra(RobokassaCheckout.EXTRA_POST_PARAMS)
        try {
            // Validate again at the activity boundary, not just in the convenience factory.
            RobokassaCheckout.createIntent(this, checkoutUrl, successUrl, failUrl, postParams)
        } catch (_: IllegalArgumentException) {
            complete(RobokassaCheckout.Outcome.ERROR)
            return
        }
        buildContent()
        configureWebView()
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { complete(RobokassaCheckout.Outcome.CANCELLED) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback!!
            )
        }
        lastMainFrameUrl = checkoutUrl
        if (postParams == null) {
            webView?.loadUrl(checkoutUrl)
        } else {
            webView?.postUrl(checkoutUrl, postParams.toByteArray(Charsets.UTF_8))
        }
    }

    private fun buildContent() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        toolbar.addView(Button(this).apply {
            text = getString(R.string.robokassa_checkout_close)
            setOnClickListener { complete(RobokassaCheckout.Outcome.CANCELLED) }
        })
        toolbar.addView(TextView(this).apply {
            text = "Robokassa"
            textSize = 20f
            setTextColor(Color.rgb(32, 39, 45))
            setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbar)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        root.addView(progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(3)
        ))
        webView = WebView(this).apply {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun configureWebView() {
        val checkout = webView ?: return
        // No JavascriptInterface, browser debugging, downloads or file/content access.
        WebView.setWebContentsDebuggingEnabled(false)
        checkout.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            saveFormData = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        checkout.setDownloadListener { _, _, _, _, _ ->
            notifyUser(R.string.robokassa_checkout_download_blocked)
        }
        checkout.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress?.progress = newProgress
                progress?.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        checkout.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return !RobokassaUrlPolicy.isHttpsUrl(request.url.toString())
                return handleNavigation(request.url.toString(), view.url ?: lastMainFrameUrl)
            }

            @Deprecated("Used by older Android WebView implementations")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleNavigation(url, view.url ?: lastMainFrameUrl)

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (url == null || completed) return
                if (returnFromProvider(url)) return
                if (RobokassaUrlPolicy.isSbpUrl(url)) {
                    view.stopLoading()
                    launchBank(Uri.parse(url))
                    return
                }
                if (!RobokassaUrlPolicy.isHttpsUrl(url)) {
                    view.stopLoading()
                    notifyUser(R.string.robokassa_checkout_unsafe_link)
                    complete(RobokassaCheckout.Outcome.ERROR)
                } else {
                    lastMainFrameUrl = url
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                notifyUser(R.string.robokassa_checkout_connection_error)
                complete(RobokassaCheckout.Outcome.ERROR)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !completed) {
                    notifyUser(R.string.robokassa_checkout_connection_error)
                    complete(RobokassaCheckout.Outcome.ERROR)
                }
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400 && !completed) {
                    notifyUser(R.string.robokassa_checkout_connection_error)
                    complete(RobokassaCheckout.Outcome.ERROR)
                }
            }
        }
    }

    private fun handleNavigation(url: String, source: String?): Boolean {
        if (completed || returnFromProvider(url)) return true
        if (RobokassaUrlPolicy.isSbpUrl(url)) {
            return launchBank(Uri.parse(url))
        }
        if (RobokassaUrlPolicy.isHttpsUrl(url)) return false
        if (!RobokassaUrlPolicy.isTrustedBankLaunchOrigin(source)) {
            notifyUser(R.string.robokassa_checkout_unsafe_link)
            return true
        }
        val target = try {
            if (url.startsWith("intent:", ignoreCase = true)) {
                // Keep only the validated data URI. Discard component, selector, package,
                // extras, fallback URLs and flags from the untrusted intent envelope.
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME).data
            } else {
                Uri.parse(url)
            }
        } catch (_: Exception) {
            null
        }
        if (target == null || !RobokassaUrlPolicy.isBankTarget(target.toString())) {
            notifyUser(R.string.robokassa_checkout_unsafe_link)
            return true
        }
        return launchBank(target)
    }

    private fun returnFromProvider(url: String): Boolean = when {
        RobokassaUrlPolicy.matchesRedirect(url, failUrl) -> {
            complete(RobokassaCheckout.Outcome.CANCELLED)
            true
        }
        RobokassaUrlPolicy.matchesRedirect(url, successUrl) ||
            RobokassaUrlPolicy.isProviderStateUrl(url) -> {
            complete(RobokassaCheckout.Outcome.RETURNED)
            true
        }
        else -> false
    }

    private fun launchBank(target: Uri): Boolean {
        val bankIntent = Intent(Intent.ACTION_VIEW, target).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return try {
            awaitingBankReturn = true
            pausedForBank = false
            startActivity(bankIntent)
            true
        } catch (_: ActivityNotFoundException) {
            awaitingBankReturn = false
            notifyUser(R.string.robokassa_checkout_bank_unavailable)
            true
        } catch (_: SecurityException) {
            awaitingBankReturn = false
            notifyUser(R.string.robokassa_checkout_bank_unavailable)
            true
        }
    }

    override fun onPause() {
        if (awaitingBankReturn) pausedForBank = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingBankReturn && pausedForBank) {
            awaitingBankReturn = false
            complete(RobokassaCheckout.Outcome.RETURNED)
        }
    }

    @Deprecated("Used on Android 12 and earlier")
    override fun onBackPressed() {
        complete(RobokassaCheckout.Outcome.CANCELLED)
    }

    private fun complete(outcome: RobokassaCheckout.Outcome) {
        if (completed) return
        completed = true
        val resultCode = when (outcome) {
            RobokassaCheckout.Outcome.RETURNED -> RESULT_OK
            RobokassaCheckout.Outcome.CANCELLED -> RESULT_CANCELED
            RobokassaCheckout.Outcome.ERROR -> RESULT_FIRST_USER
        }
        setResult(resultCode, Intent().putExtra(RobokassaCheckout.EXTRA_OUTCOME, outcome.name))
        finish()
    }

    private fun notifyUser(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        webView?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
