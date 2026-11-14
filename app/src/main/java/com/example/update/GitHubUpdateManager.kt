package com.example.update

import android.content.Intent
import android.net.Uri
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
 * Checks GitHub for newer YTube updates across:
 * 1) GitHub Releases API (`/releases/latest` and `/releases?per_page=1` including pre-releases), and
 * 2) Raw repository `update.json` (`main` and `master` branches, with cache disabled).
 *
 * Returns whichever source advertises a newer version than the installed build, and falls back to the
 * release page URL if an explicit `.apk` asset URL is omitted.
 */
class GitHubUpdateManager {

    suspend fun fetchLatestRelease(
        repoSlug: String,
        currentVersionName: String,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
    ): GitHubReleaseInfo? = withContext(Dispatchers.IO) {
        val cleanSlug = repoSlug.trim().trim('/')
        if (!isValidRepoSlug(cleanSlug)) return@withContext null

        val releaseCandidate = fetchFromReleasesApi(cleanSlug, currentVersionName)
        val manifestCandidate = fetchFromUpdateJson(cleanSlug, currentVersionName, currentVersionCode)

        when {
            releaseCandidate != null && manifestCandidate != null -> {
                if (isNewerVersion(manifestCandidate.versionName, releaseCandidate.versionName)) {
                    manifestCandidate
                } else {
                    releaseCandidate
                }
            }
            releaseCandidate != null -> releaseCandidate
            else -> manifestCandidate
        }
    }

    private fun fetchFromReleasesApi(
        cleanSlug: String,
        currentVersionName: String,
    ): GitHubReleaseInfo? {
        val latestUrl = "https://api.github.com/repos/$cleanSlug/releases/latest"
        fetchTextFromUrl(latestUrl, acceptJson = true)?.let { rawJson ->
            parseReleaseJson(rawJson, currentVersionName, cleanSlug)?.let { return it }
        }

        val allReleasesUrl = "https://api.github.com/repos/$cleanSlug/releases?per_page=5"
        fetchTextFromUrl(allReleasesUrl, acceptJson = true)?.let { rawArray ->
            return parseReleasesArrayJson(rawArray, currentVersionName, cleanSlug)
        }
        return null
    }

    private fun fetchFromUpdateJson(
        cleanSlug: String,
        currentVersionName: String,
        currentVersionCode: Int,
    ): GitHubReleaseInfo? {
        val timestamp = System.currentTimeMillis()
        for (branch in listOf("main", "master")) {
            val rawManifestUrl =
                "https://raw.githubusercontent.com/$cleanSlug/$branch/update.json?t=$timestamp"
            fetchTextFromUrl(rawManifestUrl, acceptJson = false)?.let { rawManifest ->
                parseUpdateManifestJson(
                    rawJson = rawManifest,
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode,
                    repoSlug = cleanSlug,
                    branch = branch,
                )?.let { return it }
            }
        }
        return null
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
                useCaches = false
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("Pragma", "no-cache")
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

        fun parseReleasesArrayJson(
            rawArrayJson: String,
            currentVersionName: String,
            repoSlug: String = "",
        ): GitHubReleaseInfo? {
            val array = JSONArray(rawArrayJson)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                parseReleaseObject(item, currentVersionName, repoSlug)?.let { return it }
            }
            return null
        }

        fun parseReleaseJson(
            rawJson: String,
            currentVersionName: String,
            repoSlug: String = "",
        ): GitHubReleaseInfo? {
            return parseReleaseObject(JSONObject(rawJson), currentVersionName, repoSlug)
        }

        private fun parseReleaseObject(
            json: JSONObject,
            currentVersionName: String,
            repoSlug: String,
        ): GitHubReleaseInfo? {
            if (json.optBoolean("draft", false)) return null

            val tagName = json.optString("tag_name", "").trim()
            val normalizedRemote = normalizeVersion(tagName)
            if (normalizedRemote.isEmpty() || !isNewerVersion(normalizedRemote, currentVersionName)) {
                return null
            }

            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkName = ""
            var apkSize = 0L

            if (assets != null) {
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
            }

            if (apkUrl.isEmpty()) {
                val htmlUrl = json.optString("html_url", "").trim()
                apkUrl = when {
                    htmlUrl.startsWith("https://", ignoreCase = true) -> htmlUrl
                    repoSlug.isNotBlank() -> "https://github.com/$repoSlug/releases/latest"
                    else -> ""
                }
                apkName = "YTube-v$normalizedRemote.apk"
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
            repoSlug: String = "",
            branch: String = "main",
        ): GitHubReleaseInfo? {
            val json = JSONObject(rawJson)
            val remoteVersionName = normalizeVersion(json.optString("versionName", ""))
            val remoteVersionCode = json.optInt("versionCode", 0)
            val rawApkUrl = json.optString("apkUrl", "").trim()

            val isNewer = remoteVersionCode > currentVersionCode ||
                (remoteVersionName.isNotEmpty() && isNewerVersion(remoteVersionName, currentVersionName))
            if (!isNewer) return null

            val resolvedApkUrl = when {
                rawApkUrl.startsWith("https://", ignoreCase = true) -> rawApkUrl
                rawApkUrl.isNotEmpty() && repoSlug.isNotBlank() -> {
                    val cleanPath = rawApkUrl.trimStart('/')
                    "https://github.com/$repoSlug/raw/$branch/$cleanPath"
                }
                repoSlug.isNotBlank() -> "https://github.com/$repoSlug/releases/latest"
                else -> return null
            }

            val displayVersion = remoteVersionName.ifEmpty { remoteVersionCode.toString() }
            return GitHubReleaseInfo(
                versionName = displayVersion,
                title = json.optString("title", "").ifBlank { "YTube v$displayVersion" },
                releaseNotes = json.optString("releaseNotes", "").trim(),
                apkDownloadUrl = resolvedApkUrl,
                apkFileName = "YTube-v$displayVersion.apk",
                apkSizeBytes = json.optLong("apkSizeBytes", 0L),
            )
        }

        fun normalizeVersion(rawVersion: String): String {
            return rawVersion.trim()
                .dropWhile { !it.isDigit() }
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
