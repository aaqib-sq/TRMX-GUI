package dev.trmx.gui.download

/*
 * NETWORK USE BOUNDARY:
 * Unlike the App Killer package (which enforces a strict zero-network rule),
 * the Instagram Downloader uses network ONLY for URLs explicitly pasted or
 * tapped by the user. There is NO background fetching, NO tracking, NO telemetry,
 * and NO unprompted network calls. All history stays strictly on-device.
 */

/**
 * Parses user-provided Instagram inputs (links or handles) into structured
 * link metadata (profile, post, reel, story, or highlight).
 */
object InstagramUrlParser {

    private val RESERVED_PATHS = setOf(
        "about", "accounts", "api", "developer", "direct", "directory",
        "explore", "graphql", "help", "legal", "p", "privacy", "reel",
        "reels", "s", "stories", "tv"
    )

    private val USERNAME_REGEX = Regex("^[a-zA-Z0-9._]{1,30}$")

    private val HIGHLIGHT_REGEX = Regex(
        """(?:https?://)?(?:www\.)?instagram\.com/stories/highlights/([0-9a-zA-Z_-]+)"""
    )
    private val STORY_WITH_ID_REGEX = Regex(
        """(?:https?://)?(?:www\.)?instagram\.com/stories/([a-zA-Z0-9._]{1,30})/([0-9]+)"""
    )
    private val STORY_REGEX = Regex(
        """(?:https?://)?(?:www\.)?instagram\.com/stories/([a-zA-Z0-9._]{1,30})"""
    )
    private val REEL_REGEX = Regex(
        """(?:https?://)?(?:www\.)?instagram\.com/reels?/([a-zA-Z0-9_-]+)"""
    )
    private val IGTV_REGEX = Regex(
        """(?:https?://)?(?:www\.)?instagram\.com/tv/([a-zA-Z0-9_-]+)"""
    )
    private val POST_REGEX = Regex(
        """(?:https?://)?(?:www\.)?instagram\.com/p/([a-zA-Z0-9_-]+)"""
    )
    private val PROFILE_URL_REGEX = Regex(
        """(?:https?://)?(?:www\.)?instagram\.com/([a-zA-Z0-9._]{1,30})/?$"""
    )

    fun parse(input: String): ParsedInstagramLink {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return ParsedInstagramLink(
                originalInput = input,
                type = InstagramUrlType.UNKNOWN,
                error = "URL cannot be empty"
            )
        }

        // Clean query params (?...) and fragment identifiers (#...) for regex matching
        val cleanUrl = trimmed.substringBefore("?").substringBefore("#").trimEnd('/')

        // 1. Stories highlight: /stories/highlights/12345/
        val highlightMatch = HIGHLIGHT_REGEX.find(cleanUrl)
        if (highlightMatch != null) {
            val id = highlightMatch.groupValues[1]
            if (InstagramFetchEngine.isValidHighlightId(id)) {
                return ParsedInstagramLink(
                    originalInput = trimmed,
                    type = InstagramUrlType.HIGHLIGHT,
                    highlightId = id,
                    canonicalUrl = "https://www.instagram.com/stories/highlights/$id/"
                )
            } else {
                return ParsedInstagramLink(
                    originalInput = trimmed,
                    type = InstagramUrlType.UNKNOWN,
                    error = "Invalid highlight ID: $id (highlight IDs must be numeric)"
                )
            }
        }

        // 2. Story with specific media ID: /stories/username/123456789/
        val storyIdMatch = STORY_WITH_ID_REGEX.find(cleanUrl)
        if (storyIdMatch != null) {
            val user = storyIdMatch.groupValues[1]
            val sId = storyIdMatch.groupValues[2]
            return ParsedInstagramLink(
                originalInput = trimmed,
                type = InstagramUrlType.STORY,
                username = user,
                storyId = sId,
                canonicalUrl = "https://www.instagram.com/stories/$user/$sId/"
            )
        }

        // 3. User story tray: /stories/username/
        val storyMatch = STORY_REGEX.find(cleanUrl)
        if (storyMatch != null) {
            val user = storyMatch.groupValues[1]
            return ParsedInstagramLink(
                originalInput = trimmed,
                type = InstagramUrlType.STORY,
                username = user,
                canonicalUrl = "https://www.instagram.com/stories/$user/"
            )
        }

        // 4. Reel: /reel/CODE/ or /reels/CODE/
        val reelMatch = REEL_REGEX.find(cleanUrl)
        if (reelMatch != null) {
            val code = reelMatch.groupValues[1]
            return ParsedInstagramLink(
                originalInput = trimmed,
                type = InstagramUrlType.REEL,
                shortcode = code,
                canonicalUrl = "https://www.instagram.com/reel/$code/"
            )
        }

        // 5. IGTV: /tv/CODE/
        val igtvMatch = IGTV_REGEX.find(cleanUrl)
        if (igtvMatch != null) {
            val code = igtvMatch.groupValues[1]
            return ParsedInstagramLink(
                originalInput = trimmed,
                type = InstagramUrlType.POST,
                shortcode = code,
                canonicalUrl = "https://www.instagram.com/tv/$code/"
            )
        }

        // 6. Post: /p/CODE/
        val postMatch = POST_REGEX.find(cleanUrl)
        if (postMatch != null) {
            val code = postMatch.groupValues[1]
            return ParsedInstagramLink(
                originalInput = trimmed,
                type = InstagramUrlType.POST,
                shortcode = code,
                canonicalUrl = "https://www.instagram.com/p/$code/"
            )
        }

        // 7. Profile URL: instagram.com/username/
        val profileMatch = PROFILE_URL_REGEX.find(cleanUrl)
        if (profileMatch != null) {
            val user = profileMatch.groupValues[1]
            if (!RESERVED_PATHS.contains(user.lowercase())) {
                return ParsedInstagramLink(
                    originalInput = trimmed,
                    type = InstagramUrlType.PROFILE,
                    username = user,
                    canonicalUrl = "https://www.instagram.com/$user/"
                )
            }
        }

        // 8. Handle input: @username or raw username
        val rawHandle = trimmed.removePrefix("@").trimEnd('/')
        if (USERNAME_REGEX.matches(rawHandle) && !RESERVED_PATHS.contains(rawHandle.lowercase()) && !rawHandle.contains("/")) {
            return ParsedInstagramLink(
                originalInput = trimmed,
                type = InstagramUrlType.PROFILE,
                username = rawHandle,
                canonicalUrl = "https://www.instagram.com/$rawHandle/"
            )
        }

        return ParsedInstagramLink(
            originalInput = trimmed,
            type = InstagramUrlType.UNKNOWN,
            error = "Unsupported Instagram URL format"
        )
    }
}
