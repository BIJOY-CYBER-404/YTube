package com.example

/**
 * Generates idempotent JavaScript payloads injected into YTube WebViews.
 *
 * Responsibilities:
 * 1. Override Page Visibility API and prevent timer throttling when background play is enabled.
 * 2. On FEED WebViews: intercept /watch video clicks and SPA pushState transitions so videos
 *    open smoothly in the dedicated app-style player overlay without leaving or reloading the feed.
 * 3. On WATCH WebViews: strip mobile-web top bar/promos/cast chrome so the 16:9 player sits flush
 *    at the top like the official YouTube Android app, support clean live-video miniplayer mode
 *    (html.ytube-minimized), route channel/search links back to feed, and auto-trigger next video playback (autonav).
 * 4. Disable reload when tapping the top-bar YouTube logo/text (smooth scroll-to-top only).
 * 5. Preconnect CDNs, pre-warm card thumbnails, auto-skip ads / 16x fast-forward, and sync MediaSession.
 */
object JsInjector {

    fun buildProtectionScript(
        backgroundPlayEnabled: Boolean,
        isDarkTheme: Boolean = false,
        isWatchRole: Boolean = false,
    ): String {
        val bgLiteral = if (backgroundPlayEnabled) "true" else "false"
        val schemeLiteral = if (isDarkTheme) "dark" else "light"
        val roleLiteral = if (isWatchRole) "watch" else "feed"

        return """
            (function() {
                window.__ytubeBgPlay = $bgLiteral;
                window.__ytubeRole = '$roleLiteral';
                if (document.documentElement) {
                    document.documentElement.style.colorScheme = '$schemeLiteral';
                    if ('$roleLiteral' === 'watch') {
                        document.documentElement.classList.add('ytube-watch-role');
                    } else {
                        document.documentElement.classList.remove('ytube-watch-role');
                        document.documentElement.classList.remove('ytube-minimized');
                    }
                }

                // Leave Google Sign-In pages completely clean
                var currentHost = (window.location && window.location.hostname) ? window.location.hostname.toLowerCase() : '';
                if (currentHost.indexOf('accounts.google.') !== -1 || currentHost === 'myaccount.google.com') {
                    return;
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
                    window.addEventListener('blur', function(e) {
                        if (window.__ytubeBgPlay) {
                            e.stopImmediatePropagation();
                        }
                    }, true);
                }

                // 2. Cosmetic ad blocking + app-like player & miniplayer CSS + native touch feel
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
                        'ytm-feed-nudge-renderer',
                        'ytm-footer-renderer',
                        'footer',
                        '.ytm-bottom-sheet-promo',
                        '.open-app-button',
                        '.ytm-open-app-button',
                        '.ytp-remote-button',
                        '.icon-cast',
                        'a[href^="intent://"]'
                    ].join(', ') + ' { display: none !important; visibility: hidden !important; height: 0 !important; }\n' +
                    '* { -webkit-tap-highlight-color: transparent !important; }\n' +
                    'html, body { overscroll-behavior-y: contain !important; -webkit-font-smoothing: antialiased !important; }\n' +
                    '*:not(input):not(textarea):not([contenteditable="true"]) { -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; }\n' +
                    'ytm-mobile-topbar-renderer .mobile-topbar-logo, ytm-home-logo, #home-icon { cursor: default !important; }\n' +
                    // Watch-role native YouTube-app player layout: remove web top bar above video & keep 16:9 player docked cleanly
                    'html.ytube-watch-role ytm-mobile-topbar-renderer { display: none !important; height: 0 !important; min-height: 0 !important; }\n' +
                    'html.ytube-watch-role ytm-app, html.ytube-watch-role ytm-watch { margin-top: 0 !important; padding-top: 0 !important; }\n' +
                    'html.ytube-watch-role #player-container-id { position: sticky !important; top: 0 !important; z-index: 200 !important; width: 100% !important; background: #000 !important; margin-top: 0 !important; }\n' +
                    'html.ytube-watch-role .ytp-play-progress, html.ytube-watch-role .ytp-swatch-background-color, html.ytube-watch-role .ytm-progress-bar-played { background-color: #FF0033 !important; }\n' +
                    // Live miniplayer mode inside shrunken watch WebView
                    'html.ytube-minimized, html.ytube-minimized body { overflow: hidden !important; background: #000 !important; margin: 0 !important; padding: 0 !important; }\n' +
                    'html.ytube-minimized #player-container-id { position: fixed !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; width: 100vw !important; height: 100vh !important; max-height: 100vh !important; z-index: 2147483647 !important; margin: 0 !important; padding: 0 !important; background: #000 !important; }\n' +
                    'html.ytube-minimized #player-container-id video { width: 100% !important; height: 100% !important; object-fit: cover !important; }\n' +
                    'html.ytube-minimized .player-controls-content, html.ytube-minimized .ytm-player-controls-overlay, html.ytube-minimized .ytp-chrome-top, html.ytube-minimized .ytp-chrome-bottom, html.ytube-minimized .ytp-gradient-top, html.ytube-minimized .ytp-gradient-bottom, html.ytube-minimized .ytp-spinner, html.ytube-minimized ytm-watch > *:not(#player-container-id) { opacity: 0 !important; pointer-events: none !important; }';
                    (document.head || document.documentElement).appendChild(style);
                }

                // Expose miniplayer toggle helper for Android
                window.__ytubeSetMinimized = function(minimized) {
                    if (document.documentElement) {
                        document.documentElement.classList.toggle('ytube-minimized', Boolean(minimized));
                    }
                };

                // 2a. Disable page reload when clicking YouTube logo/text in the top bar
                if (!window.__ytubeLogoClickDisabled) {
                    window.__ytubeLogoClickDisabled = true;
                    document.addEventListener('click', function(ev) {
                        try {
                            var target = ev.target;
                            if (!target || typeof target.closest !== 'function') return;
                            var logoEl = target.closest(
                                'ytm-home-logo, #home-icon, .mobile-topbar-logo, ytm-mobile-topbar-renderer a[href="/"], header a[href="/"], a[aria-label*="YouTube Home" i], [id*="logo"] a[href="/"]'
                            );
                            if (logoEl) {
                                ev.preventDefault();
                                ev.stopPropagation();
                                ev.stopImmediatePropagation();
                                if (window.scrollY > 0) {
                                    window.scrollTo({ top: 0, behavior: 'smooth' });
                                }
                            }
                        } catch (e) {}
                    }, true);
                }

                // 2b. App-style watch routing & SPA interception (no full-page feed reloads on Back!)
                var parseYouTubeUrl = function(rawHref) {
                    try {
                        if (!rawHref || rawHref.indexOf('#') === 0 || rawHref.indexOf('javascript:') === 0) return null;
                        var parsed = new URL(rawHref, window.location.origin);
                        var h = parsed.hostname.toLowerCase();
                        if (h !== 'm.youtube.com' && h !== 'www.youtube.com' && h !== 'youtube.com' && h !== 'youtu.be') {
                            return null;
                        }
                        return parsed;
                    } catch (e) {
                        return null;
                    }
                };

                if (!window.__ytubeRouterPatched) {
                    window.__ytubeRouterPatched = true;

                    document.addEventListener('click', function(ev) {
                        try {
                            if (!window.YTubeBridge) return;
                            var target = ev.target;
                            var anchor = target && target.closest ? target.closest('a[href]') : null;
                            if (!anchor) return;
                            var parsed = parseYouTubeUrl(anchor.getAttribute('href'));
                            if (!parsed) return;

                            var path = parsed.pathname || '';
                            var isWatchUrl = (path === '/watch' && Boolean(parsed.searchParams.get('v'))) || parsed.hostname.toLowerCase() === 'youtu.be';

                            if (window.__ytubeRole === 'feed' && isWatchUrl && typeof window.YTubeBridge.openVideo === 'function') {
                                ev.preventDefault();
                                ev.stopPropagation();
                                ev.stopImmediatePropagation();
                                window.YTubeBridge.openVideo(parsed.toString());
                                return;
                            }

                            if (window.__ytubeRole === 'watch' && !isWatchUrl) {
                                var isBrowseTarget = path === '/' ||
                                    path.indexOf('/@') === 0 ||
                                    path.indexOf('/channel/') === 0 ||
                                    path.indexOf('/c/') === 0 ||
                                    path.indexOf('/user/') === 0 ||
                                    path.indexOf('/results') === 0 ||
                                    path.indexOf('/feed/') === 0 ||
                                    path.indexOf('/shorts') === 0;
                                if (isBrowseTarget && typeof window.YTubeBridge.openInFeed === 'function') {
                                    ev.preventDefault();
                                    ev.stopPropagation();
                                    ev.stopImmediatePropagation();
                                    window.YTubeBridge.openInFeed(parsed.toString());
                                }
                            }
                        } catch (e) {}
                    }, true);

                    try {
                        var origPushState = history.pushState;
                        history.pushState = function(state, title, url) {
                            try {
                                if (url && window.YTubeBridge) {
                                    var parsed = parseYouTubeUrl(String(url));
                                    if (parsed) {
                                        var path = parsed.pathname || '';
                                        var isWatchUrl = (path === '/watch' && Boolean(parsed.searchParams.get('v')));
                                        if (window.__ytubeRole === 'feed' && isWatchUrl && typeof window.YTubeBridge.openVideo === 'function') {
                                            window.YTubeBridge.openVideo(parsed.toString());
                                            return;
                                        }
                                        if (window.__ytubeRole === 'watch' && isWatchUrl && typeof window.YTubeBridge.openVideo === 'function') {
                                            window.YTubeBridge.openVideo(parsed.toString());
                                        }
                                    }
                                }
                            } catch (e) {}
                            return origPushState.apply(history, arguments);
                        };

                        var origReplaceState = history.replaceState;
                        history.replaceState = function(state, title, url) {
                            try {
                                if (url && window.YTubeBridge && window.__ytubeRole === 'watch') {
                                    var parsed = parseYouTubeUrl(String(url));
                                    if (parsed) {
                                        var path = parsed.pathname || '';
                                        var isWatchUrl = (path === '/watch' && Boolean(parsed.searchParams.get('v')));
                                        if (isWatchUrl && typeof window.YTubeBridge.openVideo === 'function') {
                                            window.YTubeBridge.openVideo(parsed.toString());
                                        }
                                    }
                                }
                            } catch (e) {}
                            return origReplaceState.apply(history, arguments);
                        };
                    } catch (e) {}
                }

                // 2c. Preconnect critical origins & warm upcoming thumbnails
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

                // 2d. Signal Android once actual feed/watch UI nodes have mounted so skeletons dissolve cleanly
                var notifyContentReady = function() {
                    if (window.__ytubeRole === 'watch') {
                        if (!window.__ytubeWatchReadySignaled) {
                            var videoNode = document.querySelector('video');
                            var hasWatchDom = Boolean(videoNode && (videoNode.readyState >= 1 || document.querySelector('ytm-slim-video-metadata-section-renderer, ytm-watch')));
                            if (hasWatchDom && window.YTubeBridge && typeof window.YTubeBridge.onWatchContentReady === 'function') {
                                window.__ytubeWatchReadySignaled = true;
                                window.YTubeBridge.onWatchContentReady();
                            }
                        }
                        return;
                    }

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

                // 3. Playback control hooks for MediaPlaybackService & Miniplayer
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

                // 4. 500 ms SPA-safe timer for auto-skip, 16x ad fast-forward, automatic next play, image prewarm & MediaSession sync
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

                            // Automatic next play (autonav endscreen next button or video ended click next recommendation)
                            var autonavBtn = document.querySelector('.ytp-autonav-endscreen-next-button, .ytp-autonav-button-next, .ytp-upnext-autoplay-icon, ytm-autonav-endscreen-renderer button.ytp-autonav-endscreen-upnext');
                            if (autonavBtn && typeof autonavBtn.click === 'function') {
                                autonavBtn.click();
                            }

                            var video = document.querySelector('video');
                            if (video && video.ended) {
                                var nextLink = document.querySelector('ytm-compact-video-renderer a[href*="/watch"], ytm-rich-item-renderer a[href*="/watch"], .ytp-suggestion-set a[href*="/watch"]');
                                if (nextLink && typeof nextLink.click === 'function') {
                                    nextLink.click();
                                }
                            }

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

                                // Sync media state with Android MediaPlaybackService & Miniplayer bar
                                if (window.YTubeBridge && typeof window.YTubeBridge.onPlaybackStateChanged === 'function') {
                                    var isPlaying = !video.paused && !video.ended && video.readyState > 1;
                                    var titleEl = document.querySelector('h2.slim-video-information-title, .slim-video-metadata-header h2, ytm-slim-video-information-renderer h2, h1.ytd-watch-metadata, #title h1');
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

    fun buildMinimizedScript(minimized: Boolean): String {
        val flag = if (minimized) "true" else "false"
        return "if (window.__ytubeSetMinimized) { window.__ytubeSetMinimized($flag); }"
    }

    fun buildBackgroundPlayToggleScript(enabled: Boolean): String {
        val bgLiteral = if (enabled) "true" else "false"
        return "window.__ytubeBgPlay = $bgLiteral;"
    }

    fun buildPlayScript(): String = "if (window.__ytubePlay) { window.__ytubePlay(); }"

    fun buildPauseScript(): String = "if (window.__ytubePause) { window.__ytubePause(); }"

    fun buildStopScript(): String = "if (window.__ytubeStop) { window.__ytubeStop(); }"
}
