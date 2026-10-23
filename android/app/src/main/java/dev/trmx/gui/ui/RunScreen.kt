package dev.trmx.gui.ui

/*
 * RunScreen (Compact Redesign) — the unified execution surface.
 * Replaces the bloated ToolboxScreen + SubmitDialog with a fast,
 * focused command runner and discovered tools grid.
 *
 * 1. Quick Command Bar: Direct shell execution with 1 tap.
 * 2. Suggestion Chips: Quick common inspection commands.
 * 3. Discovered Tools: Clean cards for schemas in ~/.trmx/tools.
 */

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.ToolsState
import dev.trmx.gui.model.ToolStatus
import dev.trmx.gui.ui.Tokens.Palette as P

@Composable
fun RunScreen(
    state: ToolsState,
    onRunCommand: (String) -> Unit,
    onRefreshTools: () -> Unit,
    onOpenTool: (String) -> Unit,
    onInstallTool: (ToolStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    var commandInput by remember { mutableStateOf("") }
    var confirmInstall by remember { mutableStateOf<ToolStatus?>(null) }

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
                            onClick = {
                                commandInput = suggestion
                            },
                            label = {
                                Text(
                                    suggestion,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = P.TextSecondary,
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = P.SurfaceHigh,
                            ),
                            border = AssistChipDefaults.assistChipBorder(
                                enabled = true,
                                borderColor = P.Border,
                            ),
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

        // ---- 2. Discovered Tools Section ---------------------------------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(
                "Tools",
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
                    "${state.tools.size}",
                    fontSize = 12.sp,
                    color = P.TextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (state.loading) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            TrmxButton(
                label = "Scan",
                leading = { Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp)) },
                onClick = onRefreshTools,
                kind = TrmxButtonKind.Secondary,
            )
        }

        state.notice?.let {
            Text(it, fontSize = 13.sp, color = P.TextSecondary)
        }
        state.error?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }

        if (state.tools.isEmpty() && !state.loading) {
            TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "No tools discovered.",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = P.TextPrimary,
                    )
                    Text(
                        "Add a tool JSON schema to ~/.trmx/tools/ to generate an instant UI form.",
                        fontSize = 13.sp,
                        color = P.TextSecondary,
                    )
                }
            }
        }

        state.tools.forEach { tool ->
            CompactToolCard(
                tool = tool,
                onOpen = { onOpenTool(tool.schema.id) },
                onInstall = { confirmInstall = tool },
            )
        }
    }

    confirmInstall?.let { tool ->
        AlertDialog(
            onDismissRequest = { confirmInstall = null },
            title = { Text("Install ${tool.schema.name}?") },
            text = {
                Text(
                    "This will run `pkg install -y ${tool.schema.install?.package_name ?: tool.schema.binary}` in Termux."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = confirmInstall
                    confirmInstall = null
                    if (target != null) onInstallTool(target)
                }) {
                    Text("Install")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun CompactToolCard(
    tool: ToolStatus,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
) {
    val ready = tool.installed
    TrmxCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp,
        onClick = if (ready) onOpen else null,
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
                        toolIcon(tool.schema.id, tool.schema.binary),
                        contentDescription = null,
                        tint = P.TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tool.schema.name.ifBlank { tool.schema.id },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = P.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tool.schema.binary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = P.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            if (ready) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF142417),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF285430)),
                ) {
                    Text(
                        "READY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF66BB6A),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = P.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                TrmxButton(
                    label = "Install",
                    leading = { Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    onClick = onInstall,
                    kind = TrmxButtonKind.Secondary,
                )
            }
        }
    }
}

private fun toolIcon(id: String, binary: String): ImageVector {
    val key = (id + " " + binary).lowercase()
    return when {
        "yt-dlp" in key || "ytdlp" in key || "media" in key || "download" in key -> Icons.Outlined.Download
        "ffmpeg" in key || "video" in key || "encode" in key -> Icons.Outlined.Movie
        else -> Icons.Outlined.Build
    }
}
