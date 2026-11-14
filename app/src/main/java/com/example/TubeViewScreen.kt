package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.ui.theme.TubeRed

private val YouTubeLightBg = Color(0xFFFFFFFF)

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
            .background(YouTubeLightBg),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = YouTubeLightBg,
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
                            setProgressBackgroundColorSchemeColor(AndroidColor.WHITE)
                            setColorSchemeColors(
                                AndroidColor.parseColor("#FF2A36"),
                                AndroidColor.parseColor("#0F0F0F"),
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

                val showPreloadSkeleton =
                    !uiState.isInitialContentReady &&
                        !uiState.hasMainFrameError &&
                        !uiState.isFullscreen

                AnimatedVisibility(
                    visible = showPreloadSkeleton,
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(240)),
                ) {
                    InitialPreloadSkeleton(
                        progress = uiState.loadingProgress,
                    )
                }

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
private fun InitialPreloadSkeleton(progress: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "preload_shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_offset",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFECEEF2),
            Color(0xFFF6F7FA),
            Color(0xFFECEEF2),
        ),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 360f, 180f),
    )

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("preload_skeleton_overlay"),
        color = YouTubeLightBg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Native top bar placeholder matching YTube light chrome
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 30.dp, height = 22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(TubeRed),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(shimmerBrush),
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(shimmerBrush),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Filter chips skeleton row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(54.dp, 82.dp, 70.dp, 90.dp).forEach { chipWidth ->
                    Box(
                        modifier = Modifier
                            .width(chipWidth)
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(shimmerBrush),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Feed video card placeholders
            repeat(3) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(shimmerBrush),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(shimmerBrush),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(shimmerBrush),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.55f)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(shimmerBrush),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

            if (progress in 1..99) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OfflineErrorOverlay(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = YouTubeLightBg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_wifi_off),
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
