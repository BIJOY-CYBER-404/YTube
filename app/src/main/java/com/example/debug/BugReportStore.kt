package com.example.debug

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogSeverity {
    CRASH,
    ERROR,
    WARNING,
    INFO,
}

data class AppLogEntry(
    val id: Long,
    val timestampMs: Long,
    val severity: LogSeverity,
    val tag: String,
    val message: String,
    val stackTrace: String? = null,
    val contextInfo: String? = null,
)

/**
 * Persistent in-app diagnostics & crash store.
 *
 * Captures uncaught JVM crashes (surviving process death), WebView renderer crashes,
 * network/JS console errors, and operational warnings so users can inspect or send
 * actionable bug reports directly to the developer via WhatsApp.
 */
object BugReportStore {
    private const val PREFS_NAME = "ytube_debug_store"
    private const val KEY_LOGS_JSON = "captured_logs_json"
    private const val KEY_UNREAD_CRASH = "has_unread_crash"
    private const val KEY_SHAKE_ENABLED = "shake_to_report_enabled"
    private const val MAX_LOG_ENTRIES = 60

    private val idCounter = AtomicLong(System.currentTimeMillis())

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var crashHandlerInstalled = false

    private val _logs = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val logs: StateFlow<List<AppLogEntry>> = _logs.asStateFlow()

    private val _hasUnreadCrash = MutableStateFlow(false)
    val hasUnreadCrash: StateFlow<Boolean> = _hasUnreadCrash.asStateFlow()

    private val _shakeToReportEnabled = MutableStateFlow(true)
    val shakeToReportEnabled: StateFlow<Boolean> = _shakeToReportEnabled.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val appPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = appPrefs
        _logs.value = readLogsFromPrefs(appPrefs)
        _hasUnreadCrash.value = appPrefs.getBoolean(KEY_UNREAD_CRASH, false)
        _shakeToReportEnabled.value = appPrefs.getBoolean(KEY_SHAKE_ENABLED, true)
    }

    @Synchronized
    fun installUncaughtExceptionHandler(context: Context) {
        init(context)
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                recordCrashSync(
                    tag = "UncaughtException(${thread.name})",
                    message = throwable.message?.takeIf { it.isNotBlank() }
                        ?: throwable.javaClass.simpleName,
                    throwable = throwable,
                )
            } catch (_: Throwable) {
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun recordCrashSync(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        contextInfo: String? = null,
    ) {
        val entry = AppLogEntry(
            id = idCounter.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            severity = LogSeverity.CRASH,
            tag = tag,
            message = message,
            stackTrace = throwable?.toStackTraceString(),
            contextInfo = contextInfo,
        )
        val updated = (listOf(entry) + _logs.value).take(MAX_LOG_ENTRIES)
        _logs.value = updated
        _hasUnreadCrash.value = true
        prefs?.let { p ->
            p.edit()
                .putString(KEY_LOGS_JSON, serializeLogs(updated))
                .putBoolean(KEY_UNREAD_CRASH, true)
                .commit()
        }
    }

    fun log(
        severity: LogSeverity,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        contextInfo: String? = null,
    ) {
        val cleanMsg = message.trim().take(400)
        if (cleanMsg.isEmpty()) return

        // Avoid spamming identical consecutive error lines within 3 seconds
        val latest = _logs.value.firstOrNull()
        if (latest != null &&
            latest.severity == severity &&
            latest.tag == tag &&
            latest.message == cleanMsg &&
            System.currentTimeMillis() - latest.timestampMs < 3_000L
        ) {
            return
        }

        val entry = AppLogEntry(
            id = idCounter.incrementAndGet(),
            timestampMs = System.currentTimeMillis(),
            severity = severity,
            tag = tag,
            message = cleanMsg,
            stackTrace = throwable?.toStackTraceString(),
            contextInfo = contextInfo,
        )
        val updated = (listOf(entry) + _logs.value).take(MAX_LOG_ENTRIES)
        _logs.value = updated
        if (severity == LogSeverity.CRASH) {
            _hasUnreadCrash.value = true
        }
        prefs?.edit()?.apply {
            putString(KEY_LOGS_JSON, serializeLogs(updated))
            if (severity == LogSeverity.CRASH) {
                putBoolean(KEY_UNREAD_CRASH, true)
            }
            apply()
        }
    }

    fun markCrashSeen() {
        _hasUnreadCrash.value = false
        prefs?.edit()?.putBoolean(KEY_UNREAD_CRASH, false)?.apply()
    }

    fun setShakeToReportEnabled(enabled: Boolean) {
        _shakeToReportEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_SHAKE_ENABLED, enabled)?.apply()
    }

    fun clearLogs() {
        _logs.value = emptyList()
        _hasUnreadCrash.value = false
        prefs?.edit()
            ?.remove(KEY_LOGS_JSON)
            ?.putBoolean(KEY_UNREAD_CRASH, false)
            ?.apply()
    }

    fun buildWhatsAppReportText(
        appVersion: String,
        versionCode: Int,
        userNote: String,
        selectedCategories: Set<String>,
        currentUrl: String,
        activeVideoUrl: String?,
        isSignedIn: Boolean,
        backgroundPlayEnabled: Boolean,
        blockedCount: Int,
    ): String {
        return buildReportInternal(
            appVersion = appVersion,
            versionCode = versionCode,
            userNote = userNote,
            selectedCategories = selectedCategories,
            currentUrl = currentUrl,
            activeVideoUrl = activeVideoUrl,
            isSignedIn = isSignedIn,
            backgroundPlayEnabled = backgroundPlayEnabled,
            blockedCount = blockedCount,
            maxLogs = 8,
            maxChars = 2400,
        )
    }

    fun buildFullDiagnosticsText(
        appVersion: String,
        versionCode: Int,
        userNote: String,
        selectedCategories: Set<String>,
        currentUrl: String,
        activeVideoUrl: String?,
        isSignedIn: Boolean,
        backgroundPlayEnabled: Boolean,
        blockedCount: Int,
    ): String {
        return buildReportInternal(
            appVersion = appVersion,
            versionCode = versionCode,
            userNote = userNote,
            selectedCategories = selectedCategories,
            currentUrl = currentUrl,
            activeVideoUrl = activeVideoUrl,
            isSignedIn = isSignedIn,
            backgroundPlayEnabled = backgroundPlayEnabled,
            blockedCount = blockedCount,
            maxLogs = MAX_LOG_ENTRIES,
            maxChars = 20_000,
        )
    }

    private fun buildReportInternal(
        appVersion: String,
        versionCode: Int,
        userNote: String,
        selectedCategories: Set<String>,
        currentUrl: String,
        activeVideoUrl: String?,
        isSignedIn: Boolean,
        backgroundPlayEnabled: Boolean,
        blockedCount: Int,
        maxLogs: Int,
        maxChars: Int,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("*YTube Bug & Diagnostics Report*")
        sb.appendLine("App: v$appVersion ($versionCode)")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("State: BgPlay=${if (backgroundPlayEnabled) "ON" else "OFF"} | Google=${if (isSignedIn) "SignedIn" else "Guest"} | AdsBlocked=$blockedCount")
        sb.appendLine("Feed URL: $currentUrl")
        if (!activeVideoUrl.isNullOrBlank()) {
            sb.appendLine("Active Video: $activeVideoUrl")
        }
        if (selectedCategories.isNotEmpty()) {
            sb.appendLine("Categories: ${selectedCategories.joinToString(", ")}")
        }
        if (userNote.isNotBlank()) {
            sb.appendLine("User Note: ${userNote.trim()}")
        }

        val currentLogs = _logs.value.take(maxLogs)
        sb.appendLine()
        if (currentLogs.isEmpty()) {
            sb.appendLine("Logs: No runtime errors or crashes recorded.")
        } else {
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            sb.appendLine("Recent Logs (${currentLogs.size}):")
            for (entry in currentLogs) {
                val time = fmt.format(Date(entry.timestampMs))
                sb.appendLine("[$time] ${entry.severity.name}/${entry.tag}: ${entry.message}")
                if (!entry.contextInfo.isNullOrBlank()) {
                    sb.appendLine("  Context: ${entry.contextInfo.take(180)}")
                }
                if (!entry.stackTrace.isNullOrBlank()) {
                    val shortTrace = entry.stackTrace.lines().take(6).joinToString("\n  ")
                    sb.appendLine("  $shortTrace")
                }
                if (sb.length >= maxChars) break
            }
        }

        return sb.toString().take(maxChars).trimEnd()
    }

    private fun Throwable.toStackTraceString(): String {
        val sw = StringWriter()
        PrintWriter(sw).use { printStackTrace(it) }
        return sw.toString().take(3000)
    }

    private fun serializeLogs(entries: List<AppLogEntry>): String {
        val array = JSONArray()
        for (e in entries) {
            val obj = JSONObject()
            obj.put("id", e.id)
            obj.put("ts", e.timestampMs)
            obj.put("sev", e.severity.name)
            obj.put("tag", e.tag)
            obj.put("msg", e.message)
            if (e.stackTrace != null) obj.put("trace", e.stackTrace)
            if (e.contextInfo != null) obj.put("ctx", e.contextInfo)
            array.put(obj)
        }
        return array.toString()
    }

    private fun readLogsFromPrefs(prefs: SharedPreferences): List<AppLogEntry> {
        val raw = prefs.getString(KEY_LOGS_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val sev = runCatching {
                        LogSeverity.valueOf(obj.optString("sev", "ERROR"))
                    }.getOrDefault(LogSeverity.ERROR)
                    add(
                        AppLogEntry(
                            id = obj.optLong("id", i.toLong()),
                            timestampMs = obj.optLong("ts", System.currentTimeMillis()),
                            severity = sev,
                            tag = obj.optString("tag", "App"),
                            message = obj.optString("msg", ""),
                            stackTrace = obj.optString("trace", "").takeIf { it.isNotBlank() },
                            contextInfo = obj.optString("ctx", "").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
