package dev.trmx.gui.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.trmx.gui.killer.AppCategories
import dev.trmx.gui.killer.AppInfo
import dev.trmx.gui.killer.AppKillerViewModel
import dev.trmx.gui.killer.KillerFilterTab
import dev.trmx.gui.ui.Tokens.Palette as P
import dev.trmx.gui.ui.Tokens.Space as Sp

@Composable
fun KillScreen(
    viewModel: AppKillerViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val selectedPackages by viewModel.selectedPackages.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filterTab by viewModel.filterTab.collectAsStateWithLifecycle()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsStateWithLifecycle()
    val isUsageAccessGranted by viewModel.isUsageAccessGranted.collectAsStateWithLifecycle()
    val isKilling by viewModel.isKilling.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val lastReport by viewModel.lastReport.collectAsStateWithLifecycle()
    val hasConfirmedInSession by viewModel.hasConfirmedInSession.collectAsStateWithLifecycle()

    var showConfirmDialog by remember { mutableStateOf(false) }

    // Re-check permission states when user returns to TRMX from system settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.checkAccessibility()
        viewModel.checkUsageAccess()
        viewModel.loadApps()
    }

    val filteredApps = remember(apps, searchQuery, filterTab) {
        apps.filter { app ->
            val matchesQuery = searchQuery.isBlank() ||
                app.label.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)

            val matchesTab = when (filterTab) {
                KillerFilterTab.RUNNING -> AppCategories.isKillableRunning(app)
                KillerFilterTab.OTHER -> AppCategories.belongsToOtherTab(app)
                KillerFilterTab.CORE -> AppCategories.isCore(app.packageName)
            }
            matchesQuery && matchesTab
        }
    }

    val runningCount = remember(apps) {
        apps.count { AppCategories.isKillableRunning(it) }
    }
    val otherCount = remember(apps) {
        apps.count { AppCategories.belongsToOtherTab(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(P.Background),
    ) {
        // ---- Top Header / Controls ---------------------------------------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = Sp.s),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. Accessibility State Banner (shown when disabled)
            if (!isAccessibilityEnabled) {
                TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = P.Danger,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Accessibility Service Off",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = P.TextPrimary,
                            )
                        }
                        Text(
                            "Turn on TRMX App Killer in Settings to enable one-tap automated stopping.",
                            style = MaterialTheme.typography.bodySmall,
                            color = P.TextSecondary,
                        )
                        TrmxButton(
                            label = "Open Accessibility Settings",
                            onClick = { viewModel.openAccessibilitySettings() },
                            kind = TrmxButtonKind.Primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // 2. Usage Access State Banner (shown when not granted)
            if (!isUsageAccessGranted) {
                TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = P.Danger,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Usage Access Needed",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = P.TextPrimary,
                            )
                        }
                        Text(
                            "Allow Usage access in Settings so TRMX can detect recently active apps. Detection happens strictly on-device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = P.TextSecondary,
                        )
                        TrmxButton(
                            label = "Open Usage Access Settings",
                            onClick = { viewModel.openUsageAccessSettings() },
                            kind = TrmxButtonKind.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // 3. Active Killing Progress Card
            if (isKilling) {
                TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Force-stopping apps...",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = P.TextPrimary,
                                )
                                val p = progress
                                if (p != null) {
                                    Text(
                                        "${p.current}/${p.total} done · ${p.currentLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = P.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            TrmxButton(
                                label = "Stop",
                                leading = { Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(14.dp)) },
                                onClick = { viewModel.cancelKill() },
                                kind = TrmxButtonKind.Danger,
                            )
                        }

                        val p = progress
                        val fraction = if (p != null && p.total > 0) {
                            (p.current.toFloat() / p.total.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .background(P.SurfaceHigh, RoundedCornerShape(2.dp)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .fillMaxHeight()
                                    .background(P.Accent, RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                }
            }

            // 4. Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setSearchQuery,
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

            // 5. Filter Chips + Quick Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = filterTab == KillerFilterTab.RUNNING,
                        onClick = { viewModel.setFilterTab(KillerFilterTab.RUNNING) },
                        label = { Text("Running ($runningCount)", style = MaterialTheme.typography.labelSmall) },
                        colors = TrmxChipColors(),
                    )
                    FilterChip(
                        selected = filterTab == KillerFilterTab.OTHER,
                        onClick = { viewModel.setFilterTab(KillerFilterTab.OTHER) },
                        label = { Text("Other ($otherCount)", style = MaterialTheme.typography.labelSmall) },
                        colors = TrmxChipColors(),
                    )
                    FilterChip(
                        selected = filterTab == KillerFilterTab.CORE,
                        onClick = { viewModel.setFilterTab(KillerFilterTab.CORE) },
                        label = { Text("Core", style = MaterialTheme.typography.labelSmall) },
                        colors = TrmxChipColors(),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (filterTab != KillerFilterTab.CORE) {
                        TextButton(onClick = viewModel::selectAllForCurrentTab) {
                            Text("Select All", style = MaterialTheme.typography.labelSmall, color = P.TextPrimary)
                        }
                    }
                    TextButton(onClick = viewModel::deselectAll) {
                        Text("Clear", style = MaterialTheme.typography.labelSmall, color = P.TextSecondary)
                    }
                    IconButton(onClick = viewModel::refreshApps, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = P.TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // ---- Installed Apps List -----------------------------------------
        if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (filterTab == KillerFilterTab.RUNNING) "No running apps detected" else "No apps found",
                    color = P.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        isSelected = selectedPackages.contains(app.packageName),
                        onToggle = { viewModel.toggleSelection(app.packageName) },
                    )
                }
            }
        }

        // ---- Sticky Action Bar -------------------------------------------
        Surface(
            color = P.Surface,
            border = BorderStroke(1.dp, P.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        "${selectedPackages.size} apps selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = P.TextPrimary,
                    )
                    Text(
                        if (isAccessibilityEnabled) "Ready to stop" else "Enable Accessibility first",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isAccessibilityEnabled) Color(0xFF66BB6A) else P.Danger,
                    )
                }

                TrmxButton(
                    label = if (selectedPackages.isEmpty()) "Force Stop" else "Force Stop (${selectedPackages.size})",
                    leading = { Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    onClick = {
                        if (!isAccessibilityEnabled) {
                            viewModel.openAccessibilitySettings()
                        } else if (!hasConfirmedInSession) {
                            showConfirmDialog = true
                        } else {
                            viewModel.startKill(context)
                        }
                    },
                    kind = TrmxButtonKind.Primary,
                    enabled = selectedPackages.isNotEmpty() && !isKilling,
                )
            }
        }
    }

    // ---- Confirmation Dialog (Before first kill of session) ----------
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Force stop ${selectedPackages.size} apps?") },
            text = {
                Text(
                    "${selectedPackages.size} apps will be force-stopped one by one. " +
                    "TRMX will open Settings for each app and automate clicking Force Stop. " +
                    "You can cancel anytime during execution.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDialog = false
                    viewModel.markSessionConfirmed()
                    viewModel.startKill(context)
                }) {
                    Text("Continue", color = P.Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // ---- Final Report Dialog -----------------------------------------
    lastReport?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::clearReport,
            title = { Text("App Killer Report") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Summary: ${report.killed.size} stopped, ${report.skipped.size} skipped.",
                        fontWeight = FontWeight.Bold,
                    )
                    if (report.skipped.isNotEmpty()) {
                        Text(
                            "Skipped details:",
                            color = P.TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                            items(report.skipped) { (pkg, reason) ->
                                val label = apps.find { it.packageName == pkg }?.label ?: pkg
                                Text(
                                    "• $label ($pkg): $reason",
                                    fontSize = 12.sp,
                                    color = P.TextSecondary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearReport) {
                    Text("Done")
                }
            },
        )
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val isCore = AppCategories.isCore(app.packageName)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = P.Surface,
        border = BorderStroke(1.dp, if (isSelected) P.BorderStrong else P.Border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isCore, onClick = onToggle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isCore) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = "Core system app (protected)",
                    tint = P.TextMuted,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(2.dp),
                )
            } else {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = P.Accent,
                        uncheckedColor = P.TextSecondary,
                        checkmarkColor = P.OnAccent,
                    ),
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCore) P.TextMuted else P.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    if (isCore) {
                        CategoryBadge(label = "CORE", color = P.TextMuted, bg = P.SurfaceHigh)
                    } else if (app.isSystem) {
                        CategoryBadge(label = "SYSTEM", color = P.SteelDim, bg = P.SurfaceHigh)
                    }
                }

                Text(
                    app.packageName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = P.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CategoryBadge(label: String, color: Color, bg: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
        modifier = Modifier.padding(start = 4.dp),
    ) {
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}
