package dev.trmx.gui.ui

/*
 * Connect screen (redesign v2, 2026-10-18) — replaces the intent-pipeline
 * setup screen. Owner-approved preview: two pastes in Termux, nothing else.
 *
 * Why not the old pipeline: on the owner's device the RUN_COMMAND intents
 * are accepted but never executed (proven over three on-device rounds), so
 * a progress pipeline over them is theatre. Here the app does the only
 * thing it can honestly do — POLL the data plane and finish by itself:
 *
 *   Step 1  one copied line installs everything and starts the bridge
 *           (install.sh also writes allow-external-apps itself).
 *   Step 2  one copied command (pair-link) opens this app carrying the
 *           bridge's own token; the app adopts and verifies it.
 *
 * The help section keeps the manual fallbacks (pair line in the
 * dash-safe canonical form, status line, a patient 15 s Check, and the
 * auto-refreshing diagnostics log).
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.ui.Tokens.Palette as P
import dev.trmx.gui.wizard.ConnectPhase
import dev.trmx.gui.wizard.ConnectState
import kotlinx.coroutines.delay

private const val INSTALL_LINE =
    "curl -fsSL https://raw.githubusercontent.com/sq987in-wq/TRMX-GUI/main/termux/install.sh | sh"
private const val PAIR_LINK_CMD = "~/.trmx/trmx pair-link"
private const val STATUS_CMD = "~/.trmx/trmx status"

@Composable
fun ConnectScreen(
    state: ConnectState,
    manualToken: String,
    setupLog: String,
    onCheckNow: () -> Unit,
    onRefreshLog: () -> Unit,
    onStartPolling: () -> Unit,
    onStopPolling: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var helpOpen by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onStartPolling()
        onDispose { onStopPolling() }
    }
    // The log view must never go stale while it is open (round-2 defect).
    LaunchedEffect(helpOpen) {
        if (helpOpen) {
            while (true) {
                onRefreshLog()
                delay(3000)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.m),
        verticalArrangement = Arrangement.spacedBy(Sp.m),
    ) {
        Text(
            "Connect TRMX",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(P.Accent),
            )
            Spacer(Modifier.width(Sp.s))
            Text(
                when (state.phase) {
                    ConnectPhase.NEEDS_PAIR ->
                        "Bridge found — one more paste to pair."
                    ConnectPhase.CONNECTED -> "Connected."
                    ConnectPhase.NEEDS_INSTALL ->
                        "Not connected yet — two pastes in Termux, that's all."
                },
                color = P.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // ---- Step 1: install & start -------------------------------------
        TrmxCard(modifier = Modifier.fillMaxWidth()) {
            StepChip("STEP 1")
            Spacer(Modifier.height(Sp.s))
            Text(
                "Install & start — one line",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(Sp.xs))
            Text(
                "Copy this line, paste it in Termux, press Enter. " +
                    "It sets up everything and starts the bridge.",
                color = P.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(Sp.s))
            MonoBox(INSTALL_LINE)
            Spacer(Modifier.height(Sp.s))
            TrmxButton(
                label = "Copy the line",
                onClick = { clipboard.setText(AnnotatedString(INSTALL_LINE)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- Step 2: pair -------------------------------------------------
        TrmxCard(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (state.bridgeSeen) 1f else 0.55f),
        ) {
            StepChip("STEP 2")
            Spacer(Modifier.height(Sp.s))
            Text(
                "Pair — one more line",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(Sp.xs))
            Text(
                if (state.bridgeSeen)
                    "The bridge is running. Copy this command, paste it in " +
                        "Termux, press Enter — TRMX opens and pairs itself. " +
                        "No typing, no codes."
                else
                    "Unlocks after Step 1. One paste opens TRMX with the " +
                        "bridge's token — no typing, no codes.",
                color = P.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(Sp.s))
            TrmxButton(
                label = "Copy the connect command",
                onClick = { clipboard.setText(AnnotatedString(PAIR_LINK_CMD)) },
                kind = if (state.bridgeSeen) TrmxButtonKind.Primary
                       else TrmxButtonKind.Secondary,
                enabled = state.bridgeSeen,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- Listening strip ----------------------------------------------
        TrmxCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = P.Accent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(Sp.s))
                Text(
                    "Listening for the bridge… this screen connects by itself.",
                    color = P.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // ---- Help / manual fallbacks --------------------------------------
        TrmxButton(
            label = if (helpOpen) "Hide help" else "Stuck? Need help?",
            onClick = { helpOpen = !helpOpen },
            kind = TrmxButtonKind.Ghost,
        )
        if (helpOpen) {
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "The manual way — pair with this app's token instead. " +
                        "Paste the whole line in Termux:",
                    color = P.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Sp.s))
                // Canonical dash-safe form: --force BEFORE --, token last.
                val pairLine = "~/.trmx/trmx pair --force -- $manualToken"
                MonoBox(pairLine)
                Spacer(Modifier.height(Sp.s))
                TrmxButton(
                    label = "Copy the pair line",
                    onClick = { clipboard.setText(AnnotatedString(pairLine)) },
                    kind = TrmxButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Sp.s))
                TrmxButton(
                    label = "Copy token only",
                    onClick = { clipboard.setText(AnnotatedString(manualToken)) },
                    kind = TrmxButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Sp.s))
                TrmxButton(
                    label = "Copy the status command",
                    onClick = { clipboard.setText(AnnotatedString(STATUS_CMD)) },
                    kind = TrmxButtonKind.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Sp.m))
                TrmxButton(
                    label = if (state.checking) "Checking…"
                            else "Check the connection",
                    onClick = onCheckNow,
                    enabled = !state.checking,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.checking) {
                    Spacer(Modifier.height(Sp.s))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = P.Accent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(Sp.s))
                        Text(
                            "Checking for up to 15 seconds — the bridge " +
                                "sometimes answers slowly.",
                            color = P.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                state.message?.let { msg ->
                    Spacer(Modifier.height(Sp.s))
                    Text(
                        msg,
                        color = P.Danger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(Sp.m))
                Text(
                    "Setup log (refreshes while open)",
                    color = P.TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(Sp.s))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .background(P.FieldFill, RoundedCornerShape(Tokens.Radius.s))
                        .padding(Sp.s)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        setupLog.ifBlank { "(log is empty)" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = P.TextSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(Sp.l))
    }
}

@Composable
private fun StepChip(text: String) {
    Box(
        Modifier
            .background(P.AccentContainer, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            color = P.OnAccentContainer,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun MonoBox(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(P.FieldFill, RoundedCornerShape(Tokens.Radius.s))
            .padding(Sp.s),
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = P.TextSecondary,
        )
    }
}
