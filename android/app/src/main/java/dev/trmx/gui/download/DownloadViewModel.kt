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
import dev.trmx.gui.model.SubmitRequest
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import dev.trmx.gui.net.SseClient
import dev.trmx.gui.store.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val store = TokenStore(application)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun client() = BridgeClient(store.bridgeBaseUrl, store.token, http)
    private fun sseClient() = SseClient(store.bridgeBaseUrl, store.token, http)

    private val historyStore = DownloadHistoryStore(application.filesDir)
    private val fetchEngine = InstagramFetchEngine(
        http = http,
        ytdlpRunner = { username -> runYtdlpProfileListing(username) }
    )
    private val downloadEngine = DownloadEngine(::client, historyStore, viewModelScope)

    // UI state for Instagram Tools
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

    private val _postsLoadError = MutableStateFlow<String?>(null)
    val postsLoadError: StateFlow<String?> = _postsLoadError.asStateFlow()

    // Overlay Preview State: playable video player overlay or full photo preview overlay
    private val _activePreviewItem = MutableStateFlow<InstagramItem?>(null)
    val activePreviewItem: StateFlow<InstagramItem?> = _activePreviewItem.asStateFlow()

    // Downloader Stage & Upfront Choices (SEPARATE from Instagram section)
    private val _stagedItem = MutableStateFlow<InstagramItem?>(null)
    val stagedItem: StateFlow<InstagramItem?> = _stagedItem.asStateFlow()

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

    fun onUrlChange(newUrl: String) {
        _inputUrl.value = newUrl
        _error.value = null
    }

    fun onDirectDownloadUrlChange(newUrl: String) {
        _directDownloadUrl.value = newUrl
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
        _postsLoadError.value = null
    }

    // Box-only clear for screen-dispose: the fetched result stays, only the link box empties.
    fun clearInputOnly() {
        _inputUrl.value = ""
    }

    fun openPreview(item: InstagramItem) {
        val decision = InstagramPreviewGate.evaluate(item)
        if (decision.notice != null) {
            _notice.value = decision.notice
        }
        if (decision.canOpen) {
            _activePreviewItem.value = item
        }
    }

    fun closePreview() {
        _activePreviewItem.value = null
    }

    fun stageItemForDownload(item: InstagramItem) {
        _stagedItem.value = item
        _directDownloadUrl.value = ""
        _error.value = null
    }

    fun clearStagedItem() {
        _stagedItem.value = null
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

    fun fetchUrl(tool: InstagramTool? = null) {
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
        _postsLoadError.value = null

        viewModelScope.launch {
            try {
                when {
                    parsed.type == InstagramUrlType.PROFILE -> {
                        val result = fetchEngine.fetchProfile(parsed.username ?: query)
                        _profile.value = result.profile
                        _items.value = result.items
                        _postsLoadError.value = result.postsLoadError
                        if (result.blockedNotice != null) {
                            _notice.value = result.blockedNotice
                        }
                    }
                    else -> {
                        // Single item (Post, Reel, Story, Highlight, IGTV)
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

    companion object {
        fun resolveDownloadOption(item: InstagramItem): DownloadOption = when {
            item.type == InstagramItemType.PHOTO && !item.directMediaUrl.isNullOrBlank() -> DownloadOption.PHOTO
            item.type == InstagramItemType.CAROUSEL -> DownloadOption.CAROUSEL_ALL
            else -> DownloadOption.MEDIA
        }
    }

    fun resolveDownloadOption(item: InstagramItem): DownloadOption =
        Companion.resolveDownloadOption(item)

    fun startDownload(
        item: InstagramItem,
        option: DownloadOption? = null,
        onStarted: (() -> Unit)? = null
    ) {
        val effectiveOption = option ?: resolveDownloadOption(item)

        if (item.type == InstagramItemType.HIGHLIGHT && !item.isDownloadable) {
            _error.value = "Cannot download highlight: media could not be resolved without login."
            return
        }
        if (effectiveOption == DownloadOption.PHOTO && item.directMediaUrl.isNullOrBlank()) {
            _error.value = "Cannot download photo: direct media URL is not resolved."
            return
        }
        if (effectiveOption == DownloadOption.CAROUSEL_ALL) {
            val total = item.carouselItems.size
            val resolved = item.carouselItems.mapNotNull { it.directMediaUrl?.trim()?.takeIf { u -> u.isNotBlank() } }
            val skipped = total - resolved.size
            if (resolved.isEmpty()) {
                _error.value = "Cannot download carousel: no child slide has a direct media URL."
                return
            }
            if (skipped > 0) {
                _notice.value = "Downloading ${resolved.size} slides ($skipped skipped without direct URL)."
            }
        }

        viewModelScope.launch {
            try {
                val record = downloadEngine.startDownload(
                    item = item,
                    mode = _mediaMode.value,
                    videoQuality = _videoQuality.value,
                    audioQuality = _audioQuality.value,
                    saveCover = _saveCoverAlongsideVideo.value,
                    option = effectiveOption
                )
                if (effectiveOption != DownloadOption.CAROUSEL_ALL || item.carouselItems.size == item.carouselItems.count { !it.directMediaUrl.isNullOrBlank() }) {
                    _notice.value = "Download started: ${record.title}"
                }
                refreshHistory()
                onStarted?.invoke()
            } catch (e: Exception) {
                _error.value = "Failed to start download: ${e.message}"
            }
        }
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

        startDownload(syntheticItem, DownloadOption.MEDIA) {
            _directDownloadUrl.value = ""
            onStarted?.invoke()
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

    /**
     * Route 3 fallback: flat-playlist listing via the existing bridge.
     * Uses strict job name "profile:<shortcode>" capped at 200 characters.
     */
    private suspend fun runYtdlpProfileListing(username: String): List<InstagramItem> = withContext(Dispatchers.IO) {
        val client = client()
        val jobName = DownloadEngine.buildJobName("profile", username)
        val profileUrl = "https://www.instagram.com/$username/"
        val argv = listOf(
            "yt-dlp",
            "--newline",
            "--flat-playlist",
            "-j",
            profileUrl
        )

        val submitResult = client.submitJob(
            SubmitRequest(
                name = jobName,
                type = "argv",
                argv = argv,
                cwd = DownloadEngine.DOWNLOAD_DIR,
                confirm = true
            )
        )

        val jobId = when (submitResult) {
            is BridgeResult.Success -> submitResult.data.response.job_id
            else -> return@withContext emptyList()
        }

        val items = mutableListOf<InstagramItem>()
        val outputLines = mutableListOf<String>()

        try {
            sseClient()
                .stream("/v1/jobs/$jobId/output?from_seq=1&follow=1&stream=stdout")
                .collect { frame ->
                    if (frame.event == "stdout" && frame.data.isNotBlank()) {
                        val text = runCatching {
                            json.parseToJsonElement(frame.data).jsonObject["text"]?.jsonPrimitive?.content
                        }.getOrNull() ?: frame.data
                        outputLines.add(text)
                    }
                }
        } catch (_: Exception) {
            // Ignore stream read errors and parse what was collected
        }

        for (line in outputLines) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) continue
            runCatching {
                val obj = json.parseToJsonElement(trimmed).jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@runCatching
                val title = obj["title"]?.jsonPrimitive?.content ?: "Post: $id"
                val url = obj["url"]?.jsonPrimitive?.content ?: "https://www.instagram.com/p/$id/"
                val thumbnail = obj["thumbnail"]?.jsonPrimitive?.content
                val duration = obj["duration"]?.jsonPrimitive?.longOrNull
                val isVideo = (duration != null && duration > 0) || url.contains("/reel/")
                val itemType = if (url.contains("/reel/")) InstagramItemType.REEL else if (isVideo) InstagramItemType.VIDEO else InstagramItemType.POST

                items.add(
                    InstagramItem(
                        id = id,
                        shortcode = id,
                        type = itemType,
                        title = HtmlDecoder.decode(title),
                        caption = HtmlDecoder.decode(title),
                        url = url,
                        previewUrl = thumbnail,
                        directMediaUrl = if (isVideo) null else thumbnail,
                        durationSeconds = duration,
                        isVideo = isVideo,
                        isAvailable = true
                    )
                )
            }
        }

        items
    }
}
