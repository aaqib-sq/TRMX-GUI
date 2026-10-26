package dev.trmx.gui.download

/*
 * NETWORK USE BOUNDARY:
 * Unlike the App Killer package (which enforces a strict zero-network rule),
 * the Downloader uses network ONLY for URLs explicitly entered or
 * initiated by the user. There is NO background fetching, NO tracking, NO telemetry,
 * and NO unprompted network calls. All history stays strictly on-device.
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.store.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val store = TokenStore(application)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun client() = BridgeClient(store.bridgeBaseUrl, store.token, http)

    private val historyStore = DownloadHistoryStore(application.filesDir)
    private val downloadEngine = DownloadEngine(::client, historyStore, viewModelScope)

    // Direct Downloader URL box
    private val _directDownloadUrl = MutableStateFlow("")
    val directDownloadUrl: StateFlow<String> = _directDownloadUrl.asStateFlow()

    private val _mediaMode = MutableStateFlow(MediaMode.VIDEO)
    val mediaMode: StateFlow<MediaMode> = _mediaMode.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQuality.ORIGINAL)
    val videoQuality: StateFlow<VideoQuality> = _videoQuality.asStateFlow()

    private val _audioQuality = MutableStateFlow(AudioQuality.KBPS320)
    val audioQuality: StateFlow<AudioQuality> = _audioQuality.asStateFlow()

    private val _saveCoverAlongsideVideo = MutableStateFlow(true)
    val saveCoverAlongsideVideo: StateFlow<Boolean> = _saveCoverAlongsideVideo.asStateFlow()

    // History and progress
    private val _history = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val history: StateFlow<List<DownloadRecord>> = _history.asStateFlow()

    val activeDownloads = downloadEngine.activeDownloads

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        refreshHistory()
    }

    fun onDirectDownloadUrlChange(newUrl: String) {
        _directDownloadUrl.value = newUrl
        _error.value = null
    }

    fun setMediaMode(mode: MediaMode) {
        _mediaMode.value = mode
    }

    fun setVideoQuality(quality: VideoQuality) {
        _videoQuality.value = quality
    }

    fun setAudioQuality(quality: AudioQuality) {
        _audioQuality.value = quality
    }

    fun setSaveCoverAlongsideVideo(save: Boolean) {
        _saveCoverAlongsideVideo.value = save
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun refreshHistory() {
        _history.value = historyStore.list()
    }

    fun clearHistory() {
        historyStore.clear()
        _history.value = emptyList()
    }

    fun startDirectDownload(onStarted: (() -> Unit)? = null) {
        val rawUrl = _directDownloadUrl.value.trim()
        if (rawUrl.isBlank()) {
            _error.value = "Please enter a URL to download"
            return
        }

        val syntheticItem = InstagramItem(
            id = "dl_${System.currentTimeMillis()}",
            shortcode = rawUrl.substringAfterLast("/").substringBefore("?").ifBlank { "media" },
            type = if (_mediaMode.value == MediaMode.MP3) InstagramItemType.POST else InstagramItemType.VIDEO,
            title = "Download: ${rawUrl.substringAfterLast("/").substringBefore("?")}",
            url = rawUrl,
            isVideo = (_mediaMode.value == MediaMode.VIDEO)
        )

        viewModelScope.launch {
            try {
                val record = downloadEngine.startDownload(
                    item = syntheticItem,
                    mode = _mediaMode.value,
                    videoQuality = _videoQuality.value,
                    audioQuality = _audioQuality.value,
                    saveCover = _saveCoverAlongsideVideo.value,
                    option = DownloadOption.MEDIA
                )
                _notice.value = "Download started: ${record.title}"
                refreshHistory()
                _directDownloadUrl.value = ""
                onStarted?.invoke()
            } catch (e: Exception) {
                _error.value = "Failed to start download: ${e.message}"
            }
        }
    }

    fun retryDownload(recordId: String) {
        viewModelScope.launch {
            try {
                downloadEngine.retryDownload(recordId)
                _notice.value = "Download retrying…"
                refreshHistory()
            } catch (e: Exception) {
                _error.value = "Failed to retry download: ${e.message}"
            }
        }
    }
}
