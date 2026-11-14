package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cache.ResourceCacheManager
import com.example.update.ApkDownloadResult
import com.example.update.GitHubUpdateManager
import com.example.update.MandatoryUpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class YTubePlayerMode {
    HIDDEN,
    EXPANDED,
    MINIMIZED,
}

data class YTubeUiState(
    val backgroundPlayEnabled: Boolean = true,
    val loadingProgress: Int = 0,
    val isInitialContentReady: Boolean = false,
    val canGoBack: Boolean = false,
    val currentUrl: String = YOUTUBE_HOME_URL,
    val pageTitle: String = "YTube",
    val isFullscreen: Boolean = false,
    val hasMainFrameError: Boolean = false,
    val isPlayingMedia: Boolean = false,
    val mediaTitle: String = "YTube Audio",
    val mediaChannel: String = "YouTube",
    val blockedCount: Int = 0,
    val showAuthorDialog: Boolean = false,
    val playerMode: YTubePlayerMode = YTubePlayerMode.HIDDEN,
    val activeVideoUrl: String? = null,
    val isWatchPlayerReady: Boolean = false,
    val isGoogleSignedIn: Boolean = false,
    val updateState: MandatoryUpdateState = MandatoryUpdateState.None,
) {
    companion object {
        const val YOUTUBE_HOME_URL = "https://m.youtube.com"
    }
}

class YTubeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val adBlocker: AdBlocker = AdBlocker.fromAssets(application)
    val resourceCache: ResourceCacheManager = ResourceCacheManager.getInstance(application)
    private val updateManager = GitHubUpdateManager()

    private val _uiState = MutableStateFlow(
        YTubeUiState(
            backgroundPlayEnabled = prefs.getBoolean(KEY_BACKGROUND_PLAY, true),
            showAuthorDialog = !prefs.getBoolean(KEY_AUTHOR_DIALOG_SHOWN, false),
        ),
    )
    val uiState: StateFlow<YTubeUiState> = _uiState.asStateFlow()

    private var lastCheckTimestampMs: Long = 0L

    init {
        adBlocker.blockedCount
            .onEach { count ->
                _uiState.update { it.copy(blockedCount = count) }
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            delay(PRELOAD_SKELETON_MAX_MS)
            markInitialContentReady()
        }

        checkForRemoteUpdate(force = true)
    }

    fun markInitialContentReady() {
        if (_uiState.value.isInitialContentReady) return
        checkGoogleSignInStatus()
        _uiState.update { it.copy(isInitialContentReady = true) }
    }

    fun checkGoogleSignInStatus() {
        try {
            val cookies = CookieManager.getInstance().getCookie("https://m.youtube.com").orEmpty()
            val signedIn = cookies.contains("LOGIN_INFO") || cookies.contains("SID=") || cookies.contains("__Secure-1PSID")
            _uiState.update { it.copy(isGoogleSignedIn = signedIn) }
        } catch (_: Exception) {}
    }

    fun openWatchVideo(url: String): Boolean {
        val current = _uiState.value
        val sameVideo = current.activeVideoUrl == url && current.playerMode != YTubePlayerMode.HIDDEN
        _uiState.update {
            it.copy(
                playerMode = YTubePlayerMode.EXPANDED,
                activeVideoUrl = url,
                isWatchPlayerReady = if (sameVideo) it.isWatchPlayerReady else false,
                mediaTitle = if (sameVideo) it.mediaTitle else "Loading video…",
                mediaChannel = if (sameVideo) it.mediaChannel else "YouTube",
            )
        }
        if (!sameVideo) {
            viewModelScope.launch {
                delay(WATCH_SKELETON_MAX_MS)
                if (_uiState.value.activeVideoUrl == url) {
                    markWatchPlayerReady()
                }
            }
        }
        return !sameVideo
    }

    fun markWatchPlayerReady() {
        if (_uiState.value.isWatchPlayerReady) return
        _uiState.update { it.copy(isWatchPlayerReady = true) }
    }

    fun minimizeWatchVideo() {
        if (_uiState.value.playerMode == YTubePlayerMode.EXPANDED) {
            _uiState.update { it.copy(playerMode = YTubePlayerMode.MINIMIZED) }
        }
    }

    fun expandWatchVideo() {
        if (_uiState.value.playerMode == YTubePlayerMode.MINIMIZED) {
            _uiState.update { it.copy(playerMode = YTubePlayerMode.EXPANDED) }
        }
    }

    fun closeWatchVideo() {
        _uiState.update {
            it.copy(
                playerMode = YTubePlayerMode.HIDDEN,
                activeVideoUrl = null,
                isWatchPlayerReady = false,
                isPlayingMedia = false,
                mediaTitle = "YTube Audio",
                mediaChannel = "YouTube",
            )
        }
    }

    fun dismissAuthorDialog() {
        prefs.edit().putBoolean(KEY_AUTHOR_DIALOG_SHOWN, true).apply()
        _uiState.update { it.copy(showAuthorDialog = false) }
    }

    fun checkForRemoteUpdate(force: Boolean = false) {
        val currentState = _uiState.value.updateState
        if (currentState is MandatoryUpdateState.Downloading ||
            currentState is MandatoryUpdateState.ReadyToInstall
        ) {
            return
        }

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

    fun startInAppUpdate(openInstallerIntent: (Intent) -> Unit) {
        val current = _uiState.value.updateState
        if (current is MandatoryUpdateState.Downloading) return

        val release = when (current) {
            is MandatoryUpdateState.Available -> current.release
            is MandatoryUpdateState.ReadyToInstall -> current.release
            is MandatoryUpdateState.Failed -> current.release
            else -> return
        }

        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                updateState = MandatoryUpdateState.Downloading(
                    release = release,
                    progressPercent = 0,
                    downloadedBytes = 0L,
                    totalBytes = release.apkSizeBytes,
                ),
            )
        }

        viewModelScope.launch {
            when (
                val result = updateManager.downloadApkInApp(
                    context = app,
                    release = release,
                    onProgress = { percent, downloaded, total ->
                        _uiState.update { state ->
                            if (state.updateState is MandatoryUpdateState.Downloading) {
                                state.copy(
                                    updateState = MandatoryUpdateState.Downloading(
                                        release = release,
                                        progressPercent = percent,
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                    ),
                                )
                            } else {
                                state
                            }
                        }
                    },
                )
            ) {
                is ApkDownloadResult.Success -> {
                    val canInstall = updateManager.canRequestPackageInstalls(app)
                    _uiState.update {
                        it.copy(
                            updateState = MandatoryUpdateState.ReadyToInstall(
                                release = release,
                                apkFilePath = result.apkFile.absolutePath,
                                needsInstallPermission = !canInstall,
                            ),
                        )
                    }
                    if (canInstall) {
                        launchInstallerSafely(
                            release = release,
                            apkFile = result.apkFile,
                            openInstallerIntent = openInstallerIntent,
                        )
                    }
                }

                is ApkDownloadResult.Error -> {
                    _uiState.update {
                        it.copy(
                            updateState = MandatoryUpdateState.Failed(
                                release = release,
                                message = result.message,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun installDownloadedApk(openInstallerIntent: (Intent) -> Unit) {
        val state = _uiState.value.updateState as? MandatoryUpdateState.ReadyToInstall ?: return
        val app = getApplication<Application>()
        val apkFile = File(state.apkFilePath)
        if (!apkFile.exists()) {
            startInAppUpdate(openInstallerIntent)
            return
        }

        val canInstall = updateManager.canRequestPackageInstalls(app)
        if (!canInstall) {
            _uiState.update {
                it.copy(updateState = state.copy(needsInstallPermission = true))
            }
            try {
                openInstallerIntent(updateManager.createInstallPermissionIntent(app))
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        updateState = MandatoryUpdateState.Failed(
                            release = state.release,
                            message = e.localizedMessage ?: "Unable to open install permission settings.",
                        ),
                    )
                }
            }
            return
        }

        _uiState.update {
            it.copy(updateState = state.copy(needsInstallPermission = false))
        }
        launchInstallerSafely(
            release = state.release,
            apkFile = apkFile,
            openInstallerIntent = openInstallerIntent,
        )
    }

    fun canRequestPackageInstallsNow(): Boolean {
        return updateManager.canRequestPackageInstalls(getApplication())
    }

    private fun launchInstallerSafely(
        release: com.example.update.GitHubReleaseInfo,
        apkFile: File,
        openInstallerIntent: (Intent) -> Unit,
    ) {
        try {
            openInstallerIntent(updateManager.createInstallApkIntent(getApplication(), apkFile))
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    updateState = MandatoryUpdateState.Failed(
                        release = release,
                        message = e.localizedMessage ?: "Unable to launch package installer.",
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
        private const val PREFS_NAME = "ytube_prefs"
        private const val KEY_BACKGROUND_PLAY = "background_play_enabled"
        private const val KEY_AUTHOR_DIALOG_SHOWN = "author_dialog_shown_v3"
        private const val UPDATE_CHECK_THROTTLE_MS = 10_000L
        private const val PRELOAD_SKELETON_MAX_MS = 2_400L
        private const val WATCH_SKELETON_MAX_MS = 1_600L
    }
}
