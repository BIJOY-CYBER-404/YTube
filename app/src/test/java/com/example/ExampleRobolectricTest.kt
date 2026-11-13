package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name and package name match YTube`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("YTube", appName)
        assertEquals("com.ytube.bk", context.packageName)
    }

    @Test
    fun `adblocker loads starter asset list and blocks ad domains and substrings`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adBlocker = AdBlocker.fromAssets(context)

        assertTrue(adBlocker.isAdRequest("https://googleads.g.doubleclick.net/pagead/id"))
        assertTrue(adBlocker.isAdRequest("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(adBlocker.isAdRequest("https://m.youtube.com/api/stats/ads?adformat=2_2_1"))
        assertTrue(adBlocker.isAdRequest("https://m.youtube.com/get_midroll_info?v=abc"))

        assertFalse(adBlocker.isAdRequest("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(adBlocker.isAdRequest("https://rr2---sn-4g5e6nsz.googlevideo.com/videoplayback?expire=123"))
        assertFalse(adBlocker.isAdRequest("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"))
    }

    @Test
    fun `internal youtube hosts stay in webview while external hosts open externally`() {
        assertTrue(AdBlockingWebViewClient.isInternalYouTubeHost("m.youtube.com"))
        assertTrue(AdBlockingWebViewClient.isInternalYouTubeHost("www.youtube.com"))
        assertTrue(AdBlockingWebViewClient.isInternalYouTubeHost("youtu.be"))
        assertTrue(AdBlockingWebViewClient.isInternalYouTubeHost("rr1---sn-test.googlevideo.com"))

        assertFalse(AdBlockingWebViewClient.isInternalYouTubeHost("example.com"))
        assertFalse(AdBlockingWebViewClient.isInternalYouTubeHost("notyoutube.com"))
    }

    @Test
    fun `js injector embeds visibility toggle, skip timer, and playback hooks`() {
        val scriptEnabled = JsInjector.buildProtectionScript(backgroundPlayEnabled = true)
        assertTrue(scriptEnabled.contains("window.__tubeviewBgPlay = true;"))
        assertTrue(scriptEnabled.contains("window.__tubeviewPlay"))
        assertTrue(scriptEnabled.contains("window.__tubeviewPause"))
        assertTrue(scriptEnabled.contains("playbackRate = 16"))
        assertTrue(scriptEnabled.contains(".ytp-ad-skip-button"))

        val scriptDisabled = JsInjector.buildProtectionScript(backgroundPlayEnabled = false)
        assertTrue(scriptDisabled.contains("window.__tubeviewBgPlay = false;"))
    }

    @Test
    fun `github update manager compares semantic versions accurately`() {
        assertTrue(GitHubUpdateManager.isNewerVersion("v1.2.0", "1.1.0"))
        assertTrue(GitHubUpdateManager.isNewerVersion("2.0", "1.1.0"))
        assertFalse(GitHubUpdateManager.isNewerVersion("v1.1.0", "1.1.0"))
        assertFalse(GitHubUpdateManager.isNewerVersion("1.0.9", "1.1.0"))
    }

    @Test
    fun `github update manager parses valid release json with apk asset`() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "name": "YTube 1.2.0",
              "body": "- Improved ad filters\n- Bug fixes",
              "draft": false,
              "assets": [
                {
                  "name": "YTube-1.2.0.apk",
                  "size": 16777216,
                  "browser_download_url": "https://github.com/example/YTube/releases/download/v1.2.0/YTube-1.2.0.apk"
                }
              ]
            }
        """.trimIndent()

        val release = GitHubUpdateManager.parseReleaseJson(json, currentVersionName = "1.1.0")
        assertNotNull(release)
        assertEquals("1.2.0", release?.versionName)
        assertEquals("YTube-1.2.0.apk", release?.apkFileName)

        val sameVersion = GitHubUpdateManager.parseReleaseJson(json, currentVersionName = "1.2.0")
        assertNull(sameVersion)
    }

    @Test
    fun `github update manager parses update manifest json fallback`() {
        val manifest = """
            {
              "versionCode": 3,
              "versionName": "1.2.0",
              "mandatory": true,
              "title": "YTube v1.2.0",
              "releaseNotes": "Faster ad skip",
              "apkUrl": "https://github.com/example/YTube/raw/main/apk/YTube-v1.2.0.apk"
            }
        """.trimIndent()

        val update = GitHubUpdateManager.parseUpdateManifestJson(
            rawJson = manifest,
            currentVersionName = "1.1.0",
            currentVersionCode = 2,
        )
        assertNotNull(update)
        assertEquals("1.2.0", update?.versionName)

        val noUpdate = GitHubUpdateManager.parseUpdateManifestJson(
            rawJson = manifest,
            currentVersionName = "1.2.0",
            currentVersionCode = 3,
        )
        assertNull(noUpdate)
    }
}
