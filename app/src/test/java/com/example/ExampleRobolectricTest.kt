package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.cache.ResourceCacheManager
import com.example.update.GitHubUpdateManager
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
        assertEquals("BIJOY-CYBER-404/YTube", context.getString(R.string.github_releases_repo))
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
        assertTrue(scriptEnabled.contains("window.__ytubeBgPlay = true;"))
        assertTrue(scriptEnabled.contains("window.__ytubePlay"))
        assertTrue(scriptEnabled.contains("window.__ytubePause"))
        assertTrue(scriptEnabled.contains("playbackRate = 16"))
        assertTrue(scriptEnabled.contains(".ytp-ad-skip-button"))

        val scriptDisabled = JsInjector.buildProtectionScript(backgroundPlayEnabled = false)
        assertTrue(scriptDisabled.contains("window.__ytubeBgPlay = false;"))
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

    @Test
    fun `github update manager handles empty apkUrl and release without apk asset`() {
        val manifestEmptyUrl = """
            {
              "versionCode": 5,
              "versionName": "1.3.0",
              "releaseNotes": "Update",
              "apkUrl": ""
            }
        """.trimIndent()
        val parsedManifest = GitHubUpdateManager.parseUpdateManifestJson(
            rawJson = manifestEmptyUrl,
            currentVersionName = "1.2.1",
            currentVersionCode = 4,
            repoSlug = "owner/YTube",
        )
        assertNotNull(parsedManifest)
        assertEquals(
            "https://github.com/owner/YTube/raw/main/apk/YTube-v1.3.0.apk",
            parsedManifest?.apkDownloadUrl,
        )

        val prereleaseArray = """
            [
              {
                "tag_name": "release-1.3.0",
                "name": "YTube 1.3.0",
                "body": "Notes",
                "draft": false,
                "html_url": "https://github.com/owner/YTube/releases/tag/release-1.3.0",
                "assets": []
              }
            ]
        """.trimIndent()
        val parsedArray = GitHubUpdateManager.parseReleasesArrayJson(
            rawArrayJson = prereleaseArray,
            currentVersionName = "1.2.1",
            repoSlug = "owner/YTube",
        )
        assertNotNull(parsedArray)
        assertEquals("1.3.0", parsedArray?.versionName)
        assertEquals(
            "https://github.com/owner/YTube/raw/main/apk/YTube-v1.3.0.apk",
            parsedArray?.apkDownloadUrl,
        )
    }

    @Test
    fun `resource cache caches static bundles and thumbnails but skips streams and apis`() {
        assertTrue(ResourceCacheManager.isCacheableAssetUrl("https://i.ytimg.com/vi/abc123/hq720.jpg"))
        assertTrue(ResourceCacheManager.isCacheableAssetUrl("https://yt3.ggpht.com/ytc/avatar=s68-c-k.webp"))
        assertTrue(ResourceCacheManager.isCacheableAssetUrl("https://m.youtube.com/s/player/12345/base.js"))
        assertTrue(ResourceCacheManager.isCacheableAssetUrl("https://fonts.gstatic.com/s/roboto/v30/font.woff2"))

        assertFalse(ResourceCacheManager.isCacheableAssetUrl("https://rr1---sn-test.googlevideo.com/videoplayback?id=1"))
        assertFalse(ResourceCacheManager.isCacheableAssetUrl("https://m.youtube.com/youtubei/v1/browse"))
        assertFalse(ResourceCacheManager.isCacheableAssetUrl("https://m.youtube.com/watch?v=abc123"))
        assertFalse(ResourceCacheManager.isCacheableAssetUrl("https://accounts.google.com/ServiceLogin"))
    }

    @Test
    fun `js injector includes native touch styling and first content ready signal`() {
        val script = JsInjector.buildProtectionScript(backgroundPlayEnabled = true)
        assertTrue(script.contains("-webkit-tap-highlight-color: transparent"))
        assertTrue(script.contains("onFirstContentReady"))
        assertTrue(script.contains("rel = 'prefetch'"))
    }
}
