# YTube

**YTube** (`com.ytube.bk`, `v1.3.0`, `versionCode 6`) is a lightweight, Light-Theme Brave-style Android WebView browser for watching YouTube (`https://m.youtube.com`) with ad blocking, background audio playback, native preload & caching, pull-to-refresh, and mandatory GitHub remote in-app updates.

## Features

1. **Native Preload & Two-Tier Asset Caching (`YTubeApplication` + `ResourceCacheManager`)**
   - Pre-warms WebView subsystems and CDN TLS connections (`m.youtube.com`, `i.ytimg.com`, `yt3.ggpht.com`, `fonts.gstatic.com`) at startup.
   - Caches static `/s/` bundles, fonts, and thumbnails in L1 RAM + L2 Disk cache (`40 MB` cap), displays an animated Light Theme shimmer feed skeleton during boot, and removes browser tap highlights/overscroll/text-selection tells.

2. **Light Theme Full-Bleed YouTube + Pull-to-Refresh**
   - Clean Light Theme status/navigation bars and headerless full-bleed `m.youtube.com` browsing.
   - `SwipeRefreshLayout` pull-to-refresh at scroll top (disabled during fullscreen video and Shorts).

3. **Ad-Free YouTube (`AdBlocker` + `AdBlockingWebViewClient` + `JsInjector`)**
   - Intercepts ad requests via `assets/adblock_lists.txt` and skips/fast-forwards remaining in-stream ads.

4. **Background Audio Playback (`BackgroundAudioWebView` + `MediaPlaybackService`)**
   - Keeps audio playing when minimized or screen-off with `mediaPlayback` foreground service, wake lock, audio focus, and `MediaSession` notification controls.

5. **Play-Protect-Hardened Security & Mandatory GitHub Updates**
   - No `REQUEST_INSTALL_PACKAGES` dropper permission, `android:allowBackup="false"`, `usesCleartextTraffic="false"`, `MIXED_CONTENT_NEVER_ALLOW`, `safeBrowsingEnabled = true`, and file/content/javascript scheme blocking in `WebViewClient`.
   - `GitHubUpdateManager` + `MandatoryUpdateScreen` check GitHub Releases (`/releases/latest`) and `update.json` fallback, presenting a full-screen Material 3 Light Theme update UI with no skip option and opening the verified HTTPS GitHub release URL in the system browser/downloader.

## Build & Release

- **Debug APK:** `gradle :app:assembleDebug`
- **Minified Release APK (recommended for Play Protect on physical devices):** `gradle :app:assembleRelease` (or use **Settings → Generate APK** in AI Studio).
- **Tests:** `gradle :app:testDebugUnitTest`
