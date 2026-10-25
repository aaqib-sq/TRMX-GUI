package dev.trmx.gui.download

/*
 * NETWORK USE BOUNDARY:
 * Unlike the App Killer package (which enforces a strict zero-network rule),
 * the Instagram Downloader uses network ONLY for URLs explicitly pasted or
 * tapped by the user. There is NO background fetching, NO tracking, NO telemetry,
 * and NO unprompted network calls. All history stays strictly on-device.
 */

import dev.trmx.gui.model.SubmitRequest
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
import java.util.UUID

/**
 * Downloads Instagram media by coordinating with the Termux bridge and yt-dlp.
 * Employs the app's established shared download directory convention (`~/storage/downloads`),
 * tracks download progress, and persists all history locally on-device.
 */
class DownloadEngine(
    private val clientProvider: () -> BridgeClient,
    private val historyStore: DownloadHistoryStore,
    private val scope: CoroutineScope,
    // No-arg checker: the client is obtained lazily INSIDE the default checker, so a
    // caller that injects its own checker (e.g. tests) never pays for a client it
    // does not use. (v6 called clientProvider() before the checker, which broke the
    // missing-storage test whose provider deliberately throws if called.)
    private val storageChecker: suspend () -> Boolean = {
        when (clientProvider().statFile(DOWNLOAD_DIR)) {
            is BridgeResult.Success -> true
            is BridgeResult.HttpError -> false
            is BridgeResult.NetworkError -> true
        }
    }
) {

    companion object {
        const val DOWNLOAD_DIR = "~/storage/downloads"
        const val STORAGE_SETUP_ERROR_MESSAGE =
            "Shared storage not set up (~/storage/downloads missing). Run termux-setup-storage in Termux and allow the storage permission, then retry."
        const val MAX_JOB_NAME_CHARS = 200

        /**
         * Builds a bridge-compliant job name strictly in the format "<type>:<shortcode>".
         * HARD-CAPPED at 200 characters. NEVER built from captions or titles.
         * The bridge rejects names outside 1..200 characters.
         */
        fun buildJobName(type: String, shortcode: String?): String {
            val cleanType = type.trim().lowercase().ifBlank { "media" }
            val cleanCode = shortcode?.trim()?.ifBlank { "item" } ?: "item"
            val raw = "$cleanType:$cleanCode"
            return if (raw.length > MAX_JOB_NAME_CHARS) raw.take(MAX_JOB_NAME_CHARS) else raw
        }

        fun buildYtdlpArgv(
            url: String,
            mode: MediaMode,
            videoQuality: VideoQuality,
            audioQuality: AudioQuality,
            saveCover: Boolean = false,
            option: DownloadOption = DownloadOption.MEDIA
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
                "-f", videoQuality.ytdlpFormat,
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
                MediaMode.VIDEO -> {
                    val argv = mutableListOf(
                        "yt-dlp",
                        "--newline",
                        "-f", videoQuality.ytdlpFormat,
                        "--merge-output-format", "mp4"
                    )
                    if (saveCover) {
                        argv.add("--write-thumbnail")
                        argv.add("--convert-thumbnails")
                        argv.add("jpg")
                    }
                    argv.add("-P")
                    argv.add(DOWNLOAD_DIR)
                    argv.add(url)
                    argv
                }
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
        saveCover: Boolean = false,
        option: DownloadOption = DownloadOption.MEDIA
    ): DownloadRecord = withContext(Dispatchers.IO) {
        if (item.type == InstagramItemType.HIGHLIGHT && item.directMediaUrl.isNullOrBlank()) {
            throw IllegalArgumentException("Cannot download highlight: direct media URL is not resolved. Instagram highlights require login.")
        }
        if (!storageChecker()) {
            throw IllegalStateException(STORAGE_SETUP_ERROR_MESSAGE)
        }
        val recordId = UUID.randomUUID().toString()
        val qualityLabel = when {
            option == DownloadOption.COVER_IMAGE -> "Cover Image"
            option == DownloadOption.CAROUSEL_ALL -> "All Slides (${videoQuality.label})"
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
            executeDownload(record, item, mode, videoQuality, audioQuality, saveCover, option)
        }

        record
    }

    suspend fun retryDownload(recordId: String) = withContext(Dispatchers.IO) {
        if (!storageChecker()) {
            val failed = historyStore.byId(recordId)?.copy(
                status = "FAILED",
                error = STORAGE_SETUP_ERROR_MESSAGE,
                endedAt = System.currentTimeMillis()
            )
            if (failed != null) {
                historyStore.update(failed)
            }
            throw IllegalStateException(STORAGE_SETUP_ERROR_MESSAGE)
        }
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
            executeDownload(retried, syntheticItem, mode, vQuality, aQuality, saveCover = true, option = option)
        }
    }

    private suspend fun executeDownload(
        initialRecord: DownloadRecord,
        item: InstagramItem,
        mode: MediaMode,
        videoQuality: VideoQuality,
        audioQuality: AudioQuality,
        saveCover: Boolean,
        option: DownloadOption
    ) = withContext(Dispatchers.IO) {
        val client = clientProvider()

        if (!storageChecker()) {
            val failed = initialRecord.copy(
                status = "FAILED",
                error = STORAGE_SETUP_ERROR_MESSAGE,
                endedAt = System.currentTimeMillis()
            )
            historyStore.update(failed)
            _activeDownloads.update { it - initialRecord.id }
            return@withContext
        }

        // Strict bridge job name "<type>:<shortcode>", hard-capped at 200 chars.
        // NEVER uses item.title or item.caption.
        val typeName = when (option) {
            DownloadOption.COVER_IMAGE -> "cover"
            DownloadOption.CAROUSEL_ALL -> "carousel"
            DownloadOption.AUDIO_ONLY -> "audio"
            else -> item.type.name.lowercase()
        }
        val shortcode = item.shortcode ?: item.id
        val jobName = buildJobName(typeName, shortcode)

        val downloadUrl = if (item.type == InstagramItemType.HIGHLIGHT && !item.directMediaUrl.isNullOrBlank()) {
            item.directMediaUrl
        } else {
            item.url
        }
        val argv = buildYtdlpArgv(downloadUrl, mode, videoQuality, audioQuality, saveCover, option)
        val submitResult = client.submitJob(
            SubmitRequest(
                name = jobName,
                type = "argv",
                argv = argv,
                cwd = DOWNLOAD_DIR,
                confirm = true
            )
        )

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
        var completed = false
        var consecutiveErrors = 0

        while (scope.isActive && !completed) {
            delay(1000)
            when (val res = client.getJob(jobId)) {
                is BridgeResult.Success -> {
                    consecutiveErrors = 0
                    val job = res.data
                    val isDone = job.status in listOf("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT")

                    val progressPct = when (job.status) {
                        "COMPLETED" -> 100f
                        "STARTING", "QUEUED" -> 5f
                        "RUNNING" -> 50f
                        else -> 0f
                    }

                    val updated = _activeDownloads.value[recordId]?.copy(
                        status = if (isDone) job.status else "DOWNLOADING",
                        progressPct = progressPct,
                        progressDetail = "Status: ${job.status}",
                        error = if (job.status == "FAILED") (job.error?.message ?: "yt-dlp exited with non-zero status") else null,
                        endedAt = if (isDone) System.currentTimeMillis() else null
                    )

                    if (updated != null) {
                        historyStore.update(updated)
                        if (isDone) {
                            _activeDownloads.update { it - recordId }
                            completed = true
                        } else {
                            _activeDownloads.update { it + (recordId to updated) }
                        }
                    } else {
                        completed = true
                    }
                }
                is BridgeResult.HttpError, is BridgeResult.NetworkError -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= 10) {
                        // Bridge lost or job disappeared
                        val failed = _activeDownloads.value[recordId]?.copy(
                            status = "FAILED",
                            error = "Lost connection to bridge monitoring job $jobId",
                            endedAt = System.currentTimeMillis()
                        )
                        if (failed != null) {
                            historyStore.update(failed)
                            _activeDownloads.update { it - recordId }
                        }
                        completed = true
                    }
                }
            }
        }
    }
}
