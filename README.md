# YTube

**YTube** (`v1.1.0`, `versionCode 2`) is a minimal, Brave-style Android WebView browser for watching YouTube (`https://m.youtube.com`) cleanly with ad blocking, background audio playback, pull-to-refresh, and mandatory GitHub Releases in-app updates.

## Features

1. **Full-Bleed YouTube Experience + Pull-to-Refresh**
   - Headerless, full-bleed mobile YouTube WebView (`m.youtube.com`) matching the standard YouTube look and feel.
   - `SwipeRefreshLayout` pull-to-refresh when scrolled to the top (automatically disabled during fullscreen video and Shorts).
   - Fullscreen HTML5 video support and back-button navigation.

2. **Ad-Free YouTube (`AdBlocker` + `AdBlockingWebViewClient` + `JsInjector`)**
   - Blocks ad requests in `shouldInterceptRequest()` against `app/src/main/assets/adblock_lists.txt`.
   - Injects cosmetic CSS to hide ad containers and runs a 500 ms SPA-safe script to auto-click skip buttons and fast-forward unblocked ad segments at `16x`.

3. **Background Audio Playback (`BackgroundAudioWebView` + `MediaPlaybackService`)**
   - Keeps audio playing when minimized or with the screen off using visibility-swallowing WebView callbacks, Page Visibility API override, a `mediaPlayback` foreground service, partial wake lock, audio focus handling, and a `MediaSession` + `MediaStyle` notification.

4. **Mandatory GitHub Remote In-App Update (`GitHubUpdateManager` + `MandatoryUpdateScreen`)**
   - Checks `https://api.github.com/repos/<owner>/<repo>/releases/latest` at startup.
   - When a release with a higher semantic version and an `.apk` asset is available, displays an animated, full-screen Material 3 update screen with **no skip option**, live download progress, unknown-sources permission handling, and Android `FileProvider` package installation.

## Configuring GitHub Remote Updates

Set your repository slug (`owner/repo`) in the **Secrets panel** in AI Studio (or `.env` for local Gradle builds):

```properties
GITHUB_RELEASES_REPO=your-username/YTube
```

Publish a GitHub Release with a version tag higher than `1.1.0` (e.g., `v1.2.0`) and attach your signed `.apk` as a release asset.

## Build & Test

```bash
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

## Known Limitations

- Google blocks OAuth sign-in inside embedded WebViews (`disallowed_useragent`), so YTube is designed for signed-out use.
- YouTube anti-adblock detection and server-side ad insertion may occasionally allow ads through.
- Intended for personal and educational use rather than Google Play distribution.
