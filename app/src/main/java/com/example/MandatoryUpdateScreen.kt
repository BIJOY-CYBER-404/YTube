package com.example

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.ShieldEmeraldContainer
import com.example.ui.theme.TubeRed
import com.example.ui.theme.TubeRedDark

sealed interface MandatoryUpdateState {
    data object None : MandatoryUpdateState
    data class Available(val release: GitHubReleaseInfo) : MandatoryUpdateState
    data class Downloading(val release: GitHubReleaseInfo, val progressPercent: Int) : MandatoryUpdateState
    data class NeedsInstallPermission(val release: GitHubReleaseInfo) : MandatoryUpdateState
    data class ReadyToInstall(val release: GitHubReleaseInfo) : MandatoryUpdateState
    data class Failed(val release: GitHubReleaseInfo, val message: String) : MandatoryUpdateState
}

/**
 * Full-screen Material 3 mandatory update experience with expressive motion and zero skip/dismiss paths.
 */
@Composable
fun MandatoryUpdateScreen(
    updateState: MandatoryUpdateState,
    currentVersionName: String,
    onStartUpdate: () -> Unit,
    onGrantInstallPermission: () -> Unit,
    onInstallNow: () -> Unit,
) {
    // Strictly mandatory update: block system back navigation while visible.
    BackHandler(enabled = true) {}

    val release = when (updateState) {
        MandatoryUpdateState.None -> return
        is MandatoryUpdateState.Available -> updateState.release
        is MandatoryUpdateState.Downloading -> updateState.release
        is MandatoryUpdateState.NeedsInstallPermission -> updateState.release
        is MandatoryUpdateState.ReadyToInstall -> updateState.release
        is MandatoryUpdateState.Failed -> updateState.release
    }

    val infiniteTransition = rememberInfiniteTransition(label = "update_pulse")
    val outerScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "outer_ring_scale",
    )
    val iconFloat by infiniteTransition.animateFloat(
        initialValue = -3f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "icon_float",
    )

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("mandatory_update_screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1A0A0E),
                            MaterialTheme.colorScheme.background,
                            Color(0xFF0A0C12),
                        ),
                    ),
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Top hero emblem + version pill
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier.size(132.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .scale(outerScale)
                                .clip(CircleShape)
                                .background(TubeRed.copy(alpha = 0.14f)),
                        )
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(TubeRed, TubeRedDark),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val heroIcon = when (updateState) {
                                is MandatoryUpdateState.Downloading -> Icons.Filled.CloudDownload
                                is MandatoryUpdateState.NeedsInstallPermission -> Icons.Filled.Security
                                is MandatoryUpdateState.ReadyToInstall -> Icons.Filled.InstallMobile
                                is MandatoryUpdateState.Failed -> Icons.Filled.ErrorOutline
                                else -> Icons.Filled.SystemUpdate
                            }
                            Icon(
                                imageVector = heroIcon,
                                contentDescription = stringResource(R.string.update_title),
                                tint = Color.White,
                                modifier = Modifier
                                    .size(44.dp)
                                    .padding(top = (if (iconFloat > 0) iconFloat else 0f).dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = stringResource(R.string.update_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.update_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Version transition badge
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(ShieldEmeraldContainer)
                            .border(
                                width = 1.dp,
                                color = ShieldEmerald.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(20.dp),
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.update_version_transition,
                                currentVersionName,
                                release.versionName,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = ShieldEmerald,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp,
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Release notes card
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.update_whats_new),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (release.apkSizeBytes > 0L) {
                                    val sizeMb = release.apkSizeBytes / (1024f * 1024f)
                                    Text(
                                        text = String.format("%.1f MB", sizeMb),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = release.releaseNotes.ifBlank {
                                    stringResource(R.string.update_default_notes)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Bottom action & animated progress region (zero skip option)
                AnimatedContent(
                    targetState = updateState,
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                    },
                    label = "update_action_state",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) { state ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (state) {
                            is MandatoryUpdateState.Available -> {
                                Button(
                                    onClick = onStartUpdate,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .testTag("update_now_button"),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TubeRed,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(R.string.update_action_now),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }

                            is MandatoryUpdateState.Downloading -> {
                                val animatedProgress by animateFloatAsState(
                                    targetValue = (state.progressPercent.coerceIn(0, 100)) / 100f,
                                    animationSpec = spring(),
                                    label = "download_progress",
                                )
                                Text(
                                    text = if (state.progressPercent in 0..100) {
                                        stringResource(R.string.update_downloading_percent, state.progressPercent)
                                    } else {
                                        stringResource(R.string.update_downloading)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                if (state.progressPercent in 0..100) {
                                    LinearProgressIndicator(
                                        progress = { animatedProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .testTag("update_progress_bar"),
                                        color = TubeRed,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        strokeCap = StrokeCap.Round,
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(10.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .testTag("update_progress_bar"),
                                        color = TubeRed,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        strokeCap = StrokeCap.Round,
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            is MandatoryUpdateState.NeedsInstallPermission -> {
                                Text(
                                    text = stringResource(R.string.update_permission_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = onGrantInstallPermission,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .testTag("grant_install_permission_button"),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TubeRed,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(R.string.update_action_grant_permission),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }

                            is MandatoryUpdateState.ReadyToInstall -> {
                                Button(
                                    onClick = onInstallNow,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .testTag("install_update_button"),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ShieldEmerald,
                                        contentColor = Color(0xFF0A140F),
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(R.string.update_action_install),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }

                            is MandatoryUpdateState.Failed -> {
                                Text(
                                    text = state.message.ifBlank {
                                        stringResource(R.string.update_error_fallback)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TubeRed,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = onStartUpdate,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .testTag("retry_update_button"),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TubeRed,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(R.string.update_action_retry),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }

                            MandatoryUpdateState.None -> Unit
                        }
                    }
                }
            }
        }
    }
}
