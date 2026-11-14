package com.example.cache

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

/**
 * Two-tier (RAM LruCache + Disk) static asset cache & cold-start preloader for YTube.
 *
 * Design goals:
 * - Pre-warm DNS/TLS connections to YouTube CDNs and reload critical static JS/CSS/font bundles
 *   into RAM immediately at process startup (`YTubeApplication`).
 * - Serve repeat thumbnails, avatars, fonts, and immutable `/s/` player/app shell bundles in <1ms
 *   with proper CORS headers so Chromium accepts them seamlessly.
 * - Never cache dynamic HTML documents, `/youtubei/` API JSON calls, `googlevideo.com` media
 *   streams, or ad URLs (ads are blocked before this cache runs).
 */
class ResourceCacheManager private constructor(context: Context) {

    data class CachedAsset(
        val mimeType: String,
        val bytes: ByteArray,
        val cachedAtMs: Long,
        val ttlMs: Long,
    ) {
        fun isExpired(nowMs: Long): Boolean = nowMs - cachedAtMs > ttlMs
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cacheDir = File(appContext.cacheDir, CACHE_FOLDER_NAME).apply { mkdirs() }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val memoryCache = object : LruCache<String, CachedAsset>(MAX_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: CachedAsset): Int = value.bytes.size
    }

    private val trackedBundles: MutableSet<String> =
        Collections.synchronizedSet(loadSavedBundleUrls().toMutableSet())

    @Volatile
    private var trimInFlight = false

    fun warmUpAsync() {
        ioScope.launch {
            preconnectHosts()
            preloadSavedBundles()
            trimDiskCacheIfNeeded()
        }
    }

    fun isNetworkAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Attempts to serve static cacheable YouTube assets from RAM/Disk cache, or fetches & caches
     * them transparently on a cache miss. Returns `null` on non-cacheable URLs or network errors
     * so Chromium falls back cleanly to standard network loading.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val url = request.url?.toString() ?: return null
        val policy = classifyAsset(url) ?: return null

        val key = sha256(url)
        val now = System.currentTimeMillis()
        val requestOrigin = request.requestHeaders?.entries
            ?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }
            ?.value

        // 1. L1 RAM hit
        memoryCache.get(key)?.let { memAsset ->
            if (!memAsset.isExpired(now)) {
                return buildResponse(memAsset.mimeType, memAsset.bytes, requestOrigin)
            } else {
                memoryCache.remove(key)
            }
        }

        // 2. L2 Disk hit
        readFromDisk(key, policy.ttlMs, now)?.let { diskAsset ->
            memoryCache.put(key, diskAsset)
            return buildResponse(diskAsset.mimeType, diskAsset.bytes, requestOrigin)
        }

        // 3. Cache miss -> fetch cleanly over HTTPS keep-alive and populate L1 + L2
        val fetched = fetchAndStore(url, key, policy, request.requestHeaders) ?: return null
        if (policy.isPreloadBundle) {
            recordBundleUrl(url)
        }
        return buildResponse(fetched.mimeType, fetched.bytes, requestOrigin)
    }

    private fun preconnectHosts() {
        for (hostUrl in PRECONNECT_URLS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(hostUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 3_500
                    readTimeout = 3_500
                    instanceFollowRedirects = false
                }
                conn.responseCode
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun preloadSavedBundles() {
        val urls = synchronized(trackedBundles) { trackedBundles.toList() }
        val now = System.currentTimeMillis()
        for (url in urls.take(MAX_PRELOAD_BUNDLES)) {
            val policy = classifyAsset(url) ?: continue
            val key = sha256(url)
            if (memoryCache.get(key)?.takeUnless { it.isExpired(now) } != null) continue

            val diskAsset = readFromDisk(key, policy.ttlMs, now)
            if (diskAsset != null) {
                memoryCache.put(key, diskAsset)
            } else if (isNetworkAvailable()) {
                fetchAndStore(url, key, policy, emptyMap())
            }
        }
    }

    private fun fetchAndStore(
        url: String,
        key: String,
        policy: AssetPolicy,
        requestHeaders: Map<String, String>?,
    ): CachedAsset? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                useCaches = true
                requestHeaders?.forEach { (header, value) ->
                    if (header.equals("Accept", ignoreCase = true) ||
                        header.equals("Accept-Encoding", ignoreCase = true) ||
                        header.equals("User-Agent", ignoreCase = true)
                    ) {
                        // Avoid compressed streams we don't manually gunzip
                        if (!header.equals("Accept-Encoding", ignoreCase = true)) {
                            setRequestProperty(header, value)
                        }
                    }
                }
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_SINGLE_ASSET_BYTES) return null

            val rawBytes = connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_SINGLE_ASSET_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }

            if (rawBytes.isEmpty()) return null

            val mime = normalizeMimeType(connection.contentType, policy.defaultMime)
            val asset = CachedAsset(
                mimeType = mime,
                bytes = rawBytes,
                cachedAtMs = System.currentTimeMillis(),
                ttlMs = policy.ttlMs,
            )

            memoryCache.put(key, asset)
            ioScope.launch {
                writeToDisk(key, asset)
                trimDiskCacheIfNeeded()
            }
            asset
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readFromDisk(key: String, ttlMs: Long, nowMs: Long): CachedAsset? {
        val file = File(cacheDir, "$key.cache")
        if (!file.exists()) return null
        return try {
            val allBytes = file.readBytes()
            val sep = findHeaderEnd(allBytes) ?: run {
                file.delete()
                return null
            }
            val header = String(allBytes, 0, sep, Charsets.UTF_8)
            val parts = header.split('|')
            if (parts.size < 2) {
                file.delete()
                return null
            }
            val mime = parts[0].ifBlank { "application/octet-stream" }
            val cachedAt = parts[1].toLongOrNull() ?: 0L
            if (nowMs - cachedAt > ttlMs) {
                file.delete()
                return null
            }
            val bodyStart = sep + HEADER_MARKER.size
            if (bodyStart >= allBytes.size) {
                file.delete()
                return null
            }
            val body = allBytes.copyOfRange(bodyStart, allBytes.size)
            CachedAsset(mimeType = mime, bytes = body, cachedAtMs = cachedAt, ttlMs = ttlMs)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeToDisk(key: String, asset: CachedAsset) {
        val target = File(cacheDir, "$key.cache")
        val temp = File(cacheDir, "$key.tmp")
        try {
            val headerBytes = "${asset.mimeType}|${asset.cachedAtMs}".toByteArray(Charsets.UTF_8)
            FileOutputStream(temp).use { out ->
                out.write(headerBytes)
                out.write(HEADER_MARKER)
                out.write(asset.bytes)
                out.flush()
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (_: Exception) {
            temp.delete()
        }
    }

    private fun trimDiskCacheIfNeeded() {
        if (trimInFlight) return
        trimInFlight = true
        try {
            val files = cacheDir.listFiles()?.filter { it.name.endsWith(".cache") } ?: return
            var totalBytes = files.sumOf { it.length() }
            if (totalBytes <= MAX_DISK_BYTES) return

            val sortedOldestFirst = files.sortedBy { it.lastModified() }
            for (file in sortedOldestFirst) {
                if (totalBytes <= DISK_TRIM_TARGET_BYTES) break
                val len = file.length()
                if (file.delete()) {
                    totalBytes -= len
                }
            }
        } finally {
            trimInFlight = false
        }
    }

    private fun recordBundleUrl(url: String) {
        synchronized(trackedBundles) {
            if (trackedBundles.contains(url)) return
            if (trackedBundles.size >= MAX_PRELOAD_BUNDLES) {
                val first = trackedBundles.firstOrNull()
                if (first != null) trackedBundles.remove(first)
            }
            trackedBundles.add(url)
            val json = JSONArray(trackedBundles.toList()).toString()
            prefs.edit().putString(KEY_PRELOAD_URLS, json).apply()
        }
    }

    private fun loadSavedBundleUrls(): List<String> {
        val raw = prefs.getString(KEY_PRELOAD_URLS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optString(i)
                    if (item.startsWith("https://")) add(item)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildResponse(
        mimeType: String,
        bytes: ByteArray,
        requestOrigin: String?,
    ): WebResourceResponse {
        val headers = LinkedHashMap<String, String>()
        if (!requestOrigin.isNullOrBlank()) {
            headers["Access-Control-Allow-Origin"] = requestOrigin
            headers["Access-Control-Allow-Credentials"] = "true"
            headers["Vary"] = "Origin"
        } else {
            headers["Access-Control-Allow-Origin"] = "*"
        }
        headers["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS"
        headers["Cache-Control"] = "public, max-age=86400"

        return WebResourceResponse(
            mimeType,
            "utf-8",
            HttpURLConnection.HTTP_OK,
            "OK",
            headers,
            ByteArrayInputStream(bytes),
        )
    }

    private fun findHeaderEnd(data: ByteArray): Int? {
        val limit = minOf(data.size - HEADER_MARKER.size, 256)
        for (i in 0..limit) {
            var matched = true
            for (j in HEADER_MARKER.indices) {
                if (data[i + j] != HEADER_MARKER[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return null
    }

    data class AssetPolicy(
        val ttlMs: Long,
        val defaultMime: String,
        val isPreloadBundle: Boolean,
    )

    companion object {
        private const val PREFS_NAME = "ytube_cache_prefs"
        private const val KEY_PRELOAD_URLS = "preload_bundle_urls"
        private const val CACHE_FOLDER_NAME = "ytube_asset_cache"

        private const val MAX_MEMORY_BYTES = 10 * 1024 * 1024 // 10 MB RAM L1 cache
        private const val MAX_DISK_BYTES = 40L * 1024L * 1024L // 40 MB Disk L2 cap
        private const val DISK_TRIM_TARGET_BYTES = 30L * 1024L * 1024L
        private const val MAX_SINGLE_ASSET_BYTES = 2 * 1024 * 1024 // 2 MB cap per asset
        private const val MAX_PRELOAD_BUNDLES = 8

        private const val TTL_IMAGE_MS = 24L * 60L * 60L * 1000L // 24 hours
        private const val TTL_STATIC_BUNDLE_MS = 7L * 24L * 60L * 60L * 1000L // 7 days

        private val HEADER_MARKER = "\n--YTUBE--\n".toByteArray(Charsets.UTF_8)

        private val PRECONNECT_URLS = listOf(
            "https://m.youtube.com",
            "https://www.youtube.com",
            "https://i.ytimg.com",
            "https://yt3.ggpht.com",
            "https://fonts.gstatic.com",
        )

        @Volatile
        private var instance: ResourceCacheManager? = null

        fun getInstance(context: Context): ResourceCacheManager {
            return instance ?: synchronized(this) {
                instance ?: ResourceCacheManager(context).also { instance = it }
            }
        }

        fun isCacheableAssetUrl(url: String?): Boolean = classifyAsset(url) != null

        fun classifyAsset(rawUrl: String?): AssetPolicy? {
            if (rawUrl.isNullOrBlank() || !rawUrl.startsWith("https://", ignoreCase = true)) {
                return null
            }
            val uri = try {
                Uri.parse(rawUrl)
            } catch (_: Exception) {
                return null
            }
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val path = uri.path?.lowercase(Locale.ROOT) ?: ""

            // Never intercept or cache media streams, dynamic feeds, or API endpoints.
            if (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) return null
            if (host == "accounts.google.com") return null
            if (path.startsWith("/youtubei/") ||
                path.startsWith("/api/") ||
                path.startsWith("/watch") ||
                path.startsWith("/shorts") ||
                path.startsWith("/results") ||
                path.startsWith("/feed") ||
                path.startsWith("/ptracking") ||
                path.startsWith("/get_video_info")
            ) {
                return null
            }

            // 1. Thumbnails and channel avatars
            if (host == "ytimg.com" || host.endsWith(".ytimg.com") ||
                host == "ggpht.com" || host.endsWith(".ggpht.com")
            ) {
                if (path.endsWith(".jpg") ||
                    path.endsWith(".jpeg") ||
                    path.endsWith(".png") ||
                    path.endsWith(".webp") ||
                    path.endsWith(".avif") ||
                    path.contains("/vi/") ||
                    path.contains("/vi_webp/") ||
                    path.contains("/ytc/") ||
                    path.contains("/sb/")
                ) {
                    val mime = guessMimeFromPath(path) ?: "image/jpeg"
                    return AssetPolicy(
                        ttlMs = TTL_IMAGE_MS,
                        defaultMime = mime,
                        isPreloadBundle = false,
                    )
                }
            }

            // 2. Fonts and static versioned `/s/` bundles
            val isFontHost = host == "fonts.gstatic.com" || host == "fonts.googleapis.com"
            val isYoutubeStatic = (host == "m.youtube.com" || host == "www.youtube.com" ||
                host == "youtube.com" || host == "www.gstatic.com") && path.startsWith("/s/")

            if (isFontHost || isYoutubeStatic) {
                val mime = guessMimeFromPath(path) ?: if (isFontHost) "font/woff2" else null
                if (mime != null && !path.endsWith(".html")) {
                    val isCoreBundle = path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".woff2")
                    return AssetPolicy(
                        ttlMs = TTL_STATIC_BUNDLE_MS,
                        defaultMime = mime,
                        isPreloadBundle = isCoreBundle,
                    )
                }
            }

            return null
        }

        private fun guessMimeFromPath(path: String): String? {
            return when {
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".woff2") -> "font/woff2"
                path.endsWith(".woff") -> "font/woff"
                path.endsWith(".ttf") -> "font/ttf"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".avif") -> "image/avif"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".json") -> "application/json"
                else -> null
            }
        }

        private fun normalizeMimeType(contentType: String?, fallback: String): String {
            val clean = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            return if (!clean.isNullOrBlank()) clean else fallback
        }

        private fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                hex.append(HEX_CHARS[v ushr 4])
                hex.append(HEX_CHARS[v and 0x0F])
            }
            return hex.toString()
        }

        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
