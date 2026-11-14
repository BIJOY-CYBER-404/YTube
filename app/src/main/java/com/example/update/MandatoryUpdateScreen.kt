package com.example.update

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.YTubeRed
import com.example.ui.theme.YTubeRedDark

sealed interface MandatoryUpdateState {
    data object None : MandatoryUpdateState
    data class Available(val release: GitHubReleaseInfo) : MandatoryUpdateState
    data class Downloading(
        val release: GitHubReleaseInfo,
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : MandatoryUpdateState

    data class ReadyToInstall(
        val release: GitHubReleaseInfo,
        val apkFilePath: String,
        val needsInstallPermission: Boolean,
    ) : MandatoryUpdateState

    data class Failed(
        val release: GitHubReleaseInfo,
        val message: String,
    ) : MandatoryUpdateState
}

/**
 * Full-screen Material 3 Light Theme mandatory in-app update experience with expressive motion,
 * live in-app download progress, direct PackageInstaller launch, and zero skip/dismiss paths.
 */
@Composable
fun MandatoryUpdateScreen(
    updateState: MandatoryUpdateState,
    currentVersionName: String,
    onStartUpdate: () -> Unit,
    onInstallReadyApk: () -> Unit,
    onRecheckUpdate: () -> Unit,
) {
    // Strictly mandatory update: block system back navigation while visible.
    BackHandler(enabled = true) {}

    val release = when (updateState) {
        MandatoryUpdateState.None -> return
        is MandatoryUpdateState.Available -> updateState.release
        is MandatoryUpdateState.Downloading -> updateState.release
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

    val lightBadgeGreenBg = Color(0xFFE7F8EF)
    val lightBadgeGreenFg = Color(0xFF0E7A45)

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
                            Color(0xFFFFF2F3),
                            Color(0xFFFFFFFF),
                            Color(0xFFF5F7FB),
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
                                .background(YTubeRed.copy(alpha = 0.12f)),
                        )
                        Box(
                            modifier = Modifier
                                .size(92.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(YTubeRed, YTubeRedDark),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            val iconRes = when (updateState) {
                                is MandatoryUpdateState.Downloading,
                                is MandatoryUpdateState.ReadyToInstall -> R.drawable.ic_cloud_download
                                is MandatoryUpdateState.Failed -> R.drawable.ic_error_outline
                                else -> R.drawable.ic_system_update
                            }
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = stringResource(R.string.update_title),
                                tint = Color.White,
                                modifier = Modifier
                                    .size(42.dp)
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

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(lightBadgeGreenBg)
                            .border(
                                width = 1.dp,
                                color = lightBadgeGreenFg.copy(alpha = 0.25f),
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
                            color = lightBadgeGreenFg,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp,
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = Color.White,
                        ),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
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
                                        containerColor = YTubeRed,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_cloud_download),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .padding(end = 6.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.update_action_now),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }

                            is MandatoryUpdateState.Downloading -> {
                                val downloadedMb = state.downloadedBytes / (1024f * 1024f)
                                val totalMb = state.totalBytes / (1024f * 1024f)
                                val statusText = if (state.progressPercent >= 0 && state.totalBytes > 0L) {
                                    stringResource(
                                        R.string.update_downloading_progress,
                                        state.progressPercent,
                                        downloadedMb,
                                        totalMb,
                                    )
                                } else {
                                    stringResource(R.string.update_downloading_indeterminate, downloadedMb)
                                }

                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                if (state.progressPercent >= 0) {
                                    LinearProgressIndicator(
                                        progress = { (state.progressPercent / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .testTag("in_app_update_progress"),
                                        color = YTubeRed,
                                        trackColor = Color(0xFFECEEF2),
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .testTag("in_app_update_progress"),
                                        color = YTubeRed,
                                        trackColor = Color(0xFFECEEF2),
                                    )
                                }
                            }

                            is MandatoryUpdateState.ReadyToInstall -> {
                                val promptText = if (state.needsInstallPermission) {
                                    stringResource(R.string.update_permission_hint)
                                } else {
                                    stringResource(R.string.update_ready_install_hint)
                                }
                                Text(
                                    text = promptText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = onInstallReadyApk,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .testTag("install_update_button"),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = YTubeRed,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Text(
                                        text = if (state.needsInstallPermission) {
                                            stringResource(R.string.update_action_allow_install)
                                        } else {
                                            stringResource(R.string.update_action_install_now)
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = onRecheckUpdate,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("recheck_update_button"),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Text(text = stringResource(R.string.update_action_recheck))
                                }
                            }

                            is MandatoryUpdateState.Failed -> {
                                Text(
                                    text = state.message.ifBlank {
                                        stringResource(R.string.update_error_fallback)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = YTubeRedDark,
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
                                        containerColor = YTubeRed,
                                        contentColor = Color.White,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(R.string.update_action_now),
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
