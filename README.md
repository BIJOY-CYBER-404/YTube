# YTube

**YTube** (`com.ytube.bk`, `v1.2.0`, `versionCode 3`) is a lightweight, Light-Theme Brave-style Android WebView browser for watching YouTube (`https://m.youtube.com`) with ad blocking, background audio playback, pull-to-refresh, and mandatory GitHub remote in-app updates.

## Features

1. **Light Theme Full-Bleed YouTube + Pull-to-Refresh**
   - Clean Light Theme status/navigation bars and headerless full-bleed `m.youtube.com` browsing.
   - `SwipeRefreshLayout` pull-to-refresh at scroll top (disabled during fullscreen video and Shorts).

2. **Ad-Free YouTube (`AdBlocker` + `AdBlockingWebViewClient` + `JsInjector`)**
   - Intercepts ad requests via `assets/adblock_lists.txt` and skips/fast-forwards remaining in-stream ads.

3. **Background Audio Playback (`BackgroundAudioWebView` + `MediaPlaybackService`)**
   - Keeps audio playing when minimized or screen-off with `mediaPlayback` foreground service, wake lock, audio focus, and `MediaSession` notification controls.

4. **Play-Protect-Hardened Security & Mandatory GitHub Updates**
   - No `REQUEST_INSTALL_PACKAGES` dropper permission, `android:allowBackup="false"`, `usesCleartextTraffic="false"`, `MIXED_CONTENT_NEVER_ALLOW`, `safeBrowsingEnabled = true`, and file/content/javascript scheme blocking in `WebViewClient`.
   - `GitHubUpdateManager` + `MandatoryUpdateScreen` check GitHub Releases (`/releases/latest`) and `update.json` fallback, presenting a full-screen Material 3 Light Theme update UI with no skip option and opening the verified HTTPS GitHub release URL in the system browser/downloader.

## Build & Release

- **Debug APK:** `gradle :app:assembleDebug`
- **Minified Release APK (recommended for Play Protect on physical devices):** `gradle :app:assembleRelease` (or use **Settings → Generate APK** in AI Studio).
- **Tests:** `gradle :app:testDebugUnitTest`
