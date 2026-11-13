package com.example

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * WebViewClient enforcing network-level ad blocking via [AdBlocker], injecting
 * SPA-resilient ad/visibility scripts via [JsInjector], and routing external links
 * out to the system browser.
 *
 * Honest limitation note:
 * - Google blocks OAuth sign-in inside embedded WebViews (disallowed_useragent),
 *   so TubeView is designed for signed-out YouTube browsing.
 * - Server-side ads baked directly into googlevideo.com video segments cannot be
 *   intercepted here and rely on JsInjector's skip/fast-forward fallback.
 */
class AdBlockingWebViewClient(
    private val context: Context,
    private val adBlocker: AdBlocker,
    private val isBackgroundPlayEnabled: () -> Boolean,
    private val onPageStateChanged: (url: String?, title: String?, canGoBack: Boolean) -> Unit,
    private val onMainFrameError: (hasError: Boolean) -> Unit,
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
        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?,
    ): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false

        if (scheme != "http" && scheme != "https") {
            return openExternalUri(uri)
        }

        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (isInternalYouTubeHost(host)) {
            return false
        }

        return openExternalUri(uri)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onMainFrameError(false)
        onPageStateChanged(url, view?.title, view?.canGoBack() == true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        injectProtectionScripts(view)
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
        val script = JsInjector.buildProtectionScript(isBackgroundPlayEnabled())
        view.evaluateJavascript(script, null)
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

        fun isInternalYouTubeHost(host: String): Boolean {
            val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
            return INTERNAL_HOST_SUFFIXES.any { suffix ->
                normalized == suffix || normalized.endsWith(".$suffix")
            }
        }
    }
}
