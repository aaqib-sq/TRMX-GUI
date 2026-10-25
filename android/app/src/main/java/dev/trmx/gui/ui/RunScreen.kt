package dev.trmx.gui.ui

/*
 * RunScreen (Homepage) — the unified homepage surface (PART A & B).
 * 1. Quick Command Bar: Direct shell execution in Termux with quick suggestion chips.
 * 2. App Killer Card: Force-stop background apps.
 * 3. Downloader Card: Media & MP3 download engine and history.
 * 4. Instagram Section: Named exactly "Instagram", with separate per-tool entries:
 *    - Story Saver
 *    - Reels Downloader
 *    - Photo Downloader
 *    - Video Downloader
 *    - IGTV Downloader
 *    - Carousel Downloader
 *    - Stories Viewer
 *
 * The legacy 9-tools list and Scan affordance are completely deleted.
 */

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
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.download.InstagramTool
import dev.trmx.gui.ui.Tokens.Palette as P
import dev.trmx.gui.ui.Tokens.Radius as R
import dev.trmx.gui.ui.Tokens.Space as Sp

@Composable
fun RunScreen(
    onRunCommand: (String) -> Unit,
    onGoToKill: () -> Unit,
    onOpenDownloader: () -> Unit,
    onOpenInstagramTool: (InstagramTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    var commandInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = Sp.m),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- 1. Quick Command Bar ----------------------------------------
        TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 18.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = P.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Command Runner",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = P.TextPrimary,
                    )
                }

                Text(
                    "Execute any binary or script directly in Termux.",
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextSecondary,
                )

                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "e.g. uname -a, python script.py, df -h",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                color = P.TextMuted,
                            ),
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = P.TextPrimary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = P.BorderStrong,
                        unfocusedBorderColor = P.Border,
                        focusedContainerColor = P.FieldFill,
                        unfocusedContainerColor = P.FieldFill,
                        cursorColor = P.Accent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            if (commandInput.isNotBlank()) {
                                onRunCommand(commandInput.trim())
                                commandInput = ""
                            }
                        }
                    ),
                )

                // Quick suggestions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val suggestions = listOf("uname -a", "uptime", "df -h", "pkg list-installed", "whoami")
                    suggestions.forEach { suggestion ->
                        AssistChip(
                            onClick = { commandInput = suggestion },
                            label = {
                                Text(
                                    suggestion,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = P.TextSecondary,
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(containerColor = P.SurfaceHigh),
                            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = P.Border),
                        )
                    }
                }

                TrmxButton(
                    label = "Run in Termux",
                    leading = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    onClick = {
                        if (commandInput.isNotBlank()) {
                            onRunCommand(commandInput.trim())
                            commandInput = ""
                        }
                    },
                    kind = TrmxButtonKind.Primary,
                    enabled = commandInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }

        // ---- App Killer Entry Card ---------------------------------------
        TrmxCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
            onClick = onGoToKill,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = P.SurfaceHigh,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.PowerSettingsNew,
                            contentDescription = null,
                            tint = P.TextPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "App Killer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = P.TextPrimary,
                    )
                    Text(
                        "Force-stop background apps with one tap",
                        fontSize = 12.sp,
                        color = P.TextSecondary,
                    )
                }

                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = P.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ---- Downloader Entry Card (PART D) -------------------------------
        TrmxCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 16.dp,
            onClick = onOpenDownloader,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = P.SurfaceHigh,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            tint = P.TextPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Downloader",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = P.TextPrimary,
                    )
                    Text(
                        "Video & MP3 download engine, quality choices, history",
                        fontSize = 12.sp,
                        color = P.TextSecondary,
                    )
                }

                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = P.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ---- PART B: Instagram Section (Homepage) -------------------------
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    "Instagram",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = P.TextPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = P.SurfaceHigh,
                ) {
                    Text(
                        "7 tools",
                        fontSize = 12.sp,
                        color = P.TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            Text(
                "Anonymous media & story tools for public profiles · No login required",
                style = MaterialTheme.typography.bodySmall,
                color = P.TextMuted,
            )

            // 7 SEPARATE per-tool entries:
            InstagramTool.entries.forEach { tool ->
                InstagramToolCard(
                    tool = tool,
                    onClick = { onOpenInstagramTool(tool) }
                )
            }
        }

        Spacer(Modifier.height(Sp.m))
    }
}

@Composable
private fun InstagramToolCard(
    tool: InstagramTool,
    onClick: () -> Unit
) {
    TrmxCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 14.dp,
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = P.SurfaceHigh,
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        toolIcon(tool),
                        contentDescription = null,
                        tint = P.TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tool.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = P.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tool.description,
                    fontSize = 12.sp,
                    color = P.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = P.TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun toolIcon(tool: InstagramTool): ImageVector = when (tool) {
    InstagramTool.STORY_SAVER -> Icons.Outlined.AutoStories
    InstagramTool.REELS_DOWNLOADER -> Icons.Outlined.Movie
    InstagramTool.PHOTO_DOWNLOADER -> Icons.Outlined.Image
    InstagramTool.VIDEO_DOWNLOADER -> Icons.Outlined.VideoLibrary
    InstagramTool.IGTV_DOWNLOADER -> Icons.Outlined.Tv
    InstagramTool.CAROUSEL_DOWNLOADER -> Icons.Outlined.ViewCarousel
    InstagramTool.STORIES_VIEWER -> Icons.Outlined.Visibility
}
