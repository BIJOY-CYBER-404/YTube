package com.example

import android.content.Context
import android.net.Uri
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
 * Uses exact domain-suffix hash lookups and plain lowercase substring matching (no regex)
 * because [android.webkit.WebViewClient.shouldInterceptRequest] executes on a WebView
 * background thread for every sub-resource on the page.
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
        // Never block top-level YouTube navigation or video stream chunks directly by host,
        // because googlevideo.com serves the actual media streams.
        val host = extractHost(normalizedUrl)
        if (host != null && isBlockedHost(host)) {
            return true
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

    private fun extractHost(normalizedUrl: String): String? {
        return try {
            Uri.parse(normalizedUrl).host?.lowercase(Locale.ROOT)?.trimEnd('.')
        } catch (_: Exception) {
            null
        }
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
