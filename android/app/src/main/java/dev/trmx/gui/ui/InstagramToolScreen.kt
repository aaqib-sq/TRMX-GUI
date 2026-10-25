package dev.trmx.gui.ui

/*
 * Instagram Tool Screen:
 * 1. Dedicated tool page (Reels Downloader, Photo, Video, IGTV, Carousel)
 * 2. Link input + action button + "downloading… please wait" loading state
 * 3. Profile result: header (avatar, handle, name, bio, counts, private badge)
 *    Notice line: "Posts, stories and highlights can't be shown without an account. Link downloads still work."
 *    "Download profile photo" button
 * 4. Playable video preview overlay (MANDATORY): play/pause, time readout + scrubber, volume, fullscreen,
 *    more options, and download button in overlay. Photo preview overlay with download button.
 * 5. Specific-item result: still cover thumbnail with play control, date + likes + comments, ONE Download button.
 * 6. NO quality/format selectors here (they live strictly in the Downloader).
 * 7. No login prompts anywhere. No ads anywhere.
 */

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.trmx.gui.download.DownloadViewModel
import dev.trmx.gui.download.InstagramItem
import dev.trmx.gui.download.InstagramItemType
import dev.trmx.gui.download.InstagramPreviewGate
import dev.trmx.gui.download.InstagramProfile
import dev.trmx.gui.download.InstagramTool
import dev.trmx.gui.ui.Tokens.Palette as P
import dev.trmx.gui.ui.Tokens.Radius as R
import dev.trmx.gui.ui.Tokens.Space as Sp
import kotlinx.coroutines.delay

@Composable
fun InstagramToolScreen(
    tool: InstagramTool,
    viewModel: DownloadViewModel,
    onOpenDownloader: (InstagramItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val inputUrl by viewModel.inputUrl.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val singleItem by viewModel.singleItem.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val activePreviewItem by viewModel.activePreviewItem.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    BackHandler {
        if (activePreviewItem != null) {
            viewModel.closePreview()
        } else {
            onBack()
        }
    }

    Box(modifier = modifier.fillMaxSize().background(P.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Sp.m, vertical = Sp.s),
            verticalArrangement = Arrangement.spacedBy(Sp.m)
        ) {
            // ---- Link Input Card -------------------------------------------
            TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Text(
                        text = "Enter Instagram Link or Handle",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = P.TextPrimary
                    )

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = viewModel::onUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                tool.placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = P.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            if (inputUrl.isNotBlank()) {
                                IconButton(onClick = viewModel::clearUrl) {
                                    Icon(Icons.Outlined.Clear, contentDescription = "clear", tint = P.TextMuted)
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = P.TextPrimary),
                        shape = RoundedCornerShape(R.m),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = P.BorderStrong,
                            unfocusedBorderColor = P.Border,
                            focusedContainerColor = P.FieldFill,
                            unfocusedContainerColor = P.FieldFill,
                            cursorColor = P.Accent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { viewModel.fetchUrl(tool) })
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Sp.s)
                    ) {
                        TrmxButton(
                            label = "Paste",
                            leading = { Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                                val pasted = clip?.getItemAt(0)?.text?.toString() ?: ""
                                if (pasted.isNotBlank()) viewModel.onPaste(pasted)
                            },
                            kind = TrmxButtonKind.Secondary,
                            modifier = Modifier.weight(1f).height(46.dp)
                        )

                        TrmxButton(
                            label = tool.actionButtonLabel,
                            leading = { Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = { viewModel.fetchUrl(tool) },
                            kind = TrmxButtonKind.Primary,
                            enabled = inputUrl.isNotBlank() && !isLoading,
                            modifier = Modifier.weight(1.5f).height(46.dp)
                        )
                    }
                }
            }

            // ---- Loading State (Verified site behavior: "downloading… please wait") ----
            if (isLoading) {
                TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.l) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Sp.s)
                    ) {
                        CircularProgressIndicator(
                            color = P.Accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "downloading… please wait",
                            style = MaterialTheme.typography.bodyMedium,
                            color = P.TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // ---- Errors & Notices ------------------------------------------
            error?.let { err ->
                TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = P.Danger)
                        Spacer(Modifier.width(Sp.s))
                        Text(err, color = P.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = viewModel::clearError) {
                            Icon(Icons.Outlined.Clear, contentDescription = "dismiss", tint = P.TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            notice?.let { not ->
                TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = P.TextPrimary)
                        Spacer(Modifier.width(Sp.s))
                        Text(not, color = P.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = viewModel::clearNotice) {
                            Icon(Icons.Outlined.Clear, contentDescription = "dismiss", tint = P.TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // ---- Profile Result View ---------------------------------------
            profile?.let { prof ->
                ProfileHeaderCard(profile = prof)

                Text(
                    text = "Posts, stories and highlights can't be shown without an account. Link downloads still work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextMuted,
                    modifier = Modifier.padding(horizontal = Sp.xs)
                )

                val enginePic = items.firstOrNull { it.type == InstagramItemType.PROFILE_PIC }
                val avatarDirect = prof.fullSizeAvatarUrl ?: prof.avatarUrl
                val profilePicItem = enginePic?.let { pic ->
                    if (pic.url.isNotBlank() && !pic.url.contains("instagram.com/")) pic
                    else pic.copy(url = pic.directMediaUrl ?: avatarDirect ?: pic.url)
                } ?: InstagramItem(
                    id = "profile_pic_${prof.username}",
                    type = InstagramItemType.PROFILE_PIC,
                    title = "@${prof.username}",
                    url = avatarDirect ?: "https://www.instagram.com/${prof.username}/",
                    previewUrl = avatarDirect,
                    directMediaUrl = avatarDirect,
                    isVideo = false,
                    isAvailable = true
                )

                // Hide when no avatar media resolved (e.g. private profile with no avatar
                // URL): a button with nowhere to download from would only fail in Downloader.
                if (profilePicItem.hasResolvedMedia) {
                    TrmxButton(
                        label = "Download profile photo",
                        leading = { Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        onClick = { onOpenDownloader(profilePicItem) },
                        kind = TrmxButtonKind.Primary,
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    )
                }
            }

            // ---- Specific Item Result View (Single Item) -------------------
            singleItem?.let { item ->
                Text(
                    text = "Result",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = P.TextSecondary
                )

                if (item.type == InstagramItemType.CAROUSEL && item.carouselItems.isNotEmpty()) {
                    // Carousel View: Replicate behavior (shows ALL its items)
                    CarouselMultiItemCard(
                        item = item,
                        onPreview = viewModel::openPreview,
                        onDownloadItem = onOpenDownloader,
                        onDownloadAll = { onOpenDownloader(item.copy(type = InstagramItemType.CAROUSEL)) }
                    )
                } else {
                    // Standard Specific-Item Result Card: still thumbnail with play control, date+likes+comments, ONE Download button
                    SpecificItemCard(
                        item = item,
                        onPreview = { viewModel.openPreview(item) },
                        onDownload = { onOpenDownloader(item) }
                    )
                }
            }

            Spacer(Modifier.height(Sp.xl))
        }

        // ---- MANDATORY PLAYABLE PREVIEW OVERLAY ----------------------------
        activePreviewItem?.let { previewItem ->
            val decision = InstagramPreviewGate.evaluate(previewItem)
            if (decision.canOpen && decision.isVideoOverlay) {
                PlayableVideoPreviewOverlay(
                    item = previewItem,
                    onClose = viewModel::closePreview,
                    onDownload = {
                        viewModel.closePreview()
                        onOpenDownloader(previewItem)
                    }
                )
            } else if (decision.canOpen && !decision.isVideoOverlay) {
                FullPhotoPreviewOverlay(
                    item = previewItem,
                    onClose = viewModel::closePreview,
                    onDownload = {
                        viewModel.closePreview()
                        onOpenDownloader(previewItem)
                    }
                )
            }
        }
    }
}

// ============================================================================
// PROFILE COMPONENTS
// ============================================================================

@Composable
private fun ProfileHeaderCard(profile: InstagramProfile) {
    TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Circular Avatar
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(P.SurfaceHigh)
                ) {
                    RemoteImage(
                        url = profile.avatarUrl,
                        contentDescription = profile.username,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        fallbackText = profile.fullName.ifBlank { profile.username }
                    )
                }

                Spacer(Modifier.width(Sp.m))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.fullName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = P.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@${profile.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = P.TextSecondary
                    )
                    if (profile.isPrivate) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, tint = P.Danger, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Private Account", style = MaterialTheme.typography.labelSmall, color = P.Danger)
                        }
                    }
                }
            }

            if (profile.biography.isNotBlank()) {
                Text(
                    text = profile.biography,
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Stats row (Posts / Followers / Following)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(P.FieldFill, RoundedCornerShape(R.s))
                    .padding(vertical = Sp.s, horizontal = Sp.m),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                StatColumn(label = "Posts", value = formatCount(profile.postsCount))
                StatColumn(label = "Followers", value = formatCount(profile.followersCount))
                StatColumn(label = "Following", value = formatCount(profile.followingCount))
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = P.TextPrimary)
        Text(label, fontSize = 11.sp, color = P.TextMuted)
    }
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000.0)
    else -> count.toString()
}

// ============================================================================
// SPECIFIC ITEM RESULT (SINGLE RESULT CARD)
// ============================================================================

@Composable
private fun SpecificItemCard(
    item: InstagramItem,
    onPreview: () -> Unit,
    onDownload: () -> Unit
) {
    TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            // Still thumbnail (cover image) with a play control that opens playable video player
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.2f)
                    .clip(RoundedCornerShape(R.m))
                    .background(P.SurfaceHigh)
                    .clickable(enabled = item.hasResolvedMedia, onClick = onPreview),
                contentAlignment = Alignment.Center
            ) {
                RemoteImage(
                    url = item.previewUrl ?: item.directMediaUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    fallbackText = if (item.type == InstagramItemType.PROFILE_PIC) item.title else null
                )

                if ((item.isVideo || item.type == InstagramItemType.REEL || item.type == InstagramItemType.VIDEO) && item.hasResolvedMedia) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xBB000000),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play preview", tint = P.TextPrimary, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }

            // Date + likes + comments shown on the card
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Sp.m),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item.formattedDate?.let { dateStr ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.DateRange, contentDescription = null, tint = P.TextMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(dateStr, fontSize = 12.sp, color = P.TextMuted)
                    }
                }
                item.likesCount?.let { likes ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = P.TextMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(formatCount(likes), fontSize = 12.sp, color = P.TextMuted)
                    }
                }
                item.commentsCount?.let { comments ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = P.TextMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(formatCount(comments), fontSize = 12.sp, color = P.TextMuted)
                    }
                }
            }

            // Decoded title and caption
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = P.TextPrimary
            )

            if (item.caption.isNotBlank() && item.caption != item.title) {
                Text(
                    text = item.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ONE Download button (NO selectors here, opens Downloader!)
            val canDownload = item.isDownloadable
            TrmxButton(
                label = if (canDownload) "Download" else "Unavailable",
                leading = { Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp)) },
                onClick = onDownload,
                enabled = canDownload,
                kind = TrmxButtonKind.Primary,
                modifier = Modifier.fillMaxWidth().height(46.dp)
            )
        }
    }
}

// ============================================================================
// CAROUSEL VIEW (SHOWS ALL ITEMS)
// ============================================================================

@Composable
private fun CarouselMultiItemCard(
    item: InstagramItem,
    onPreview: (InstagramItem) -> Unit,
    onDownloadItem: (InstagramItem) -> Unit,
    onDownloadAll: () -> Unit
) {
    TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            Text(
                text = "Carousel Post (${item.carouselItems.size} items)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = P.TextPrimary
            )

            if (item.caption.isNotBlank()) {
                Text(
                    text = item.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            TrmxButton(
                label = "Download All Slides (${item.carouselItems.size})",
                leading = { Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp)) },
                onClick = onDownloadAll,
                kind = TrmxButtonKind.Primary,
                modifier = Modifier.fillMaxWidth().height(44.dp)
            )

            Spacer(Modifier.height(Sp.xs))

            Text(
                text = "Individual Slides:",
                style = MaterialTheme.typography.labelMedium,
                color = P.TextMuted,
                fontWeight = FontWeight.SemiBold
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Sp.s),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(item.carouselItems) { slide ->
                    TrmxCard(
                        modifier = Modifier.width(160.dp),
                        contentPadding = Sp.s
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(R.s))
                                    .background(P.SurfaceHigh)
                                    .clickable { onPreview(slide) },
                                contentAlignment = Alignment.Center
                            ) {
                                RemoteImage(
                                    url = slide.previewUrl ?: slide.directMediaUrl,
                                    contentDescription = slide.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                if (slide.isVideo) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = P.TextPrimary)
                                }
                            }
                            Text(
                                text = slide.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = P.TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            TrmxButton(
                                label = "Download",
                                leading = { Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(12.dp)) },
                                onClick = { onDownloadItem(slide) },
                                kind = TrmxButtonKind.Secondary,
                                modifier = Modifier.fillMaxWidth().height(32.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// MANDATORY PLAYABLE PREVIEW OVERLAY (VIDEO + PHOTO)
// ============================================================================

@Composable
fun PlayableVideoPreviewOverlay(
    item: InstagramItem,
    onClose: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(1000L) }
    var isMuted by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }

    val mediaUri = item.directMediaUrl ?: ""

    // Position poller while video plays
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(250)
            videoViewRef?.let { vv ->
                currentPositionMs = vv.currentPosition.toLong().coerceAtLeast(0L)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            videoViewRef?.stopPlayback()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0000000))
            .clickable(onClick = onClose)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreen) 0.dp else Sp.m)
                .clickable(enabled = false) {}, // consume clicks so overlay background closes
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Controls Bar: Title + Close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Sp.s, vertical = Sp.s),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = P.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close preview", tint = P.TextPrimary)
                }
            }

            // Embedded Video Player
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(Uri.parse(mediaUri))
                            setOnPreparedListener { mp ->
                                mediaPlayerRef = mp
                                mp.isLooping = true
                                durationMs = mp.duration.toLong().coerceAtLeast(1000L)
                                if (isMuted) mp.setVolume(0f, 0f) else mp.setVolume(1f, 1f)
                                if (isPlaying) start()
                            }
                            setOnCompletionListener {
                                isPlaying = false
                            }
                            videoViewRef = this
                        }
                    },
                    update = { view ->
                        videoViewRef = view
                        if (isPlaying && !view.isPlaying) {
                            view.start()
                        } else if (!isPlaying && view.isPlaying) {
                            view.pause()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (isFullscreen) 16f / 9f else 9f / 16f)
                        .clip(RoundedCornerShape(if (isFullscreen) 0.dp else R.m))
                )

                // Play / Pause central toggle on tap
                if (!isPlaying) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0x99000000),
                        modifier = Modifier
                            .size(64.dp)
                            .clickable {
                                isPlaying = true
                                videoViewRef?.start()
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }
                }
            }

            // Bottom Player Controls
            Surface(
                shape = RoundedCornerShape(R.m),
                color = P.Surface,
                border = BorderStroke(1.dp, P.Border),
                modifier = Modifier.fillMaxWidth().padding(Sp.s)
            ) {
                Column(
                    modifier = Modifier.padding(Sp.s),
                    verticalArrangement = Arrangement.spacedBy(Sp.xs)
                ) {
                    // Time readout + scrubber slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTimeMs(currentPositionMs),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = P.TextSecondary
                        )

                        Slider(
                            value = currentPositionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                            onValueChange = { targetPos ->
                                currentPositionMs = targetPos.toLong()
                                videoViewRef?.seekTo(targetPos.toInt())
                            },
                            valueRange = 0f..durationMs.toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = Sp.s),
                            colors = SliderDefaults.colors(
                                thumbColor = P.Accent,
                                activeTrackColor = P.Accent,
                                inactiveTrackColor = P.BorderStrong
                            )
                        )

                        Text(
                            text = formatTimeMs(durationMs),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = P.TextSecondary
                        )
                    }

                    // Bottom Action Row: Play/Pause, Volume, Fullscreen, More, Download
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                isPlaying = !isPlaying
                                if (isPlaying) videoViewRef?.start() else videoViewRef?.pause()
                            }) {
                                Icon(
                                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = "play/pause",
                                    tint = P.TextPrimary
                                )
                            }

                            IconButton(onClick = {
                                isMuted = !isMuted
                                mediaPlayerRef?.let { mp ->
                                    if (isMuted) mp.setVolume(0f, 0f) else mp.setVolume(1f, 1f)
                                }
                            }) {
                                Icon(
                                    if (isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                                    contentDescription = "volume",
                                    tint = P.TextSecondary
                                )
                            }

                            IconButton(onClick = { isFullscreen = !isFullscreen }) {
                                Icon(
                                    if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                    contentDescription = "fullscreen",
                                    tint = P.TextSecondary
                                )
                            }

                            Box {
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "more options", tint = P.TextSecondary)
                                }
                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Copy Link") },
                                        onClick = {
                                            showMoreMenu = false
                                            val clip = context.getSystemService(ClipboardManager::class.java)
                                            clip?.setPrimaryClip(android.content.ClipData.newPlainText("Instagram URL", item.url))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Open in Browser") },
                                        onClick = {
                                            showMoreMenu = false
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                                        }
                                    )
                                }
                            }
                        }

                        // Download button in the overlay (opens Downloader!)
                        TrmxButton(
                            label = "Download",
                            leading = { Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            onClick = onDownload,
                            kind = TrmxButtonKind.Primary,
                            modifier = Modifier.height(38.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FullPhotoPreviewOverlay(
    item: InstagramItem,
    onClose: () -> Unit,
    onDownload: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0000000))
            .clickable(onClick = onClose)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Sp.m)
                .clickable(enabled = false) {},
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = P.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close preview", tint = P.TextPrimary)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                RemoteImage(
                    url = item.previewUrl ?: item.directMediaUrl ?: item.url,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(R.m)),
                    contentScale = ContentScale.Fit
                )
            }

            Surface(
                shape = RoundedCornerShape(R.m),
                color = P.Surface,
                border = BorderStroke(1.dp, P.Border),
                modifier = Modifier.fillMaxWidth().padding(top = Sp.s)
            ) {
                Row(
                    modifier = Modifier.padding(Sp.m),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item.caption.ifBlank { "Full resolution photo" },
                        style = MaterialTheme.typography.bodySmall,
                        color = P.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = Sp.s)
                    )

                    TrmxButton(
                        label = "Download",
                        leading = { Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        onClick = onDownload,
                        kind = TrmxButtonKind.Primary,
                        modifier = Modifier.height(38.dp)
                    )
                }
            }
        }
    }
}

private fun formatTimeMs(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
}
