# TubeView — PRD & Google AI Studio Build Prompt

Product: **TubeView** — a Brave-style YouTube WebView browser for Android.
Version: 1.0 (PRD written 13 Nov 2026)
Status: PRD for build. A starter Android Studio project already exists
(`tubeview-webview-browser.zip`, package `com.bkonfire.tubeview`) — AI Studio
should review, complete, and harden it, not start from zero blindly.

---

## PART A — Paste-ready prompt for Google AI Studio

Copy everything inside the box below and paste it into Google AI Studio as the
first message. Then follow the build order in Section 9 of the PRD, one step at
a time, testing each step before moving on.

```
Build me a complete, working Android Studio project in Kotlin called TubeView
(package com.bkonfire.tubeview) — a Brave-style YouTube WebView browser with
exactly TWO core features:

1. AD-FREE YOUTUBE: Load https://m.youtube.com in an Android WebView and
   block ads at the network level. Implement a custom WebViewClient that
   overrides shouldInterceptRequest() and checks every resource request
   against a filter list (EasyList-style domains + URL substrings) loaded from
   assets/adblock_lists.txt. Blocked requests must return an empty
   WebResourceResponse so the ad never downloads. Also inject JavaScript on
   every page load (evaluateJavascript) that: (a) hides leftover ad containers
   with CSS (#player-ads, .ytp-ad-player-overlay, ytd-display-ad-renderer),
   (b) clicks the skip button (.ytp-ad-skip-button) on a 500 ms timer, and
   (c) sets video.playbackRate = 16 while an ad is showing, else 1. YouTube
   is a single-page app, so the timer approach is required — do NOT rely only
   on page-load events.

2. BACKGROUND PLAY: Audio must keep playing when the app is minimised or the
   screen is off. Implement ALL of these together, because any one alone
   fails: (a) a custom WebView subclass that swallows onWindowVisibilityChanged
   and onVisibilityChanged so Chromium keeps thinking the page is visible,
   (b) a foreground service (type mediaPlayback) with a partial wake lock,
   (c) a MediaSession with Play/Pause/Stop so the notification and lock screen
   can control playback via injected JS (window.__tubeviewPlay/Pause), and
   (d) Activity lifecycle rules: NEVER call webView.onPause() while background
   play is on — not in onPause() and not in onStop(). Add a menu toggle
   "Background play" (on by default); when off, minimise must pause the video.

Also include: fullscreen video support (WebChromeClient onShowCustomView /
onHideCustomView), back-button navigation (goBack if possible), a top progress
bar, cookie support (CookieManager, third-party cookies on), an options menu
(Background play toggle, Reload, YouTube home), and an adaptive launcher icon.

Tech constraints: Kotlin, AndroidX, minSdk 26, targetSdk 35, compileSdk 35,
AGP 8.5.2, Kotlin 2.0.0, Java 17. No extra heavy dependencies — only
androidx.core:core-ktx and androidx.appcompat:appcompat. Deliver the FULL
project structure (settings.gradle, root build.gradle, app/build.gradle,
AndroidManifest.xml with INTERNET, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS and WAKE_LOCK
permissions, all Kotlin sources, res/ files, assets/adblock_lists.txt, and a
README with build steps).

Be honest in the code comments about limits: YouTube login may fail in a
WebView (Google blocks sign-in from embedded WebViews), some ads may still
slip through (YouTube anti-adblock detection and server-side ad insertion),
and this bypasses YouTube Premium features, so it must NOT be published on
Google Play as-is — it is a personal/learning project.

Output the complete file contents, one file at a time, starting with the
Gradle files and manifest, then the Kotlin sources, then resources. After
each file, state what to test before continuing.
```

---

## PART B — Full PRD

### 1. Product overview

TubeView is a minimal Android browser whose only job is to open YouTube
(`m.youtube.com`) in a WebView and make watching better in two specific ways:
no video ads, and audio that keeps playing in the background. It is a
browser, not a YouTube client: it does not use the YouTube API, does not
download videos, and does not touch the official YouTube app. Everything it
does, it does because it owns the browsing surface — the same reason Brave
can block ads while a separate app cannot.

### 2. Problem statement

- Watching YouTube in a mobile browser shows pre-roll, mid-roll, and banner
  ads, and playback stops the moment the user switches apps or turns the
  screen off.
- The official YouTube app cannot be modified by another app (Android
  sandbox, TLS + certificate pinning, ads and video served from the same
  `googlevideo.com` domain), so the fix must be a browser of its own.
- Existing solutions are either paid (YouTube Premium) or patched APKs that
  break on every update. A WebView browser is the maintainable middle path.

### 3. Goals (v1.0)

| # | Goal | How success is judged |
|---|------|----------------------|
| G1 | Block YouTube video ads in the WebView | Pre-roll/mid-roll ads do not play in normal testing |
| G2 | Background audio keeps playing | Audio continues for 10+ minutes with app minimised / screen off |
| G3 | Feels like a real browser, not a hack | Back, fullscreen, reload, progress bar all work |
| G4 | Buildable by one person in Android Studio | Project opens, syncs, and builds an APK with no manual fixes |

### 4. Non-goals (explicitly OUT of scope for v1.0)

- Blocking ads inside the official YouTube app (technically impossible
  without root/patching — see Section 11).
- Video downloading, offline playback, or casting.
- YouTube account sign-in (Google blocks sign-in from embedded WebViews).
- Play Store publication.
- iOS version.
- A video player of our own (no ExoPlayer; the web player is the player).

### 5. Target user

One person: an Android user who watches long YouTube videos (tutorials,
music, podcasts) and wants them ad-free with the screen off, on their own
phone, without paying for Premium. Technical level: can open a project in
Android Studio and press Run.

### 6. User stories

- US1: As a viewer, I open TubeView and YouTube loads, so I can browse and
  search videos exactly like in a browser.
- US2: As a viewer, I tap a video and it plays without a pre-roll ad, so I
  am not interrupted.
- US3: As a viewer, when a mid-roll ad would appear, it is skipped or
  fast-forwarded automatically, so I do not have to watch it.
- US4: As a listener, I press Home / turn the screen off and the audio keeps
  playing, so I can listen like a podcast.
- US5: As a listener, I pause / resume from the notification shade or lock
  screen, so I do not have to reopen the app.
- US6: As a viewer, I can turn background play off from the menu, so the
  video pauses when I leave the app (normal browser behaviour).
- US7: As a viewer, I can go fullscreen, go back, and reload, so basic
  browsing works.

### 7. Functional requirements — Scope 1: Ad-free

| ID | Requirement |
|----|-------------|
| FR1.1 | Every resource request from the WebView passes through `WebViewClient.shouldInterceptRequest()` |
| FR1.2 | Requests matching the filter list return an empty `WebResourceResponse` (`text/plain`, empty stream) — the ad never downloads |
| FR1.3 | Filter list lives in `assets/adblock_lists.txt` with two sections: `[domains]` (host suffix match) and `[url-substrings]` (substring match on full URL) |
| FR1.4 | Starter list must include at minimum: `doubleclick.net`, `googleadservices.com`, `googlesyndication.com`, `googleads.g.doubleclick.net`, `pagead2.googlesyndication.com`, `adservice.google.com`, `ads.youtube.com`, plus substrings `doubleclick`, `pagead/`, `googleads`, `/api/stats/ads` |
| FR1.5 | Matching must be cheap (sets, no regex) — `shouldInterceptRequest()` runs on a background thread for every resource |
| FR1.6 | JS injection on page finish: hide `#player-ads`, `.ytp-ad-player-overlay`, `ytd-display-ad-renderer`, `ytd-promoted-sparkles-web-renderer` via injected CSS |
| FR1.7 | A 500 ms timer clicks `.ytp-ad-skip-button` / `.ytp-skip-ad-button` whenever present |
| FR1.8 | While an ad is showing (`.ad-showing` or `.ytp-ad-player-overlay` present), set `video.playbackRate = 16`; otherwise `1` |
| FR1.9 | Injection must survive YouTube's single-page navigation — timer-based, not load-event-only |
| FR1.10 | YouTube / googlevideo links stay inside the WebView; all other links open in the external browser |

### 8. Functional requirements — Scope 2: Background play

| ID | Requirement |
|----|-------------|
| FR2.1 | Custom WebView subclass swallows `onWindowVisibilityChanged()` and `onVisibilityChanged()` so Chromium keeps treating the page as visible |
| FR2.2 | Foreground service with `foregroundServiceType="mediaPlayback"` and a partial wake lock keeps the process alive in the background |
| FR2.3 | `MediaSession` (Play/Pause/Stop) drives playback through injected JS functions `window.__tubeviewPlay` / `window.__tubeviewPause` |
| FR2.4 | Notification (MediaStyle) shows Play/Pause and Stop, and tapping it returns to the app |
| FR2.5 | `MainActivity` must NEVER call `webView.onPause()` while background play is enabled — not in `onPause()`, not in `onStop()` |
| FR2.6 | Menu toggle "Background play" (default ON). When OFF, leaving the app pauses the video |
| FR2.7 | Injected JS overrides the Page Visibility API (`visibilityState` → `'visible'`, `hidden` → `false`, stop `visibilitychange` propagation) so the player never auto-pauses |
| FR2.8 | Audio focus and noisy-disconnect (headphones unplugged) pause playback; this is expected system behaviour, not a bug |

### 9. Other requirements

| ID | Requirement |
|----|-------------|
| FR3.1 | Fullscreen video via `WebChromeClient.onShowCustomView` / `onHideCustomView` with a fullscreen container |
| FR3.2 | Back button: exit fullscreen first, else `webView.goBack()` if possible, else close the app |
| FR3.3 | Horizontal progress bar at the top, visible while a page loads |
| FR3.4 | JavaScript ON, DOM storage ON, `mediaPlaybackRequiresUserGesture = false` |
| FR3.5 | Cookies accepted, including third-party cookies (YouTube needs them) |
| FR3.6 | `configChanges="orientation|screenSize|screenLayout|keyboardHidden"` so rotation does not reload the page |
| FR3.7 | Adaptive launcher icon (red rounded rectangle + white play triangle) |
| FR3.8 | App opens on `https://m.youtube.com` on first launch |

### 10. Technical architecture

**Stack:** Kotlin, AndroidX, Android System WebView (Chromium), minSdk 26,
targetSdk 35, compileSdk 35, AGP 8.5.2, Kotlin 2.0.0, Java 17.
Dependencies: only `androidx.core:core-ktx:1.13.1` and
`androidx.appcompat:appcompat:1.7.0`. No ExoPlayer, no YouTube API, no
networking library — the WebView is the engine.

**Modules (package `com.bkonfire.tubeview`):**

| File | Responsibility |
|------|----------------|
| `MainActivity.kt` | WebView setup, fullscreen, menu, lifecycle rules (FR2.5) |
| `AdBlocker.kt` | Loads `adblock_lists.txt`, answers `isAdRequest(url)` |
| `AdBlockingWebViewClient.kt` | `shouldInterceptRequest()` blocking, `onPageFinished` injection, link routing |
| `BackgroundAudioWebView.kt` | Visibility-callback swallowing WebView subclass |
| `JsInjector.kt` | Visibility override, cosmetic CSS, skip timer, `__tubeviewPlay/Pause` bridge |
| `MediaPlaybackService.kt` | Foreground service, wake lock, MediaSession, notification; holds `PlayerBridge` (WeakReference to the WebView) |

**Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`.

**Data flow (ads):**
`m.youtube.com` loads → every resource request → `shouldInterceptRequest()`
→ `AdBlocker.isAdRequest()` → match: empty response (ad dies) / no match:
normal load → `JsInjector` hides leftovers + skips.

**Data flow (background):**
User minimises → Activity `onPause()` (does NOT pause WebView) → Chromium
still sees page as visible (swallowed callbacks + JS visibility override) →
foreground service + wake lock keep the process alive → audio continues →
notification / lock screen controls via MediaSession → JS play/pause.

### 11. Known limitations — must be stated, not hidden

1. **Sign-in:** Google blocks OAuth sign-in from embedded WebViews, so
   YouTube login will very likely fail. The app is designed for signed-out
   use. Do not promise login.
2. **Not 100% ad-free:** YouTube's anti-adblock detection and server-side ad
   insertion (ads baked into the video stream from `googlevideo.com`) mean
   some ads will occasionally slip through. This is a cat-and-mouse game;
   filter rules need updating when YouTube changes.
3. **Not Brave-strength:** Brave has a native Rust engine with scriptlets.
   A WebView app has `shouldInterceptRequest()` + JS injection only — good,
   but weaker.
4. **Cannot touch the official app:** a separate app cannot block ads inside
   the official YouTube app (sandbox, TLS + certificate pinning, same-domain
   delivery). This PRD does not attempt that.
5. **Legal / store:** ad-free + background play bypass YouTube Premium
   features and violate YouTube's Terms of Service. Do NOT publish on
   Google Play as-is. Personal / learning project only.
6. **Battery:** background playback with a wake lock uses more battery than
   a paused video — expected, and the reason the toggle exists.

### 12. Acceptance criteria (test checklist)

- [ ] Project opens in Android Studio, Gradle syncs, APK builds with zero errors.
- [ ] App launches to `m.youtube.com`; search, browse, and video playback work.
- [ ] A video with a known pre-roll plays with no pre-roll ad (test 3 videos).
- [ ] Skip button is auto-clicked when it appears (observe once).
- [ ] Press Home mid-video: audio keeps playing for 10 minutes.
- [ ] Turn screen off mid-video: audio keeps playing for 10 minutes.
- [ ] Notification shows Pause; tapping it pauses; tapping Play resumes.
- [ ] Lock-screen media controls work.
- [ ] Menu → Background play OFF → press Home → video pauses.
- [ ] Fullscreen button works; back exits fullscreen, then goes back in history.
- [ ] Reload and "YouTube home" menu items work.
- [ ] App survives rotation without reloading the page.
- [ ] No crash on back-press from the home page (app closes cleanly).

### 13. Build order for AI Studio (do not skip steps)

1. **Step 1 — Skeleton:** Gradle files, manifest, empty Activity + layout.
   Test: project syncs and the app launches (blank WebView).
2. **Step 2 — WebView basics:** settings, cookies, load `m.youtube.com`,
   back/fullscreen/progress bar. Test: YouTube browses and plays video.
3. **Step 3 — Scope 1:** `AdBlocker` + `AdBlockingWebViewClient` +
   `adblock_lists.txt`. Test: ads blocked on 3 videos.
4. **Step 4 — Scope 1 JS:** `JsInjector` (CSS hide, skip timer, 16x).
   Test: leftover ad boxes gone, skip auto-clicks.
5. **Step 5 — Scope 2:** `BackgroundAudioWebView` + lifecycle rules.
   Test: audio survives minimise (short test first).
6. **Step 6 — Scope 2 service:** `MediaPlaybackService` + notification +
   MediaSession. Test: 10-minute screen-off test + notification controls.
7. **Step 7 — Polish:** menu toggle, icon, README. Final pass of the
   acceptance checklist in Section 12.

### 14. How to use this prompt in Google AI Studio

1. Go to **Google AI Studio** (aistudio.google.com) → start a new chat
   (or a Build session).
2. Paste **Part A** as the first message.
3. Work through Section 13 one step at a time. After each step, ask:
   *"Show me the complete contents of the next file, then tell me how to
   test it."* Do not ask for all files in one message — review each one.
4. Download/copy the generated files into the matching folders of an
   Android Studio project (or into the existing `tubeview-webview-browser`
   starter project, replacing files one by one).
5. In Android Studio: sync Gradle → Run on a phone (or Build → Build APK).
6. Run the Section 12 checklist. Paste any build error back into AI Studio
   with the exact error text and the file it points to.

**Honest note:** Google AI Studio generates code — it does **not** compile an
Android APK by itself. Its "Build apps" mode makes web apps, not native
Android apps. So AI Studio is the coder here, Android Studio is the builder.
If AI Studio ever claims the APK is ready, verify it yourself: the real test
is Section 12, run on a real phone.
