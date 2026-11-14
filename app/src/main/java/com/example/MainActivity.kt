package com.example

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.YTubeTheme
import com.example.update.MandatoryUpdateScreen
import com.example.update.MandatoryUpdateState

class MainActivity : ComponentActivity() {

    private val viewModel: YTubeViewModel by viewModels()

    private lateinit var feedWebView: BackgroundAudioWebView
    private lateinit var watchWebView: BackgroundAudioWebView
    private lateinit var fullscreenContainer: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var wasPausedForBgOff: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialDark = isDeviceDarkTheme()
        applySystemBarsAppearance(initialDark)

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        feedWebView = createConfiguredWebView(initialDark = initialDark, isWatchRole = false)
        watchWebView = createConfiguredWebView(initialDark = initialDark, isWatchRole = true)
        MediaPlaybackService.PlayerBridge.attach(feedWebView)
        syncYouTubeThemeCookie(initialDark)
        feedWebView.loadUrl(YTubeUiState.YOUTUBE_HOME_URL)

        setContent {
            val isDarkTheme = isSystemInDarkTheme()
            SideEffect {
                applySystemBarsAppearance(isDarkTheme)
                applyWebViewTheme(feedWebView, isDarkTheme)
                applyWebViewTheme(watchWebView, isDarkTheme)
                syncYouTubeThemeCookie(isDarkTheme)
                val themeScript = JsInjector.buildThemeSyncScript(isDarkTheme)
                feedWebView.evaluateJavascript(themeScript, null)
                watchWebView.evaluateJavascript(themeScript, null)
            }

            YTubeTheme(darkTheme = isDarkTheme) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                NotificationPermissionPrompt(
                    backgroundPlayEnabled = uiState.backgroundPlayEnabled,
                )

                val updateActive = uiState.updateState != MandatoryUpdateState.None
                LaunchedEffect(updateActive) {
                    if (updateActive) {
                        val pauseScript = JsInjector.buildPauseScript()
                        feedWebView.evaluateJavascript(pauseScript, null)
                        watchWebView.evaluateJavascript(pauseScript, null)
                    }
                }

                val canHandleBack = !updateActive && (
                    uiState.isFullscreen ||
                        uiState.playerMode == YTubePlayerMode.EXPANDED ||
                        uiState.canGoBack
                    )

                BackHandler(enabled = canHandleBack) {
                    when {
                        uiState.isFullscreen -> exitFullscreen()
                        uiState.playerMode == YTubePlayerMode.EXPANDED -> minimizeWatchPlayer()
                        feedWebView.canGoBack() -> feedWebView.goBack()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (!updateActive) {
                        YTubeScreen(
                            uiState = uiState,
                            feedWebView = feedWebView,
                            watchWebView = watchWebView,
                            fullscreenContainer = fullscreenContainer,
                            onReloadClick = {
                                viewModel.setMainFrameError(false)
                                val cacheMode = if (viewModel.resourceCache.isNetworkAvailable()) {
                                    WebSettings.LOAD_DEFAULT
                                } else {
                                    WebSettings.LOAD_CACHE_ELSE_NETWORK
                                }
                                feedWebView.settings.cacheMode = cacheMode
                                feedWebView.reload()
                            },
                            onMinimizeWatch = { minimizeWatchPlayer() },
                            onExpandWatch = { expandWatchPlayer() },
                            onToggleWatchPlayPause = { toggleWatchPlayPause() },
                            onCloseWatch = { closeWatchPlayer() },
                        )

                        if (uiState.isInitialContentReady && uiState.showAuthorDialog) {
                            AuthorInfoDialog(
                                onContactWhatsApp = {
                                    viewModel.dismissAuthorDialog()
                                    try {
                                        startActivity(AuthorContactHelper.createWhatsAppIntent())
                                    } catch (_: Exception) {
                                    }
                                },
                                onDismiss = {
                                    viewModel.dismissAuthorDialog()
                                },
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = updateActive,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(10f),
                    ) {
                        MandatoryUpdateScreen(
                            updateState = uiState.updateState,
                            currentVersionName = BuildConfig.VERSION_NAME,
                            onStartUpdate = {
                                viewModel.startInAppUpdate { intent ->
                                    startActivity(intent)
                                }
                            },
                            onInstallReadyApk = {
                                viewModel.installDownloadedApk { intent ->
                                    startActivity(intent)
                                }
                            },
                            onRecheckUpdate = {
                                viewModel.checkForRemoteUpdate(force = true)
                            },
                        )
                    }
                }
            }
        }
    }

    private fun openWatchVideo(url: String) {
        MediaPlaybackService.PlayerBridge.attach(watchWebView)
        val shouldLoadNewUrl = viewModel.openWatchVideo(url)
        watchWebView.evaluateJavascript(JsInjector.buildMinimizedScript(false), null)
        if (shouldLoadNewUrl) {
            watchWebView.settings.cacheMode = if (viewModel.resourceCache.isNetworkAvailable()) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }
            watchWebView.loadUrl(url)
        } else {
            watchWebView.evaluateJavascript(JsInjector.buildPlayScript(), null)
        }
    }

    private fun minimizeWatchPlayer() {
        viewModel.minimizeWatchVideo()
        watchWebView.evaluateJavascript(JsInjector.buildMinimizedScript(true), null)
    }

    private fun expandWatchPlayer() {
        viewModel.expandWatchVideo()
        watchWebView.evaluateJavascript(JsInjector.buildMinimizedScript(false), null)
    }

    private fun toggleWatchPlayPause() {
        val isPlaying = viewModel.uiState.value.isPlayingMedia
        val script = if (isPlaying) JsInjector.buildPauseScript() else JsInjector.buildPlayScript()
        watchWebView.evaluateJavascript(script, null)
        viewModel.updateMediaState(
            isPlaying = !isPlaying,
            title = viewModel.uiState.value.mediaTitle,
            channel = viewModel.uiState.value.mediaChannel,
        )
        if (viewModel.uiState.value.backgroundPlayEnabled) {
            MediaPlaybackService.syncPlaybackState(
                context = this,
                isPlaying = !isPlaying,
                title = viewModel.uiState.value.mediaTitle,
                channel = viewModel.uiState.value.mediaChannel,
            )
        }
    }

    private fun closeWatchPlayer() {
        watchWebView.evaluateJavascript(JsInjector.buildStopScript(), null)
        watchWebView.evaluateJavascript(JsInjector.buildMinimizedScript(false), null)
        watchWebView.loadUrl("about:blank")
        MediaPlaybackService.stop(this)
        MediaPlaybackService.PlayerBridge.attach(feedWebView)
        viewModel.closeWatchVideo()
    }

    private fun openBrowseUrlInFeed(url: String) {
        minimizeWatchPlayer()
        feedWebView.loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        if (wasPausedForBgOff) {
            feedWebView.onResume()
            watchWebView.onResume()
            wasPausedForBgOff = false
        }

        val currentUpdate = viewModel.uiState.value.updateState
        if (currentUpdate is MandatoryUpdateState.ReadyToInstall &&
            currentUpdate.needsInstallPermission &&
            viewModel.canRequestPackageInstallsNow()
        ) {
            viewModel.installDownloadedApk { intent ->
                startActivity(intent)
            }
        } else if (currentUpdate == MandatoryUpdateState.None) {
            viewModel.checkForRemoteUpdate(force = false)
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        val bgEnabled = viewModel.uiState.value.backgroundPlayEnabled
        feedWebView.keepAliveInBackground = bgEnabled
        watchWebView.keepAliveInBackground = bgEnabled

        // CRITICAL LIFECYCLE RULE:
        // NEVER call webView.onPause() while background play is enabled — not here and not in onStop().
        if (bgEnabled) {
            if (viewModel.uiState.value.isPlayingMedia) {
                MediaPlaybackService.syncPlaybackState(
                    context = this,
                    isPlaying = true,
                    title = viewModel.uiState.value.mediaTitle,
                    channel = viewModel.uiState.value.mediaChannel,
                )
            }
        } else {
            val pauseScript = JsInjector.buildPauseScript()
            feedWebView.evaluateJavascript(pauseScript, null)
            watchWebView.evaluateJavascript(pauseScript, null)
            feedWebView.onPause()
            watchWebView.onPause()
            wasPausedForBgOff = true
        }
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
        val bgEnabled = viewModel.uiState.value.backgroundPlayEnabled
        if (!bgEnabled && !wasPausedForBgOff) {
            val pauseScript = JsInjector.buildPauseScript()
            feedWebView.evaluateJavascript(pauseScript, null)
            watchWebView.evaluateJavascript(pauseScript, null)
            feedWebView.onPause()
            watchWebView.onPause()
            wasPausedForBgOff = true
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            MediaPlaybackService.stop(this)
        }
        MediaPlaybackService.PlayerBridge.detach(watchWebView)
        MediaPlaybackService.PlayerBridge.detach(feedWebView)
        (watchWebView.parent as? ViewGroup)?.removeView(watchWebView)
        (feedWebView.parent as? ViewGroup)?.removeView(feedWebView)
        watchWebView.destroy()
        feedWebView.destroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        applySystemBarsAppearance(isDark)
        applyWebViewTheme(feedWebView, isDark)
        applyWebViewTheme(watchWebView, isDark)
        syncYouTubeThemeCookie(isDark)
        val themeScript = JsInjector.buildThemeSyncScript(isDark)
        feedWebView.evaluateJavascript(themeScript, null)
        watchWebView.evaluateJavascript(themeScript, null)
    }

    private fun isDeviceDarkTheme(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun applySystemBarsAppearance(isDark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    @Suppress("DEPRECATION")
    private fun applyWebViewTheme(targetWebView: WebView, isDark: Boolean) {
        targetWebView.setBackgroundColor(
            if (isDark) AndroidColor.parseColor("#0F0F0F") else AndroidColor.WHITE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            targetWebView.settings.isAlgorithmicDarkeningAllowed = isDark
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            targetWebView.settings.forceDark = if (isDark) {
                WebSettings.FORCE_DARK_ON
            } else {
                WebSettings.FORCE_DARK_OFF
            }
        }
    }

    private fun syncYouTubeThemeCookie(isDark: Boolean) {
        val cookieManager = CookieManager.getInstance()
        val themeValue = if (isDark) "f6=400" else "f6=80000"
        val existingCookies = cookieManager.getCookie("https://m.youtube.com").orEmpty()
        val existingPref = existingCookies
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("PREF=") }
            ?.removePrefix("PREF=")
            .orEmpty()

        val filteredParams = existingPref
            .split('&')
            .filter { it.isNotBlank() && !it.startsWith("f6=") }
        val newPref = (filteredParams + themeValue).joinToString("&")
        val cookieString = "PREF=$newPref; Domain=.youtube.com; Path=/; Secure; SameSite=Lax"
        cookieManager.setCookie("https://m.youtube.com", cookieString)
        cookieManager.flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createConfiguredWebView(
        initialDark: Boolean,
        isWatchRole: Boolean,
    ): BackgroundAudioWebView {
        val initialBg = viewModel.uiState.value.backgroundPlayEnabled
        val view = BackgroundAudioWebView(this).apply {
            keepAliveInBackground = initialBg
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isNestedScrollingEnabled = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = if (viewModel.resourceCache.isNetworkAvailable()) {
                    WebSettings.LOAD_DEFAULT
                } else {
                    WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
                userAgentString = userAgentString
                    .replace("; wv)", ")")
                    .replace("Version/4.0 ", "")
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = false
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
            addJavascriptInterface(YTubeJsBridge(isWatchBridge = isWatchRole), "YTubeBridge")
        }

        applyWebViewTheme(view, initialDark)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }

        view.webViewClient = AdBlockingWebViewClient(
            context = this,
            adBlocker = viewModel.adBlocker,
            resourceCache = viewModel.resourceCache,
            isBackgroundPlayEnabled = { viewModel.uiState.value.backgroundPlayEnabled },
            isDarkTheme = { isDeviceDarkTheme() },
            interceptWatchLinks = !isWatchRole,
            isWatchRole = isWatchRole,
            isMinimizedWatch = { viewModel.uiState.value.playerMode == YTubePlayerMode.MINIMIZED },
            onOpenWatchVideo = if (!isWatchRole) {
                { videoUrl -> runOnUiThread { openWatchVideo(videoUrl) } }
            } else {
                null
            },
            onOpenInFeed = if (isWatchRole) {
                { browseUrl -> runOnUiThread { openBrowseUrlInFeed(browseUrl) } }
            } else {
                null
            },
            onPageStateChanged = { url, title, canGoBack ->
                if (!isWatchRole) {
                    viewModel.updatePageState(url, title, canGoBack)
                }
            },
            onMainFrameError = { hasError ->
                if (!isWatchRole) {
                    viewModel.setMainFrameError(hasError)
                }
            },
            onFirstVisualCommit = {
                if (!isWatchRole && viewModel.uiState.value.loadingProgress >= 90) {
                    viewModel.markInitialContentReady()
                }
            },
            onRenderProcessCrashed = {
                if (!isWatchRole) {
                    viewModel.setMainFrameError(true)
                } else {
                    viewModel.closeWatchVideo()
                }
            },
        )

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(webView: WebView?, newProgress: Int) {
                super.onProgressChanged(webView, newProgress)
                if (!isWatchRole) {
                    viewModel.updateLoadingProgress(newProgress)
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                val incoming = view ?: return
                customView = incoming
                customViewCallback = callback
                fullscreenContainer.removeAllViews()
                fullscreenContainer.addView(
                    incoming,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                setImmersiveSystemBars(hidden = true)
                viewModel.setFullscreen(true)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        return view
    }

    private fun exitFullscreen() {
        if (customView == null) return
        fullscreenContainer.removeAllViews()
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        setImmersiveSystemBars(hidden = false)
        viewModel.setFullscreen(false)
    }

    private fun setImmersiveSystemBars(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private inner class YTubeJsBridge(private val isWatchBridge: Boolean) {
        @JavascriptInterface
        fun onFirstContentReady() {
            if (!isWatchBridge) {
                runOnUiThread {
                    viewModel.markInitialContentReady()
                }
            }
        }

        @JavascriptInterface
        fun onWatchContentReady() {
            if (isWatchBridge) {
                runOnUiThread {
                    viewModel.markWatchPlayerReady()
                }
            }
        }

        @JavascriptInterface
        fun openVideo(url: String?) {
            if (url.isNullOrBlank()) return
            runOnUiThread {
                openWatchVideo(url)
            }
        }

        @JavascriptInterface
        fun openInFeed(url: String?) {
            if (url.isNullOrBlank()) return
            runOnUiThread {
                openBrowseUrlInFeed(url)
            }
        }

        @JavascriptInterface
        fun onPlaybackStateChanged(isPlaying: Boolean, title: String?, channel: String?) {
            val cleanTitle = title?.takeIf { it.isNotBlank() } ?: "YTube Audio"
            val cleanChannel = channel?.takeIf { it.isNotBlank() } ?: "YouTube"
            runOnUiThread {
                if (!isWatchBridge && viewModel.uiState.value.playerMode != YTubePlayerMode.HIDDEN) {
                    return@runOnUiThread
                }
                viewModel.updateMediaState(isPlaying, cleanTitle, cleanChannel)
                if (viewModel.uiState.value.backgroundPlayEnabled && isPlaying) {
                    MediaPlaybackService.syncPlaybackState(
                        context = this@MainActivity,
                        isPlaying = true,
                        title = cleanTitle,
                        channel = cleanChannel,
                    )
                } else if (viewModel.uiState.value.backgroundPlayEnabled &&
                    MediaPlaybackService.PlayerBridge.isPlaying
                ) {
                    MediaPlaybackService.syncPlaybackState(
                        context = this@MainActivity,
                        isPlaying = false,
                        title = cleanTitle,
                        channel = cleanChannel,
                    )
                }
            }
        }
    }
}
