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
import com.example.debug.BugReportStore
import com.example.debug.LogSeverity
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * WebViewClient enforcing network-level ad blocking via [AdBlocker], serving cached
 * static assets via [ResourceCacheManager], routing /watch videos to the dedicated
 * app-style Watch player without disturbing or reloading the feed, keeping Google Sign-In
 * inside the WebView with persistent cookies, and routing external links out.
 */
class AdBlockingWebViewClient(
    private val context: Context,
    private val adBlocker: AdBlocker,
    private val resourceCache: ResourceCacheManager? = null,
    private val isBackgroundPlayEnabled: () -> Boolean,
    private val isDarkTheme: () -> Boolean = { false },
    private val interceptWatchLinks: Boolean = false,
    private val isWatchRole: Boolean = false,
    private val isMinimizedWatch: () -> Boolean = { false },
    private val onOpenWatchVideo: ((String) -> Unit)? = null,
    private val onOpenInFeed: ((String) -> Unit)? = null,
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

        if (scheme == "about") {
            return false
        }

        if (scheme == "file" || scheme == "content" || scheme == "javascript" || scheme == "data") {
            return true
        }

        if (scheme != "http" && scheme != "https") {
            return openExternalUri(uri)
        }

        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (scheme == "https" && isInternalYouTubeOrAuthHost(host)) {
            val path = uri.path.orEmpty()
            val isWatchUrl = isYouTubeWatchUri(uri)

            if (interceptWatchLinks && isWatchUrl && onOpenWatchVideo != null) {
                onOpenWatchVideo.invoke(uri.toString())
                return true
            }

            if (isWatchRole && !isWatchUrl && !isAuthHost(host) && onOpenInFeed != null) {
                val isBrowsePath = path == "/" ||
                    path.startsWith("/@") ||
                    path.startsWith("/channel/") ||
                    path.startsWith("/c/") ||
                    path.startsWith("/user/") ||
                    path.startsWith("/results") ||
                    path.startsWith("/feed/") ||
                    path.startsWith("/shorts")
                if (isBrowsePath) {
                    onOpenInFeed.invoke(uri.toString())
                    return true
                }
            }
            return false
        }

        return openExternalUri(uri)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url == "about:blank") return
        onMainFrameError(false)
        onPageStateChanged(url, view?.title, view?.canGoBack() == true)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        if (url == "about:blank") return
        injectProtectionScripts(view)
        onFirstVisualCommit()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (url == "about:blank") return
        injectProtectionScripts(view)
        flushSessionCookies(url)
        onFirstVisualCommit()
        onPageStateChanged(url, view?.title, view?.canGoBack() == true)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (url == "about:blank") return

        if (interceptWatchLinks && url != null && onOpenWatchVideo != null) {
            val parsed = runCatching { Uri.parse(url) }.getOrNull()
            if (parsed != null && isYouTubeWatchUri(parsed)) {
                onOpenWatchVideo.invoke(url)
                if (view?.canGoBack() == true) {
                    view.goBack()
                }
                return
            }
        }

        // YouTube is an SPA; reinforce protection scripts lightly on client-side transitions
        // (e.g. Shorts swipes, tab switches) without re-evaluating the full 43KB payload.
        injectSpaTransitionScript(view)
        onPageStateChanged(url, view?.title, view?.canGoBack() == true)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request?.url?.toString() == "about:blank") return
        if (request?.isForMainFrame == true) {
            BugReportStore.log(
                severity = LogSeverity.ERROR,
                tag = if (isWatchRole) "WatchWebView" else "FeedWebView",
                message = "Main-frame error (${error?.errorCode}): ${error?.description ?: "Unknown"}",
                contextInfo = request.url?.toString(),
            )
            onMainFrameError(true)
        }
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?,
    ): Boolean {
        val didCrash = detail?.didCrash() == true
        BugReportStore.log(
            severity = if (didCrash) LogSeverity.CRASH else LogSeverity.ERROR,
            tag = if (isWatchRole) "WatchRenderer" else "FeedRenderer",
            message = if (didCrash) {
                "WebView render process crashed (didCrash=true)"
            } else {
                "WebView render process killed by system (didCrash=false)"
            },
            contextInfo = view?.url,
        )
        onRenderProcessCrashed()
        return true
    }

    private fun injectProtectionScripts(view: WebView?) {
        view ?: return
        val script = JsInjector.buildProtectionScript(
            backgroundPlayEnabled = isBackgroundPlayEnabled(),
            isDarkTheme = isDarkTheme(),
            isWatchRole = isWatchRole,
        )
        view.evaluateJavascript(script, null)
        if (isWatchRole) {
            view.evaluateJavascript(JsInjector.buildMinimizedScript(isMinimizedWatch()), null)
        }
    }

    private fun injectSpaTransitionScript(view: WebView?) {
        view ?: return
        val script = JsInjector.buildSpaSyncScript(
            backgroundPlayEnabled = isBackgroundPlayEnabled(),
            isDarkTheme = isDarkTheme(),
            isWatchRole = isWatchRole,
            isMinimized = isWatchRole && isMinimizedWatch(),
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

        fun isYouTubeWatchUri(uri: Uri): Boolean {
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
            if (host == "youtu.be") {
                return uri.path?.trim('/')?.isNotEmpty() == true
            }
            val isYt = host == "youtube.com" || host.endsWith(".youtube.com")
            if (!isYt) return false
            val path = uri.path.orEmpty()
            return path.equals("/watch", ignoreCase = true) && !uri.getQueryParameter("v").isNullOrBlank()
        }

        fun isAuthHost(host: String): Boolean {
            val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
            return INTERNAL_AUTH_HOSTS.contains(normalized) || normalized.startsWith("accounts.google.")
        }

        fun isInternalYouTubeHost(host: String): Boolean = isInternalYouTubeOrAuthHost(host)

        fun isInternalYouTubeOrAuthHost(host: String): Boolean {
            val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
            if (isAuthHost(normalized)) {
                return true
            }
            return INTERNAL_HOST_SUFFIXES.any { suffix ->
                normalized == suffix || normalized.endsWith(".$suffix")
            }
        }
    }
}
