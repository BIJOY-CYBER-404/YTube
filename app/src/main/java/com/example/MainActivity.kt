package com.example

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.TubeViewTheme

class MainActivity : ComponentActivity() {

    private val viewModel: TubeViewViewModel by viewModels()

    private lateinit var webView: BackgroundAudioWebView
    private lateinit var fullscreenContainer: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var wasPausedForBgOff: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        webView = createConfiguredWebView()
        MediaPlaybackService.PlayerBridge.attach(webView)
        webView.loadUrl(TubeViewUiState.YOUTUBE_HOME_URL)

        setContent {
            TubeViewTheme(darkTheme = false) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                NotificationPermissionPrompt(
                    backgroundPlayEnabled = uiState.backgroundPlayEnabled,
                )

                val updateActive = uiState.updateState != MandatoryUpdateState.None
                LaunchedEffect(updateActive) {
                    if (updateActive) {
                        webView.evaluateJavascript(JsInjector.buildPauseScript(), null)
                    }
                }

                BackHandler(enabled = !updateActive && (uiState.isFullscreen || uiState.canGoBack)) {
                    when {
                        uiState.isFullscreen -> exitFullscreen()
                        webView.canGoBack() -> webView.goBack()
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (!updateActive) {
                        TubeViewScreen(
                            uiState = uiState,
                            webView = webView,
                            fullscreenContainer = fullscreenContainer,
                            onReloadClick = {
                                viewModel.setMainFrameError(false)
                                webView.reload()
                            },
                        )
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
                                viewModel.launchBrowserUpdate { intent ->
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

    override fun onResume() {
        super.onResume()
        if (wasPausedForBgOff) {
            webView.onResume()
            wasPausedForBgOff = false
        }
        if (viewModel.uiState.value.updateState == MandatoryUpdateState.None) {
            viewModel.checkForRemoteUpdate(force = false)
        }
    }

    override fun onPause() {
        super.onPause()
        val bgEnabled = viewModel.uiState.value.backgroundPlayEnabled
        webView.keepAliveInBackground = bgEnabled

        // CRITICAL LIFECYCLE RULE (FR2.5):
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
            webView.evaluateJavascript(JsInjector.buildPauseScript(), null)
            webView.onPause()
            wasPausedForBgOff = true
        }
    }

    override fun onStop() {
        super.onStop()
        val bgEnabled = viewModel.uiState.value.backgroundPlayEnabled
        if (!bgEnabled && !wasPausedForBgOff) {
            webView.evaluateJavascript(JsInjector.buildPauseScript(), null)
            webView.onPause()
            wasPausedForBgOff = true
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            MediaPlaybackService.stop(this)
        }
        MediaPlaybackService.PlayerBridge.detach(webView)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createConfiguredWebView(): BackgroundAudioWebView {
        val initialBg = viewModel.uiState.value.backgroundPlayEnabled
        val view = BackgroundAudioWebView(this).apply {
            keepAliveInBackground = initialBg
            setBackgroundColor(AndroidColor.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadsImagesAutomatically = true
                useWideViewPort = true
                loadWithOverviewMode = true
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
            addJavascriptInterface(TubeViewJsBridge(), "TubeViewBridge")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }

        view.webViewClient = AdBlockingWebViewClient(
            context = this,
            adBlocker = viewModel.adBlocker,
            isBackgroundPlayEnabled = { viewModel.uiState.value.backgroundPlayEnabled },
            onPageStateChanged = { url, title, canGoBack ->
                viewModel.updatePageState(url, title, canGoBack)
            },
            onMainFrameError = { hasError ->
                viewModel.setMainFrameError(hasError)
            },
            onRenderProcessCrashed = {
                viewModel.setMainFrameError(true)
            },
        )

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(webView: WebView?, newProgress: Int) {
                super.onProgressChanged(webView, newProgress)
                viewModel.updateLoadingProgress(newProgress)
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

    private inner class TubeViewJsBridge {
        @JavascriptInterface
        fun onPlaybackStateChanged(isPlaying: Boolean, title: String?, channel: String?) {
            val cleanTitle = title?.takeIf { it.isNotBlank() } ?: "YTube Audio"
            val cleanChannel = channel?.takeIf { it.isNotBlank() } ?: "YouTube"
            runOnUiThread {
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
