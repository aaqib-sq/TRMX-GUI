package dev.trmx.gui.ui

/*
 * NETWORK USE BOUNDARY:
 * Unlike the App Killer package (which enforces a strict zero-network rule),
 * the Instagram Downloader uses network ONLY for URLs explicitly pasted or
 * tapped by the user. There is NO background fetching, NO tracking, NO telemetry,
 * and NO unprompted network calls. All history stays strictly on-device.
 */

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.trmx.gui.download.AudioQuality
import dev.trmx.gui.download.DownloadOption
import dev.trmx.gui.download.DownloadRecord
import dev.trmx.gui.download.DownloadTab
import dev.trmx.gui.download.DownloadViewModel
import dev.trmx.gui.download.InstagramItem
import dev.trmx.gui.download.InstagramItemType
import dev.trmx.gui.download.InstagramProfile
import dev.trmx.gui.download.MediaMode
import dev.trmx.gui.download.VideoQuality
import dev.trmx.gui.ui.Tokens.Palette as P
import dev.trmx.gui.ui.Tokens.Radius as R
import dev.trmx.gui.ui.Tokens.Space as Sp

@Composable
fun DownloadScreen(
    viewModel: DownloadViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val inputUrl by viewModel.inputUrl.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val singleItem by viewModel.singleItem.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val mediaMode by viewModel.mediaMode.collectAsStateWithLifecycle()
    val videoQuality by viewModel.videoQuality.collectAsStateWithLifecycle()
    val audioQuality by viewModel.audioQuality.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(P.Background)
            .padding(horizontal = Sp.m),
        verticalArrangement = Arrangement.spacedBy(Sp.m)
    ) {
        // ---- Top Spacing ---------------------------------------------------
        item(key = "top_spacer") {
            Spacer(Modifier.height(Sp.xs))
        }

        // ---- Link Input Field + Paste / Inspect -----------------------------
        item(key = "link_input") {
            LinkInputCard(
                inputUrl = inputUrl,
                isLoading = isLoading,
                onUrlChange = viewModel::onUrlChange,
                onPaste = {
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    val pasted = clip?.getItemAt(0)?.text?.toString() ?: ""
                    if (pasted.isNotBlank()) viewModel.onPaste(pasted)
                },
                onClear = viewModel::clearUrl,
                onInspect = viewModel::fetchUrl
            )
        }

        // ---- Upfront Controls: Video/MP3 Mode + Quality ---------------------
        item(key = "upfront_controls") {
            UpfrontFormatCard(
                mediaMode = mediaMode,
                videoQuality = videoQuality,
                audioQuality = audioQuality,
                onModeChange = viewModel::setMediaMode,
                onVideoQualityChange = viewModel::setVideoQuality,
                onAudioQualityChange = viewModel::setAudioQuality
            )
        }

        // ---- Notices / Errors ----------------------------------------------
        if (error != null) {
            item(key = "error_banner") {
                BannerMessage(
                    message = error ?: "",
                    isError = true,
                    onDismiss = viewModel::clearError
                )
            }
        }

        if (notice != null) {
            item(key = "notice_banner") {
                BannerMessage(
                    message = notice ?: "",
                    isError = false,
                    onDismiss = viewModel::clearNotice
                )
            }
        }

        // ---- Loading Indicator ---------------------------------------------
        if (isLoading) {
            item(key = "loading_indicator") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Sp.l),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = P.Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // ---- Profile Result View -------------------------------------------
        profile?.let { prof ->
            item(key = "profile_header") {
                ProfileHeaderCard(
                    profile = prof,
                    onDownloadProfilePic = {
                        val picItem = items.firstOrNull { it.type == InstagramItemType.PROFILE_PIC }
                            ?: InstagramItem(
                                id = "profile_pic_${prof.username}",
                                type = InstagramItemType.PROFILE_PIC,
                                title = "Profile Photo (Full Size)",
                                url = "https://www.instagram.com/${prof.username}/"
                            )
                        viewModel.downloadItem(picItem, DownloadOption.MEDIA)
                    }
                )
            }

            item(key = "profile_tabs") {
                ProfileTabs(
                    selectedTab = selectedTab,
                    onSelectTab = viewModel::setTab
                )
            }

            // If private account: show clean message, never ask for login
            if (prof.isPrivate) {
                item(key = "private_profile_notice") {
                    PrivateProfileNotice()
                }
            } else {
                // Tab-specific items
                when (selectedTab) {
                    DownloadTab.STORIES -> {
                        item(key = "stories_header") {
                            AnonymousStoriesBanner()
                        }
                        val storyItems = items.filter { it.type == InstagramItemType.STORY }
                        if (storyItems.isEmpty()) {
                            item(key = "stories_empty") {
                                EmptyTabNotice("No active public stories at this moment.")
                            }
                        } else {
                            items(storyItems, key = { it.id }) { item ->
                                MediaItemCard(
                                    item = item,
                                    mediaMode = mediaMode,
                                    videoQuality = videoQuality,
                                    audioQuality = audioQuality,
                                    onDownload = { opt -> viewModel.downloadItem(item, opt) },
                                    onDownloadAllCarousel = { viewModel.downloadCarouselAll(item) },
                                    onDownloadCover = { viewModel.downloadCover(item) }
                                )
                            }
                        }
                    }

                    DownloadTab.HIGHLIGHTS -> {
                        val highlightItems = items.filter { it.type == InstagramItemType.HIGHLIGHT }
                        if (highlightItems.isEmpty()) {
                            item(key = "highlights_empty") {
                                EmptyTabNotice("No public highlights found.")
                            }
                        } else {
                            items(highlightItems, key = { it.id }) { item ->
                                MediaItemCard(
                                    item = item,
                                    mediaMode = mediaMode,
                                    videoQuality = videoQuality,
                                    audioQuality = audioQuality,
                                    onDownload = { opt -> viewModel.downloadItem(item, opt) },
                                    onDownloadAllCarousel = { viewModel.downloadCarouselAll(item) },
                                    onDownloadCover = { viewModel.downloadCover(item) }
                                )
                            }
                        }
                    }

                    DownloadTab.REELS -> {
                        val reelItems = items.filter { it.type == InstagramItemType.REEL }
                        if (reelItems.isEmpty()) {
                            item(key = "reels_empty") {
                                EmptyTabNotice("No public reels found.")
                            }
                        } else {
                            items(reelItems, key = { it.id }) { item ->
                                MediaItemCard(
                                    item = item,
                                    mediaMode = mediaMode,
                                    videoQuality = videoQuality,
                                    audioQuality = audioQuality,
                                    onDownload = { opt -> viewModel.downloadItem(item, opt) },
                                    onDownloadAllCarousel = { viewModel.downloadCarouselAll(item) },
                                    onDownloadCover = { viewModel.downloadCover(item) }
                                )
                            }
                        }
                    }

                    DownloadTab.POSTS -> {
                        // Per-type lists shown UPFRONT (better than reference)
                        val postItems = items.filter { it.type != InstagramItemType.PROFILE_PIC }
                        if (postItems.isEmpty()) {
                            item(key = "posts_empty") {
                                EmptyTabNotice("No public posts found.")
                            }
                        } else {
                            items(postItems, key = { it.id }) { item ->
                                MediaItemCard(
                                    item = item,
                                    mediaMode = mediaMode,
                                    videoQuality = videoQuality,
                                    audioQuality = audioQuality,
                                    onDownload = { opt -> viewModel.downloadItem(item, opt) },
                                    onDownloadAllCarousel = { viewModel.downloadCarouselAll(item) },
                                    onDownloadCover = { viewModel.downloadCover(item) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Single Item Result View ---------------------------------------
        singleItem?.let { item ->
            item(key = "single_item_header") {
                Text(
                    text = "Result",
                    style = MaterialTheme.typography.titleSmall,
                    color = P.TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }

            item(key = "single_item_card") {
                MediaItemCard(
                    item = item,
                    mediaMode = mediaMode,
                    videoQuality = videoQuality,
                    audioQuality = audioQuality,
                    onDownload = { opt -> viewModel.downloadItem(item, opt) },
                    onDownloadAllCarousel = { viewModel.downloadCarouselAll(item) },
                    onDownloadCover = { viewModel.downloadCover(item) }
                )
            }
        }

        // ---- Active Downloads Section --------------------------------------
        if (activeDownloads.isNotEmpty()) {
            item(key = "active_downloads_header") {
                Text(
                    text = "Active Downloads",
                    style = MaterialTheme.typography.titleSmall,
                    color = P.TextSecondary,
                    fontWeight = FontWeight.Bold
                )
            }

            items(activeDownloads.values.toList(), key = { "active_${it.id}" }) { record ->
                ActiveDownloadCard(record = record)
            }
        }

        // ---- On-Device Download History Section ----------------------------
        item(key = "history_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Download History",
                    style = MaterialTheme.typography.titleSmall,
                    color = P.TextSecondary,
                    fontWeight = FontWeight.Bold
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearHistory) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "clear history",
                            tint = P.TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Sp.xs))
                        Text(
                            text = "Clear",
                            style = MaterialTheme.typography.labelSmall,
                            color = P.TextMuted
                        )
                    }
                }
            }
        }

        if (history.isEmpty()) {
            item(key = "history_empty") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(R.m),
                    colors = CardDefaults.cardColors(containerColor = P.Surface),
                    border = BorderStroke(1.dp, P.Border)
                ) {
                    Text(
                        text = "No downloads yet. Downloads are saved to ~/downloads.",
                        style = MaterialTheme.typography.bodySmall,
                        color = P.TextMuted,
                        modifier = Modifier.padding(Sp.m)
                    )
                }
            }
        } else {
            items(history, key = { "hist_${it.id}" }) { record ->
                HistoryRecordCard(
                    record = record,
                    onRetry = { viewModel.retryDownload(record.id) }
                )
            }
        }

        // Bottom padding
        item(key = "bottom_spacer") {
            Spacer(Modifier.height(Sp.l))
        }
    }
}

// ----------------------------------------------------------------------------
// Components
// ----------------------------------------------------------------------------

@Composable
private fun LinkInputCard(
    inputUrl: String,
    isLoading: Boolean,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onInspect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(R.l),
        colors = CardDefaults.cardColors(containerColor = P.Surface),
        border = BorderStroke(1.dp, P.Border)
    ) {
        Column(modifier = Modifier.padding(Sp.m)) {
            OutlinedTextField(
                value = inputUrl,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        "Paste Instagram link or handle…",
                        color = P.TextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(R.m),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = P.FieldFill,
                    unfocusedContainerColor = P.FieldFill,
                    focusedBorderColor = P.BorderStrong,
                    unfocusedBorderColor = P.Border,
                    focusedTextColor = P.TextPrimary,
                    unfocusedTextColor = P.TextPrimary,
                    cursorColor = P.Accent
                ),
                trailingIcon = {
                    if (inputUrl.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Outlined.Clear, contentDescription = "clear", tint = P.TextMuted)
                        }
                    }
                }
            )

            Spacer(Modifier.height(Sp.s))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Sp.s)
            ) {
                OutlinedButton(
                    onClick = onPaste,
                    shape = RoundedCornerShape(R.m),
                    border = BorderStroke(1.dp, P.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = P.TextPrimary),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Outlined.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = P.TextPrimary
                    )
                    Spacer(Modifier.width(Sp.xs))
                    Text("Paste", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = onInspect,
                    enabled = !isLoading && inputUrl.isNotBlank(),
                    shape = RoundedCornerShape(R.m),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = P.Accent,
                        contentColor = P.OnAccent,
                        disabledContainerColor = P.SurfaceHigh,
                        disabledContentColor = P.TextMuted
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (isLoading) "Loading…" else "Inspect",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun UpfrontFormatCard(
    mediaMode: MediaMode,
    videoQuality: VideoQuality,
    audioQuality: AudioQuality,
    onModeChange: (MediaMode) -> Unit,
    onVideoQualityChange: (VideoQuality) -> Unit,
    onAudioQualityChange: (AudioQuality) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(R.l),
        colors = CardDefaults.cardColors(containerColor = P.Surface),
        border = BorderStroke(1.dp, P.Border)
    ) {
        Column(modifier = Modifier.padding(Sp.m)) {
            // Mode toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Download Mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = P.TextSecondary,
                    fontWeight = FontWeight.Bold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    FilterChip(
                        selected = mediaMode == MediaMode.VIDEO,
                        onClick = { onModeChange(MediaMode.VIDEO) },
                        label = { Text("Video", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(Icons.Outlined.PlayCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = P.Accent,
                            selectedLabelColor = P.OnAccent,
                            selectedLeadingIconColor = P.OnAccent,
                            containerColor = P.FieldFill,
                            labelColor = P.TextSecondary
                        ),
                        border = BorderStroke(1.dp, if (mediaMode == MediaMode.VIDEO) P.Accent else P.Border)
                    )

                    FilterChip(
                        selected = mediaMode == MediaMode.MP3,
                        onClick = { onModeChange(MediaMode.MP3) },
                        label = { Text("MP3 Mode", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(14.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = P.Accent,
                            selectedLabelColor = P.OnAccent,
                            selectedLeadingIconColor = P.OnAccent,
                            containerColor = P.FieldFill,
                            labelColor = P.TextSecondary
                        ),
                        border = BorderStroke(1.dp, if (mediaMode == MediaMode.MP3) P.Accent else P.Border)
                    )
                }
            }

            Text(
                text = if (mediaMode == MediaMode.MP3)
                    "MP3 mode extracts audio only (no video bytes wasted)"
                else
                    "Video downloads in original MP4 quality up to 1080p",
                style = MaterialTheme.typography.bodySmall,
                color = P.TextMuted,
                modifier = Modifier.padding(top = Sp.xs, bottom = Sp.s)
            )

            HorizontalDivider(color = P.Border, thickness = 1.dp)
            Spacer(Modifier.height(Sp.s))

            // Upfront Quality choices
            Text(
                text = if (mediaMode == MediaMode.VIDEO) "Resolution Choice (Upfront)" else "Audio Bitrate (Upfront)",
                style = MaterialTheme.typography.labelSmall,
                color = P.TextSecondary,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(Sp.xs))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Sp.xs)
            ) {
                if (mediaMode == MediaMode.VIDEO) {
                    VideoQuality.entries.forEach { quality ->
                        FilterChip(
                            selected = videoQuality == quality,
                            onClick = { onVideoQualityChange(quality) },
                            label = { Text(quality.label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = P.Accent,
                                selectedLabelColor = P.OnAccent,
                                containerColor = P.FieldFill,
                                labelColor = P.TextSecondary
                            ),
                            border = BorderStroke(1.dp, if (videoQuality == quality) P.Accent else P.Border)
                        )
                    }
                } else {
                    AudioQuality.entries.forEach { bitrate ->
                        FilterChip(
                            selected = audioQuality == bitrate,
                            onClick = { onAudioQualityChange(bitrate) },
                            label = { Text(bitrate.label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = P.Accent,
                                selectedLabelColor = P.OnAccent,
                                containerColor = P.FieldFill,
                                labelColor = P.TextSecondary
                            ),
                            border = BorderStroke(1.dp, if (audioQuality == bitrate) P.Accent else P.Border)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeaderCard(
    profile: InstagramProfile,
    onDownloadProfilePic: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(R.l),
        colors = CardDefaults.cardColors(containerColor = P.Surface),
        border = BorderStroke(1.dp, P.Border)
    ) {
        Column(modifier = Modifier.padding(Sp.m)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar circle
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(P.SurfaceHigh)
                        .border(1.dp, P.BorderStrong, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = profile.username.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = P.Accent,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.width(Sp.m))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.fullName,
                        style = MaterialTheme.typography.titleMedium,
                        color = P.TextPrimary,
                        fontWeight = FontWeight.Bold,
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
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = P.Danger,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "Private Account",
                                style = MaterialTheme.typography.labelSmall,
                                color = P.Danger
                            )
                        }
                    }
                }
            }

            if (profile.biography.isNotBlank()) {
                Spacer(Modifier.height(Sp.s))
                Text(
                    text = profile.biography,
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(Sp.m))

            // Stats row
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

            Spacer(Modifier.height(Sp.s))

            // Full size profile picture download button
            OutlinedButton(
                onClick = onDownloadProfilePic,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(R.m),
                border = BorderStroke(1.dp, P.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = P.TextPrimary)
            ) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = P.TextPrimary
                )
                Spacer(Modifier.width(Sp.s))
                Text("Download Profile Picture (Full Size)", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ProfileTabs(
    selectedTab: DownloadTab,
    onSelectTab: (DownloadTab) -> Unit
) {
    val tabs = DownloadTab.entries
    TabRow(
        selectedTabIndex = tabs.indexOf(selectedTab),
        containerColor = Color.Transparent,
        contentColor = P.Accent,
        divider = { HorizontalDivider(color = P.Border) },
        indicator = { tabPositions ->
            val index = tabs.indexOf(selectedTab)
            if (index in tabPositions.indices) {
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(tabPositions[index])
                        .height(2.dp)
                        .background(P.Accent)
                )
            }
        }
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                text = {
                    Text(
                        tab.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedTab == tab) P.TextPrimary else P.TextMuted
                    )
                }
            )
        }
    }
}

@Composable
private fun PrivateProfileNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(R.m),
        colors = CardDefaults.cardColors(containerColor = P.Surface),
        border = BorderStroke(1.dp, P.Border)
    ) {
        Row(
            modifier = Modifier.padding(Sp.m),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = P.Danger,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(Sp.m))
            Column {
                Text(
                    text = "This profile is private",
                    style = MaterialTheme.typography.titleSmall,
                    color = P.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Stories and media are not available publicly. TRMX respects privacy and never prompts for user credentials.",
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextMuted
                )
            }
        }
    }
}

@Composable
private fun AnonymousStoriesBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(R.m),
        color = P.SurfaceHigh,
        border = BorderStroke(1.dp, P.Border)
    ) {
        Row(
            modifier = Modifier.padding(Sp.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.VisibilityOff,
                contentDescription = null,
                tint = P.Accent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Sp.s))
            Text(
                "Anonymous Story Viewer · Viewed without login and without recording views",
                style = MaterialTheme.typography.labelSmall,
                color = P.TextSecondary
            )
        }
    }
}

@Composable
private fun MediaItemCard(
    item: InstagramItem,
    mediaMode: MediaMode,
    videoQuality: VideoQuality,
    audioQuality: AudioQuality,
    onDownload: (DownloadOption) -> Unit,
    onDownloadAllCarousel: () -> Unit,
    onDownloadCover: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(R.l),
        colors = CardDefaults.cardColors(containerColor = P.Surface),
        border = BorderStroke(1.dp, P.Border)
    ) {
        Column(modifier = Modifier.padding(Sp.m)) {
            // Type badge + Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(R.s),
                    color = P.SurfaceHigh,
                    border = BorderStroke(1.dp, P.BorderStrong)
                ) {
                    Text(
                        text = item.type.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = P.Accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = Sp.s, vertical = 2.dp)
                    )
                }

                if (item.shortcode != null) {
                    Text(
                        text = item.shortcode,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = P.TextMuted
                    )
                }
            }

            Spacer(Modifier.height(Sp.s))

            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = P.TextPrimary,
                fontWeight = FontWeight.Bold
            )

            if (item.caption.isNotBlank()) {
                Text(
                    text = item.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (item.errorMessage != null) {
                Spacer(Modifier.height(Sp.s))
                Text(
                    text = item.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = P.Danger
                )
            }

            // If carousel item: list children slides
            if (item.carouselItems.isNotEmpty()) {
                Spacer(Modifier.height(Sp.s))
                Text(
                    text = "Carousel Slides (${item.carouselItems.size} items):",
                    style = MaterialTheme.typography.labelSmall,
                    color = P.TextSecondary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(Sp.xs))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Sp.s),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(item.carouselItems) { child ->
                        Surface(
                            shape = RoundedCornerShape(R.s),
                            color = P.FieldFill,
                            border = BorderStroke(1.dp, P.Border),
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Column(modifier = Modifier.padding(Sp.s)) {
                                Text(
                                    text = child.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = P.TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(Sp.xs))
                                OutlinedButton(
                                    onClick = { onDownload(DownloadOption.MEDIA) },
                                    shape = RoundedCornerShape(R.s),
                                    border = BorderStroke(1.dp, P.Border),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = P.TextPrimary)
                                ) {
                                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Download", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Sp.m))

            // Action download buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Sp.s)
            ) {
                // Primary download action (Video / Photo / MP3)
                Button(
                    onClick = { onDownload(DownloadOption.MEDIA) },
                    shape = RoundedCornerShape(R.m),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = P.Accent,
                        contentColor = P.OnAccent
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (mediaMode == MediaMode.MP3) Icons.Outlined.MusicNote else Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = P.OnAccent
                    )
                    Spacer(Modifier.width(Sp.xs))
                    Text(
                        text = if (mediaMode == MediaMode.MP3)
                            "Download MP3 (${audioQuality.label})"
                        else if (item.type == InstagramItemType.PHOTO)
                            "Download JPG"
                        else
                            "Download Video (${videoQuality.label})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // If Reel: Cover image option
                if (item.type == InstagramItemType.REEL) {
                    OutlinedButton(
                        onClick = onDownloadCover,
                        shape = RoundedCornerShape(R.m),
                        border = BorderStroke(1.dp, P.Border),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = P.TextPrimary)
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cover", style = MaterialTheme.typography.labelSmall)
                    }
                }

                // If Carousel: Download All (Full HD)
                if (item.type == InstagramItemType.CAROUSEL) {
                    OutlinedButton(
                        onClick = onDownloadAllCarousel,
                        shape = RoundedCornerShape(R.m),
                        border = BorderStroke(1.dp, P.BorderStrong),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = P.Accent)
                    ) {
                        Icon(Icons.Outlined.ViewCarousel, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("All (Full HD)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadCard(record: DownloadRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(R.m),
        colors = CardDefaults.cardColors(containerColor = P.Surface),
        border = BorderStroke(1.dp, P.BorderStrong)
    ) {
        Column(modifier = Modifier.padding(Sp.m)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = P.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${record.progressPct.toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = P.Accent,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(Sp.xs))

            LinearProgressIndicator(
                progress = (record.progressPct / 100f).coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = P.Accent,
                trackColor = P.SurfaceHigh
            )

            if (record.progressDetail.isNotBlank()) {
                Spacer(Modifier.height(Sp.xs))
                Text(
                    text = record.progressDetail,
                    style = MaterialTheme.typography.labelSmall,
                    color = P.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(
    record: DownloadRecord,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(R.m),
        colors = CardDefaults.cardColors(containerColor = P.Surface),
        border = BorderStroke(1.dp, P.Border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Sp.m),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Icon
            Icon(
                when (record.status) {
                    "COMPLETED" -> Icons.Outlined.CheckCircle
                    "FAILED" -> Icons.Outlined.ErrorOutline
                    else -> Icons.Outlined.Refresh
                },
                contentDescription = null,
                tint = when (record.status) {
                    "COMPLETED" -> Color(0xFF66BB6A)
                    "FAILED" -> P.Danger
                    else -> P.Accent
                },
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(Sp.m))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${record.mediaMode} · ${record.quality} · ${record.targetPath}",
                    style = MaterialTheme.typography.labelSmall,
                    color = P.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (record.error != null) {
                    Text(
                        text = record.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = P.Danger,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (record.status == "FAILED") {
                Spacer(Modifier.width(Sp.s))
                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(R.s),
                    border = BorderStroke(1.dp, P.Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = P.TextPrimary)
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "retry", modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = P.TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = P.TextMuted
        )
    }
}

@Composable
private fun EmptyTabNotice(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Sp.l),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = P.TextMuted
        )
    }
}

@Composable
private fun BannerMessage(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(R.m),
        color = if (isError) P.DangerContainer else P.SurfaceHigh,
        border = BorderStroke(1.dp, if (isError) P.Danger else P.Border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Sp.m, vertical = Sp.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) P.OnDangerContainer else P.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Outlined.Clear,
                    contentDescription = "dismiss",
                    tint = if (isError) P.OnDangerContainer else P.TextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
    else -> count.toString()
}
