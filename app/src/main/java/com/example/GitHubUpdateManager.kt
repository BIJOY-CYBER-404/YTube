package com.example

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
 * Opens the verified HTTPS GitHub APK asset URL in Android's system browser / Download Manager
 * rather than requesting REQUEST_INSTALL_PACKAGES directly inside the app, avoiding Play Protect
 * dropper security warnings.
 */
class GitHubUpdateManager {

    suspend fun fetchLatestRelease(
        repoSlug: String,
        currentVersionName: String,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
    ): GitHubReleaseInfo? = withContext(Dispatchers.IO) {
        val cleanSlug = repoSlug.trim().trim('/')
        if (!isValidRepoSlug(cleanSlug)) return@withContext null

        val apiUrl = "https://api.github.com/repos/$cleanSlug/releases/latest"
        fetchTextFromUrl(apiUrl, acceptJson = true)?.let { rawJson ->
            val fromRelease = parseReleaseJson(rawJson, currentVersionName)
            if (fromRelease != null) return@withContext fromRelease
        }

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

    fun createBrowserDownloadIntent(release: GitHubReleaseInfo): Intent {
        val uri = Uri.parse(release.apkDownloadUrl)
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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

    companion object {
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
