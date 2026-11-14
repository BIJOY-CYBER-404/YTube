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
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
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
import coil.compose.AsyncImage
import com.example.ui.theme.YTubeRed
import java.util.Locale

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

fun extractYouTubeVideoId(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return try {
        val uri = android.net.Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host.contains("youtu.be")) {
            uri.path?.trim('/')?.takeIf { it.isNotBlank() }
        } else if (host.contains("youtube.com")) {
            if (uri.path?.contains("/shorts/") == true) {
                uri.path?.substringAfter("/shorts/")?.substringBefore('/')
            } else {
                uri.getQueryParameter("v")
            }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun YTubeScreen(
    uiState: YTubeUiState,
    feedWebViewProvider: () -> BackgroundAudioWebView,
    feedRecoveryEpoch: Int,
    watchWebViewProvider: () -> BackgroundAudioWebView,
    watchRecoveryEpoch: Int,
    fullscreenContainer: FrameLayout,
    onReloadClick: () -> Unit,
    onMinimizeWatch: () -> Unit,
    onExpandWatch: () -> Unit,
    onToggleWatchPlayPause: () -> Unit,
    onCloseWatch: () -> Unit,
    onGoogleSignInClick: () -> Unit,
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
                if (!uiState.isGoogleSignedIn && uiState.isInitialContentReady && uiState.playerMode == YTubePlayerMode.HIDDEN && !uiState.isFullscreen) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 12.dp)
                            .zIndex(4f)
                            .clickable(onClick = onGoogleSignInClick),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 4.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_person),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Sign in",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                // 1. Feed WebView (Home / Shorts / Subscriptions / Search / Sign-In)
                val latestUiState by rememberUpdatedState(uiState)
                key(feedRecoveryEpoch) {
                    val activeFeedWebView = feedWebViewProvider()
                    AndroidView(
                        factory = { ctx ->
                            SwipeRefreshLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                setOnChildScrollUpCallback { _, _ ->
                                    val liveState = latestUiState
                                    val url = liveState.currentUrl
                                    val webUrl = activeFeedWebView.url.orEmpty()
                                    val isShortsOrWatch = url.contains("/shorts", ignoreCase = true) ||
                                        webUrl.contains("/shorts", ignoreCase = true) ||
                                        url.contains("/watch", ignoreCase = true) ||
                                        webUrl.contains("/watch", ignoreCase = true)
                                    val isPlaybackActive = liveState.isFullscreen ||
                                        liveState.playerMode != YTubePlayerMode.HIDDEN ||
                                        liveState.isPlayingMedia ||
                                        isShortsOrWatch
                                    isPlaybackActive ||
                                        activeFeedWebView.scrollY > 0 ||
                                        activeFeedWebView.canScrollVertically(-1)
                                }
                                setOnRefreshListener {
                                    val liveState = latestUiState
                                    val url = liveState.currentUrl
                                    val webUrl = activeFeedWebView.url.orEmpty()
                                    val isShortsOrWatch = url.contains("/shorts", ignoreCase = true) ||
                                        webUrl.contains("/shorts", ignoreCase = true) ||
                                        url.contains("/watch", ignoreCase = true) ||
                                        webUrl.contains("/watch", ignoreCase = true)
                                    val isPlaybackActive = liveState.isFullscreen ||
                                        liveState.playerMode != YTubePlayerMode.HIDDEN ||
                                        liveState.isPlayingMedia ||
                                        isShortsOrWatch
                                    if (!liveState.hasMainFrameError && isPlaybackActive) {
                                        isRefreshing = false
                                    } else {
                                        onReloadClick()
                                    }
                                }
                                (activeFeedWebView.parent as? ViewGroup)?.removeView(activeFeedWebView)
                                addView(
                                    activeFeedWebView,
                                    ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    ),
                                )
                            }
                        },
                        update = { swipeRefresh ->
                            if (swipeRefresh.tag != isDark) {
                                swipeRefresh.tag = isDark
                                swipeRefresh.setProgressBackgroundColorSchemeColor(
                                    if (isDark) AndroidColor.parseColor("#212121") else AndroidColor.WHITE,
                                )
                                swipeRefresh.setColorSchemeColors(
                                    AndroidColor.parseColor("#FF2A36"),
                                    if (isDark) AndroidColor.WHITE else AndroidColor.parseColor("#0F0F0F"),
                                )
                            }
                            val url = uiState.currentUrl
                            val webUrl = activeFeedWebView.url.orEmpty()
                            val isShorts = url.contains("/shorts", ignoreCase = true) ||
                                webUrl.contains("/shorts", ignoreCase = true)
                            val isWatch = url.contains("/watch", ignoreCase = true) ||
                                webUrl.contains("/watch", ignoreCase = true)
                            val isAuth = url.contains("accounts.google.", ignoreCase = true) ||
                                url.contains("accounts.youtube.", ignoreCase = true)
                            val allowPullToRefresh =
                                !uiState.isFullscreen &&
                                    uiState.playerMode == YTubePlayerMode.HIDDEN &&
                                    !uiState.isPlayingMedia &&
                                    !isShorts &&
                                    !isWatch &&
                                    !isAuth
                            if (swipeRefresh.isEnabled != allowPullToRefresh) {
                                swipeRefresh.isEnabled = allowPullToRefresh
                            }
                            if ((!allowPullToRefresh || uiState.loadingProgress >= 90 || uiState.hasMainFrameError) &&
                                swipeRefresh.isRefreshing
                            ) {
                                swipeRefresh.isRefreshing = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("youtube_webview_refresh"),
                    )
                }

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

                // 2. Native App-Style Watch Player + Floating Compose Miniplayer Card
                if (uiState.playerMode != YTubePlayerMode.HIDDEN) {
                    val isExpanded = uiState.playerMode == YTubePlayerMode.EXPANDED
                    val isMinimized = uiState.playerMode == YTubePlayerMode.MINIMIZED

                    val feedHasBottomNav = !uiState.currentUrl.contains("/results", ignoreCase = true) &&
                        !uiState.currentUrl.contains("accounts.google.", ignoreCase = true) &&
                        !uiState.currentUrl.contains("accounts.youtube.", ignoreCase = true)

                    val miniHeight = 64.dp
                    val miniBottomPadding = if (feedHasBottomNav) 48.dp else 0.dp

                    // Watch WebView container (Visible when Expanded; when Minimized, kept compact & alive in background without allocating full-screen GPU layer)
                    Box(
                        modifier = Modifier
                            .let { mod ->
                                if (isExpanded) {
                                    mod.fillMaxSize().zIndex(5f).alpha(1f)
                                } else {
                                    mod.size(1.dp).zIndex(-1f).alpha(0.01f)
                                }
                            },
                    ) {
                        key(watchRecoveryEpoch) {
                            val activeWatchWebView = watchWebViewProvider()
                            AndroidView(
                                factory = {
                                    (activeWatchWebView.parent as? ViewGroup)?.removeView(activeWatchWebView)
                                    activeWatchWebView.apply {
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
                        }

                        if (isExpanded && !uiState.isWatchPlayerReady) {
                            WatchLoadingSkeleton()
                        }

                        // Native top-left Minimize chevron + top-center swipe-down drag handle
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
                                                if (dragDeltaY > 44f) {
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
                                        .background(Color.White.copy(alpha = 0.4f)),
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
                                        .background(Color.Black.copy(alpha = 0.45f)),
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
                    }

                    // Native Compose Miniplayer Card (Floating at bottom when minimized)
                    if (isMinimized) {
                        val cardBg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF9F9F9)
                        val borderColor = if (isDark) Color(0xFF2E2E2E) else Color(0xFFE5E5E5)
                        val videoId = extractYouTubeVideoId(uiState.activeVideoUrl)
                        val thumbnailUrl = videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = miniBottomPadding)
                                .fillMaxWidth()
                                .height(miniHeight)
                                .clickable(onClick = onExpandWatch)
                                .testTag("miniplayer_card"),
                            color = cardBg,
                            shadowElevation = 10.dp,
                            tonalElevation = 4.dp,
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                HorizontalDivider(color = borderColor, thickness = 1.dp)
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(onClick = onExpandWatch),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(width = 86.dp, height = 48.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.Black),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (thumbnailUrl != null) {
                                                AsyncImage(
                                                    model = thumbnailUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(
                                            modifier = Modifier.weight(1f),
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
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = onToggleWatchPlayPause,
                                            modifier = Modifier
                                                .size(42.dp)
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
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }

                                        IconButton(
                                            onClick = onCloseWatch,
                                            modifier = Modifier
                                                .size(42.dp)
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
