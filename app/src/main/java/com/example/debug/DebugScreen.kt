package com.example.debug

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.AuthorContactHelper
import com.example.BuildConfig
import com.example.R
import com.example.YTubeUiState
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.ShieldEmeraldContainer
import com.example.ui.theme.YTubeRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DebugScreen(
    uiState: YTubeUiState,
    logs: List<AppLogEntry>,
    hasUnreadCrash: Boolean,
    shakeEnabled: Boolean,
    onToggleShake: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var userNote by remember { mutableStateOf("") }
    var selectedCategories by remember { mutableStateOf(setOf<String>()) }
    var expandedLogIds by remember { mutableStateOf(setOf<Long>()) }

    BackHandler(onBack = onClose)

    val crashCount = remember(logs) { logs.count { it.severity == LogSeverity.CRASH } }
    val errorCount = remember(logs) { logs.count { it.severity == LogSeverity.ERROR } }
    val whatsAppGreen = Color(0xFF25D366)
    val categories = remember {
        listOf(
            "Playback",
            "Auto-Next",
            "App Crash",
            "Sign-In",
            "Layout / UI",
            "Background Audio",
        )
    }

    val sendWhatsAppReport: () -> Unit = {
        val reportText = BugReportStore.buildWhatsAppReportText(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            userNote = userNote,
            selectedCategories = selectedCategories,
            currentUrl = uiState.currentUrl,
            activeVideoUrl = uiState.activeVideoUrl,
            isSignedIn = uiState.isGoogleSignedIn,
            backgroundPlayEnabled = uiState.backgroundPlayEnabled,
            blockedCount = uiState.blockedCount,
        )
        try {
            context.startActivity(AuthorContactHelper.createWhatsAppIntent(reportText))
        } catch (_: Exception) {
            clipboardManager.setText(AnnotatedString(reportText))
            Toast.makeText(
                context,
                context.getString(R.string.debug_whatsapp_unavailable),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val copyFullReport: () -> Unit = {
        val fullText = BugReportStore.buildFullDiagnosticsText(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            userNote = userNote,
            selectedCategories = selectedCategories,
            currentUrl = uiState.currentUrl,
            activeVideoUrl = uiState.activeVideoUrl,
            isSignedIn = uiState.isGoogleSignedIn,
            backgroundPlayEnabled = uiState.backgroundPlayEnabled,
            blockedCount = uiState.blockedCount,
        )
        clipboardManager.setText(AnnotatedString(fullText))
        Toast.makeText(
            context,
            context.getString(R.string.debug_copied_toast),
            Toast.LENGTH_SHORT,
        ).show()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag("debug_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("debug_close_button"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.debug_back_desc),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.debug_screen_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (crashCount > 0 || errorCount > 0) {
                                YTubeRed.copy(alpha = 0.16f)
                            } else {
                                ShieldEmeraldContainer
                            },
                        ) {
                            Text(
                                text = when {
                                    crashCount > 0 -> "$crashCount Crash"
                                    errorCount > 0 -> "$errorCount Error${if (errorCount > 1) "s" else ""}"
                                    else -> "Healthy"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (crashCount > 0 || errorCount > 0) YTubeRed else ShieldEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = copyFullReport,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("debug_copy_action"),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_content_copy),
                            contentDescription = stringResource(R.string.debug_copy_report),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (logs.isNotEmpty()) {
                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("debug_clear_action"),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_sweep),
                                contentDescription = stringResource(R.string.debug_clear_logs),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (hasUnreadCrash || crashCount > 0) {
                item {
                    val latestCrash = logs.firstOrNull { it.severity == LogSeverity.CRASH }
                    ElevatedCard(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = YTubeRed.copy(alpha = 0.12f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_error_outline),
                                    contentDescription = null,
                                    tint = YTubeRed,
                                    modifier = Modifier.size(22.dp),
                                )
                                Text(
                                    text = stringResource(R.string.debug_crash_banner_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = YTubeRed,
                                )
                            }
                            Text(
                                text = latestCrash?.message
                                    ?: stringResource(R.string.debug_crash_banner_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // 1. Report Bug to Developer Card
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_bug_report),
                                contentDescription = null,
                                tint = YTubeRed,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.debug_report_section_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            categories.forEach { category ->
                                val selected = category in selectedCategories
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedCategories = if (selected) {
                                            selectedCategories - category
                                        } else {
                                            selectedCategories + category
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = category,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = YTubeRed.copy(alpha = 0.16f),
                                        selectedLabelColor = YTubeRed,
                                    ),
                                )
                            }
                        }

                        OutlinedTextField(
                            value = userNote,
                            onValueChange = { userNote = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("debug_bug_note_input"),
                            label = { Text(stringResource(R.string.debug_note_hint)) },
                            placeholder = { Text(stringResource(R.string.debug_note_placeholder)) },
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(14.dp),
                        )

                        Button(
                            onClick = sendWhatsAppReport,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("debug_whatsapp_report_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = whatsAppGreen,
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chat_bubble),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.debug_whatsapp_report_cta),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        OutlinedButton(
                            onClick = copyFullReport,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("debug_copy_report_button"),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_content_copy),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.debug_copy_report),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }

            // 2. Shake-to-Report Toggle & System Telemetry Card
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_vibration),
                                        contentDescription = null,
                                        tint = YTubeRed,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Column {
                                    Text(
                                        text = stringResource(R.string.debug_shake_title),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = stringResource(R.string.debug_shake_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = shakeEnabled,
                                onCheckedChange = onToggleShake,
                                modifier = Modifier.testTag("debug_shake_switch"),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = YTubeRed,
                                ),
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        TelemetryRow(
                            label = "Version",
                            value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        )
                        TelemetryRow(
                            label = "Device",
                            value = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                        )
                        TelemetryRow(
                            label = "Ads Blocked",
                            value = "${uiState.blockedCount}",
                        )
                        TelemetryRow(
                            label = "Session",
                            value = if (uiState.isGoogleSignedIn) "Google Signed In" else "Guest",
                        )
                    }
                }
            }

            // 3. Captured Error & Crash Logs
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.debug_logs_header, logs.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (logs.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.debug_tap_expand_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (logs.isEmpty()) {
                item {
                    ElevatedCard(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_bug_report),
                                contentDescription = null,
                                tint = ShieldEmerald,
                                modifier = Modifier.size(36.dp),
                            )
                            Text(
                                text = stringResource(R.string.debug_empty_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.debug_empty_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(logs, key = { it.id }) { entry ->
                    val isExpanded = entry.id in expandedLogIds
                    LogEntryCard(
                        entry = entry,
                        isExpanded = isExpanded,
                        onToggleExpand = {
                            expandedLogIds = if (isExpanded) {
                                expandedLogIds - entry.id
                            } else {
                                expandedLogIds + entry.id
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LogEntryCard(
    entry: AppLogEntry,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val badgeColor = when (entry.severity) {
        LogSeverity.CRASH -> Color(0xFFD32F2F)
        LogSeverity.ERROR -> YTubeRed
        LogSeverity.WARNING -> Color(0xFFE65100)
        LogSeverity.INFO -> ShieldEmerald
    }
    val timeLabel = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMs))
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = entry.severity.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        text = entry.tag,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!entry.contextInfo.isNullOrBlank()) {
                        Text(
                            text = "Context: ${entry.contextInfo}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!entry.stackTrace.isNullOrBlank()) {
                        Text(
                            text = entry.stackTrace,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else if (entry.contextInfo.isNullOrBlank()) {
                        Text(
                            text = "No stack trace attached.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
