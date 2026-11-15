package com.example

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale

/**
 * Lightweight, allocation-conscious ad filter engine loaded from assets/adblock_lists.txt.
 *
 * Uses fast string-slice host extraction, exact domain-suffix hash lookups, and plain substring
 * matching (no Uri.parse or regex allocations) because [android.webkit.WebViewClient.shouldInterceptRequest]
 * executes on Chromium's IO thread for every sub-resource on the page.
 */
class AdBlocker(
    private val blockedDomains: Set<String>,
    private val blockedSubstrings: List<String>,
) {
    private val _blockedCount = MutableStateFlow(0)
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    fun isAdRequest(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        val normalizedUrl = url.lowercase(Locale.ROOT)
        if (normalizedUrl.contains("consent.google.") ||
            normalizedUrl.contains("consent.youtube.") ||
            normalizedUrl.contains("accounts.google.") ||
            normalizedUrl.contains("fundingchoicesmessages.google.") ||
            normalizedUrl.contains("myaccount.google.")
        ) {
            return false
        }

        val host = extractHostFast(normalizedUrl)
        if (host != null) {
            if (host == "googlevideo.com" || host.endsWith(".googlevideo.com") ||
                host == "ytimg.com" || host.endsWith(".ytimg.com") ||
                host == "ggpht.com" || host.endsWith(".ggpht.com")
            ) {
                return false
            }
            if (isBlockedHost(host)) {
                return true
            }
        }

        for (i in blockedSubstrings.indices) {
            if (normalizedUrl.contains(blockedSubstrings[i])) {
                return true
            }
        }
        return false
    }

    fun recordBlocked() {
        _blockedCount.update { it + 1 }
    }

    fun resetBlockedCount() {
        _blockedCount.value = 0
    }

    private fun extractHostFast(normalizedUrl: String): String? {
        val schemeEnd = normalizedUrl.indexOf("://")
        if (schemeEnd < 0) return null
        val hostStart = schemeEnd + 3
        if (hostStart >= normalizedUrl.length) return null

        var hostEnd = normalizedUrl.indexOf('/', hostStart)
        if (hostEnd < 0) hostEnd = normalizedUrl.indexOf('?', hostStart)
        if (hostEnd < 0) hostEnd = normalizedUrl.indexOf('#', hostStart)
        if (hostEnd < 0) hostEnd = normalizedUrl.length

        var authority = normalizedUrl.substring(hostStart, hostEnd)
        val atIdx = authority.lastIndexOf('@')
        if (atIdx >= 0 && atIdx + 1 < authority.length) {
            authority = authority.substring(atIdx + 1)
        }
        val colonIdx = authority.indexOf(':')
        if (colonIdx > 0) {
            authority = authority.substring(0, colonIdx)
        }
        return authority.trimEnd('.').takeIf { it.isNotEmpty() }
    }

    private fun isBlockedHost(host: String): Boolean {
        var current: String? = host
        while (!current.isNullOrEmpty()) {
            if (blockedDomains.contains(current)) {
                return true
            }
            val dotIndex = current.indexOf('.')
            current = if (dotIndex >= 0 && dotIndex + 1 < current.length) {
                current.substring(dotIndex + 1)
            } else {
                null
            }
        }
        return false
    }

    companion object {
        private const val ASSET_FILE = "adblock_lists.txt"

        fun fromAssets(context: Context): AdBlocker {
            return try {
                context.assets.open(ASSET_FILE).use { stream ->
                    fromStream(stream)
                }
            } catch (_: Exception) {
                AdBlocker(emptySet(), emptyList())
            }
        }

        fun fromStream(inputStream: InputStream): AdBlocker {
            val domains = HashSet<String>()
            val substrings = ArrayList<String>()
            var section = ""

            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine

                    when (line.lowercase(Locale.ROOT)) {
                        "[domains]" -> section = "domains"
                        "[url-substrings]" -> section = "substrings"
                        else -> when (section) {
                            "domains" -> domains.add(line.lowercase(Locale.ROOT).trimStart('.'))
                            "substrings" -> substrings.add(line.lowercase(Locale.ROOT))
                        }
                    }
                }
            }

            return AdBlocker(
                blockedDomains = domains,
                blockedSubstrings = substrings,
            )
        }
    }
}
