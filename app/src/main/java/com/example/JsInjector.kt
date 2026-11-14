package com.example

/**
 * Generates idempotent JavaScript payloads injected into YTube pages.
 *
 * Responsibilities:
 * 1. Override Page Visibility API when background play is enabled so YouTube's player
 *    does not pause when the app is minimized or the screen turns off.
 * 2. Inject cosmetic CSS hiding leftover ad containers, "Open in YouTube app" web banners,
 *    tap highlights, and non-input text selection so browsing feels native.
 * 3. Preconnect CDNs, prefetch tapped watch/shorts links on touchstart, and pre-warm
 *    upcoming card thumbnails via IntersectionObserver.
 * 4. Run a 500 ms SPA-safe timer that auto-clicks skip buttons, fast-forwards in-stream ads
 *    at 16x, and reports playback state/title back to Android for MediaSession sync.
 * 5. Expose window.__ytubePlay / __ytubePause / __ytubeStop for MediaSession.
 */
object JsInjector {

    fun buildProtectionScript(
        backgroundPlayEnabled: Boolean,
        isDarkTheme: Boolean = false,
    ): String {
        val bgLiteral = if (backgroundPlayEnabled) "true" else "false"
        val schemeLiteral = if (isDarkTheme) "dark" else "light"
        return """
            (function() {
                window.__ytubeBgPlay = $bgLiteral;
                if (document.documentElement) {
                    document.documentElement.style.colorScheme = '$schemeLiteral';
                }

                // 1. Page Visibility API override (gated by window.__ytubeBgPlay)
                if (!window.__ytubeVisibilityPatched) {
                    window.__ytubeVisibilityPatched = true;
                    try {
                        var origVisDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
                        var origHidDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');

                        Object.defineProperty(document, 'visibilityState', {
                            configurable: true,
                            get: function() {
                                if (window.__ytubeBgPlay) return 'visible';
                                return origVisDesc && origVisDesc.get ? origVisDesc.get.call(document) : 'visible';
                            }
                        });
                        Object.defineProperty(document, 'hidden', {
                            configurable: true,
                            get: function() {
                                if (window.__ytubeBgPlay) return false;
                                return origHidDesc && origHidDesc.get ? origHidDesc.get.call(document) : false;
                            }
                        });
                        Object.defineProperty(document, 'webkitVisibilityState', {
                            configurable: true,
                            get: function() { return window.__ytubeBgPlay ? 'visible' : document.visibilityState; }
                        });
                        Object.defineProperty(document, 'webkitHidden', {
                            configurable: true,
                            get: function() { return window.__ytubeBgPlay ? false : document.hidden; }
                        });
                    } catch (e) {}

                    var blockEventIfBg = function(e) {
                        if (window.__ytubeBgPlay) {
                            e.stopImmediatePropagation();
                        }
                    };
                    document.addEventListener('visibilitychange', blockEventIfBg, true);
                    document.addEventListener('webkitvisibilitychange', blockEventIfBg, true);
                    window.addEventListener('pagehide', blockEventIfBg, true);
                }

                // 2. Cosmetic ad-container hiding + "Open in YouTube App" web prompt hiding + native touch CSS
                if (!document.getElementById('ytube-adblock-css')) {
                    var style = document.createElement('style');
                    style.id = 'ytube-adblock-css';
                    style.textContent = [
                        '#player-ads',
                        '.ytp-ad-player-overlay',
                        '.ytp-ad-player-overlay-instream-info',
                        '.ytp-ad-overlay-container',
                        '.ytp-ad-module',
                        '.ytp-ad-image-overlay',
                        '.ytp-ad-text-overlay',
                        'ytd-display-ad-renderer',
                        'ytd-promoted-sparkles-web-renderer',
                        'ytd-ad-slot-renderer',
                        'ytm-promoted-sparkles-web-renderer',
                        'ytm-companion-slot-renderer',
                        'ytm-ad-slot-renderer',
                        'ad-slot-renderer',
                        '#masthead-ad',
                        'ytm-mealbar-promo-renderer',
                        'ytm-upsell-dialog-renderer',
                        'ytm-app-promo',
                        '.open-app-button',
                        '.ytm-open-app-button',
                        'a[href^="intent://"]'
                    ].join(', ') + ' { display: none !important; visibility: hidden !important; height: 0 !important; }\n' +
                    '* { -webkit-tap-highlight-color: transparent !important; }\n' +
                    'html, body { overscroll-behavior-y: contain !important; -webkit-font-smoothing: antialiased !important; }\n' +
                    '*:not(input):not(textarea):not([contenteditable="true"]) { -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; }';
                    (document.head || document.documentElement).appendChild(style);
                }

                // 2b. Preconnect critical media/thumbnail origins, touchstart link prefetch, & thumbnail prewarm
                if (!window.__ytubePreconnectDone) {
                    window.__ytubePreconnectDone = true;
                    try {
                        var head = document.head || document.documentElement;
                        ['https://i.ytimg.com', 'https://yt3.ggpht.com', 'https://www.youtube.com', 'https://fonts.gstatic.com'].forEach(function(origin) {
                            var link = document.createElement('link');
                            link.rel = 'preconnect';
                            link.href = origin;
                            link.crossOrigin = 'anonymous';
                            head.appendChild(link);
                        });
                    } catch (e) {}

                    var lastPrefetched = '';
                    document.addEventListener('touchstart', function(ev) {
                        try {
                            var target = ev.target;
                            var anchor = target && target.closest ? target.closest('a[href]') : null;
                            if (!anchor) return;
                            var href = anchor.getAttribute('href') || '';
                            if ((href.indexOf('/watch') === 0 || href.indexOf('/shorts') === 0) && href !== lastPrefetched) {
                                lastPrefetched = href;
                                var oldPrefetch = document.getElementById('ytube-hover-prefetch');
                                if (oldPrefetch) oldPrefetch.remove();
                                var pf = document.createElement('link');
                                pf.id = 'ytube-hover-prefetch';
                                pf.rel = 'prefetch';
                                pf.as = 'document';
                                pf.href = href;
                                (document.head || document.documentElement).appendChild(pf);
                            }
                        } catch (e) {}
                    }, { passive: true });

                    try {
                        if ('IntersectionObserver' in window) {
                            var imgObserver = new IntersectionObserver(function(entries) {
                                entries.forEach(function(entry) {
                                    if (!entry.isIntersecting) return;
                                    var img = entry.target;
                                    var src = img.getAttribute('data-thumb') || img.getAttribute('src');
                                    if (src && src.indexOf('https://') === 0 && !img.__ytubePreloaded) {
                                        img.__ytubePreloaded = true;
                                        var pre = new Image();
                                        pre.decoding = 'async';
                                        pre.src = src;
                                    }
                                    imgObserver.unobserve(img);
                                });
                            }, { rootMargin: '600px 0px' });

                            window.__ytubeScanImages = function() {
                                var imgs = document.querySelectorAll('img[src*="ytimg.com"], img[data-thumb*="ytimg.com"]');
                                for (var i = 0; i < imgs.length && i < 30; i++) {
                                    if (!imgs[i].__ytubeObserved) {
                                        imgs[i].__ytubeObserved = true;
                                        imgObserver.observe(imgs[i]);
                                    }
                                }
                            };
                        }
                    } catch (e) {}
                }

                // 2c. Signal Android once actual feed/video UI nodes have mounted so splash skeleton dissolves cleanly
                var notifyContentReady = function() {
                    if (window.__ytubeFirstContentSignaled) return;
                    var hasContent = Boolean(
                        document.querySelector('ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-reel-video-renderer, ytm-watch, #player-container-id, video')
                    );
                    if (hasContent && window.YTubeBridge && typeof window.YTubeBridge.onFirstContentReady === 'function') {
                        window.__ytubeFirstContentSignaled = true;
                        window.YTubeBridge.onFirstContentReady();
                    }
                };
                notifyContentReady();

                // 3. Playback control hooks for MediaPlaybackService MediaSession
                window.__ytubePlay = function() {
                    var video = document.querySelector('video');
                    if (video) {
                        var p = video.play();
                        if (p && p.catch) p.catch(function(){});
                        return true;
                    }
                    return false;
                };

                window.__ytubePause = function() {
                    var video = document.querySelector('video');
                    if (video && !video.paused) {
                        video.pause();
                        return true;
                    }
                    return false;
                };

                window.__ytubeStop = function() {
                    var video = document.querySelector('video');
                    if (video) {
                        video.pause();
                    }
                };

                // 4. 500 ms SPA-safe timer for auto-skip, 16x ad fast-forward, image prewarm & MediaSession sync
                if (!window.__ytubeAdTimer) {
                    window.__ytubeAdSpeedActive = false;
                    window.__ytubeSavedMuted = null;
                    window.__ytubeLastReported = '';

                    window.__ytubeAdTimer = setInterval(function() {
                        try {
                            notifyContentReady();
                            if (window.__ytubeScanImages) {
                                window.__ytubeScanImages();
                            }

                            // Auto-click skip buttons
                            var skipSelectors = [
                                '.ytp-ad-skip-button',
                                '.ytp-skip-ad-button',
                                '.ytp-ad-skip-button-modern',
                                'button.ytp-ad-skip-button-slot',
                                '.ytp-ad-overlay-close-button'
                            ];
                            for (var i = 0; i < skipSelectors.length; i++) {
                                var btn = document.querySelector(skipSelectors[i]);
                                if (btn && typeof btn.click === 'function') {
                                    btn.click();
                                }
                            }

                            var video = document.querySelector('video');
                            var player = document.querySelector('#movie_player, .html5-video-player');
                            var adOverlay = document.querySelector('.ytp-ad-player-overlay, .ytp-ad-persistent-progress-bar-container');
                            var isAdShowing = Boolean(
                                (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) ||
                                adOverlay
                            );

                            if (video) {
                                if (isAdShowing) {
                                    if (!window.__ytubeAdSpeedActive) {
                                        window.__ytubeSavedMuted = video.muted;
                                        window.__ytubeAdSpeedActive = true;
                                    }
                                    video.muted = true;
                                    if (video.playbackRate !== 16) {
                                        video.playbackRate = 16;
                                    }
                                    if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.5) {
                                        video.currentTime = video.duration - 0.2;
                                    }
                                } else if (window.__ytubeAdSpeedActive) {
                                    window.__ytubeAdSpeedActive = false;
                                    if (video.playbackRate === 16) {
                                        video.playbackRate = 1;
                                    }
                                    if (window.__ytubeSavedMuted !== null) {
                                        video.muted = window.__ytubeSavedMuted;
                                        window.__ytubeSavedMuted = null;
                                    }
                                }

                                // Sync media state with Android MediaPlaybackService
                                if (window.YTubeBridge && typeof window.YTubeBridge.onPlaybackStateChanged === 'function') {
                                    var isPlaying = !video.paused && !video.ended && video.readyState > 2;
                                    var titleEl = document.querySelector('h2.slim-video-information-title, .slim-video-metadata-header h2, h1.ytd-watch-metadata, #title h1');
                                    var rawTitle = (titleEl && titleEl.textContent) ? titleEl.textContent.trim() : document.title.replace(/\s*-\s*YouTube\s*$/i, '').trim();
                                    var channelEl = document.querySelector('.slim-owner-icon-and-title .yt-core-attributed-string, ytm-slim-owner-renderer .slim-owner-channel-name, #owner #channel-name');
                                    var rawChannel = (channelEl && channelEl.textContent) ? channelEl.textContent.trim() : 'YouTube';
                                    var key = isPlaying + '|' + rawTitle + '|' + rawChannel;
                                    if (key !== window.__ytubeLastReported) {
                                        window.__ytubeLastReported = key;
                                        window.YTubeBridge.onPlaybackStateChanged(isPlaying, rawTitle || 'YTube Audio', rawChannel || 'YouTube');
                                    }
                                }
                            }
                        } catch (err) {}
                    }, 500);
                }
            })();
        """.trimIndent()
    }

    fun buildThemeSyncScript(isDarkTheme: Boolean): String {
        val schemeLiteral = if (isDarkTheme) "dark" else "light"
        return "(function(){if(document.documentElement){document.documentElement.style.colorScheme='$schemeLiteral';}})();"
    }

    fun buildBackgroundPlayToggleScript(enabled: Boolean): String {
        val bgLiteral = if (enabled) "true" else "false"
        return "window.__ytubeBgPlay = $bgLiteral;"
    }

    fun buildPlayScript(): String = "if (window.__ytubePlay) { window.__ytubePlay(); }"

    fun buildPauseScript(): String = "if (window.__ytubePause) { window.__ytubePause(); }"
}
