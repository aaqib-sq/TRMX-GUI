package dev.trmx.gui.download

/*
 * NETWORK USE BOUNDARY:
 * Unlike the App Killer package (which enforces a strict zero-network rule),
 * the Instagram Downloader uses network ONLY for URLs explicitly pasted or
 * tapped by the user. There is NO background fetching, NO tracking, NO telemetry,
 * and NO unprompted network calls. All history stays strictly on-device.
 */

import dev.trmx.gui.model.SubmitRequest
import dev.trmx.gui.model.ToolSubmitRequest
import dev.trmx.gui.net.BridgeClient
import dev.trmx.gui.net.BridgeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Downloads Instagram media by coordinating with the Termux bridge and yt-dlp.
 * Employs the app's established download directory convention (`~/downloads`),
 * tracks download progress, and persists all history locally on-device.
 */
class DownloadEngine(
    private val clientProvider: () -> BridgeClient,
    private val historyStore: DownloadHistoryStore,
    private val scope: CoroutineScope
) {

    companion object {
        const val DOWNLOAD_DIR = "~/downloads"

        fun buildYtdlpArgv(
            url: String,
            mode: MediaMode,
            videoQuality: VideoQuality,
            audioQuality: AudioQuality,
            option: DownloadOption
        ): List<String> = when (option) {
            DownloadOption.COVER_IMAGE -> listOf(
                "yt-dlp",
                "--newline",
                "--skip-download",
                "--write-thumbnail",
                "--convert-thumbnails", "jpg",
                "-P", DOWNLOAD_DIR,
                url
            )
            DownloadOption.CAROUSEL_ALL -> listOf(
                "yt-dlp",
                "--newline",
                "--yes-playlist",
                "-f", "bestvideo+bestaudio/best",
                "--merge-output-format", "mp4",
                "-P", DOWNLOAD_DIR,
                url
            )
            DownloadOption.AUDIO_ONLY -> listOf(
                "yt-dlp",
                "--newline",
                "-x",
                "--audio-format", "mp3",
                "--audio-quality", audioQuality.ytdlpQuality,
                "-P", DOWNLOAD_DIR,
                url
            )
            DownloadOption.MEDIA -> when (mode) {
                MediaMode.MP3 -> listOf(
                    "yt-dlp",
                    "--newline",
                    "-x",
                    "--audio-format", "mp3",
                    "--audio-quality", audioQuality.ytdlpQuality,
                    "-P", DOWNLOAD_DIR,
                    url
                )
                MediaMode.VIDEO -> listOf(
                    "yt-dlp",
                    "--newline",
                    "-f", videoQuality.ytdlpFormat,
                    "--merge-output-format", "mp4",
                    "-P", DOWNLOAD_DIR,
                    url
                )
            }
        }
    }

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadRecord>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadRecord>> = _activeDownloads.asStateFlow()

    suspend fun startDownload(
        item: InstagramItem,
        mode: MediaMode,
        videoQuality: VideoQuality,
        audioQuality: AudioQuality,
        option: DownloadOption = DownloadOption.MEDIA
    ): DownloadRecord = withContext(Dispatchers.IO) {
        val recordId = UUID.randomUUID().toString()
        val qualityLabel = when {
            option == DownloadOption.COVER_IMAGE -> "Cover Image"
            option == DownloadOption.CAROUSEL_ALL -> "All Full HD"
            mode == MediaMode.MP3 || option == DownloadOption.AUDIO_ONLY -> audioQuality.label
            else -> videoQuality.label
        }
        val mediaTypeLabel = when (option) {
            DownloadOption.COVER_IMAGE -> "COVER"
            DownloadOption.CAROUSEL_ALL -> "CAROUSEL"
            DownloadOption.AUDIO_ONLY -> "AUDIO"
            else -> item.type.name
        }

        val record = DownloadRecord(
            id = recordId,
            originalUrl = item.url,
            title = item.title,
            itemType = mediaTypeLabel,
            mediaMode = if (mode == MediaMode.MP3 || option == DownloadOption.AUDIO_ONLY) "MP3" else "VIDEO",
            quality = qualityLabel,
            targetPath = "$DOWNLOAD_DIR/${item.shortcode ?: item.id}",
            status = "QUEUED",
            createdAt = System.currentTimeMillis()
        )
        historyStore.add(record)
        _activeDownloads.update { it + (recordId to record) }

        scope.launch {
            executeDownload(record, item, mode, videoQuality, audioQuality, option)
        }

        record
    }

    suspend fun retryDownload(recordId: String) = withContext(Dispatchers.IO) {
        val retried = historyStore.retry(recordId) ?: return@withContext
        val mode = if (retried.mediaMode == "MP3") MediaMode.MP3 else MediaMode.VIDEO
        val vQuality = VideoQuality.fromLabel(retried.quality)
        val aQuality = AudioQuality.fromLabel(retried.quality)

        val option = when (retried.itemType) {
            "COVER" -> DownloadOption.COVER_IMAGE
            "CAROUSEL" -> DownloadOption.CAROUSEL_ALL
            "AUDIO" -> DownloadOption.AUDIO_ONLY
            else -> DownloadOption.MEDIA
        }

        val syntheticItem = InstagramItem(
            id = retried.id,
            type = InstagramItemType.valueOf(
                runCatching { retried.itemType }.getOrDefault("POST")
                    .let { if (it in listOf("COVER", "CAROUSEL", "AUDIO")) "POST" else it }
            ),
            title = retried.title,
            url = retried.originalUrl
        )

        _activeDownloads.update { it + (recordId to retried) }
        scope.launch {
            executeDownload(retried, syntheticItem, mode, vQuality, aQuality, option)
        }
    }

    private suspend fun executeDownload(
        initialRecord: DownloadRecord,
        item: InstagramItem,
        mode: MediaMode,
        videoQuality: VideoQuality,
        audioQuality: AudioQuality,
        option: DownloadOption
    ) = withContext(Dispatchers.IO) {
        val client = clientProvider()
        val jobName = "Instagram: ${item.title} (${initialRecord.quality})"

        // Determine whether to use standard ToolSubmitRequest or custom argv SubmitRequest
        val submitResult = if (option == DownloadOption.MEDIA && videoQuality == VideoQuality.ORIGINAL && mode == MediaMode.VIDEO) {
            // Standard yt-dlp tool schema path
            val toolArgs = mapOf(
                "url" to JsonPrimitive(item.url),
                "format" to JsonPrimitive("mp4"),
                "audio" to JsonPrimitive(false),
                "outdir" to JsonPrimitive(DOWNLOAD_DIR)
            )
            client.submitToolJob(
                ToolSubmitRequest(
                    name = jobName,
                    tool = "yt-dlp",
                    args = toolArgs,
                    cwd = DOWNLOAD_DIR
                )
            )
        } else {
            // Enhanced custom options (1080p, 720p, MP3 bitrates, cover images, carousel playlists)
            val argv = buildYtdlpArgv(item.url, mode, videoQuality, audioQuality, option)
            client.submitJob(
                SubmitRequest(
                    name = jobName,
                    type = "argv",
                    argv = argv,
                    cwd = DOWNLOAD_DIR,
                    confirm = true
                )
            )
        }

        when (submitResult) {
            is BridgeResult.Success -> {
                val jobId = submitResult.data.response.job_id
                val updating = initialRecord.copy(
                    status = "DOWNLOADING",
                    bridgeJobId = jobId,
                    progressDetail = "Starting download…"
                )
                historyStore.update(updating)
                _activeDownloads.update { it + (initialRecord.id to updating) }

                // Monitor bridge job progress until completion
                monitorJob(initialRecord.id, jobId)
            }
            is BridgeResult.HttpError -> {
                val failed = initialRecord.copy(
                    status = "FAILED",
                    error = "Bridge HTTP error ${submitResult.status}: ${submitResult.message}",
                    endedAt = System.currentTimeMillis()
                )
                historyStore.update(failed)
                _activeDownloads.update { it - initialRecord.id }
            }
            is BridgeResult.NetworkError -> {
                val failed = initialRecord.copy(
                    status = "FAILED",
                    error = "Bridge unreachable (${submitResult.cause.message ?: "Connection refused"}). Check Termux.",
                    endedAt = System.currentTimeMillis()
                )
                historyStore.update(failed)
                _activeDownloads.update { it - initialRecord.id }
            }
        }
    }

    private suspend fun monitorJob(recordId: String, jobId: String) {
        val client = clientProvider()
        try {
            while (scope.isActive) {
                delay(1000)
                when (val r = client.getJob(jobId)) {
                    is BridgeResult.Success -> {
                        val job = r.data
                        val pct = (job.progress_pct ?: 0.0).toFloat()
                        val detail = job.progress_detail ?: ""

                        when (job.status) {
                            "COMPLETED" -> {
                                val completed = (historyStore.byId(recordId) ?: return).copy(
                                    status = "COMPLETED",
                                    progressPct = 100f,
                                    progressDetail = detail.ifBlank { "Completed" },
                                    endedAt = System.currentTimeMillis()
                                )
                                historyStore.update(completed)
                                _activeDownloads.update { it - recordId }
                                return
                            }
                            "FAILED", "CANCELLED" -> {
                                val failed = (historyStore.byId(recordId) ?: return).copy(
                                    status = "FAILED",
                                    error = job.error?.message?.takeIf { it.isNotBlank() } ?: "Download terminated (${job.status})",
                                    endedAt = System.currentTimeMillis()
                                )
                                historyStore.update(failed)
                                _activeDownloads.update { it - recordId }
                                return
                            }
                            else -> {
                                val inProgress = (historyStore.byId(recordId) ?: return).copy(
                                    status = "DOWNLOADING",
                                    progressPct = pct,
                                    progressDetail = detail
                                )
                                historyStore.update(inProgress)
                                _activeDownloads.update { it + (recordId to inProgress) }
                            }
                        }
                    }
                    is BridgeResult.HttpError -> {
                        if (r.status == 404) {
                            // Job pruned or missing
                            val failed = (historyStore.byId(recordId) ?: return).copy(
                                status = "FAILED",
                                error = "Job $jobId no longer exists",
                                endedAt = System.currentTimeMillis()
                            )
                            historyStore.update(failed)
                            _activeDownloads.update { it - recordId }
                            return
                        }
                    }
                    is BridgeResult.NetworkError -> {
                        // Bridge network hiccup, retry next loop
                    }
                }
            }
        } catch (e: CancellationException) {
            // Coroutine cancelled
        }
    }
}
