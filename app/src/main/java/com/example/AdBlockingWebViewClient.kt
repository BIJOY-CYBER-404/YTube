package com.example

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.cache.ResourceCacheManager
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * WebViewClient enforcing network-level ad blocking via [AdBlocker], serving cached
 * static assets via [ResourceCacheManager], keeping YouTube and Google Sign-In flows
 * inside the WebView with persistent cookie flushing, and routing external links out
 * to the system browser.
 */
class AdBlockingWebViewClient(
    private val context: Context,
    private val adBlocker: AdBlocker,
    private val resourceCache: ResourceCacheManager? = null,
    private val isBackgroundPlayEnabled: () -> Boolean,
    private val isDarkTheme: () -> Boolean = { false },
    private val onPageStateChanged: (url: String?, title: String?, canGoBack: Boolean) -> Unit,
    private val onMainFrameError: (hasError: Boolean) -> Unit,
    private val onFirstVisualCommit: () -> Unit = {},
    private val onRenderProcessCrashed: () -> Unit = {},
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (adBlocker.isAdRequest(url)) {
            adBlocker.recordBlocked()
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        if (request != null) {
            resourceCache?.intercept(request)?.let { cached ->
                return cached
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return true

        if (scheme == "file" || scheme == "content" || scheme == "javascript" || scheme == "data") {
            return true
        }

        if (scheme != "http" && scheme != "https") {
            return openExternalUri(uri)
        }

        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (scheme == "https" && isInternalYouTubeOrAuthHost(host)) {
            return false
        }

        return openExternalUri(uri)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onMainFrameError(false)
        onPageStateChanged(url, view?.title, view?.canGoBack() == true)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        injectProtectionScripts(view)
        onFirstVisualCommit()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        injectProtectionScripts(view)
        flushSessionCookies(url)
        onFirstVisualCommit()
        onPageStateChanged(url, view?.title, view?.canGoBack() == true)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        // YouTube is an SPA; reinforce protection scripts on client-side route transitions.
        injectProtectionScripts(view)
        onPageStateChanged(url, view?.title, view?.canGoBack() == true)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            onMainFrameError(true)
        }
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?,
    ): Boolean {
        onRenderProcessCrashed()
        return true
    }

    private fun injectProtectionScripts(view: WebView?) {
        view ?: return
        val script = JsInjector.buildProtectionScript(
            backgroundPlayEnabled = isBackgroundPlayEnabled(),
            isDarkTheme = isDarkTheme(),
        )
        view.evaluateJavascript(script, null)
    }

    private fun flushSessionCookies(url: String?) {
        if (url == null) return
        try {
            val host = Uri.parse(url).host?.lowercase(Locale.ROOT) ?: return
            if (isInternalYouTubeOrAuthHost(host)) {
                CookieManager.getInstance().flush()
            }
        } catch (_: Throwable) {
        }
    }

    private fun openExternalUri(uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    companion object {
        private val INTERNAL_HOST_SUFFIXES = listOf(
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "googlevideo.com",
            "ytimg.com",
        )

        private val INTERNAL_AUTH_HOSTS = setOf(
            "accounts.google.com",
            "accounts.youtube.com",
            "myaccount.google.com",
            "ogs.google.com",
            "gds.google.com",
            "consent.google.com",
            "consent.youtube.com",
        )

        fun isInternalYouTubeHost(host: String): Boolean = isInternalYouTubeOrAuthHost(host)

        fun isInternalYouTubeOrAuthHost(host: String): Boolean {
            val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
            if (INTERNAL_AUTH_HOSTS.contains(normalized) || normalized.startsWith("accounts.google.")) {
                return true
            }
            return INTERNAL_HOST_SUFFIXES.any { suffix ->
                normalized == suffix || normalized.endsWith(".$suffix")
            }
        }
    }
}
