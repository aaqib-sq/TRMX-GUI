package dev.trmx.gui.download

/*
 * NETWORK USE BOUNDARY:
 * Unlike the App Killer package (which enforces a strict zero-network rule),
 * the Instagram Downloader uses network ONLY for URLs explicitly pasted or
 * tapped by the user. There is NO background fetching, NO tracking, NO telemetry,
 * and NO unprompted network calls. All history stays strictly on-device.
 */

import kotlinx.serialization.Serializable

/** Supported Instagram URL types recognized by the parser. */
enum class InstagramUrlType {
    PROFILE,
    POST,
    REEL,
    STORY,
    HIGHLIGHT,
    UNKNOWN
}

/** Result of parsing an Instagram URL or handle. */
data class ParsedInstagramLink(
    val originalInput: String,
    val type: InstagramUrlType,
    val username: String? = null,
    val shortcode: String? = null,
    val storyId: String? = null,
    val highlightId: String? = null,
    val canonicalUrl: String = originalInput,
    val error: String? = null
)

/** Mode toggle: Full video vs Audio extraction only. */
enum class MediaMode {
    VIDEO,
    MP3
}

/** Video resolution quality choices shown upfront. */
enum class VideoQuality(val label: String, val height: Int?, val ytdlpFormat: String) {
    ORIGINAL("Original", null, "bestvideo+bestaudio/best"),
    P1080("1080p", 1080, "bestvideo[height<=1080]+bestaudio/best[height<=1080]"),
    P720("720p", 720, "bestvideo[height<=720]+bestaudio/best[height<=720]"),
    P480("480p", 480, "bestvideo[height<=480]+bestaudio/best[height<=480]");

    companion object {
        fun fromLabel(label: String): VideoQuality =
            entries.find { it.label.equals(label, ignoreCase = true) } ?: ORIGINAL
    }
}

/** Audio bitrate choices shown upfront in MP3 mode. */
enum class AudioQuality(val label: String, val bitrateKbps: Int, val ytdlpQuality: String) {
    KBPS320("320 kbps", 320, "320K"),
    KBPS192("192 kbps", 192, "192K"),
    KBPS128("128 kbps", 128, "128K");

    companion object {
        fun fromLabel(label: String): AudioQuality =
            entries.find { it.label.startsWith(label, ignoreCase = true) } ?: KBPS320
    }
}

/** Specific media type of an individual downloadable item. */
enum class InstagramItemType {
    POST,
    REEL,
    STORY,
    HIGHLIGHT,
    PHOTO,
    VIDEO,
    IGTV,
    CAROUSEL,
    PROFILE_PIC
}

/** Profile tabs matching reference: POSTS / STORIES / HIGHLIGHTS / REELS. */
enum class DownloadTab {
    POSTS,
    STORIES,
    HIGHLIGHTS,
    REELS
}

/** Download target action variant. */
enum class DownloadOption {
    MEDIA,          // Standard video/photo download
    AUDIO_ONLY,     // MP3 audio extract
    COVER_IMAGE,    // Thumbnail / cover image
    CAROUSEL_ALL    // All items of a carousel
}

/** Public Instagram profile header metadata. */
data class InstagramProfile(
    val username: String,
    val fullName: String,
    val biography: String = "",
    val avatarUrl: String? = null,
    val postsCount: Long = 0,
    val followersCount: Long = 0,
    val followingCount: Long = 0,
    val isPrivate: Boolean = false,
    val isVerified: Boolean = false,
    val fullSizeAvatarUrl: String? = null
)

/** An individual media item returned from profile or single post fetch. */
data class InstagramItem(
    val id: String,
    val shortcode: String? = null,
    val type: InstagramItemType,
    val title: String,
    val caption: String = "",
    val url: String,
    val previewUrl: String? = null,
    val directMediaUrl: String? = null,
    val durationSeconds: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val isVideo: Boolean = false,
    val timestamp: Long? = null,
    val carouselItems: List<InstagramItem> = emptyList(),
    val errorMessage: String? = null,
    val isAvailable: Boolean = true
)

/** Persistent on-device record of a past or running download. */
@Serializable
data class DownloadRecord(
    val id: String,
    val originalUrl: String,
    val title: String,
    val itemType: String,
    val mediaMode: String,
    val quality: String,
    val targetPath: String,
    val status: String, // "QUEUED", "DOWNLOADING", "COMPLETED", "FAILED"
    val progressPct: Float = 0f,
    val progressDetail: String = "",
    val error: String? = null,
    val bridgeJobId: String? = null,
    val createdAt: Long = 0L,
    val endedAt: Long? = null
)
