package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

sealed interface ApkDownloadResult {
    data class Success(val apkFile: File) : ApkDownloadResult
    data class Error(val message: String) : ApkDownloadResult
}

/**
 * Checks GitHub for newer YTube updates across:
 * 1) GitHub Releases API (`/releases/latest` and `/releases?per_page=5` including pre-releases), and
 * 2) Raw repository `update.json` (`main` and `master` branches, with cache disabled).
 *
 * Downloads the verified `.apk` package directly inside the app into `cacheDir/updates/`
 * and launches Android's PackageInstaller via [FileProvider] without leaving the app.
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

    suspend fun downloadApkInApp(
        context: Context,
        release: GitHubReleaseInfo,
        onProgress: (progressPercent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): ApkDownloadResult = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, UPDATES_DIR_NAME).apply { mkdirs() }
        // Clear stale downloaded APKs so cache stays small.
        updatesDir.listFiles()?.forEach { old ->
            if (old.isFile) old.delete()
        }

        val safeFileName = release.apkFileName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        val partFile = File(updatesDir, "$safeFileName.part")
        val targetFile = File(updatesDir, safeFileName)

        var currentUrl = release.apkDownloadUrl
        var connection: HttpURLConnection? = null
        try {
            var redirects = 0
            while (redirects < MAX_REDIRECTS) {
                if (!currentUrl.startsWith("https://", ignoreCase = true)) {
                    return@withContext ApkDownloadResult.Error("Insecure update URL rejected.")
                }
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    useCaches = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                    setRequestProperty("User-Agent", "YTube-Android/${BuildConfig.VERSION_NAME}")
                }
                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 ||
                    code == 308
                ) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    connection = null
                    if (location.isNullOrBlank()) {
                        return@withContext ApkDownloadResult.Error("Update redirect target missing.")
                    }
                    currentUrl = URL(URL(currentUrl), location).toString()
                    redirects++
                } else {
                    break
                }
            }

            val activeConn = connection
                ?: return@withContext ApkDownloadResult.Error("Unable to reach update server.")

            if (activeConn.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext ApkDownloadResult.Error(
                    "Download failed (HTTP ${activeConn.responseCode}).",
                )
            }

            val totalBytes = if (activeConn.contentLengthLong > 0L) {
                activeConn.contentLengthLong
            } else {
                release.apkSizeBytes
            }

            var downloaded = 0L
            var lastReportedPercent = -1
            val headerBytes = ByteArray(4)
            var headerCaptured = 0

            activeConn.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (headerCaptured < 4) {
                            val toCopy = minOf(read, 4 - headerCaptured)
                            System.arraycopy(buffer, 0, headerBytes, headerCaptured, toCopy)
                            headerCaptured += toCopy
                        }
                        output.write(buffer, 0, read)
                        downloaded += read

                        val percent = if (totalBytes > 0L) {
                            ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        } else {
                            -1
                        }
                        if (percent != lastReportedPercent || percent == -1) {
                            lastReportedPercent = percent
                            onProgress(percent, downloaded, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            // Validate ZIP/APK magic header (PK\x03\x04) so HTML pages are never sent to PackageInstaller.
            val isValidApkMagic = headerCaptured == 4 &&
                headerBytes[0] == 0x50.toByte() &&
                headerBytes[1] == 0x4B.toByte() &&
                headerBytes[2] == 0x03.toByte() &&
                headerBytes[3] == 0x04.toByte()

            if (!isValidApkMagic || downloaded < 10_000L) {
                partFile.delete()
                return@withContext ApkDownloadResult.Error(
                    "Downloaded file is not a valid APK package. Please check the GitHub APK link.",
                )
            }

            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            onProgress(100, downloaded, downloaded)
            ApkDownloadResult.Success(targetFile)
        } catch (e: Exception) {
            partFile.delete()
            ApkDownloadResult.Error(
                e.localizedMessage ?: "Unable to download update inside app.",
            )
        } finally {
            connection?.disconnect()
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun createInstallPermissionIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun createInstallApkIntent(context: Context, apkFile: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        private const val UPDATES_DIR_NAME = "updates"
        private const val MAX_REDIRECTS = 6

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

            if (apkUrl.isEmpty() && repoSlug.isNotBlank()) {
                apkName = "YTube-v$normalizedRemote.apk"
                apkUrl = "https://github.com/$repoSlug/raw/main/apk/$apkName"
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

            val displayVersion = remoteVersionName.ifEmpty { remoteVersionCode.toString() }
            val resolvedApkUrl = when {
                rawApkUrl.startsWith("https://", ignoreCase = true) -> rawApkUrl
                rawApkUrl.isNotEmpty() && repoSlug.isNotBlank() -> {
                    val cleanPath = rawApkUrl.trimStart('/')
                    "https://github.com/$repoSlug/raw/$branch/$cleanPath"
                }
                repoSlug.isNotBlank() -> {
                    "https://github.com/$repoSlug/raw/$branch/apk/YTube-v$displayVersion.apk"
                }
                else -> return null
            }

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
