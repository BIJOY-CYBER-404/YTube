package com.example

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.example.cache.ResourceCacheManager

/**
 * Application entry point that warms Chromium's WebView settings/cookie store and
 * triggers asynchronous CDN preconnect + static bundle preloading on cold start.
 */
class YTubeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        ResourceCacheManager.getInstance(this).warmUpAsync()

        Handler(Looper.getMainLooper()).post {
            try {
                WebSettings.getDefaultUserAgent(this)
                CookieManager.getInstance()
            } catch (_: Throwable) {
                // Safe no-op on headless test runners without WebView provider.
            }
        }
    }
}
