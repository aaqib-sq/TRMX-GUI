package dev.trmx.gui.ui

/*
 * InstagramHomeScreen (PART A) — Dedicated sub-screen housing the 5 Instagram tools:
 * 1. Reels Downloader
 * 2. Photo Downloader
 * 3. Video Downloader
 * 4. IGTV Downloader
 * 5. Carousel Downloader
 *
 * Reached exclusively by tapping the "Instagram" card on the RunScreen homepage.
 * Top bar back arrow returns to homepage.
 */

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.download.InstagramTool
import dev.trmx.gui.ui.Tokens.Palette as P
import dev.trmx.gui.ui.Tokens.Space as Sp

@Composable
fun InstagramHomeScreen(
    onOpenTool: (InstagramTool) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(P.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = Sp.m),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Public link downloads · No login required",
            style = MaterialTheme.typography.bodySmall,
            color = P.TextSecondary,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        // 5 individual tool cards
        InstagramTool.entries.forEach { tool ->
            InstagramToolCard(
                tool = tool,
                onClick = { onOpenTool(tool) },
            )
        }

        Spacer(Modifier.height(Sp.m))
    }
}

@Composable
fun InstagramToolCard(
    tool: InstagramTool,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TrmxCard(
        modifier = modifier.fillMaxWidth(),
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

fun toolIcon(tool: InstagramTool): ImageVector = when (tool) {
    InstagramTool.REELS_DOWNLOADER -> Icons.Outlined.Movie
    InstagramTool.PHOTO_DOWNLOADER -> Icons.Outlined.Image
    InstagramTool.VIDEO_DOWNLOADER -> Icons.Outlined.VideoLibrary
    InstagramTool.IGTV_DOWNLOADER -> Icons.Outlined.Tv
    InstagramTool.CAROUSEL_DOWNLOADER -> Icons.Outlined.ViewCarousel
}
