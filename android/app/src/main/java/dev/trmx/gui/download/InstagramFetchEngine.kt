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
    val blockedNotice: String? = null
)

/**
 * Public, unauthenticated Instagram fetcher.
 * Queries public endpoints with standard browser headers to retrieve profile metadata,
 * posts, reels, and stories without requiring any login or user account.
 *
 * All HTML entities (such as &#x909;) are decoded strictly at the parse layer
 * so titles and captions never display raw encoded escape sequences.
 */
class InstagramFetchEngine(private val http: OkHttpClient) {

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

    suspend fun fetchProfile(username: String): ProfileFetchResult = withContext(Dispatchers.IO) {
        // Step 1: Attempt web_profile_info public API
        runCatching {
            fetchProfileViaApi(username)
        }.getOrElse { apiError ->
            // Step 2: Fallback to scraping public HTML page
            runCatching {
                fetchProfileViaHtml(username)
            }.getOrElse { htmlError ->
                // Step 3: Graceful degradation — create a fallback public profile
                // allowing direct yt-dlp downloads without ever crashing or asking for login.
                createGracefulProfileFallback(username, "Instagram public web gateway is rate-limited. Direct yt-dlp download is ready.")
            }
        }
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

    private fun fetchProfileViaApi(username: String): ProfileFetchResult {
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
        val avatarUrl = user["profile_pic_url_hd"]?.jsonPrimitive?.content
            ?: user["profile_pic_url"]?.jsonPrimitive?.content
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

    private fun parseMediaNode(node: JsonObject, username: String): InstagramItem? {
        val id = node["id"]?.jsonPrimitive?.content ?: return null
        val shortcode = node["shortcode"]?.jsonPrimitive?.content ?: id
        val typename = node["__typename"]?.jsonPrimitive?.content ?: ""
        val isVideo = node["is_video"]?.jsonPrimitive?.booleanOrNull ?: false
        val displayUrl = node["display_url"]?.jsonPrimitive?.content
        val videoUrl = node["video_url"]?.jsonPrimitive?.content
        val rawCaption = node["edge_media_to_caption"]?.jsonObject?.get("edges")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("node")?.jsonObject?.get("text")
            ?.jsonPrimitive?.content ?: ""
        val caption = HtmlDecoder.decode(rawCaption)
        val timestamp = node["taken_at_timestamp"]?.jsonPrimitive?.longOrNull
        val dim = node["dimensions"]?.jsonObject
        val width = dim?.get("width")?.jsonPrimitive?.content?.toIntOrNull()
        val height = dim?.get("height")?.jsonPrimitive?.content?.toIntOrNull()

        val likes = node["edge_liked_by"]?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull
            ?: node["edge_media_preview_like"]?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull
        val comments = node["edge_media_to_comment"]?.jsonObject?.get("count")?.jsonPrimitive?.longOrNull

        // Carousel items (GraphSidecar)
        val sidecarEdges = node["edge_sidecar_to_children"]?.jsonObject?.get("edges")?.jsonArray
        val carouselChildren = mutableListOf<InstagramItem>()
        if (sidecarEdges != null) {
            for ((index, sidecarEdge) in sidecarEdges.withIndex()) {
                val childNode = sidecarEdge.jsonObject["node"]?.jsonObject ?: continue
                val cId = childNode["id"]?.jsonPrimitive?.content ?: "${id}_$index"
                val cDisplay = childNode["display_url"]?.jsonPrimitive?.content
                val cVideo = childNode["video_url"]?.jsonPrimitive?.content
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

    private fun fetchProfileViaHtml(username: String): ProfileFetchResult {
        val url = "https://www.instagram.com/$username/"
        val request = Request.Builder()
            .url(url)
            .apply { browserHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        val response = http.newCall(request).execute()
        val html = response.body?.string() ?: throw IOException("Empty HTML response")

        val titleMatch = Pattern.compile("""<meta\s+property="og:title"\s+content="([^"]*)"""", Pattern.CASE_INSENSITIVE).matcher(html)
        val rawTitle = if (titleMatch.find()) titleMatch.group(1) else username

        val descMatch = Pattern.compile("""<meta\s+property="og:description"\s+content="([^"]*)"""", Pattern.CASE_INSENSITIVE).matcher(html)
        val rawDesc = if (descMatch.find()) descMatch.group(1) else ""

        val imageMatch = Pattern.compile("""<meta\s+property="og:image"\s+content="([^"]*)"""", Pattern.CASE_INSENSITIVE).matcher(html)
        val avatarUrl = if (imageMatch.find()) imageMatch.group(1) else null

        // Parse followers/posts from og:description (e.g. "12K Followers, 300 Following, 45 Posts")
        var followers = 0L
        var following = 0L
        var posts = 0L
        val statsPattern = Pattern.compile("""([\d,.]+[KMkm]?)\s+Followers,\s+([\d,.]+[KMkm]?)\s+Following,\s+([\d,.]+[KMkm]?)\s+Posts""")
        val statsMatcher = statsPattern.matcher(rawDesc)
        if (statsMatcher.find()) {
            followers = parseCount(statsMatcher.group(1))
            following = parseCount(statsMatcher.group(2))
            posts = parseCount(statsMatcher.group(3))
        }

        val isPrivate = html.contains("This Account is Private") || html.contains("\"is_private\":true")

        // Decode HTML entities at parse layer
        val decodedFullName = HtmlDecoder.decode(rawTitle.substringBefore("(@").trim().ifBlank { username })
        val decodedBio = HtmlDecoder.decode(rawDesc)

        val profile = InstagramProfile(
            username = username,
            fullName = decodedFullName,
            biography = decodedBio,
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

        return ProfileFetchResult(profile, items)
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
        val previewUrl = if (imageMatch.find()) imageMatch.group(1) else null

        val videoMatch = Pattern.compile("""<meta\s+property="og:video"\s+content="([^"]*)"""", Pattern.CASE_INSENSITIVE).matcher(html)
        val videoUrl = if (videoMatch.find()) videoMatch.group(1) else null

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

    private fun createGracefulProfileFallback(username: String, reason: String): ProfileFetchResult {
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
