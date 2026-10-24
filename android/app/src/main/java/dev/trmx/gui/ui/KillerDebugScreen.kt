package dev.trmx.gui.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.trmx.gui.killer.AppDiagnosticInfo
import dev.trmx.gui.killer.AppKillerViewModel
import dev.trmx.gui.ui.Tokens.Palette as P

@Composable
fun KillerDebugScreen(
    viewModel: AppKillerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val diagnosticData by viewModel.diagnosticData.collectAsStateWithLifecycle()
    val searchQuery by viewModel.diagnosticSearchQuery.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadDiagnosticData()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.loadDiagnosticData()
    }

    val apps = diagnosticData?.apps ?: emptyList()
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                app.label.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(P.Background),
    ) {
        // TopBar with Back navigation & Refresh action
        TrmxTopBar(
            title = "App Killer Debug",
            subtitle = "Signal ground truth per app",
            onBack = onBack,
            actions = {
                IconButton(onClick = viewModel::loadDiagnosticData) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "Refresh diagnostics",
                        tint = P.TextSecondary,
                    )
                }
            },
        )

        val data = diagnosticData
        if (data == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Loading diagnostic ground truth...",
                    color = P.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Summary Card: Toggle states + Signal counts
                TrmxCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = 12.dp,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Signal Ground Truth Overview",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = P.TextPrimary,
                        )

                        // 1. Toggle states at the top
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "TOGGLE STATES",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = P.TextMuted,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "Accessibility enabled",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = P.TextSecondary,
                                )
                                Text(
                                    if (data.isAccessibilityEnabled) "yes" else "no",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (data.isAccessibilityEnabled) Color(0xFF66BB6A) else P.Danger,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "Usage access granted",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = P.TextSecondary,
                                )
                                Text(
                                    if (data.isUsageAccessGranted) "yes" else "no",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (data.isUsageAccessGranted) Color(0xFF66BB6A) else P.Danger,
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(P.Border),
                        )

                        // 2. Signal counts
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = "SIGNAL COUNTS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = P.TextMuted,
                            )
                            CountRow(label = "Total installed apps", count = "${data.totalInstalledApps}")
                            CountRow(label = "UsageStats (24-hour window)", count = "${data.usageStatsCount}")
                            CountRow(label = "Accessibility-observed packages", count = "${data.accessibilityObservedCount}")
                            CountRow(label = "ActivityManager fallback", count = "${data.activityManagerFallbackCount}")
                            CountRow(label = "Final Running count", count = "${data.runningCount}", isHighlight = true)
                        }
                    }
                }

                // 3. Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = viewModel::setDiagnosticSearchQuery,
                    placeholder = { Text("Search apps or packages...", color = P.TextMuted) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = P.TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = P.FieldFill,
                        unfocusedContainerColor = P.FieldFill,
                        focusedBorderColor = P.BorderStrong,
                        unfocusedBorderColor = P.Border,
                        focusedTextColor = P.TextPrimary,
                        unfocusedTextColor = P.TextPrimary,
                    ),
                )

                // List header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Installed Apps (${filteredApps.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = P.TextSecondary,
                    )
                }

                // 4. Per-app diagnostic list
                if (filteredApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No apps match search query",
                            color = P.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            DiagnosticAppRow(app = app)
                        }
                        item {
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountRow(label: String, count: String, isHighlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (isHighlight) P.TextPrimary else P.TextSecondary,
            fontWeight = if (isHighlight) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            text = count,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = if (isHighlight) P.Accent else P.TextPrimary,
        )
    }
}

@Composable
private fun DiagnosticAppRow(
    app: AppDiagnosticInfo,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = P.Surface,
        border = BorderStroke(1.dp, P.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Row 1: Label + Package Name + In Running badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = P.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = app.packageName,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = P.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                // In Running badge
                if (app.inRunning) {
                    DiagnosticBadge(
                        label = "In Running: yes",
                        color = Color(0xFF66BB6A),
                        bg = Color(0xFF1B3B22),
                    )
                } else {
                    DiagnosticBadge(
                        label = "In Running: no",
                        color = P.TextSecondary,
                        bg = P.SurfaceHigh,
                    )
                }
            }

            // Row 2: Plain reason if NOT in running
            if (!app.inRunning && app.notRunningReason != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(P.SurfaceHigh, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "Reason: ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = P.TextSecondary,
                    )
                    Text(
                        text = app.notRunningReason,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (app.notRunningReason == "core-blocked") P.TextMuted else P.Accent,
                    )
                }
            }

            // Row 3: Signal breakdown
            // - lastTimeUsed
            // - Accessibility-observed
            // - FLAG_STOPPED
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "lastTimeUsed (UsageStats):",
                        fontSize = 11.sp,
                        color = P.TextMuted,
                    )
                    Text(
                        text = app.lastTimeUsedFormatted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (app.lastTimeUsedFormatted == "no stats") P.TextMuted else P.TextPrimary,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Accessibility-observed:",
                        fontSize = 11.sp,
                        color = P.TextMuted,
                    )
                    Text(
                        text = if (app.isAccessibilityObserved) "yes" else "no",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (app.isAccessibilityObserved) Color(0xFF66BB6A) else P.TextMuted,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "FLAG_STOPPED:",
                        fontSize = 11.sp,
                        color = P.TextMuted,
                    )
                    Text(
                        text = if (app.isStopped) "stopped" else "not stopped",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (app.isStopped) P.Danger else P.TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticBadge(label: String, color: Color, bg: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
