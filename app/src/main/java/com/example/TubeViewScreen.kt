package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.ui.theme.TubeRed

private val YouTubeDarkBg = Color(0xFF0F0F0F)

@Composable
fun NotificationPermissionPrompt(backgroundPlayEnabled: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !backgroundPlayEnabled) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Graceful: playback still works if notification permission is denied */ },
    )
    LaunchedEffect(backgroundPlayEnabled) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun TubeViewScreen(
    uiState: TubeViewUiState,
    webView: BackgroundAudioWebView,
    fullscreenContainer: FrameLayout,
    onReloadClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(YouTubeDarkBg),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = YouTubeDarkBg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .let { mod ->
                        if (uiState.isFullscreen) {
                            mod
                        } else {
                            mod
                                .statusBarsPadding()
                                .navigationBarsPadding()
                        }
                    },
            ) {
                AndroidView(
                    factory = { ctx ->
                        SwipeRefreshLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setProgressBackgroundColorSchemeColor(AndroidColor.parseColor("#212121"))
                            setColorSchemeColors(
                                AndroidColor.parseColor("#FF2A36"),
                                AndroidColor.WHITE,
                            )
                            setOnChildScrollUpCallback { _, _ ->
                                webView.scrollY > 0 || webView.canScrollVertically(-1)
                            }
                            setOnRefreshListener {
                                onReloadClick()
                            }
                            (webView.parent as? ViewGroup)?.removeView(webView)
                            addView(
                                webView,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                    update = { swipeRefresh ->
                        val isShorts = uiState.currentUrl.contains("/shorts", ignoreCase = true)
                        swipeRefresh.isEnabled = !uiState.isFullscreen && !isShorts
                        if (swipeRefresh.isRefreshing &&
                            (uiState.loadingProgress >= 90 || uiState.hasMainFrameError)
                        ) {
                            swipeRefresh.isRefreshing = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("youtube_webview_refresh"),
                )

                if (!uiState.isFullscreen && uiState.loadingProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { uiState.loadingProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter)
                            .testTag("page_progress_bar"),
                        color = TubeRed,
                        trackColor = Color.Transparent,
                    )
                }

                AnimatedVisibility(
                    visible = uiState.hasMainFrameError,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    OfflineErrorOverlay(onRetry = onReloadClick)
                }
            }
        }

        if (uiState.isFullscreen) {
            AndroidView(
                factory = {
                    (fullscreenContainer.parent as? ViewGroup)?.removeView(fullscreenContainer)
                    fullscreenContainer
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .testTag("fullscreen_video_container"),
            )
        }
    }
}

@Composable
private fun OfflineErrorOverlay(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = YouTubeDarkBg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = null,
                tint = TubeRed,
                modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.offline_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.offline_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("retry_button"),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}
