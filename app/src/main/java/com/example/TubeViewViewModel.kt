package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

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
    private val updateManager = GitHubUpdateManager(application)
    private var downloadedApkFile: File? = null

    private val _uiState = MutableStateFlow(
        TubeViewUiState(
            backgroundPlayEnabled = prefs.getBoolean(KEY_BACKGROUND_PLAY, true),
        ),
    )
    val uiState: StateFlow<TubeViewUiState> = _uiState.asStateFlow()

    init {
        adBlocker.blockedCount
            .onEach { count ->
                _uiState.update { it.copy(blockedCount = count) }
            }
            .launchIn(viewModelScope)

        checkForRemoteUpdate()
    }

    fun checkForRemoteUpdate() {
        val repoSlug = BuildConfig.GITHUB_RELEASES_REPO
        if (!GitHubUpdateManager.isValidRepoSlug(repoSlug)) return

        viewModelScope.launch {
            val release = updateManager.fetchLatestRelease(
                repoSlug = repoSlug,
                currentVersionName = BuildConfig.VERSION_NAME,
            )
            if (release != null) {
                _uiState.update {
                    it.copy(updateState = MandatoryUpdateState.Available(release))
                }
            }
        }
    }

    fun startMandatoryUpdateDownload(onReadyToInstall: (Intent) -> Unit) {
        val release = when (val current = _uiState.value.updateState) {
            is MandatoryUpdateState.Available -> current.release
            is MandatoryUpdateState.Failed -> current.release
            else -> return
        }

        _uiState.update {
            it.copy(updateState = MandatoryUpdateState.Downloading(release, progressPercent = 0))
        }

        viewModelScope.launch {
            try {
                val apkFile = updateManager.downloadApk(release) { percent ->
                    _uiState.update {
                        it.copy(updateState = MandatoryUpdateState.Downloading(release, percent))
                    }
                }
                downloadedApkFile = apkFile

                if (updateManager.canInstallPackages()) {
                    _uiState.update {
                        it.copy(updateState = MandatoryUpdateState.ReadyToInstall(release))
                    }
                    onReadyToInstall(updateManager.createInstallApkIntent(apkFile))
                } else {
                    _uiState.update {
                        it.copy(updateState = MandatoryUpdateState.NeedsInstallPermission(release))
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        updateState = MandatoryUpdateState.Failed(
                            release = release,
                            message = e.localizedMessage ?: "Download failed.",
                        ),
                    )
                }
            }
        }
    }

    fun createUnknownSourcesIntent(): Intent = updateManager.createUnknownSourcesSettingsIntent()

    fun onReturnedFromInstallPermission(onReadyToInstall: (Intent) -> Unit) {
        val current = _uiState.value.updateState
        val release = when (current) {
            is MandatoryUpdateState.NeedsInstallPermission -> current.release
            is MandatoryUpdateState.ReadyToInstall -> current.release
            else -> return
        }
        val apkFile = downloadedApkFile ?: return
        if (updateManager.canInstallPackages()) {
            _uiState.update {
                it.copy(updateState = MandatoryUpdateState.ReadyToInstall(release))
            }
            onReadyToInstall(updateManager.createInstallApkIntent(apkFile))
        }
    }

    fun installDownloadedUpdate(onReadyToInstall: (Intent) -> Unit) {
        val apkFile = downloadedApkFile ?: return
        if (updateManager.canInstallPackages()) {
            onReadyToInstall(updateManager.createInstallApkIntent(apkFile))
        } else {
            val release = (_uiState.value.updateState as? MandatoryUpdateState.ReadyToInstall)?.release ?: return
            _uiState.update {
                it.copy(updateState = MandatoryUpdateState.NeedsInstallPermission(release))
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
    }
}
