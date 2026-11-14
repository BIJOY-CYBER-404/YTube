# YTube

**YTube** (`com.ytube.bk`, `v1.4.5`, `versionCode 12`) is a lightweight Android YouTube client (`https://m.youtube.com`) that automatically matches your device Light/Dark theme, with ad blocking, persistent in-app Google sign-in, background audio playback, native preload & Service Worker asset caching, pull-to-refresh, and mandatory inside-the-app GitHub updates.

## Features

1. **Native Preload & Two-Tier + Service Worker Caching (`YTubeApplication` + `ResourceCacheManager`)**
   - Pre-warms WebView subsystems and CDN TLS connections (`m.youtube.com`, `i.ytimg.com`, `yt3.ggpht.com`, `fonts.gstatic.com`) at startup.
   - Intercepts both WebView and Service Worker requests to serve cached static `/s/` bundles, fonts, and thumbnails from L1 RAM + L2 Disk cache (`40 MB` cap).
   - Displays a YouTube-style device-themed shimmer feed & bottom-bar skeleton during boot, hides "Open in YouTube app" web banners, preloads upcoming card thumbnails, and removes browser tap highlights/overscroll/text-selection tells.

2. **Device-Default Light/Dark Theme + Pull-to-Refresh**
   - Automatically syncs system bars, Material 3 UI, and YouTube WebView (`PREF` theme cookie, CSS `color-scheme`, and WebView darkening) with the device's Light/Dark setting.
   - `SwipeRefreshLayout` pull-to-refresh at scroll top (disabled during fullscreen video and Shorts).

3. **Ad-Free YouTube (`AdBlocker` + `AdBlockingWebViewClient` + `JsInjector`)**
   - Intercepts ad requests via `assets/adblock_lists.txt` and skips/fast-forwards remaining in-stream ads.

4. **Background Audio Playback (`BackgroundAudioWebView` + `MediaPlaybackService`)**
   - Keeps audio playing when minimized or screen-off with `mediaPlayback` foreground service, wake lock, audio focus, and `MediaSession` notification controls.

5. **Mandatory Inside-the-App GitHub Updates (`GitHubUpdateManager` + `MandatoryUpdateScreen`)**
   - Checks GitHub Releases and `update.json`, presents a full-screen Material 3 Light Theme update UI with no skip option, downloads the `.apk` directly inside YTube with live progress, and launches Android's PackageInstaller via `FileProvider` without leaving the app.

## Build & Release

- **Debug APK:** `gradle :app:assembleDebug`
- **Minified Release APK (recommended for Play Protect on physical devices):** `gradle :app:assembleRelease` (or use **Settings → Generate APK** in AI Studio).
- **Tests:** `gradle :app:testDebugUnitTest`
