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
                    'html, body { overscroll-behavior-y: none !important; touch-action: manipulation !important; -webkit-font-smoothing: antialiased !important; }\n' +
                    '*:not(input):not(textarea):not([contenteditable="true"]) { -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; }\n' +
                    'ytm-mobile-topbar-renderer .mobile-topbar-logo, ytm-home-logo, #home-icon { cursor: default !important; }\n' +
                    // Watch-role native YouTube-app player layout: remove web top bar above video & eliminate phantom 56.25% placeholder gap
                    'html.ytube-watch-role ytm-mobile-topbar-renderer, html.ytube-watch-role ytm-header, html.ytube-watch-role header.ghost-header { display: none !important; height: 0 !important; min-height: 0 !important; padding: 0 !important; margin: 0 !important; }\n' +
                    'html.ytube-watch-role ytm-app, html.ytube-watch-role ytm-watch, html.ytube-watch-role ytm-app.sticky-player { margin-top: 0 !important; padding-top: 0 !important; }\n' +
                    'html.ytube-watch-role #player-container-id { position: sticky !important; top: 0 !important; z-index: 200 !important; width: 100% !important; background: #000 !important; margin: 0 !important; padding: 0 !important; aspect-ratio: 16 / 9 !important; height: calc(100vw * 9 / 16) !important; max-height: calc(100vw * 9 / 16) !important; min-height: 0 !important; }\n' +
                    'html.ytube-watch-role #player-container-id .player-size, html.ytube-watch-role #player-container-id #player { width: 100% !important; height: 100% !important; max-height: 100% !important; min-height: 0 !important; padding-bottom: 0 !important; margin: 0 !important; }\n' +
                    'html.ytube-watch-role #player-container-id video, html.ytube-watch-role #movie_player video, html.ytube-watch-role .html5-video-player video { width: 100% !important; height: 100% !important; object-fit: contain !important; }\n' +
                    'html.ytube-watch-role .player-placeholder-wrapper, html.ytube-watch-role .player-placeholder, html.ytube-watch-role [class*="player-placeholder"], html.ytube-watch-role ytm-app .player-placeholder-wrapper, html.ytube-watch-role .player-size.sticky-player:not(#player-container-id *), html.ytube-watch-role #player-cinematics-container, html.ytube-watch-role .player-cinematics { display: none !important; height: 0 !important; min-height: 0 !important; max-height: 0 !important; padding: 0 !important; margin: 0 !important; }\n' +
                    'html.ytube-watch-role .watch-below-the-player, html.ytube-watch-role .watch-main-col, html.ytube-watch-role ytm-slim-video-metadata-section-renderer, html.ytube-watch-role ytm-single-column-watch-next-results-renderer { margin-top: 0 !important; padding-top: 0 !important; }\n' +
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

                    var getVideoId = function(urlObj) {
                        try {
                            if (!urlObj) return '';
                            if (urlObj.hostname && urlObj.hostname.toLowerCase() === 'youtu.be') {
                                return (urlObj.pathname || '').replace(/^\/+/, '').split('/')[0] || '';
                            }
                            return (urlObj.searchParams && urlObj.searchParams.get('v')) ? urlObj.searchParams.get('v') : '';
                        } catch (e) {
                            return '';
                        }
                    };
                    window.__ytubeGetVideoId = getVideoId;

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
                                        if (window.__ytubeRole === 'watch' && isWatchUrl) {
                                            if (typeof window.YTubeBridge.syncWatchUrl === 'function') {
                                                window.YTubeBridge.syncWatchUrl(parsed.toString());
                                            }
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
                                        if (isWatchUrl && typeof window.YTubeBridge.syncWatchUrl === 'function') {
                                            window.YTubeBridge.syncWatchUrl(parsed.toString());
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

                // 2d. Collapse phantom placeholder spaces below player (mark collapsed nodes to avoid style thrashing) & automate video unmuting
                var collapseWatchSpacing = function() {
                    if (window.__ytubeRole !== 'watch') return;
                    try {
                        var placeholders = document.querySelectorAll(
                            '.player-placeholder-wrapper, .player-placeholder, [class*="player-placeholder"], ytm-app .player-placeholder-wrapper, #player-cinematics-container, .player-cinematics'
                        );
                        for (var i = 0; i < placeholders.length; i++) {
                            var ph = placeholders[i];
                            if (!ph.__ytubeCollapsed) {
                                ph.__ytubeCollapsed = true;
                                ph.style.setProperty('display', 'none', 'important');
                                ph.style.setProperty('height', '0px', 'important');
                                ph.style.setProperty('min-height', '0px', 'important');
                                ph.style.setProperty('padding', '0px', 'important');
                                ph.style.setProperty('margin', '0px', 'important');
                            }
                        }
                        var appEl = document.querySelector('ytm-app, ytm-watch');
                        if (appEl && !appEl.__ytubeCollapsed) {
                            appEl.__ytubeCollapsed = true;
                            appEl.style.setProperty('padding-top', '0px', 'important');
                            appEl.style.setProperty('margin-top', '0px', 'important');
                        }
                        var belowEls = document.querySelectorAll(
                            '.watch-below-the-player, ytm-slim-video-metadata-section-renderer, .watch-main-col, ytm-single-column-watch-next-results-renderer'
                        );
                        for (var j = 0; j < belowEls.length; j++) {
                            var bel = belowEls[j];
                            if (!bel.__ytubeCollapsed) {
                                bel.__ytubeCollapsed = true;
                                bel.style.setProperty('padding-top', '0px', 'important');
                                bel.style.setProperty('margin-top', '0px', 'important');
                            }
                        }
                    } catch (e) {}
                };
                collapseWatchSpacing();

                var triggerUnmute = function() {
                    if (window.__ytubeAdSpeedActive) return;
                    try {
                        var videos = document.querySelectorAll('video');
                        for (var i = 0; i < videos.length; i++) {
                            var v = videos[i];
                            if (v.muted) {
                                v.muted = false;
                            }
                            if (v.volume < 1.0) {
                                v.volume = 1.0;
                            }
                        }
                    } catch (e) {}
                    try {
                        var player = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
                        if (player) {
                            if (typeof player.isMuted === 'function' && player.isMuted()) {
                                if (typeof player.unMute === 'function') player.unMute();
                            } else if (typeof player.unMute === 'function') {
                                player.unMute();
                            }
                            if (typeof player.setVolume === 'function') {
                                player.setVolume(100);
                            }
                        }
                    } catch (e) {}
                    try {
                        var unmuteBtns = document.querySelectorAll(
                            '.ytp-unmute, .ytp-unmute-inner, button.ytm-unmute-button, button[aria-label*="unmute" i], .ytp-volume-control button, button.ytp-mute-button[data-title-no-tooltip="Unmute"], .ytp-unmute.ytp-panel-header'
                        );
                        for (var b = 0; b < unmuteBtns.length; b++) {
                            var btn = unmuteBtns[b];
                            if (btn && typeof btn.click === 'function') {
                                btn.click();
                            }
                        }
                    } catch (e) {}
                };
                window.__ytubeUnmute = triggerUnmute;

                var monitorVideoAudio = function(videoNode) {
                    if (!videoNode || videoNode.__ytubeAudioMonitored) return;
                    videoNode.__ytubeAudioMonitored = true;
                    var handleAudio = function() {
                        if (!window.__ytubeAdSpeedActive) {
                            triggerUnmute();
                        }
                    };
                    ['play', 'playing', 'loadedmetadata', 'loadeddata', 'canplay', 'volumechange'].forEach(function(evt) {
                        videoNode.addEventListener(evt, handleAudio, true);
                    });
                    handleAudio();
                };

                // Signal Android once actual feed/watch UI nodes have mounted so skeletons dissolve cleanly
                var notifyContentReady = function() {
                    if (window.__ytubeRole === 'watch') {
                        if (!window.__ytubeWatchReadySignaled) {
                            collapseWatchSpacing();
                            var v0 = document.querySelector('video');
                            if (!v0 || v0.muted || v0.volume < 1.0) {
                                triggerUnmute();
                            }
                            var hasWatchDom = Boolean(v0 && (v0.readyState >= 1 || document.querySelector('ytm-slim-video-metadata-section-renderer, ytm-watch')));
                            if (hasWatchDom && window.YTubeBridge && typeof window.YTubeBridge.onWatchContentReady === 'function') {
                                window.__ytubeWatchReadySignaled = true;
                                window.YTubeBridge.onWatchContentReady();
                            }
                        }
                        return;
                    }

                    if (window.__ytubeFirstContentSignaled) return;
                    var hasContent = Boolean(
                        document.querySelector('ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-rich-grid-renderer, ytm-reel-video-renderer, ytm-watch, #player-container-id, video')
                    );
                    if (hasContent && window.YTubeBridge && typeof window.YTubeBridge.onFirstContentReady === 'function') {
                        window.__ytubeFirstContentSignaled = true;
                        window.YTubeBridge.onFirstContentReady();
                    }
                };
                window.__ytubeNotifyReady = notifyContentReady;
                notifyContentReady();

                // 3. Playback control hooks for MediaPlaybackService & Miniplayer
                window.__ytubePlay = function() {
                    var video = document.querySelector('video');
                    if (video) {
                        triggerUnmute();
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

                // 4. 500 ms SPA-safe timer for auto-skip, 16x ad fast-forward, automatic next play, throttled image prewarm & spacing collapse
                if (!window.__ytubeAdTimer) {
                    window.__ytubeAdSpeedActive = false;
                    window.__ytubeSavedMuted = null;
                    window.__ytubeLastReported = '';
                    window.__ytubeTickCount = 0;

                    window.__ytubeAdTimer = setInterval(function() {
                        try {
                            window.__ytubeTickCount = (window.__ytubeTickCount + 1) % 3;
                            notifyContentReady();
                            if (window.__ytubeTickCount === 0) {
                                collapseWatchSpacing();
                                if (window.__ytubeScanImages) {
                                    window.__ytubeScanImages();
                                }
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

                            var player = document.querySelector('#movie_player, .html5-video-player');
                            var adOverlay = document.querySelector('.ytp-ad-player-overlay, .ytp-ad-persistent-progress-bar-container');
                            var isAdShowing = Boolean(
                                (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) ||
                                adOverlay
                            );

                            var video = document.querySelector('video');
                            var isVisibleEl = function(el) {
                                if (!el) return false;
                                try {
                                    var st = window.getComputedStyle(el);
                                    if (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0') return false;
                                    var r = el.getBoundingClientRect();
                                    return r.width > 4 && r.height > 4;
                                } catch (e) {
                                    return false;
                                }
                            };

                            if (window.__ytubeRole === 'watch' && video && !isAdShowing) {
                                // Ensure single watch videos do not self-loop when reaching the end
                                if (video.loop) {
                                    video.loop = false;
                                }

                                var currentUrlParsed = parseYouTubeUrl(window.location.href);
                                var currentVidId = (window.__ytubeGetVideoId && currentUrlParsed)
                                    ? window.__ytubeGetVideoId(currentUrlParsed)
                                    : '';

                                if (currentVidId && currentVidId !== window.__ytubeActiveVideoId && !video.ended) {
                                    if (window.__ytubeActiveVideoId) {
                                        window.__ytubePrevVideoId = window.__ytubeActiveVideoId;
                                    }
                                    window.__ytubeActiveVideoId = currentVidId;
                                }

                                var nearEnd = isFinite(video.duration) && video.duration > 5 &&
                                    video.currentTime >= video.duration - 0.35 && video.paused;
                                var hasEnded = Boolean(video.ended || nearEnd);

                                var endscreenContainer = document.querySelector('ytm-autonav-endscreen-renderer, .ytp-autonav-endscreen-countdown-overlay');
                                var endscreenVisible = isVisibleEl(endscreenContainer);
                                var autonavBtn = endscreenVisible
                                    ? document.querySelector('.ytp-autonav-endscreen-next-button, ytm-autonav-endscreen-renderer a[href*="/watch"], ytm-autonav-endscreen-renderer button.ytp-autonav-endscreen-upnext')
                                    : null;

                                var nowMs = Date.now();
                                var canTriggerNext = Boolean(currentVidId) &&
                                    window.__ytubeAutoNextFiredForId !== currentVidId &&
                                    (nowMs - (window.__ytubeLastAutoNextAt || 0) > 6000);

                                if (canTriggerNext && (hasEnded || (autonavBtn && isVisibleEl(autonavBtn)))) {
                                    if (autonavBtn && typeof autonavBtn.click === 'function' && isVisibleEl(autonavBtn)) {
                                        window.__ytubeAutoNextFiredForId = currentVidId;
                                        window.__ytubeLastAutoNextAt = nowMs;
                                        autonavBtn.click();
                                    } else if (hasEnded) {
                                        var candidateLinks = document.querySelectorAll(
                                            'ytm-autonav-endscreen-renderer a[href*="/watch"], ytm-compact-video-renderer a[href*="/watch"], ytm-video-with-context-renderer a[href*="/watch"], .ytp-suggestion-set a[href*="/watch"]'
                                        );
                                        var chosenLink = null;
                                        for (var c = 0; c < candidateLinks.length; c++) {
                                            var candParsed = parseYouTubeUrl(candidateLinks[c].getAttribute('href'));
                                            var candId = (window.__ytubeGetVideoId && candParsed)
                                                ? window.__ytubeGetVideoId(candParsed)
                                                : '';
                                            if (candId && candId !== currentVidId && candId !== window.__ytubePrevVideoId) {
                                                chosenLink = candidateLinks[c];
                                                break;
                                            }
                                        }
                                        if (!chosenLink) {
                                            for (var c2 = 0; c2 < candidateLinks.length; c2++) {
                                                var candParsed2 = parseYouTubeUrl(candidateLinks[c2].getAttribute('href'));
                                                var candId2 = (window.__ytubeGetVideoId && candParsed2)
                                                    ? window.__ytubeGetVideoId(candParsed2)
                                                    : '';
                                                if (candId2 && candId2 !== currentVidId) {
                                                    chosenLink = candidateLinks[c2];
                                                    break;
                                                }
                                            }
                                        }
                                        if (chosenLink && typeof chosenLink.click === 'function') {
                                            window.__ytubeAutoNextFiredForId = currentVidId;
                                            window.__ytubeLastAutoNextAt = nowMs;
                                            chosenLink.click();
                                        }
                                    }
                                }
                            }

                            if (video) {
                                monitorVideoAudio(video);

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
                                    video.muted = false;
                                    video.volume = 1.0;
                                    window.__ytubeSavedMuted = null;
                                    triggerUnmute();
                                } else {
                                    // Regular video playing: ensure unmuted with full volume
                                    if (video.muted || video.volume < 1.0) {
                                        triggerUnmute();
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

    fun buildSpaSyncScript(
        backgroundPlayEnabled: Boolean,
        isDarkTheme: Boolean,
        isWatchRole: Boolean,
        isMinimized: Boolean,
    ): String {
        val bgLiteral = if (backgroundPlayEnabled) "true" else "false"
        val schemeLiteral = if (isDarkTheme) "dark" else "light"
        val roleLiteral = if (isWatchRole) "watch" else "feed"
        val minLiteral = if (isMinimized) "true" else "false"
        return "(function(){window.__ytubeBgPlay=$bgLiteral;window.__ytubeRole='$roleLiteral';" +
            "if(document.documentElement){document.documentElement.style.colorScheme='$schemeLiteral';" +
            "if('$roleLiteral'==='watch'){document.documentElement.classList.add('ytube-watch-role');}" +
            "else{document.documentElement.classList.remove('ytube-watch-role','ytube-minimized');}}" +
            "if(window.__ytubeSetMinimized&&'$roleLiteral'==='watch'){window.__ytubeSetMinimized($minLiteral);}" +
            "if(window.__ytubeNotifyReady){window.__ytubeNotifyReady();}" +
            "if(window.__ytubeScanImages){window.__ytubeScanImages();}})();"
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

    fun buildPlayScript(): String = """
        (function() {
            if (window.__ytubePlay) {
                window.__ytubePlay();
            } else {
                var v = document.querySelector('video');
                if (v) {
                    v.muted = false;
                    v.volume = 1.0;
                    var p = v.play();
                    if (p && p.catch) p.catch(function(){});
                }
            }
        })();
    """.trimIndent()

    fun buildUnmuteScript(): String = """
        (function() {
            if (window.__ytubeUnmute) {
                window.__ytubeUnmute();
            } else {
                try {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        videos[i].muted = false;
                        videos[i].volume = 1.0;
                    }
                    var player = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
                    if (player && typeof player.unMute === 'function') player.unMute();
                    if (player && typeof player.setVolume === 'function') player.setVolume(100);
                    var btns = document.querySelectorAll('.ytp-unmute, .ytp-unmute-inner, button.ytm-unmute-button, button[aria-label*="unmute" i]');
                    for (var j = 0; j < btns.length; j++) { btns[j].click(); }
                } catch(e) {}
            }
        })();
    """.trimIndent()

    fun buildPauseScript(): String = "if (window.__ytubePause) { window.__ytubePause(); }"

    fun buildStopScript(): String = "if (window.__ytubeStop) { window.__ytubeStop(); }"
}
