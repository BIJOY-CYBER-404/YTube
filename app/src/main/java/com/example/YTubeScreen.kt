package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.ui.theme.YTubeRed

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

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun YTubeScreen(
    uiState: YTubeUiState,
    feedWebView: BackgroundAudioWebView,
    watchWebView: BackgroundAudioWebView,
    fullscreenContainer: FrameLayout,
    onReloadClick: () -> Unit,
    onMinimizeWatch: () -> Unit,
    onExpandWatch: () -> Unit,
    onToggleWatchPlayPause: () -> Unit,
    onCloseWatch: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val containerBg = MaterialTheme.colorScheme.background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(containerBg),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = containerBg,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { innerPadding ->
            BoxWithConstraints(
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
                val viewportWidth = maxWidth
                val viewportHeight = maxHeight

                // 1. Feed WebView (Home / Shorts / Subscriptions / Search / Sign-In)
                AndroidView(
                    factory = { ctx ->
                        SwipeRefreshLayout(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setOnChildScrollUpCallback { _, _ ->
                                feedWebView.scrollY > 0 || feedWebView.canScrollVertically(-1)
                            }
                            setOnRefreshListener {
                                onReloadClick()
                            }
                            (feedWebView.parent as? ViewGroup)?.removeView(feedWebView)
                            addView(
                                feedWebView,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                    update = { swipeRefresh ->
                        swipeRefresh.setProgressBackgroundColorSchemeColor(
                            if (isDark) AndroidColor.parseColor("#212121") else AndroidColor.WHITE,
                        )
                        swipeRefresh.setColorSchemeColors(
                            AndroidColor.parseColor("#FF2A36"),
                            if (isDark) AndroidColor.WHITE else AndroidColor.parseColor("#0F0F0F"),
                        )
                        val url = uiState.currentUrl
                        val isShorts = url.contains("/shorts", ignoreCase = true)
                        val isAuth = url.contains("accounts.google.", ignoreCase = true) ||
                            url.contains("accounts.youtube.", ignoreCase = true)
                        swipeRefresh.isEnabled =
                            !uiState.isFullscreen &&
                                uiState.playerMode != YTubePlayerMode.EXPANDED &&
                                !isShorts &&
                                !isAuth
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
                    InitialPreloadSkeleton()
                }

                AnimatedVisibility(
                    visible = uiState.hasMainFrameError && uiState.playerMode != YTubePlayerMode.EXPANDED,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    OfflineErrorOverlay(onRetry = onReloadClick)
                }

                // 2. Native App-Style Watch Player + Floating Bottom Miniplayer
                if (uiState.playerMode != YTubePlayerMode.HIDDEN) {
                    val isExpanded = uiState.playerMode == YTubePlayerMode.EXPANDED
                    val isMinimized = uiState.playerMode == YTubePlayerMode.MINIMIZED

                    val feedHasBottomNav = !uiState.currentUrl.contains("/results", ignoreCase = true) &&
                        !uiState.currentUrl.contains("accounts.google.", ignoreCase = true) &&
                        !uiState.currentUrl.contains("accounts.youtube.", ignoreCase = true)

                    val miniHeight = 62.dp
                    val miniVideoWidth = 110.dp
                    val miniBottomPadding = if (feedHasBottomNav) 49.dp else 0.dp
                    val miniOffsetY = (viewportHeight - miniHeight - miniBottomPadding).coerceAtLeast(0.dp)

                    val animSpec = tween<androidx.compose.ui.unit.Dp>(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing,
                    )
                    val playerWidth by animateDpAsState(
                        targetValue = if (isExpanded) viewportWidth else miniVideoWidth,
                        animationSpec = animSpec,
                        label = "player_width",
                    )
                    val playerHeight by animateDpAsState(
                        targetValue = if (isExpanded) viewportHeight else miniHeight,
                        animationSpec = animSpec,
                        label = "player_height",
                    )
                    val playerOffsetY by animateDpAsState(
                        targetValue = if (isExpanded) 0.dp else miniOffsetY,
                        animationSpec = animSpec,
                        label = "player_offset_y",
                    )
                    val backdropAlpha by animateFloatAsState(
                        targetValue = if (isExpanded) 1f else 0f,
                        animationSpec = tween(200),
                        label = "backdrop_alpha",
                    )

                    // Expanded Watch full-screen backdrop
                    if (backdropAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(backdropAlpha)
                                .background(containerBg),
                        )
                    }

                    // Miniplayer Chrome Bar (visible when minimized, docked right above bottom tabs)
                    if (isMinimized) {
                        val barColor = if (isDark) Color(0xFF1E1E1E) else Color(0xFFFDFDFD)
                        val barBorder = if (isDark) Color(0xFF2F2F2F) else Color(0xFFE6E6E6)
                        Surface(
                            modifier = Modifier
                                .offset(x = 0.dp, y = miniOffsetY)
                                .fillMaxWidth()
                                .height(miniHeight)
                                .testTag("miniplayer_bar"),
                            color = barColor,
                            shadowElevation = 8.dp,
                            tonalElevation = 2.dp,
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                HorizontalDivider(color = barBorder, thickness = 0.75.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable(onClick = onExpandWatch),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Reserved left slot where the live watchWebView renders
                                    Spacer(modifier = Modifier.width(miniVideoWidth))

                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 10.dp),
                                        verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(
                                            text = uiState.mediaTitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = uiState.mediaChannel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    IconButton(
                                        onClick = onToggleWatchPlayPause,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("miniplayer_play_pause_button"),
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                if (uiState.isPlayingMedia) {
                                                    R.drawable.ic_pause
                                                } else {
                                                    R.drawable.ic_play_arrow
                                                },
                                            ),
                                            contentDescription = stringResource(
                                                if (uiState.isPlayingMedia) {
                                                    R.string.action_pause
                                                } else {
                                                    R.string.action_play
                                                },
                                            ),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }

                                    IconButton(
                                        onClick = onCloseWatch,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("miniplayer_close_button"),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_close),
                                            contentDescription = stringResource(R.string.close_player_desc),
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Single animated Watch WebView container (Expanded full watch OR 110x62 live video in Miniplayer)
                    Box(
                        modifier = Modifier
                            .offset(x = 0.dp, y = playerOffsetY)
                            .width(playerWidth)
                            .height(playerHeight)
                            .zIndex(if (isExpanded) 5f else 6f),
                    ) {
                        AndroidView(
                            factory = {
                                (watchWebView.parent as? ViewGroup)?.removeView(watchWebView)
                                watchWebView.apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("watch_webview"),
                        )

                        // Watch skeleton while initial video/metadata mounts so there is zero blank flash
                        if (isExpanded && !uiState.isWatchPlayerReady) {
                            WatchLoadingSkeleton()
                        }

                        // Native top-left Minimize chevron + subtle top-center swipe-down handle (only in Expanded mode)
                        if (isExpanded && !uiState.isFullscreen) {
                            var dragDeltaY = 0f
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .width(140.dp)
                                    .height(28.dp)
                                    .pointerInput(Unit) {
                                        detectVerticalDragGestures(
                                            onDragStart = { dragDeltaY = 0f },
                                            onVerticalDrag = { _, dragAmount ->
                                                dragDeltaY += dragAmount
                                                if (dragDeltaY > 48f) {
                                                    dragDeltaY = 0f
                                                    onMinimizeWatch()
                                                }
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .size(width = 36.dp, height = 4.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.35f)),
                                )
                            }

                            IconButton(
                                onClick = onMinimizeWatch,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = 4.dp, top = 4.dp)
                                    .size(48.dp)
                                    .testTag("minimize_player_button"),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.42f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_expand_more),
                                        contentDescription = stringResource(R.string.minimize_video_desc),
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }

                        // Tap catcher over the 110x62 miniplayer video box so tapping it expands cleanly
                        if (isMinimized) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable(onClick = onExpandWatch)
                                    .testTag("miniplayer_video_tap_target"),
                            )
                        }
                    }
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
private fun WatchLoadingSkeleton() {
    val isDark = isSystemInDarkTheme()
    val shimmerBase = if (isDark) Color(0xFF1F1F1F) else Color(0xFFECEEF2)
    val shimmerHighlight = if (isDark) Color(0xFF2E2E2E) else Color(0xFFF6F7FA)

    val infiniteTransition = rememberInfiniteTransition(label = "watch_shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 950, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "watch_shimmer_offset",
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(shimmerBase, shimmerHighlight, shimmerBase),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 300f, 120f),
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Native edge-to-edge 16:9 black video stage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerBrush),
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(shimmerBrush),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .width(140.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(shimmerBrush),
                    )
                }
                Spacer(modifier = Modifier.height(22.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(shimmerBrush),
                )
            }
        }
    }
}

@Composable
private fun InitialPreloadSkeleton() {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "preload_shimmer")
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_offset",
    )

    val shimmerBase = if (isDark) Color(0xFF1F1F1F) else Color(0xFFECEEF2)
    val shimmerHighlight = if (isDark) Color(0xFF2E2E2E) else Color(0xFFF6F7FA)
    val dividerColor = if (isDark) Color(0xFF272727) else Color(0xFFECEEF2)

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            shimmerBase,
            shimmerHighlight,
            shimmerBase,
        ),
        start = Offset(shimmerOffset, 0f),
        end = Offset(shimmerOffset + 360f, 180f),
    )

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("preload_skeleton_overlay"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
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
                                .background(YTubeRed),
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

                Spacer(modifier = Modifier.height(10.dp))

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

                Spacer(modifier = Modifier.height(14.dp))

                repeat(2) {
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
                        Spacer(modifier = Modifier.height(18.dp))
                    }
                }
            }

            HorizontalDivider(color = dividerColor)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(4) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(shimmerBrush),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(shimmerBrush),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineErrorOverlay(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
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
                tint = YTubeRed,
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
