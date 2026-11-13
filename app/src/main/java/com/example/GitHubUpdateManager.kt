package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val versionName: String,
    val title: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
)

/**
 * Checks GitHub for newer YTube APK updates using:
 * 1) GitHub Releases API (`https://api.github.com/repos/<owner>/<repo>/releases/latest`), and
 * 2) Raw repository `update.json` fallback (`https://raw.githubusercontent.com/<owner>/<repo>/main/update.json`).
 *
 * Streams the APK with live progress updates and launches Android's package installer via [FileProvider].
 */
class GitHubUpdateManager(private val context: Context) {

    suspend fun fetchLatestRelease(
        repoSlug: String,
        currentVersionName: String,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
    ): GitHubReleaseInfo? = withContext(Dispatchers.IO) {
        val cleanSlug = repoSlug.trim().trim('/')
        if (!isValidRepoSlug(cleanSlug)) return@withContext null

        // 1. Try GitHub Releases API first
        val apiUrl = "https://api.github.com/repos/$cleanSlug/releases/latest"
        fetchTextFromUrl(apiUrl, acceptJson = true)?.let { rawJson ->
            val fromRelease = parseReleaseJson(rawJson, currentVersionName)
            if (fromRelease != null) return@withContext fromRelease
        }

        // 2. Fallback to tracked update.json in the repository's main branch
        val rawManifestUrl = "https://raw.githubusercontent.com/$cleanSlug/main/update.json"
        fetchTextFromUrl(rawManifestUrl, acceptJson = false)?.let { rawManifest ->
            return@withContext parseUpdateManifestJson(
                rawJson = rawManifest,
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
            )
        }

        null
    }

    private fun fetchTextFromUrl(url: String, acceptJson: Boolean): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                if (acceptJson) {
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                setRequestProperty("User-Agent", "YTube-Android/${BuildConfig.VERSION_NAME}")
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun downloadApk(
        release: GitHubReleaseInfo,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, UPDATES_DIR).apply {
            if (!exists()) mkdirs()
        }
        val targetFile = File(updatesDir, APK_FILE_NAME)
        val tempFile = File(updatesDir, "$APK_FILE_NAME.part")
        if (tempFile.exists()) tempFile.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(release.apkDownloadUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "YTube-Android/${BuildConfig.VERSION_NAME}")
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode} while downloading update")
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSizeBytes
            var downloadedBytes = 0L
            var lastPercent = -1

            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            onProgress(100)
            targetFile
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createUnknownSourcesSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    fun createInstallApkIntent(apkFile: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        private const val UPDATES_DIR = "updates"
        private const val APK_FILE_NAME = "ytube-update.apk"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val BUFFER_SIZE = 16 * 1024

        fun isValidRepoSlug(repoSlug: String): Boolean {
            val clean = repoSlug.trim().trim('/')
            if (clean.isEmpty() || clean == "CONFIGURE_OWNER/CONFIGURE_REPO") return false
            val parts = clean.split("/")
            return parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()
        }

        fun parseReleaseJson(rawJson: String, currentVersionName: String): GitHubReleaseInfo? {
            val json = JSONObject(rawJson)
            if (json.optBoolean("draft", false)) return null

            val tagName = json.optString("tag_name", "").trim()
            val normalizedRemote = normalizeVersion(tagName)
            if (normalizedRemote.isEmpty() || !isNewerVersion(normalizedRemote, currentVersionName)) {
                return null
            }

            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl = ""
            var apkName = ""
            var apkSize = 0L

            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) &&
                    downloadUrl.startsWith("https://", ignoreCase = true)
                ) {
                    apkUrl = downloadUrl
                    apkName = name
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }

            if (apkUrl.isEmpty()) return null

            return GitHubReleaseInfo(
                versionName = normalizedRemote,
                title = json.optString("name", "").ifBlank { "YTube v$normalizedRemote" },
                releaseNotes = json.optString("body", "").trim(),
                apkDownloadUrl = apkUrl,
                apkFileName = apkName,
                apkSizeBytes = apkSize,
            )
        }

        fun parseUpdateManifestJson(
            rawJson: String,
            currentVersionName: String,
            currentVersionCode: Int,
        ): GitHubReleaseInfo? {
            val json = JSONObject(rawJson)
            val remoteVersionName = normalizeVersion(json.optString("versionName", ""))
            val remoteVersionCode = json.optInt("versionCode", 0)
            val apkUrl = json.optString("apkUrl", "").trim()

            if (!apkUrl.startsWith("https://", ignoreCase = true)) return null

            val isNewer = remoteVersionCode > currentVersionCode ||
                (remoteVersionName.isNotEmpty() && isNewerVersion(remoteVersionName, currentVersionName))
            if (!isNewer) return null

            val displayVersion = remoteVersionName.ifEmpty { remoteVersionCode.toString() }
            return GitHubReleaseInfo(
                versionName = displayVersion,
                title = json.optString("title", "").ifBlank { "YTube v$displayVersion" },
                releaseNotes = json.optString("releaseNotes", "").trim(),
                apkDownloadUrl = apkUrl,
                apkFileName = "YTube-v$displayVersion.apk",
                apkSizeBytes = json.optLong("apkSizeBytes", 0L),
            )
        }

        fun normalizeVersion(rawVersion: String): String {
            return rawVersion.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
                .trim()
        }

        fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
            val remoteParts = parseVersionParts(remoteVersion)
            val currentParts = parseVersionParts(currentVersion)
            if (remoteParts.isEmpty()) return false

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        private fun parseVersionParts(raw: String): List<Int> {
            val normalized = normalizeVersion(raw)
            if (normalized.isEmpty()) return emptyList()
            return normalized.split('.').mapNotNull { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull()
            }
        }
    }
}
