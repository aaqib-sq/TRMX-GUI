package dev.trmx.gui.ui

/*
 * Settings (revival): slim on purpose — connection status, one-tap repair,
 * re-run setup, and a read-only view of the active AI backend (the full
 * editor lives under the advanced menu's AI builder). Nothing else.
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trmx.gui.AiConfigState
import dev.trmx.gui.wizard.RepairState
import dev.trmx.gui.ui.Tokens.Palette as P

@Composable
fun SettingsScreen(
    connected: Boolean,
    repair: RepairState,
    aiConfig: AiConfigState,
    onRepair: () -> Unit,
    onDismissRepair: () -> Unit,
    onRerunSetup: () -> Unit,
    onLoadAiConfig: () -> Unit,
    onApplyLocalPreset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onLoadAiConfig() }

    Column(modifier = modifier.fillMaxSize()) {
        TrmxTopBar(title = "Settings", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.m),
        ) {
            // ---- connection ------------------------------------------------
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Text("Connection", fontWeight = FontWeight.Bold,
                         style = MaterialTheme.typography.titleMedium)
                    Text(if (connected) "Connected to the bridge."
                         else "Not connected.",
                         style = MaterialTheme.typography.bodyMedium,
                         color = P.TextSecondary)
                    when {
                        repair.running -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = P.TextSecondary,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(Sp.s))
                                Text(repair.label.ifEmpty { "Repairing…" },
                                     style = MaterialTheme.typography.bodySmall,
                                     color = P.TextSecondary)
                            }
                        }
                        repair.failure != null -> {
                            Text(repair.failure,
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.error)
                            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                                TrmxButton(label = "Try again", onClick = onRepair,
                                           kind = TrmxButtonKind.Secondary)
                                TextButton(onClick = onDismissRepair) {
                                    Text("dismiss")
                                }
                            }
                        }
                        else -> {
                            if (repair.finished) {
                                Text("Connection repaired.",
                                     style = MaterialTheme.typography.bodySmall,
                                     color = P.TextSecondary)
                            }
                            TrmxButton(label = "Repair connection", onClick = onRepair,
                                       kind = TrmxButtonKind.Secondary)
                        }
                    }
                }
            }

            // ---- setup -----------------------------------------------------
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Text("Setup", fontWeight = FontWeight.Bold,
                         style = MaterialTheme.typography.titleMedium)
                    Text("Connect again — the 2-paste flow (install line, then pair).",
                         style = MaterialTheme.typography.bodySmall,
                         color = P.TextSecondary)
                    TrmxButton(label = "Re-run setup", onClick = onRerunSetup,
                               kind = TrmxButtonKind.Secondary)
                }
            }

            // ---- AI backend (read-only view) -------------------------------
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI backend", fontWeight = FontWeight.Bold,
                             style = MaterialTheme.typography.titleMedium,
                             modifier = Modifier.weight(1f))
                        if (aiConfig.loading) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = P.TextSecondary,
                                modifier = Modifier.size(16.dp))
                        }
                    }
                    if (!aiConfig.loaded && !aiConfig.loading) {
                        Text("Couldn't load the backend info.",
                             style = MaterialTheme.typography.bodySmall,
                             color = P.TextSecondary)
                    } else {
                        Text(backendSummary(aiConfig),
                             style = MaterialTheme.typography.bodyMedium)
                        val localEndpoint =
                            aiConfig.endpoint.contains("127.0.0.1") ||
                                aiConfig.endpoint.contains("localhost")
                        Text(
                            when {
                                aiConfig.mode != "http_api" ->
                                    "Local mode — free, runs on this phone."
                                localEndpoint ->
                                    "Local server on this phone — free, no " +
                                        "internet, no API money."
                                else ->
                                    "API mode — every agent step spends API money."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (aiConfig.mode == "http_api" && !localEndpoint)
                                P.Danger else P.TextSecondary)
                        if (localEndpoint) {
                            Text("Start the model server in Termux (llama.cpp), " +
                                    "then the agent can use it:",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = P.TextSecondary)
                            Text("llama-server -m MODEL.gguf --port 8080",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = P.TextMuted)
                            Text("Replace MODEL.gguf with your .gguf file's " +
                                    "full path — the same file PocketPal / " +
                                    "ChatterUI use works, no copy needed.",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = P.TextMuted)
                            CopyButton(
                                label = "Copy the server command",
                                textToCopy = "llama-server -m MODEL.gguf --port 8080",
                                kind = TrmxButtonKind.Secondary)
                        }
                    }
                    aiConfig.notice?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                             color = P.TextSecondary)
                    }
                    aiConfig.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                             color = P.Danger)
                    }
                    val presetOn = aiConfig.mode == "http_api" &&
                        aiConfig.provider == "openai_compatible" &&
                        aiConfig.endpoint.contains("127.0.0.1:8080") &&
                        aiConfig.model == "local-gguf"
                    TrmxButton(
                        label = when {
                            aiConfig.saving -> "Saving\u2026"
                            presetOn -> "Local GGUF is on"
                            else -> "Use a local GGUF model (this phone)"
                        },
                        onClick = onApplyLocalPreset,
                        enabled = !aiConfig.saving && !presetOn,
                        kind = TrmxButtonKind.Secondary)
                    Text("Full backend settings live in Tools → AI Schema Builder.",
                         style = MaterialTheme.typography.labelSmall,
                         color = P.TextMuted)
                }
            }
        }
    }
}

private fun backendSummary(c: AiConfigState): String {
    if (!c.exists) return "Not configured."
    return if (c.mode == "http_api") {
        buildString {
            append("http_api")
            if (c.provider.isNotEmpty()) append(" · ").append(c.provider)
            if (c.model.isNotEmpty()) append(" · ").append(c.model)
        }
    } else {
        "cli · " + c.command.ifEmpty { "local model" }
    }
}
