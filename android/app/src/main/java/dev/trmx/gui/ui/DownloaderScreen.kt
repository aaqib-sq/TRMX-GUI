package dev.trmx.gui.ui

/*
 * Downloader Screen — SEPARATE from the Instagram section (PART D).
 * Reached from:
 * 1. Tapping ANY Download button in the Instagram section (with staged item)
 * 2. Directly from the Homepage "Downloader" card
 *
 * Holds ALL download choices chosen UPFRONT before download starts:
 * - Mode: Video mode / Audio (MP3) mode toggle
 * - Quality: video Original / 1080p / 720p / 480p; MP3 320 / 192 / 128 kbps
 * - Reel cover image saved alongside video downloads toggle
 * - Download folder display (~/downloads)
 * - Progress display for active jobs
 * - Download history with retry and clear
 */

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.trmx.gui.download.AudioQuality
import dev.trmx.gui.download.DownloadEngine
import dev.trmx.gui.download.DownloadOption
import dev.trmx.gui.download.DownloadRecord
import dev.trmx.gui.download.DownloadViewModel
import dev.trmx.gui.download.InstagramItem
import dev.trmx.gui.download.InstagramItemType
import dev.trmx.gui.download.MediaMode
import dev.trmx.gui.download.VideoQuality
import dev.trmx.gui.ui.Tokens.Palette as P
import dev.trmx.gui.ui.Tokens.Radius as R
import dev.trmx.gui.ui.Tokens.Space as Sp

@Composable
fun DownloaderScreen(
    stagedItem: InstagramItem?,
    viewModel: DownloadViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val directUrl by viewModel.directDownloadUrl.collectAsStateWithLifecycle()
    val mediaMode by viewModel.mediaMode.collectAsStateWithLifecycle()
    val videoQuality by viewModel.videoQuality.collectAsStateWithLifecycle()
    val audioQuality by viewModel.audioQuality.collectAsStateWithLifecycle()
    val saveCover by viewModel.saveCoverAlongsideVideo.collectAsStateWithLifecycle()
    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(P.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Sp.m, vertical = Sp.s),
        verticalArrangement = Arrangement.spacedBy(Sp.m)
    ) {
        // ---- Error & Notice Banners ----------------------------------------
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

        // ---- Staged Item Card (From Instagram section) ---------------------
        if (stagedItem != null) {
            TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
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
                                text = stagedItem.type.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = P.Accent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = Sp.s, vertical = 2.dp)
                            )
                        }

                        if (stagedItem.shortcode != null) {
                            Text(
                                text = stagedItem.shortcode,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = P.TextMuted
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(R.s))
                                .background(P.SurfaceHigh)
                        ) {
                            RemoteImage(
                                url = stagedItem.previewUrl ?: stagedItem.directMediaUrl,
                                contentDescription = stagedItem.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Spacer(Modifier.width(Sp.m))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stagedItem.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = P.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (stagedItem.caption.isNotBlank()) {
                                Text(
                                    text = stagedItem.caption,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = P.TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Direct URL Input Card (When opened from Homepage)
            TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Text(
                        text = "Download URL",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = P.TextPrimary
                    )

                    OutlinedTextField(
                        value = directUrl,
                        onValueChange = viewModel::onDirectDownloadUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                "Paste any direct video or audio link…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = P.TextMuted
                            )
                        },
                        trailingIcon = {
                            if (directUrl.isNotBlank()) {
                                IconButton(onClick = { viewModel.onDirectDownloadUrlChange("") }) {
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
                        keyboardActions = KeyboardActions(onGo = { viewModel.startDirectDownload() })
                    )

                    TrmxButton(
                        label = "Paste from Clipboard",
                        leading = { Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        onClick = {
                            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                            val pasted = clip?.getItemAt(0)?.text?.toString() ?: ""
                            if (pasted.isNotBlank()) viewModel.onDirectDownloadUrlChange(pasted)
                        },
                        kind = TrmxButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    )
                }
            }
        }

        // ---- UPFRONT DOWNLOAD CHOICES (PART D) ------------------------------
        TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
            Column(verticalArrangement = Arrangement.spacedBy(Sp.m)) {
                Text(
                    text = "Download Options (Upfront Choices)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = P.TextPrimary
                )

                // 1. Mode: Video mode / Audio (MP3) mode toggle
                Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    Text(
                        text = "Format / Mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = P.TextMuted
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Sp.s)
                    ) {
                        FilterChip(
                            selected = mediaMode == MediaMode.VIDEO,
                            onClick = { viewModel.setMediaMode(MediaMode.VIDEO) },
                            label = { Text("Video (MP4)") },
                            leadingIcon = {
                                Icon(Icons.Outlined.VideoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = P.Accent,
                                selectedLabelColor = P.OnAccent,
                                selectedLeadingIconColor = P.OnAccent,
                                containerColor = P.SurfaceHigh,
                                labelColor = P.TextSecondary,
                                iconColor = P.TextMuted
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = mediaMode == MediaMode.VIDEO,
                                borderColor = P.BorderStrong,
                                selectedBorderColor = P.Accent
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        FilterChip(
                            selected = mediaMode == MediaMode.MP3,
                            onClick = { viewModel.setMediaMode(MediaMode.MP3) },
                            label = { Text("Audio (MP3)") },
                            leadingIcon = {
                                Icon(Icons.Outlined.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = P.Accent,
                                selectedLabelColor = P.OnAccent,
                                selectedLeadingIconColor = P.OnAccent,
                                containerColor = P.SurfaceHigh,
                                labelColor = P.TextSecondary,
                                iconColor = P.TextMuted
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = mediaMode == MediaMode.MP3,
                                borderColor = P.BorderStrong,
                                selectedBorderColor = P.Accent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 2. Quality selection
                Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    Text(
                        text = if (mediaMode == MediaMode.VIDEO) "Video Quality (Resolution)" else "Audio Bitrate",
                        style = MaterialTheme.typography.labelMedium,
                        color = P.TextMuted
                    )

                    if (mediaMode == MediaMode.VIDEO) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Sp.s)
                        ) {
                            VideoQuality.entries.forEach { q ->
                                FilterChip(
                                    selected = videoQuality == q,
                                    onClick = { viewModel.setVideoQuality(q) },
                                    label = { Text(q.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = P.SurfaceHigh,
                                        selectedLabelColor = P.TextPrimary,
                                        containerColor = P.FieldFill,
                                        labelColor = P.TextMuted
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = videoQuality == q,
                                        borderColor = P.Border,
                                        selectedBorderColor = P.Accent
                                    )
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Sp.s)
                        ) {
                            AudioQuality.entries.forEach { q ->
                                FilterChip(
                                    selected = audioQuality == q,
                                    onClick = { viewModel.setAudioQuality(q) },
                                    label = { Text(q.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = P.SurfaceHigh,
                                        selectedLabelColor = P.TextPrimary,
                                        containerColor = P.FieldFill,
                                        labelColor = P.TextMuted
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = audioQuality == q,
                                        borderColor = P.Border,
                                        selectedBorderColor = P.Accent
                                    )
                                )
                            }
                        }
                    }
                }

                // 3. Reel cover image saved alongside video downloads
                if (mediaMode == MediaMode.VIDEO) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Save Cover Image",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = P.TextPrimary
                            )
                            Text(
                                text = "Save reel cover thumbnail in ~/downloads beside video",
                                fontSize = 11.sp,
                                color = P.TextMuted
                            )
                        }

                        Switch(
                            checked = saveCover,
                            onCheckedChange = viewModel::setSaveCoverAlongsideVideo,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = P.Accent,
                                checkedTrackColor = P.BorderStrong,
                                uncheckedThumbColor = P.TextMuted,
                                uncheckedTrackColor = P.SurfaceHigh
                            )
                        )
                    }
                }

                // 4. Download Folder Location (~/downloads)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(P.FieldFill, RoundedCornerShape(R.s))
                        .padding(horizontal = Sp.m, vertical = Sp.s),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = P.TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Sp.s))
                    Column {
                        Text(
                            text = "Download Folder: ~/downloads",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = P.TextPrimary
                        )
                        Text(
                            text = "Saved to Termux on-device storage",
                            fontSize = 11.sp,
                            color = P.TextMuted
                        )
                    }
                }

                // 5. Start Download Primary Button
                TrmxButton(
                    label = if (mediaMode == MediaMode.MP3)
                        "Start Download MP3 (${audioQuality.label})"
                    else
                        "Start Download Video (${videoQuality.label})",
                    leading = { Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        if (stagedItem != null) {
                            viewModel.startDownload(stagedItem)
                        } else {
                            viewModel.startDirectDownload()
                        }
                    },
                    kind = TrmxButtonKind.Primary,
                    enabled = stagedItem != null || directUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                )
            }
        }

        // ---- ACTIVE DOWNLOADS SECTION --------------------------------------
        if (activeDownloads.isNotEmpty()) {
            Text(
                text = "Active Downloads (${activeDownloads.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = P.TextSecondary
            )

            activeDownloads.values.forEach { record ->
                ActiveDownloadItem(record = record)
            }
        }

        // ---- DOWNLOAD HISTORY SECTION (PART D) -----------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Download History",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = P.TextSecondary
            )

            if (history.isNotEmpty()) {
                TextButton(onClick = viewModel::clearHistory) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "clear history", tint = P.TextMuted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear", style = MaterialTheme.typography.labelSmall, color = P.TextMuted)
                }
            }
        }

        if (history.isEmpty()) {
            TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
                Text(
                    text = "No download history yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextMuted
                )
            }
        } else {
            history.forEach { record ->
                HistoryRecordCard(
                    record = record,
                    onRetry = { viewModel.retryDownload(record.id) }
                )
            }
        }

        Spacer(Modifier.height(Sp.xl))
    }
}

@Composable
private fun ActiveDownloadItem(record: DownloadRecord) {
    TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = P.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    shape = RoundedCornerShape(R.s),
                    color = P.SurfaceHigh
                ) {
                    Text(
                        text = record.status,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = P.Accent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "${record.itemType} · ${record.quality} · ${record.targetPath}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = P.TextMuted
            )

            Spacer(Modifier.height(Sp.xs))

            LinearProgressIndicator(
                progress = { record.progressPct / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = P.Accent,
                trackColor = P.SurfaceHigh
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = record.progressDetail.ifBlank { "Downloading…" },
                    style = MaterialTheme.typography.labelSmall,
                    color = P.TextSecondary
                )
                Text(
                    text = "${record.progressPct.toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = P.TextPrimary
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
    val isSuccess = record.status == "COMPLETED"
    val isFailed = record.status == "FAILED"

    TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = Sp.m) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = P.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    shape = RoundedCornerShape(R.s),
                    color = when {
                        isSuccess -> Color(0xFF142417)
                        isFailed -> P.DangerContainer
                        else -> P.SurfaceHigh
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isSuccess -> Color(0xFF285430)
                            isFailed -> P.Danger
                            else -> P.Border
                        }
                    )
                ) {
                    Text(
                        text = record.status,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isSuccess -> Color(0xFF66BB6A)
                            isFailed -> P.Danger
                            else -> P.TextSecondary
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "${record.itemType} · ${record.mediaMode} · ${record.quality}",
                style = MaterialTheme.typography.bodySmall,
                color = P.TextSecondary
            )

            Text(
                text = record.targetPath,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = P.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (isFailed) {
                record.error?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = P.Danger,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(Sp.xs))

                TrmxButton(
                    label = "Retry Download",
                    leading = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    onClick = onRetry,
                    kind = TrmxButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                )
            }
        }
    }
}
