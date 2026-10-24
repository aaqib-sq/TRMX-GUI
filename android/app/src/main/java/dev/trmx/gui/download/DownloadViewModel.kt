package dev.trmx.gui.download

/*
 * NETWORK USE BOUNDARY:
 * Unlike the App Killer package (which enforces a strict zero-network rule),
 * the Instagram Downloader uses network ONLY for URLs explicitly pasted or
 * tapped by the user. There is NO background fetching, NO tracking, NO telemetry,
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
import kotlinx.coroutines.flow.update
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
    private val fetchEngine = InstagramFetchEngine(http)
    private val downloadEngine = DownloadEngine(::client, historyStore, viewModelScope)

    // UI state
    private val _inputUrl = MutableStateFlow("")
    val inputUrl: StateFlow<String> = _inputUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _parsedLink = MutableStateFlow<ParsedInstagramLink?>(null)
    val parsedLink: StateFlow<ParsedInstagramLink?> = _parsedLink.asStateFlow()

    private val _profile = MutableStateFlow<InstagramProfile?>(null)
    val profile: StateFlow<InstagramProfile?> = _profile.asStateFlow()

    private val _singleItem = MutableStateFlow<InstagramItem?>(null)
    val singleItem: StateFlow<InstagramItem?> = _singleItem.asStateFlow()

    private val _items = MutableStateFlow<List<InstagramItem>>(emptyList())
    val items: StateFlow<List<InstagramItem>> = _items.asStateFlow()

    private val _selectedTab = MutableStateFlow(DownloadTab.POSTS)
    val selectedTab: StateFlow<DownloadTab> = _selectedTab.asStateFlow()

    // Upfront Mode & Quality Selection
    private val _mediaMode = MutableStateFlow(MediaMode.VIDEO)
    val mediaMode: StateFlow<MediaMode> = _mediaMode.asStateFlow()

    private val _videoQuality = MutableStateFlow(VideoQuality.P1080)
    val videoQuality: StateFlow<VideoQuality> = _videoQuality.asStateFlow()

    private val _audioQuality = MutableStateFlow(AudioQuality.KBPS320)
    val audioQuality: StateFlow<AudioQuality> = _audioQuality.asStateFlow()

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

    fun onUrlChange(newUrl: String) {
        _inputUrl.value = newUrl
        _error.value = null
    }

    fun onPaste(pasted: String) {
        _inputUrl.value = pasted
        _error.value = null
        fetchUrl()
    }

    fun clearUrl() {
        _inputUrl.value = ""
        _error.value = null
        _profile.value = null
        _singleItem.value = null
        _items.value = emptyList()
        _parsedLink.value = null
    }

    fun setTab(tab: DownloadTab) {
        _selectedTab.value = tab
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

    fun fetchUrl() {
        val query = _inputUrl.value.trim()
        if (query.isBlank()) {
            _error.value = "Please enter an Instagram link or handle"
            return
        }

        val parsed = InstagramUrlParser.parse(query)
        _parsedLink.value = parsed

        if (parsed.type == InstagramUrlType.UNKNOWN) {
            _error.value = parsed.error ?: "Unsupported Instagram URL format"
            return
        }

        _isLoading.value = true
        _error.value = null
        _profile.value = null
        _singleItem.value = null
        _items.value = emptyList()

        viewModelScope.launch {
            try {
                when (parsed.type) {
                    InstagramUrlType.PROFILE -> {
                        val result = fetchEngine.fetchProfile(parsed.username ?: query)
                        _profile.value = result.profile
                        _items.value = result.items
                        if (result.blockedNotice != null) {
                            _notice.value = result.blockedNotice
                        }
                    }
                    else -> {
                        // Single item (Post, Reel, Story, Highlight)
                        val item = fetchEngine.fetchSingleItem(parsed)
                        _singleItem.value = item
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to inspect Instagram link"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun downloadItem(item: InstagramItem, option: DownloadOption = DownloadOption.MEDIA) {
        viewModelScope.launch {
            try {
                val record = downloadEngine.startDownload(
                    item = item,
                    mode = _mediaMode.value,
                    videoQuality = _videoQuality.value,
                    audioQuality = _audioQuality.value,
                    option = option
                )
                _notice.value = "Download started: ${record.title}"
                refreshHistory()
            } catch (e: Exception) {
                _error.value = "Failed to start download: ${e.message}"
            }
        }
    }

    fun downloadCarouselAll(item: InstagramItem) {
        downloadItem(item, DownloadOption.CAROUSEL_ALL)
    }

    fun downloadCover(item: InstagramItem) {
        downloadItem(item, DownloadOption.COVER_IMAGE)
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
