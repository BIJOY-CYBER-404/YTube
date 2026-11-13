package com.example

/**
 * Generates idempotent JavaScript payloads injected into YouTube WebView pages.
 *
 * Responsibilities:
 * 1. Override Page Visibility API when background play is enabled so YouTube's player
 *    does not pause when the app is minimized or the screen turns off.
 * 2. Inject cosmetic CSS hiding leftover ad containers and promoted cards.
 * 3. Run a 500 ms SPA-safe timer (YouTube navigates client-side without full page reloads)
 *    that auto-clicks skip buttons, fast-forwards in-stream ads at 16x, and reports
 *    current video playback state/title back to Android for MediaSession sync.
 * 4. Expose window.__tubeviewPlay / __tubeviewPause / __tubeviewStop for MediaSession.
 */
object JsInjector {

    fun buildProtectionScript(backgroundPlayEnabled: Boolean): String {
        val bgLiteral = if (backgroundPlayEnabled) "true" else "false"
        return """
            (function() {
                window.__tubeviewBgPlay = $bgLiteral;

                // 1. Page Visibility API override (gated by window.__tubeviewBgPlay)
                if (!window.__tubeviewVisibilityPatched) {
                    window.__tubeviewVisibilityPatched = true;
                    try {
                        var origVisDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'visibilityState');
                        var origHidDesc = Object.getOwnPropertyDescriptor(Document.prototype, 'hidden');

                        Object.defineProperty(document, 'visibilityState', {
                            configurable: true,
                            get: function() {
                                if (window.__tubeviewBgPlay) return 'visible';
                                return origVisDesc && origVisDesc.get ? origVisDesc.get.call(document) : 'visible';
                            }
                        });
                        Object.defineProperty(document, 'hidden', {
                            configurable: true,
                            get: function() {
                                if (window.__tubeviewBgPlay) return false;
                                return origHidDesc && origHidDesc.get ? origHidDesc.get.call(document) : false;
                            }
                        });
                        Object.defineProperty(document, 'webkitVisibilityState', {
                            configurable: true,
                            get: function() { return window.__tubeviewBgPlay ? 'visible' : document.visibilityState; }
                        });
                        Object.defineProperty(document, 'webkitHidden', {
                            configurable: true,
                            get: function() { return window.__tubeviewBgPlay ? false : document.hidden; }
                        });
                    } catch (e) {}

                    var blockEventIfBg = function(e) {
                        if (window.__tubeviewBgPlay) {
                            e.stopImmediatePropagation();
                        }
                    };
                    document.addEventListener('visibilitychange', blockEventIfBg, true);
                    document.addEventListener('webkitvisibilitychange', blockEventIfBg, true);
                    window.addEventListener('pagehide', blockEventIfBg, true);
                }

                // 2. Cosmetic ad-container hiding CSS
                if (!document.getElementById('tubeview-adblock-css')) {
                    var style = document.createElement('style');
                    style.id = 'tubeview-adblock-css';
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
                        '#masthead-ad'
                    ].join(', ') + ' { display: none !important; visibility: hidden !important; height: 0 !important; }';
                    (document.head || document.documentElement).appendChild(style);
                }

                // 3. Playback control hooks for MediaPlaybackService MediaSession
                window.__tubeviewPlay = function() {
                    var video = document.querySelector('video');
                    if (video) {
                        var p = video.play();
                        if (p && p.catch) p.catch(function(){});
                        return true;
                    }
                    return false;
                };

                window.__tubeviewPause = function() {
                    var video = document.querySelector('video');
                    if (video && !video.paused) {
                        video.pause();
                        return true;
                    }
                    return false;
                };

                window.__tubeviewStop = function() {
                    var video = document.querySelector('video');
                    if (video) {
                        video.pause();
                    }
                };

                // 4. 500 ms SPA-safe timer for auto-skip, 16x ad fast-forward, & MediaSession sync
                if (!window.__tubeviewAdTimer) {
                    window.__tubeviewAdSpeedActive = false;
                    window.__tubeviewSavedMuted = null;
                    window.__tubeviewLastReported = '';

                    window.__tubeviewAdTimer = setInterval(function() {
                        try {
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
                                    if (!window.__tubeviewAdSpeedActive) {
                                        window.__tubeviewSavedMuted = video.muted;
                                        window.__tubeviewAdSpeedActive = true;
                                    }
                                    video.muted = true;
                                    if (video.playbackRate !== 16) {
                                        video.playbackRate = 16;
                                    }
                                    if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.5) {
                                        video.currentTime = video.duration - 0.2;
                                    }
                                } else if (window.__tubeviewAdSpeedActive) {
                                    window.__tubeviewAdSpeedActive = false;
                                    if (video.playbackRate === 16) {
                                        video.playbackRate = 1;
                                    }
                                    if (window.__tubeviewSavedMuted !== null) {
                                        video.muted = window.__tubeviewSavedMuted;
                                        window.__tubeviewSavedMuted = null;
                                    }
                                }

                                // Sync media state with Android MediaPlaybackService
                                if (window.TubeViewBridge && typeof window.TubeViewBridge.onPlaybackStateChanged === 'function') {
                                    var isPlaying = !video.paused && !video.ended && video.readyState > 2;
                                    var titleEl = document.querySelector('h2.slim-video-information-title, .slim-video-metadata-header h2, h1.ytd-watch-metadata, #title h1');
                                    var rawTitle = (titleEl && titleEl.textContent) ? titleEl.textContent.trim() : document.title.replace(/\s*-\s*YouTube\s*$/i, '').trim();
                                    var channelEl = document.querySelector('.slim-owner-icon-and-title .yt-core-attributed-string, ytm-slim-owner-renderer .slim-owner-channel-name, #owner #channel-name');
                                    var rawChannel = (channelEl && channelEl.textContent) ? channelEl.textContent.trim() : 'YouTube';
                                    var key = isPlaying + '|' + rawTitle + '|' + rawChannel;
                                    if (key !== window.__tubeviewLastReported) {
                                        window.__tubeviewLastReported = key;
                                        window.TubeViewBridge.onPlaybackStateChanged(isPlaying, rawTitle || 'TubeView Audio', rawChannel || 'YouTube');
                                    }
                                }
                            }
                        } catch (err) {}
                    }, 500);
                }
            })();
        """.trimIndent()
    }

    fun buildBackgroundPlayToggleScript(enabled: Boolean): String {
        val bgLiteral = if (enabled) "true" else "false"
        return "window.__tubeviewBgPlay = $bgLiteral;"
    }

    fun buildPlayScript(): String = "if (window.__tubeviewPlay) { window.__tubeviewPlay(); }"

    fun buildPauseScript(): String = "if (window.__tubeviewPause) { window.__tubeviewPause(); }"
}
