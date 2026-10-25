package dev.trmx.gui.download

/**
 * Gate decision for media preview overlay.
 */
data class PreviewGateDecision(
    val canOpen: Boolean,
    val isVideoOverlay: Boolean,
    val notice: String? = null
)

/**
 * Pure logic preview gate for Instagram media items.
 *
 * Enforces:
 * 1. Video and Reel preview overlay opens ONLY when a direct playable video URL is resolved.
 *    If direct video URL is missing, preview does NOT open and a clear notice is shown.
 * 2. Photo preview overlay continues to work legitimately via previewUrl (FullPhotoPreviewOverlay).
 */
object InstagramPreviewGate {
    const val VIDEO_NEED_DIRECT_LINK_NOTICE =
        "Video preview needs a direct video link — download the video to watch it."

    fun evaluate(item: InstagramItem): PreviewGateDecision {
        val isVideoItem = item.isVideo ||
            item.type == InstagramItemType.REEL ||
            item.type == InstagramItemType.VIDEO ||
            item.type == InstagramItemType.IGTV

        return if (isVideoItem) {
            if (item.directMediaUrl.isNullOrBlank()) {
                PreviewGateDecision(
                    canOpen = false,
                    isVideoOverlay = false,
                    notice = VIDEO_NEED_DIRECT_LINK_NOTICE
                )
            } else {
                PreviewGateDecision(
                    canOpen = true,
                    isVideoOverlay = true,
                    notice = null
                )
            }
        } else {
            // Photo items legitimately preview via previewUrl (FullPhotoPreviewOverlay)
            if (item.hasResolvedMedia) {
                PreviewGateDecision(
                    canOpen = true,
                    isVideoOverlay = false,
                    notice = null
                )
            } else {
                PreviewGateDecision(
                    canOpen = false,
                    isVideoOverlay = false,
                    notice = null
                )
            }
        }
    }
}
