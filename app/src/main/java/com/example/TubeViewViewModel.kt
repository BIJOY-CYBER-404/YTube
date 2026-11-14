package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.update.GitHubUpdateManager
import com.example.update.MandatoryUpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TubeViewUiState(
    val backgroundPlayEnabled: Boolean = true,
    val loadingProgress: Int = 0,
    val canGoBack: Boolean = false,
    val currentUrl: String = YOUTUBE_HOME_URL,
    val pageTitle: String = "YouTube",
    val isFullscreen: Boolean = false,
    val hasMainFrameError: Boolean = false,
    val isPlayingMedia: Boolean = false,
    val mediaTitle: String = "YTube Audio",
    val mediaChannel: String = "YouTube",
    val blockedCount: Int = 0,
    val updateState: MandatoryUpdateState = MandatoryUpdateState.None,
) {
    companion object {
        const val YOUTUBE_HOME_URL = "https://m.youtube.com"
    }
}

class TubeViewViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val adBlocker: AdBlocker = AdBlocker.fromAssets(application)
    private val updateManager = GitHubUpdateManager()

    private val _uiState = MutableStateFlow(
        TubeViewUiState(
            backgroundPlayEnabled = prefs.getBoolean(KEY_BACKGROUND_PLAY, true),
        ),
    )
    val uiState: StateFlow<TubeViewUiState> = _uiState.asStateFlow()

    private var lastCheckTimestampMs: Long = 0L

    init {
        adBlocker.blockedCount
            .onEach { count ->
                _uiState.update { it.copy(blockedCount = count) }
            }
            .launchIn(viewModelScope)

        checkForRemoteUpdate(force = true)
    }

    fun checkForRemoteUpdate(force: Boolean = false) {
        val repoSlug = resolveRepoSlug() ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckTimestampMs < UPDATE_CHECK_THROTTLE_MS) return
        lastCheckTimestampMs = now

        viewModelScope.launch {
            val release = updateManager.fetchLatestRelease(
                repoSlug = repoSlug,
                currentVersionName = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE,
            )
            _uiState.update {
                it.copy(
                    updateState = if (release != null) {
                        MandatoryUpdateState.Available(release)
                    } else {
                        MandatoryUpdateState.None
                    },
                )
            }
        }
    }

    private fun resolveRepoSlug(): String? {
        val fromSecret = BuildConfig.GITHUB_RELEASES_REPO
        if (GitHubUpdateManager.isValidRepoSlug(fromSecret)) return fromSecret

        val fromResource = getApplication<Application>().getString(R.string.github_releases_repo)
        if (GitHubUpdateManager.isValidRepoSlug(fromResource)) return fromResource

        return null
    }

    fun launchBrowserUpdate(openIntent: (Intent) -> Unit) {
        val release = when (val current = _uiState.value.updateState) {
            is MandatoryUpdateState.Available -> current.release
            is MandatoryUpdateState.DownloadLaunched -> current.release
            is MandatoryUpdateState.Failed -> current.release
            MandatoryUpdateState.None -> return
        }

        try {
            openIntent(updateManager.createBrowserDownloadIntent(release))
            _uiState.update {
                it.copy(updateState = MandatoryUpdateState.DownloadLaunched(release))
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    updateState = MandatoryUpdateState.Failed(
                        release = release,
                        message = e.localizedMessage ?: "No browser found to download update.",
                    ),
                )
            }
        }
    }

    fun setBackgroundPlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_PLAY, enabled).apply()
        _uiState.update { it.copy(backgroundPlayEnabled = enabled) }
        if (!enabled) {
            MediaPlaybackService.stop(getApplication())
        }
    }

    fun updateLoadingProgress(progress: Int) {
        _uiState.update { it.copy(loadingProgress = progress.coerceIn(0, 100)) }
    }

    fun updatePageState(url: String?, title: String?, canGoBack: Boolean) {
        _uiState.update { state ->
            state.copy(
                currentUrl = url ?: state.currentUrl,
                pageTitle = title?.takeIf { it.isNotBlank() } ?: state.pageTitle,
                canGoBack = canGoBack,
            )
        }
    }

    fun setFullscreen(fullscreen: Boolean) {
        _uiState.update { it.copy(isFullscreen = fullscreen) }
    }

    fun setMainFrameError(hasError: Boolean) {
        _uiState.update { it.copy(hasMainFrameError = hasError) }
    }

    fun updateMediaState(isPlaying: Boolean, title: String, channel: String) {
        _uiState.update {
            it.copy(
                isPlayingMedia = isPlaying,
                mediaTitle = title,
                mediaChannel = channel,
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "tubeview_prefs"
        private const val KEY_BACKGROUND_PLAY = "background_play_enabled"
        private const val UPDATE_CHECK_THROTTLE_MS = 10_000L
    }
}
