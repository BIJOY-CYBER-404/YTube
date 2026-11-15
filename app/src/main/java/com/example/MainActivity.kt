package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.debug.BugReportStore
import com.example.debug.DebugScreen
import com.example.debug.LogSeverity
import com.example.debug.ShakeDetector
import com.example.ui.theme.YTubeTheme
import com.example.update.MandatoryUpdateScreen
import com.example.update.MandatoryUpdateState
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

class MainActivity : ComponentActivity() {

    private val viewModel: YTubeViewModel by viewModels()

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            val email = account?.email
            if (!email.isNullOrBlank()) {
                val encodedEmail = android.net.Uri.encode(email)
                feedWebView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com&Email=$encodedEmail")
            } else {
                feedWebView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com")
            }
        } catch (e: Exception) {
            BugReportStore.log(
                severity = LogSeverity.WARNING,
                tag = "GoogleSignIn",
                message = "Native account picker fallback to web login (${e.localizedMessage ?: e.javaClass.simpleName})",
            )
            feedWebView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com")
        }
    }

    private lateinit var feedWebView: BackgroundAudioWebView
    private var watchWebView: BackgroundAudioWebView? = null
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var shakeDetector: ShakeDetector
    private var feedRecoveryEpoch by mutableIntStateOf(0)
    private var watchRecoveryEpoch by mutableIntStateOf(0)
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var wasPausedForBgOff: Boolean = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingWatchLoadUrl: String? = null
    private var pendingWatchLayoutListener: View.OnLayoutChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BugReportStore.init(this)
        enableEdgeToEdge()
        val initialDark = isDeviceDarkTheme()
        applySystemBarsAppearance(initialDark)
        syncDeviceGoogleAccount()
        registerNetworkRecoveryCallback()

        shakeDetector = ShakeDetector(this) {
            runOnUiThread {
                val uiState = viewModel.uiState.value
                val updateActive = uiState.updateState != MandatoryUpdateState.None
                if (!updateActive && !uiState.showDebugScreen && BugReportStore.shakeToReportEnabled.value) {
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewModel.openDebugScreen()
                }
            }
        }

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        feedWebView = createConfiguredWebView(initialDark = initialDark, isWatchRole = false)
        MediaPlaybackService.PlayerBridge.attach(feedWebView)
        syncYouTubeThemeCookie(initialDark)
        feedWebView.loadUrl(YTubeUiState.YOUTUBE_HOME_URL)

        setContent {
            val isDarkTheme = isSystemInDarkTheme()
            LaunchedEffect(isDarkTheme) {
                applySystemBarsAppearance(isDarkTheme)
                applyWebViewTheme(feedWebView, isDarkTheme)
                watchWebView?.let { applyWebViewTheme(it, isDarkTheme) }
                syncYouTubeThemeCookie(isDarkTheme)
                val themeScript = JsInjector.buildThemeSyncScript(isDarkTheme)
                feedWebView.evaluateJavascript(themeScript, null)
                watchWebView?.evaluateJavascript(themeScript, null)
            }

            YTubeTheme(darkTheme = isDarkTheme) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val debugLogs by BugReportStore.logs.collectAsStateWithLifecycle()
                val hasUnreadCrash by BugReportStore.hasUnreadCrash.collectAsStateWithLifecycle()
                val shakeEnabled by BugReportStore.shakeToReportEnabled.collectAsStateWithLifecycle()

                NotificationPermissionPrompt(
                    backgroundPlayEnabled = uiState.backgroundPlayEnabled,
                )

                val updateActive = uiState.updateState != MandatoryUpdateState.None
                LaunchedEffect(updateActive) {
                    if (updateActive) {
                        val pauseScript = JsInjector.buildPauseScript()
                        feedWebView.evaluateJavascript(pauseScript, null)
                        watchWebView?.evaluateJavascript(pauseScript, null)
                    }
                }

                val canHandleBack = !updateActive && !uiState.showDebugScreen && (
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
                            feedWebViewProvider = { feedWebView },
                            feedRecoveryEpoch = feedRecoveryEpoch,
                            watchWebViewProvider = { getOrCreateWatchWebView() },
                            watchRecoveryEpoch = watchRecoveryEpoch,
                            fullscreenContainer = fullscreenContainer,
                            onReloadClick = {
                                val currentState = viewModel.uiState.value
                                val feedUrl = feedWebView.url.orEmpty()
                                val isShortsOrWatch = currentState.currentUrl.contains("/shorts", ignoreCase = true) ||
                                    feedUrl.contains("/shorts", ignoreCase = true) ||
                                    currentState.currentUrl.contains("/watch", ignoreCase = true) ||
                                    feedUrl.contains("/watch", ignoreCase = true)
                                val isPlaybackActive = currentState.isFullscreen ||
                                    currentState.playerMode != YTubePlayerMode.HIDDEN ||
                                    currentState.isPlayingMedia ||
                                    isShortsOrWatch
                                if (!currentState.hasMainFrameError && isPlaybackActive) {
                                    return@YTubeScreen
                                }
                                viewModel.setMainFrameError(false)
                                val cacheMode = if (viewModel.resourceCache.isNetworkAvailable()) {
                                    WebSettings.LOAD_DEFAULT
                                } else {
                                    WebSettings.LOAD_CACHE_ELSE_NETWORK
                                }
                                feedWebView.settings.cacheMode = cacheMode
                                feedWebView.reload()
                            },
                            onReloadWatchClick = { reloadActiveWatchVideo() },
                            onMinimizeWatch = { minimizeWatchPlayer() },
                            onExpandWatch = { expandWatchPlayer() },
                            onToggleWatchPlayPause = { toggleWatchPlayPause() },
                            onCloseWatch = { closeWatchPlayer() },
                            onGoogleSignInClick = { openGoogleSignIn() },
                        )

                        if (uiState.isInitialContentReady && uiState.showAuthorDialog && !uiState.showDebugScreen) {
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

                        AnimatedVisibility(
                            visible = uiState.showDebugScreen,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(8f),
                        ) {
                            DebugScreen(
                                uiState = uiState,
                                logs = debugLogs,
                                hasUnreadCrash = hasUnreadCrash,
                                shakeEnabled = shakeEnabled,
                                onToggleShake = { viewModel.setShakeToReportEnabled(it) },
                                onClearLogs = { viewModel.clearDebugLogs() },
                                onClose = { viewModel.closeDebugScreen() },
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

    private fun syncDeviceGoogleAccount() {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.flush()
            viewModel.checkGoogleSignInStatus()
        } catch (_: Exception) {}
    }

    private fun openGoogleSignIn() {
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build()
            val client = GoogleSignIn.getClient(this, gso)
            googleSignInLauncher.launch(client.signInIntent)
        } catch (_: Exception) {
            feedWebView.loadUrl("https://accounts.google.com/AccountChooser?service=youtube&continue=https%3A%2F%2Fm.youtube.com")
        }
    }

    private fun getOrCreateWatchWebView(): BackgroundAudioWebView {
        return watchWebView ?: createConfiguredWebView(
            initialDark = isDeviceDarkTheme(),
            isWatchRole = true,
        ).also { watchWebView = it }
    }

    private fun loadWatchUrlWhenLaidOut(activeWatch: BackgroundAudioWebView, targetUrl: String) {
        pendingWatchLayoutListener?.let { activeWatch.removeOnLayoutChangeListener(it) }
        pendingWatchLayoutListener = null
        pendingWatchLoadUrl = targetUrl

        if (activeWatch.isAttachedToWindow && activeWatch.width > 0 && activeWatch.height > 0) {
            pendingWatchLoadUrl = null
            activeWatch.loadUrl(targetUrl)
            return
        }

        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (right - left > 0 && bottom - top > 0) {
                    activeWatch.removeOnLayoutChangeListener(this)
                    if (pendingWatchLayoutListener === this) {
                        pendingWatchLayoutListener = null
                    }
                    val urlToLoad = pendingWatchLoadUrl ?: return
                    if (viewModel.uiState.value.activeVideoUrl == urlToLoad) {
                        pendingWatchLoadUrl = null
                        activeWatch.loadUrl(urlToLoad)
                    }
                }
            }
        }
        pendingWatchLayoutListener = listener
        activeWatch.addOnLayoutChangeListener(listener)
        activeWatch.postDelayed({
            val urlToLoad = pendingWatchLoadUrl ?: return@postDelayed
            if (viewModel.uiState.value.activeVideoUrl == urlToLoad) {
                activeWatch.removeOnLayoutChangeListener(listener)
                if (pendingWatchLayoutListener === listener) {
                    pendingWatchLayoutListener = null
                }
                pendingWatchLoadUrl = null
                activeWatch.loadUrl(urlToLoad)
            }
        }, 300)
    }

    private fun openWatchVideo(url: String) {
        val activeWatch = getOrCreateWatchWebView()
        MediaPlaybackService.PlayerBridge.attach(activeWatch)
        val videoId = extractYouTubeVideoId(url)
        val targetLoadUrl = if (!videoId.isNullOrBlank()) {
            "https://m.youtube.com/watch?v=$videoId"
        } else {
            url
        }
        val shouldLoadNewUrl = viewModel.openWatchVideo(targetLoadUrl)
        if (shouldLoadNewUrl) {
            activeWatch.stopLoading()
            activeWatch.settings.cacheMode = WebSettings.LOAD_DEFAULT
            loadWatchUrlWhenLaidOut(activeWatch, targetLoadUrl)
            activeWatch.postDelayed({
                activeWatch.evaluateJavascript(JsInjector.buildPlayScript(), null)
                activeWatch.evaluateJavascript(JsInjector.buildUnmuteScript(), null)
            }, 800)
            activeWatch.postDelayed({
                activeWatch.evaluateJavascript(JsInjector.buildPlayScript(), null)
                activeWatch.evaluateJavascript(JsInjector.buildUnmuteScript(), null)
            }, 1800)
        } else {
            activeWatch.evaluateJavascript(JsInjector.buildMinimizedScript(false), null)
            activeWatch.evaluateJavascript(JsInjector.buildPlayScript(), null)
            activeWatch.evaluateJavascript(JsInjector.buildUnmuteScript(), null)
        }
    }

    private fun reloadActiveWatchVideo() {
        val activeUrl = viewModel.uiState.value.activeVideoUrl ?: return
        val activeWatch = getOrCreateWatchWebView()
        viewModel.setWatchError(false)
        activeWatch.stopLoading()
        activeWatch.settings.cacheMode = if (viewModel.resourceCache.isNetworkAvailable()) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
        loadWatchUrlWhenLaidOut(activeWatch, activeUrl)
    }

    private fun registerNetworkRecoveryCallback() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivityManager = cm
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread {
                        feedWebView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                        watchWebView?.settings?.cacheMode = WebSettings.LOAD_DEFAULT
                        val state = viewModel.uiState.value
                        if (state.hasMainFrameError) {
                            viewModel.setMainFrameError(false)
                            feedWebView.reload()
                        }
                        if (state.hasWatchError && state.playerMode != YTubePlayerMode.HIDDEN) {
                            reloadActiveWatchVideo()
                        }
                    }
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
        }
    }

    private fun minimizeWatchPlayer() {
        watchWebView?.evaluateJavascript(JsInjector.buildMinimizedScript(true), null)
        viewModel.minimizeWatchVideo()
    }

    private fun expandWatchPlayer() {
        watchWebView?.evaluateJavascript(JsInjector.buildMinimizedScript(false), null)
        viewModel.expandWatchVideo()
    }

    private fun toggleWatchPlayPause() {
        val activeWatch = watchWebView ?: return
        val isPlaying = viewModel.uiState.value.isPlayingMedia
        val script = if (isPlaying) JsInjector.buildPauseScript() else JsInjector.buildPlayScript()
        activeWatch.evaluateJavascript(script, null)
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
        watchWebView?.let { activeWatch ->
            activeWatch.evaluateJavascript(JsInjector.buildStopScript(), null)
            activeWatch.loadUrl("about:blank")
        }
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
        shakeDetector.start()
        if (wasPausedForBgOff) {
            feedWebView.onResume()
            watchWebView?.onResume()
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
        shakeDetector.stop()
        super.onPause()
        CookieManager.getInstance().flush()
        val bgEnabled = viewModel.uiState.value.backgroundPlayEnabled
        feedWebView.keepAliveInBackground = bgEnabled
        watchWebView?.keepAliveInBackground = bgEnabled

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
            watchWebView?.evaluateJavascript(pauseScript, null)
            feedWebView.onPause()
            watchWebView?.onPause()
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
            watchWebView?.evaluateJavascript(pauseScript, null)
            feedWebView.onPause()
            watchWebView?.onPause()
            wasPausedForBgOff = true
        }
    }

    override fun onDestroy() {
        shakeDetector.stop()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        networkCallback = null
        if (isFinishing) {
            MediaPlaybackService.stop(this)
        }
        watchWebView?.let { activeWatch ->
            MediaPlaybackService.PlayerBridge.detach(activeWatch)
            (activeWatch.parent as? ViewGroup)?.removeView(activeWatch)
            activeWatch.destroy()
        }
        watchWebView = null
        MediaPlaybackService.PlayerBridge.detach(feedWebView)
        (feedWebView.parent as? ViewGroup)?.removeView(feedWebView)
        feedWebView.destroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isDark = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        applySystemBarsAppearance(isDark)
        applyWebViewTheme(feedWebView, isDark)
        watchWebView?.let { applyWebViewTheme(it, isDark) }
        syncYouTubeThemeCookie(isDark)
        val themeScript = JsInjector.buildThemeSyncScript(isDark)
        feedWebView.evaluateJavascript(themeScript, null)
        watchWebView?.evaluateJavascript(themeScript, null)
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

        val existingParams = existingPref.split('&').filter { it.isNotBlank() }
        if (existingParams.any { it == themeValue }) {
            return
        }
        val filteredParams = existingParams.filter { !it.startsWith("f6=") }
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
                cacheMode = WebSettings.LOAD_DEFAULT
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
                    viewModel.checkGoogleSignInStatus()
                }
            },
            onMainFrameError = { hasError ->
                if (!isWatchRole) {
                    viewModel.setMainFrameError(hasError)
                } else {
                    viewModel.setWatchError(hasError)
                }
            },
            onFirstVisualCommit = {
                if (!isWatchRole && viewModel.uiState.value.loadingProgress >= 90) {
                    viewModel.markInitialContentReady()
                }
            },
            onRenderProcessCrashed = {
                runOnUiThread {
                    if (!isWatchRole) {
                        val lastUrl = viewModel.uiState.value.currentUrl
                        (feedWebView.parent as? ViewGroup)?.removeView(feedWebView)
                        runCatching { feedWebView.destroy() }
                        feedWebView = createConfiguredWebView(
                            initialDark = isDeviceDarkTheme(),
                            isWatchRole = false,
                        )
                        if (viewModel.uiState.value.playerMode == YTubePlayerMode.HIDDEN) {
                            MediaPlaybackService.PlayerBridge.attach(feedWebView)
                        }
                        feedWebView.loadUrl(lastUrl)
                        feedRecoveryEpoch++
                    } else {
                        val lastVideoUrl = viewModel.uiState.value.activeVideoUrl
                        watchWebView?.let { crashedWatch ->
                            MediaPlaybackService.PlayerBridge.detach(crashedWatch)
                            (crashedWatch.parent as? ViewGroup)?.removeView(crashedWatch)
                            runCatching { crashedWatch.destroy() }
                        }
                        watchWebView = null
                        if (!lastVideoUrl.isNullOrBlank() && viewModel.uiState.value.playerMode != YTubePlayerMode.HIDDEN) {
                            val recovered = getOrCreateWatchWebView()
                            MediaPlaybackService.PlayerBridge.attach(recovered)
                            recovered.loadUrl(lastVideoUrl)
                            watchRecoveryEpoch++
                        } else {
                            viewModel.closeWatchVideo()
                        }
                    }
                }
            },
        )

        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    BugReportStore.log(
                        severity = LogSeverity.ERROR,
                        tag = if (isWatchRole) "WatchConsole" else "FeedConsole",
                        message = consoleMessage.message().orEmpty(),
                        contextInfo = "${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }

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
                    watchWebView?.evaluateJavascript(JsInjector.buildUnmuteScript(), null)
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
        fun syncWatchUrl(url: String?) {
            if (url.isNullOrBlank()) return
            if (isWatchBridge) {
                runOnUiThread {
                    viewModel.syncWatchVideoUrl(url)
                    watchWebView?.evaluateJavascript(JsInjector.buildUnmuteScript(), null)
                }
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
