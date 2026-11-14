package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference

/**
 * Foreground service (type: mediaPlayback) keeping WebView audio alive when the app
 * is minimized or the screen is off, wired to a system [MediaSession] and MediaStyle
 * notification controls.
 */
class MediaPlaybackService : Service() {

    /**
     * Bridge holding a [WeakReference] to the active [BackgroundAudioWebView] so
     * MediaSession callbacks and notification buttons control the web video player
     * without leaking the Activity.
     */
    object PlayerBridge {
        @Volatile
        private var webViewRef: WeakReference<BackgroundAudioWebView>? = null

        @Volatile
        var currentTitle: String = "YTube Audio"

        @Volatile
        var currentChannel: String = "YouTube"

        @Volatile
        var isPlaying: Boolean = false

        fun attach(webView: BackgroundAudioWebView) {
            webViewRef = WeakReference(webView)
        }

        fun detach(webView: BackgroundAudioWebView) {
            if (webViewRef?.get() === webView) {
                webViewRef = null
            }
        }

        fun play() {
            val view = webViewRef?.get() ?: return
            view.post {
                view.evaluateJavascript(JsInjector.buildPlayScript(), null)
            }
            isPlaying = true
        }

        fun pause() {
            val view = webViewRef?.get() ?: return
            view.post {
                view.evaluateJavascript(JsInjector.buildPauseScript(), null)
            }
            isPlaying = false
        }
    }

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false
    private var noisyReceiverRegistered: Boolean = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                PlayerBridge.pause()
                updateMediaSessionState(isPlaying = false)
                refreshNotification()
                releaseWakeLock()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Keep manual control predictable rather than auto-blasting unexpectedly
                updateMediaSessionState(isPlaying = PlayerBridge.isPlaying)
            }
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                PlayerBridge.pause()
                updateMediaSessionState(isPlaying = false)
                refreshNotification()
                releaseWakeLock()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        initMediaSession()
        registerNoisyReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                requestAudioFocus()
                PlayerBridge.play()
                acquireWakeLock()
                updateMediaSessionState(isPlaying = true)
                startForegroundCompat(buildNotification())
            }
            ACTION_PAUSE -> {
                PlayerBridge.pause()
                releaseWakeLock()
                updateMediaSessionState(isPlaying = false)
                startForegroundCompat(buildNotification())
            }
            ACTION_STOP -> {
                PlayerBridge.pause()
                stopSelfCleanly()
            }
            ACTION_UPDATE_STATE -> {
                val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, PlayerBridge.isPlaying)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: PlayerBridge.currentTitle
                val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: PlayerBridge.currentChannel
                PlayerBridge.isPlaying = playing
                PlayerBridge.currentTitle = title
                PlayerBridge.currentChannel = channel

                if (playing) {
                    requestAudioFocus()
                    acquireWakeLock()
                } else {
                    releaseWakeLock()
                }
                updateMediaSessionState(isPlaying = playing)
                startForegroundCompat(buildNotification())
            }
            else -> {
                if (PlayerBridge.isPlaying) {
                    requestAudioFocus()
                    acquireWakeLock()
                }
                updateMediaSessionState(isPlaying = PlayerBridge.isPlaying)
                startForegroundCompat(buildNotification())
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (noisyReceiverRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver)
            } catch (_: IllegalArgumentException) {
            }
            noisyReceiverRegistered = false
        }
        abandonAudioFocus()
        releaseWakeLock()
        mediaSession?.apply {
            isActive = false
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initMediaSession() {
        mediaSession = MediaSession(this, "YTubeMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    requestAudioFocus()
                    PlayerBridge.play()
                    acquireWakeLock()
                    updateMediaSessionState(isPlaying = true)
                    startForegroundCompat(buildNotification())
                }

                override fun onPause() {
                    PlayerBridge.pause()
                    releaseWakeLock()
                    updateMediaSessionState(isPlaying = false)
                    startForegroundCompat(buildNotification())
                }

                override fun onStop() {
                    PlayerBridge.pause()
                    stopSelfCleanly()
                }
            })
            isActive = true
        }
        updateMediaSessionState(isPlaying = PlayerBridge.isPlaying)
    }

    private fun updateMediaSessionState(isPlaying: Boolean) {
        val session = mediaSession ?: return
        val stateCode = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP

        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(stateCode, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (isPlaying) 1f else 0f)
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, PlayerBridge.currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, PlayerBridge.currentChannel)
                .build(),
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleAction = if (PlayerBridge.isPlaying) {
            val pausePending = servicePendingIntent(ACTION_PAUSE, 1)
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                getString(R.string.action_pause),
                pausePending,
            ).build()
        } else {
            val playPending = servicePendingIntent(ACTION_PLAY, 2)
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_play),
                getString(R.string.action_play),
                playPending,
            ).build()
        }

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.action_stop),
            servicePendingIntent(ACTION_STOP, 3),
        ).build()

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val subtext = if (PlayerBridge.isPlaying) {
            getString(R.string.notification_playing_subtext)
        } else {
            getString(R.string.notification_paused_subtext)
        }

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1)

        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(PlayerBridge.currentTitle.ifBlank { getString(R.string.notification_default_title) })
            .setContentText(PlayerBridge.currentChannel.ifBlank { subtext })
            .setSubText(subtext)
            .setContentIntent(contentPendingIntent)
            .setOngoing(PlayerBridge.isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(toggleAction)
            .addAction(stopAction)
            .setStyle(mediaStyle)
            .build()
    }

    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            ContextCompat.registerReceiver(
                this,
                becomingNoisyReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyReceiverRegistered = true
        }
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val manager = audioManager ?: return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = req
            manager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTube::MediaPlaybackWakeLock")
        lock.setReferenceCounted(false)
        lock.acquire(6 * 60 * 60 * 1000L) // 6-hour safety cap
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: RuntimeException) {
        }
    }

    private fun stopSelfCleanly() {
        abandonAudioFocus()
        releaseWakeLock()
        updateMediaSessionState(isPlaying = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "ytube_playback_channel"
        const val NOTIFICATION_ID = 1042

        const val ACTION_START = "com.ytube.bk.action.START"
        const val ACTION_PLAY = "com.ytube.bk.action.PLAY"
        const val ACTION_PAUSE = "com.ytube.bk.action.PAUSE"
        const val ACTION_STOP = "com.ytube.bk.action.STOP"
        const val ACTION_UPDATE_STATE = "com.ytube.bk.action.UPDATE_STATE"

        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CHANNEL = "extra_channel"

        fun syncPlaybackState(
            context: Context,
            isPlaying: Boolean,
            title: String,
            channel: String,
        ) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_UPDATE_STATE
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CHANNEL, channel)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: IllegalStateException) {
                // App was not eligible to start a foreground service from background.
            } catch (_: SecurityException) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaPlaybackService::class.java))
        }
    }
}
