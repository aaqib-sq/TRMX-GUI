package dev.trmx.gui.download

/*
 * NETWORK USE BOUNDARY:
 * Unlike the App Killer package (which enforces a strict zero-network rule),
 * the Instagram Downloader uses network ONLY for URLs explicitly pasted or
 * tapped by the user. There is NO background fetching, NO tracking, NO telemetry,
 * and NO unprompted network calls. All history stays strictly on-device.
 */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

data class ProfileFetchResult(
    val profile: InstagramProfile,
    val items: List<InstagramItem>,
    val blockedNotice: String? = null,
    val postsLoadError: String? = null
)

/**
 * Tab empty-state helpers ensuring truthful UI reporting.
 * Empty-states must never claim "No public posts/reels found" when retrieval actually failed.
 */
object InstagramTabHelper {
    fun getPostsEmptyMessage(postsCount: Long, postsLoadError: String?): String {
        return if (postsCount > 0) {
            postsLoadError ?: "Could not load public posts. All retrieval routes (web API, page data, yt-dlp bridge) failed to retrieve timeline items without login."
        } else {
            "No public posts found for this profile."
        }
    }

    fun getReelsEmptyMessage(postsCount: Long, hasMediaItems: Boolean, postsLoadError: String?): String {
        return if (postsCount > 0 && !hasMediaItems) {
            postsLoadError ?: "Could not load public reels. All retrieval routes failed to retrieve timeline items without login."
        } else {
            "No public reels found."
        }
    }
}

/**
 * Public, unauthenticated Instagram fetcher.
 * Queries public endpoints with standard browser headers to retrieve profile metadata,
 * posts, reels, and stories without requiring any login or user account.
 *
 * Implements the 3-route resolution order (PART B):
 * 1. web_profile_info timeline edges, when present
 * 2. Profile-page embedded JSON timeline data
 * 3. yt-dlp flat-playlist listing via existing bridge as fallback
 *
 * All HTML entities (such as &#x909; and &amp;) are decoded strictly at the parse layer
 * so titles, captions, and avatar URLs never display raw encoded escape sequences.
 */
class InstagramFetchEngine(
    private val http: OkHttpClient,
    val ytdlpRunner: (suspend (username: String) -> List<InstagramItem>)? = null
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-Dest" to "document"
    )

    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "X-IG-App-ID" to "936619743392459",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "X-Requested-With" to "XMLHttpRequest"
    )

    companion object {
        fun cleanUrl(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return HtmlDecoder.decode(raw).trim()
        }

        /**
         * Validates whether a string is a plausible real Instagram highlight reel ID.
         * Real highlight reel IDs are long numeric identifiers (typically 8 to 20 digits).
         * Short numbers (such as "245") or ambiguous non-numeric tokens are dropped.
         */
        fun isValidHighlightId(id: String?): Boolean {
            if (id.isNullOrBlank()) return false
            return id.length in 8..20 && id.all { it.isDigit() }
        }
    }

    suspend fun fetchProfile(username: String): ProfileFetchResult = withContext(Dispatchers.IO) {
        var profile: InstagramProfile? = null
        val items = mutableListOf<InstagramItem>()
        var blockedNotice: String? = null
        var postsLoadError: String? = null

        // --------------------------------------------------------------------
        // Route 1: web_profile_info public API
        // --------------------------------------------------------------------
        val apiResult = runCatching { fetchProfileViaApi(username) }.getOrNull()
        if (apiResult != null) {
            profile = apiResult.profile
            items.addAll(apiResult.items)
        }

        val hasMediaItemsFromApi = items.any { it.isMediaItem }

        // If private, never attempt login workarounds
        if (profile?.isPrivate == true) {
            return@withContext ProfileFetchResult(profile, items)
        }

        // --------------------------------------------------------------------
        // Route 2: Profile-page embedded JSON timeline data
        // --------------------------------------------------------------------
        if (!hasMediaItemsFromApi) {
            val htmlResult = runCatching { fetchProfileViaHtml(username) }.getOrNull()
            if (htmlResult != null) {
                if (profile == null) {
                    profile = htmlResult.profile
                } else {
                    // Enrich metadata from HTML if API was missing parts
                    if (profile.biography.isBlank() && htmlResult.profile.biography.isNotBlank()) {
                        profile = profile.copy(biography = htmlResult.profile.biography)
                    }
                    if (profile.avatarUrl.isNullOrBlank() && !htmlResult.profile.avatarUrl.isNullOrBlank()) {
                        profile = profile.copy(
                            avatarUrl = htmlResult.profile.avatarUrl,
                            fullSizeAvatarUrl = htmlResult.profile.avatarUrl
                        )
                    }
                    if (profile.postsCount == 0L && htmlResult.profile.postsCount > 0L) {
                        profile = profile.copy(postsCount = htmlResult.profile.postsCount)
                    }
                }

                val htmlTimelineItems = htmlResult.items.filter { it.type != InstagramItemType.PROFILE_PIC }
                val existingIds = items.map { it.id }.toSet()
                for (item in htmlTimelineItems) {
                    if (item.id !in existingIds) {
                        items.add(item)
                    }
                }
            }
        }

        val currentPostsCount = profile?.postsCount ?: 0L
        val hasMediaItems = items.any { it.isMediaItem }

        // --------------------------------------------------------------------
        // Route 3: yt-dlp flat-playlist via bridge fallback
        // --------------------------------------------------------------------
        if (!hasMediaItems && currentPostsCount > 0L && ytdlpRunner != null) {
            val ytdlpItems = runCatching { ytdlpRunner.invoke(username) }.getOrNull()
            if (!ytdlpItems.isNullOrEmpty()) {
                val existingIds = items.map { it.id }.toSet()
                for (item in ytdlpItems) {
                    if (item.id !in existingIds) {
                        items.add(item)
                    }
                }
            }
        }

        // Graceful fallback if neither API nor HTML could fetch profile metadata
        if (profile == null) {
            val fallback = createGracefulProfileFallback(username, "Instagram public web gateway is rate-limited. Direct yt-dlp download is ready.")
            profile = fallback.profile
            items.addAll(fallback.items)
            blockedNotice = fallback.blockedNotice
        }

        // Final verification for posts/reels: never show false empty when postsCount > 0
        val finalHasMediaItems = items.any { it.isMediaItem }
        if (profile.postsCount > 0L && !finalHasMediaItems && !profile.isPrivate) {
            postsLoadError = "Could not load public posts. All retrieval routes (web API, page data, yt-dlp bridge) failed to retrieve timeline items without login."
        }

        // Ensure avatar entities decoded
        profile = profile.copy(
            avatarUrl = cleanUrl(profile.avatarUrl),
            fullSizeAvatarUrl = cleanUrl(profile.fullSizeAvatarUrl)
        )

        ProfileFetchResult(
            profile = profile,
            items = items,
            blockedNotice = blockedNotice,
            postsLoadError = postsLoadError
        )
    }

    suspend fun fetchSingleItem(link: ParsedInstagramLink): InstagramItem = withContext(Dispatchers.IO) {
        val canonical = link.canonicalUrl
        val shortcode = link.shortcode ?: link.username ?: "item"

        runCatching {
            fetchItemViaHtml(canonical, link)
        }.getOrElse {
            // Graceful fallback: construct usable item for yt-dlp
            val itemType = when (link.type) {
                InstagramUrlType.REEL -> InstagramItemType.REEL
                InstagramUrlType.STORY -> InstagramItemType.STORY
                InstagramUrlType.HIGHLIGHT -> InstagramItemType.HIGHLIGHT
                else -> InstagramItemType.POST
            }
            InstagramItem(
                id = shortcode,
                shortcode = link.shortcode,
                type = itemType,
                title = HtmlDecoder.decode("Instagram ${itemType.name.lowercase().replaceFirstChar { it.uppercase() }}: $shortcode"),
                url = canonical,
                isVideo = (link.type == InstagramUrlType.REEL),
                isAvailable = true,
                errorMessage = null
            )
        }
    }

    fun fetchProfileViaApi(username: String): ProfileFetchResult {
        val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username"
        val request = Request.Builder()
            .url(url)
            .apply { apiHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("web_profile_info returned HTTP ${response.code}")
        }

        val bodyString = response.body?.string() ?: throw IOException("Empty body")
        val root = json.parseToJsonElement(bodyString).jsonObject
        val data = root["data"]?.jsonObject ?: throw IOException("No data object")
        val user = data["user"]?.jsonObject ?: throw IOException("User not found")

        val rawFullName = user["full_name"]?.jsonPrimitive?.content ?: username
        val rawBio = user["biography"]?.jsonPrimitive?.content ?: ""
        val avatarUrl = cleanUrl(
            user["profile_pic_url_hd"]?.jsonPrimitive?.content
                ?: user["profile_pic_url"]?.jsonPrimitive?.content
        )
        val isPrivate = user["is_private"]?.jsonPrimitive?.booleanOrNull ?: false
        val isVerified = user["is_verified"]?.jsonPrimitive?.booleanOrNull ?: false

        val postsCount = user["edge_owner_to_timeline_media"]?.jsonObject?.get("count")
            ?.jsonPrimitive?.longOrNull ?: 0L
        val followersCount = user["edge_followed_by"]?.jsonObject?.get("count")
            ?.jsonPrimitive?.longOrNull ?: 0L
        val followingCount = user["edge_follow"]?.jsonObject?.get("count")
            ?.jsonPrimitive?.longOrNull ?: 0L

        // Decode HTML entities at parse layer
        val profile = InstagramProfile(
            username = username,
            fullName = HtmlDecoder.decode(rawFullName).ifBlank { username },
            biography = HtmlDecoder.decode(rawBio),
            avatarUrl = avatarUrl,
            postsCount = postsCount,
            followersCount = followersCount,
            followingCount = followingCount,
            isPrivate = isPrivate,
            isVerified = isVerified,
            fullSizeAvatarUrl = avatarUrl
        )

        val items = mutableListOf<InstagramItem>()

        // Add Profile Photo item UPFRONT
        if (!avatarUrl.isNullOrBlank()) {
            items.add(
                InstagramItem(
                    id = "profile_pic_${username}",
                    type = InstagramItemType.PROFILE_PIC,
                    title = "Profile Photo (Full Size)",
                    caption = "@$username full size profile picture",
                    url = "https://www.instagram.com/$username/",
                    previewUrl = avatarUrl,
                    directMediaUrl = avatarUrl,
                    isVideo = false,
                    isAvailable = true
                )
            )
        }

        // If private, never attempt login workarounds — return empty items with clean state
        if (isPrivate) {
            return ProfileFetchResult(profile, items)
        }

        // Parse timeline media edges
        val edges = user["edge_owner_to_timeline_media"]?.jsonObject?.get("edges")?.jsonArray
        if (edges != null) {
            for (edge in edges) {
                val node = edge.jsonObject["node"]?.jsonObject ?: continue
                val parsedItem = parseMediaNode(node, username)
                if (parsedItem != null) {
                    items.add(parsedItem)
                }
            }
        }

        return ProfileFetchResult(profile, items)
    }

    fun parseMediaNode(node: JsonObject, username: String): InstagramItem? {
        val id = node["id"]?.jsonPrimitive?.content ?: node["pk"]?.jsonPrimitive?.content ?: return null
        val shortcode = node["shortcode"]?.jsonPrimitive?.content ?: node["code"]?.jsonPrimitive?.content ?: id
        val typename = node["__typename"]?.jsonPrimitive?.content ?: ""
        val isVideo = node["is_video"]?.jsonPrimitive?.booleanOrNull ?: false
        val displayUrl = cleanUrl(
            node["display_url"]?.jsonPrimitive?.content
                ?: node["display_uri"]?.jsonPrimitive?.content
                ?: node["thumbnail_src"]?.jsonPrimitive?.content
        )
        val videoUrl = cleanUrl(node["video_url"]?.jsonPrimitive?.content)
        val rawCaption = node["edge_media_to_caption"]?.jsonObject?.get("edges")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("node")?.jsonObject?.get("text")
            ?.jsonPrimitive?.content
            ?: node["caption"]?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: ""
        val caption = HtmlDecoder.decode(rawCaption)
        val timestamp = node["taken_at_timestamp"]?.jsonPrimitive?.longOrNull
            ?: node["taken_at"]?.jsonPrimitive?.longOrNull
        val dim = node["dimensions"]?.jsonObject
        val width = dim?.get("width")?.jsonPrimitive?.content?.toIntOrNull()
        val height = dim?.get("height")?.jsonPrimitive?.content?.toIntOrNull()

        val likes = node["edge_liked_by"]?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull
            ?: node["edge_media_preview_like"]?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull
            ?: node["like_count"]?.jsonPrimitive?.longOrNull
        val comments = node["edge_media_to_comment"]?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull
            ?: node["comment_count"]?.jsonPrimitive?.longOrNull

        // Carousel items (GraphSidecar)
        val sidecarEdges = node["edge_sidecar_to_children"]?.jsonObject?.get("edges")?.jsonArray
        val carouselChildren = mutableListOf<InstagramItem>()
        if (sidecarEdges != null) {
            for ((index, sidecarEdge) in sidecarEdges.withIndex()) {
                val childNode = sidecarEdge.jsonObject["node"]?.jsonObject ?: continue
                val cId = childNode["id"]?.jsonPrimitive?.content ?: "${id}_$index"
                val cDisplay = cleanUrl(childNode["display_url"]?.jsonPrimitive?.content)
                val cVideo = cleanUrl(childNode["video_url"]?.jsonPrimitive?.content)
                val cIsVideo = childNode["is_video"]?.jsonPrimitive?.booleanOrNull ?: false
                carouselChildren.add(
                    InstagramItem(
                        id = cId,
                        shortcode = shortcode,
                        type = if (cIsVideo) InstagramItemType.VIDEO else InstagramItemType.PHOTO,
                        title = HtmlDecoder.decode("Slide #${index + 1}"),
                        caption = caption,
                        url = "https://www.instagram.com/p/$shortcode/",
                        previewUrl = cDisplay,
                        directMediaUrl = cVideo ?: cDisplay,
                        isVideo = cIsVideo,
                        isAvailable = true,
                        formattedDate = timestamp?.let { formatTimestamp(it) }
                    )
                )
            }
        }

        val itemType = when {
            carouselChildren.isNotEmpty() || typename == "GraphSidecar" -> InstagramItemType.CAROUSEL
            isVideo && (typename == "GraphVideo" || typename == "GraphClips" || (height ?: 0) > (width ?: 0)) -> InstagramItemType.REEL
            isVideo -> InstagramItemType.VIDEO
            else -> InstagramItemType.PHOTO
        }

        val title = when (itemType) {
            InstagramItemType.REEL -> "Reel: $shortcode"
            InstagramItemType.CAROUSEL -> "Carousel (${carouselChildren.size} items)"
            InstagramItemType.VIDEO -> "Video: $shortcode"
            InstagramItemType.PHOTO -> "Photo: $shortcode"
            else -> "Post: $shortcode"
        }

        return InstagramItem(
            id = id,
            shortcode = shortcode,
            type = itemType,
            title = HtmlDecoder.decode(title),
            caption = caption,
            url = "https://www.instagram.com/p/$shortcode/",
            previewUrl = displayUrl,
            directMediaUrl = videoUrl ?: displayUrl,
            width = width,
            height = height,
            isVideo = isVideo,
            timestamp = timestamp,
            formattedDate = timestamp?.let { formatTimestamp(it) },
            likesCount = likes,
            commentsCount = comments,
            carouselItems = carouselChildren,
            isAvailable = true
        )
    }

    fun fetchProfileViaHtml(username: String): ProfileFetchResult {
        val url = "https://www.instagram.com/$username/"
        val request = Request.Builder()
            .url(url)
            .apply { browserHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        val response = http.newCall(request).execute()
        val html = response.body?.string() ?: throw IOException("Empty HTML response")
        return parseProfileHtml(html, username, url)
    }

    fun parseProfileHtml(html: String, username: String, url: String = "https://www.instagram.com/$username/"): ProfileFetchResult {
        val titleMatch = Pattern.compile("""<meta\s+property="og:title"\s+content="([^"]*)"""", Pattern.CASE_INSENSITIVE).matcher(html)
        val rawTitle = if (titleMatch.find()) titleMatch.group(1) else username

        val descMatch = Pattern.compile("""<meta\s+property="og:description"\s+content="([^"]*)"""", Pattern.CASE_INSENSITIVE).matcher(html)
        val rawDesc = if (descMatch.find()) descMatch.group(1) else ""

        val imageMatch = Pattern.compile("""<meta\s+property="og:image"\s+content="([^"]*)"""", Pattern.CASE_INSENSITIVE).matcher(html)
        val rawAvatarUrl = if (imageMatch.find()) imageMatch.group(1) else null
        val avatarUrl = cleanUrl(rawAvatarUrl)

        // Parse followers/following/posts STRICTLY from og:description (e.g. "12K Followers, 300 Following, 45 Posts")
        var followers = 0L
        var following = 0L
        var posts = 0L
        val statsPattern = Pattern.compile("""([\d,.]+[KMkm]?)\s+Followers,\s+([\d,.]+[KMkm]?)\s+Following,\s+([\d,.]+[KMkm]?)\s+Posts""", Pattern.CASE_INSENSITIVE)
        val statsMatcher = statsPattern.matcher(rawDesc)
        if (statsMatcher.find()) {
            followers = parseCount(statsMatcher.group(1))
            following = parseCount(statsMatcher.group(2))
            posts = parseCount(statsMatcher.group(3))
        }

        val isPrivate = html.contains("This Account is Private") || html.contains("\"is_private\":true")

        // Decode HTML entities at parse layer
        val decodedFullName = HtmlDecoder.decode(rawTitle.substringBefore("(@").trim().ifBlank { username })

        // Parse REAL biography from page JSON — og:description is ONLY for counts, NEVER for biography!
        val realBio = extractBiographyFromHtml(html)

        val profile = InstagramProfile(
            username = username,
            fullName = decodedFullName,
            biography = realBio,
            avatarUrl = avatarUrl,
            postsCount = posts,
            followersCount = followers,
            followingCount = following,
            isPrivate = isPrivate,
            isVerified = html.contains("\"is_verified\":true"),
            fullSizeAvatarUrl = avatarUrl
        )

        val items = mutableListOf<InstagramItem>()
        if (!avatarUrl.isNullOrBlank()) {
            items.add(
                InstagramItem(
                    id = "profile_pic_${username}",
                    type = InstagramItemType.PROFILE_PIC,
                    title = "Profile Photo (Full Size)",
                    caption = "@$username full size profile picture",
                    url = url,
                    previewUrl = avatarUrl,
                    directMediaUrl = avatarUrl,
                    isVideo = false,
                    isAvailable = true
                )
            )
        }

        // Parse embedded JSON timeline data
        if (!isPrivate) {
            val timelineItems = parseTimelineFromHtml(html, username)
            items.addAll(timelineItems)
        }

        return ProfileFetchResult(profile, items)
    }

    /**
     * Extracts the real biography from page JSON.
     * og:description is strictly excluded — counts lines never become biography.
     */
    fun extractBiographyFromHtml(html: String): String {
        // 1. Try ld+json description
        val ldPattern = Pattern.compile("""<script\s+type="application/ld\+json"[^>]*>(.*?)</script>""", Pattern.DOTALL)
        val ldMatcher = ldPattern.matcher(html)
        while (ldMatcher.find()) {
            val ldBody = ldMatcher.group(1).trim()
            runCatching {
                val ldJson = json.parseToJsonElement(ldBody).jsonObject
                val desc = ldJson["description"]?.jsonPrimitive?.content
                    ?: ldJson["mainEntity"]?.jsonObject?.get("description")?.jsonPrimitive?.content
                if (!desc.isNullOrBlank() && !isCountsLine(desc)) {
                    return HtmlDecoder.decode(desc).trim()
                }
            }
        }

        // 2. Try embedded "biography":"..."
        val bioPattern = Pattern.compile(""""biography"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
        val bioMatcher = bioPattern.matcher(html)
        if (bioMatcher.find()) {
            val rawBio = bioMatcher.group(1)
            val unescaped = unescapeJsonString(rawBio)
            if (unescaped.isNotBlank() && !isCountsLine(unescaped)) {
                return HtmlDecoder.decode(unescaped).trim()
            }
        }

        // 3. Try "biography_with_entities"
        val bioEntitiesPattern = Pattern.compile(""""biography_with_entities"\s*:\s*\{\s*"raw_text"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
        val bioEntitiesMatcher = bioEntitiesPattern.matcher(html)
        if (bioEntitiesMatcher.find()) {
            val rawBio = bioEntitiesMatcher.group(1)
            val unescaped = unescapeJsonString(rawBio)
            if (unescaped.isNotBlank() && !isCountsLine(unescaped)) {
                return HtmlDecoder.decode(unescaped).trim()
            }
        }

        return ""
    }

    private fun isCountsLine(text: String): Boolean {
        return text.contains("Followers", ignoreCase = true) &&
                text.contains("Following", ignoreCase = true) &&
                text.contains("Posts", ignoreCase = true)
    }

    private fun unescapeJsonString(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\"' -> { sb.append('\"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        if (i + 6 <= raw.length) {
                            val hex = raw.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                            } else {
                                sb.append(c)
                                i++
                            }
                        } else {
                            sb.append(c)
                            i++
                        }
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Parses embedded timeline media and highlight reels from the profile HTML page JSON.
     */
    fun parseTimelineFromHtml(html: String, username: String): List<InstagramItem> {
        val items = mutableListOf<InstagramItem>()
        val seenCodes = mutableSetOf<String>()

        // 1. Look for application/json script tags
        val scriptPattern = Pattern.compile("""<script\s+type="application/json"[^>]*>(.*?)</script>""", Pattern.DOTALL)
        val scriptMatcher = scriptPattern.matcher(html)
        while (scriptMatcher.find()) {
            val body = scriptMatcher.group(1).trim()
            if (body.contains("\"edge_owner_to_timeline_media\"") ||
                body.contains("\"xdt_api__v1__profile_timeline\"") ||
                body.contains("\"xdt_api__v1__clips_user_connection\"") ||
                body.contains("\"shortcode\"")) {
                runCatching {
                    val root = json.parseToJsonElement(body)
                    findAndCollectMedia(root, username, items, seenCodes)
                }
            }
        }

        // 2. Also search for public highlights in the HTML
        val highlightPattern = Pattern.compile("""(?:highlight:|/stories/highlights/)(\d+)""")
        val highlightMatcher = highlightPattern.matcher(html)
        while (highlightMatcher.find()) {
            val hId = highlightMatcher.group(1)
            if (!isValidHighlightId(hId)) continue
            if (seenCodes.add("highlight_$hId")) {
                items.add(
                    InstagramItem(
                        id = "highlight_$hId",
                        shortcode = hId,
                        type = InstagramItemType.HIGHLIGHT,
                        title = "Highlight #$hId",
                        caption = "@$username story highlight",
                        url = "https://www.instagram.com/stories/highlights/$hId/",
                        isVideo = false,
                        isAvailable = false,
                        errorMessage = "Highlight media requires login and could not be resolved from public page data."
                    )
                )
            }
        }

        return items
    }

    private fun findAndCollectMedia(
        element: JsonElement,
        username: String,
        items: MutableList<InstagramItem>,
        seenCodes: MutableSet<String>
    ) {
        when (element) {
            is JsonObject -> {
                // If it's an edge container
                val timelineEdges = element["edge_owner_to_timeline_media"]?.jsonObject?.get("edges")?.jsonArray
                    ?: element["xdt_api__v1__profile_timeline"]?.jsonObject?.get("edges")?.jsonArray
                    ?: element["xdt_api__v1__clips_user_connection"]?.jsonObject?.get("edges")?.jsonArray

                if (timelineEdges != null) {
                    for (edge in timelineEdges) {
                        val node = edge.jsonObject["node"]?.jsonObject ?: edge.jsonObject
                        val parsed = parseMediaNode(node, username)
                        if (parsed != null && seenCodes.add(parsed.shortcode ?: parsed.id)) {
                            items.add(parsed)
                        }
                    }
                }

                // If element looks like a media node itself
                if (element.containsKey("shortcode") && (element.containsKey("display_url") || element.containsKey("display_uri") || element.containsKey("id"))) {
                    val parsed = parseMediaNode(element, username)
                    if (parsed != null && seenCodes.add(parsed.shortcode ?: parsed.id)) {
                        items.add(parsed)
                    }
                }

                for ((_, v) in element) {
                    findAndCollectMedia(v, username, items, seenCodes)
                }
            }
            is JsonArray -> {
                for (item in element) {
                    findAndCollectMedia(item, username, items, seenCodes)
                }
            }
            else -> {}
        }
    }

    private fun fetchItemViaHtml(url: String, link: ParsedInstagramLink): InstagramItem {
        val request = Request.Builder()
            .url(url)
            .apply { browserHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        val response = http.newCall(request).execute()
        val html = response.body?.string() ?: throw IOException("Empty HTML response")

        val titleMatch = Pattern.compile("""<meta\s+property="og:title"\s+content="([^"]*)"""", Pattern.CASE_INSENSITIVE).matcher(html)
        val rawTitle = if (titleMatch.find()) titleMatch.group(1) else "Instagram Media"
        val decodedTitle = HtmlDecoder.decode(rawTitle)

        val descMatch = Pattern.compile("""<meta\s+property="og:description"\s+content="([^"]*)"""", Pattern.CASE_INSENSITIVE).matcher(html)
        val rawDesc = if (descMatch.find()) descMatch.group(1) else ""
        val decodedDesc = HtmlDecoder.decode(rawDesc)

        val imageMatch = Pattern.compile("""<meta\s+property="og:image"\s+content="([^"]*)"""", Pattern.CASE_INSENSITIVE).matcher(html)
        val previewUrl = cleanUrl(if (imageMatch.find()) imageMatch.group(1) else null)

        val videoMatch = Pattern.compile("""<meta\s+property="og:video"\s+content="([^"]*)"""", Pattern.CASE_INSENSITIVE).matcher(html)
        val videoUrl = cleanUrl(if (videoMatch.find()) videoMatch.group(1) else null)

        // Try extracting likes and comments from description
        var likes: Long? = null
        var comments: Long? = null
        val likesMatcher = Pattern.compile("""([\d,.]+[KMkm]?)\s+likes?""", Pattern.CASE_INSENSITIVE).matcher(rawDesc)
        if (likesMatcher.find()) {
            likes = parseCount(likesMatcher.group(1))
        }
        val commentsMatcher = Pattern.compile("""([\d,.]+[KMkm]?)\s+comments?""", Pattern.CASE_INSENSITIVE).matcher(rawDesc)
        if (commentsMatcher.find()) {
            comments = parseCount(commentsMatcher.group(1))
        }

        val isVideo = (videoUrl != null) || (link.type == InstagramUrlType.REEL)
        val itemType = when {
            link.type == InstagramUrlType.REEL -> InstagramItemType.REEL
            link.type == InstagramUrlType.STORY -> InstagramItemType.STORY
            link.type == InstagramUrlType.HIGHLIGHT -> InstagramItemType.HIGHLIGHT
            isVideo -> InstagramItemType.VIDEO
            else -> InstagramItemType.PHOTO
        }

        val shortcode = link.shortcode ?: link.username ?: "item"

        return InstagramItem(
            id = shortcode,
            shortcode = link.shortcode,
            type = itemType,
            title = decodedTitle,
            caption = if (decodedDesc.isNotBlank()) decodedDesc else decodedTitle,
            url = url,
            previewUrl = previewUrl,
            directMediaUrl = videoUrl ?: previewUrl,
            isVideo = isVideo,
            isAvailable = true,
            likesCount = likes,
            commentsCount = comments,
            formattedDate = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date())
        )
    }

    fun createGracefulProfileFallback(username: String, reason: String): ProfileFetchResult {
        val profile = InstagramProfile(
            username = username,
            fullName = username,
            biography = "",
            avatarUrl = null,
            postsCount = 0,
            followersCount = 0,
            followingCount = 0,
            isPrivate = false,
            isVerified = false,
            fullSizeAvatarUrl = null
        )

        val directItem = InstagramItem(
            id = "direct_$username",
            shortcode = username,
            type = InstagramItemType.POST,
            title = "All Media for @$username",
            caption = "Download public profile content directly via yt-dlp.",
            url = "https://www.instagram.com/$username/",
            isVideo = false,
            isAvailable = true,
            errorMessage = reason
        )

        return ProfileFetchResult(
            profile = profile,
            items = listOf(directItem),
            blockedNotice = reason
        )
    }

    private fun formatTimestamp(epochSec: Long): String {
        return runCatching {
            val date = Date(epochSec * 1000L)
            SimpleDateFormat("MMM d, yyyy", Locale.US).format(date)
        }.getOrDefault("")
    }

    private fun parseCount(raw: String): Long {
        val s = raw.trim().replace(",", "")
        return when {
            s.endsWith("k", ignoreCase = true) -> {
                val num = s.dropLast(1).toDoubleOrNull() ?: 0.0
                (num * 1000).toLong()
            }
            s.endsWith("m", ignoreCase = true) -> {
                val num = s.dropLast(1).toDoubleOrNull() ?: 0.0
                (num * 1_000_000).toLong()
            }
            else -> s.toLongOrNull() ?: 0L
        }
    }
}
