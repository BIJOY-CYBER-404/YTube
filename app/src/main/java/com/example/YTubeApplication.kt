package com.example

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import com.example.cache.ResourceCacheManager
import com.example.debug.BugReportStore
import java.io.ByteArrayInputStream

/**
 * Application entry point that initializes persistent crash capture, warms Chromium's WebView
 * settings/cookie store, routes Service Worker network fetches through YTube's AdBlocker +
 * ResourceCacheManager, and triggers asynchronous CDN preconnect + static bundle preloading.
 */
class YTubeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        BugReportStore.installUncaughtExceptionHandler(this)
        val resourceCache = ResourceCacheManager.getInstance(this)
        val adBlocker = AdBlocker.fromAssets(this)
        resourceCache.warmUpAsync()

        Handler(Looper.getMainLooper()).post {
            try {
                WebSettings.getDefaultUserAgent(this)
                CookieManager.getInstance()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    ServiceWorkerController.getInstance().setServiceWorkerClient(
                        object : ServiceWorkerClient() {
                            override fun shouldInterceptRequest(
                                request: WebResourceRequest,
                            ): WebResourceResponse? {
                                val url = request.url?.toString()
                                if (adBlocker.isAdRequest(url)) {
                                    adBlocker.recordBlocked()
                                    return WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        ByteArrayInputStream(ByteArray(0)),
                                    )
                                }
                                return resourceCache.intercept(request)
                            }
                        },
                    )
                }
            } catch (_: Throwable) {
                // Safe no-op on headless test runners without WebView provider.
            }
        }
    }
}
