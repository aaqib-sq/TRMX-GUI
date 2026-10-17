package dev.trmx.gui.ui

/*
 * One-screen setup, kept deliberately phone-simple (2026-10-17): one
 * primary button, a self-checking permission checklist, one honest
 * progress line, and errors as one short explanation + one repair
 * button. Technical detail lives only under "Details / log" (the
 * shareable diagnostics log). Rules for this screen: every button
 * visibly responds the moment it is tapped, a disabled button always
 * says why, and manual pairing is a last-resort link — one pasted
 * command in Termux (pair-link), never a stop/start recital.
 */

import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.trmx.gui.wizard.SetupChecks
import dev.trmx.gui.wizard.WizardState
import dev.trmx.gui.wizard.WizardStep
import dev.trmx.gui.ui.Tokens.Palette as P
import kotlinx.coroutines.delay

@Composable
fun SetupScreen(
    state: WizardState,
    checks: SetupChecks,
    onResume: () -> Unit,
    onOpenRunCommandSettings: () -> Unit,
    onOpenTermuxApp: () -> Unit,
    onConfirmAllowExternal: () -> Unit,
    onBegin: () -> Unit,
    onSkipPythonWait: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    existingToken: String = "",
    onVerifyManualPairing: () -> Unit = {},
    setupLog: String = "",
) {
    var showManualWelcome by remember { mutableStateOf(false) }
    // "waiting for you" states resolve here — the user comes back from
    // settings, the checklist re-verifies itself, no taps needed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) onResume()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("TRMX", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Set up the Termux backend",
             style = MaterialTheme.typography.bodyLarge)

        if (!checks.termuxInstalled) {
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Termux is not installed", fontWeight = FontWeight.Bold,
                         color = MaterialTheme.colorScheme.error)
                    Text(
                        "Install Termux from GitHub Releases (termux.dev), open it " +
                            "once, then come back here. Do not mix sources: " +
                            "GitHub and F-Droid builds must never be combined — " +
                            "same app, different signatures.")
                }
            }
        }

        ChecklistCard(
            checks = checks,
            onOpenRunCommandSettings = onOpenRunCommandSettings,
            onOpenTermuxApp = onOpenTermuxApp,
            onConfirmAllowExternal = onConfirmAllowExternal,
        )

        if (state.step == WizardStep.WELCOME) {
            TrmxButton(
                label = "Start setup",
                onClick = onBegin,
                kind = TrmxButtonKind.Primary,
                enabled = checks.ready && !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            )
            if (!checks.ready) {
                // A disabled button must always say why (2026-10-17 rule).
                Text(
                    "Finish the checklist items above first — then this button turns on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextSecondary)
            }
            state.failure?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "This installs Python (only if missing), downloads the TRMX " +
                    "bridge and starts it. Nothing runs as root; everything " +
                    "stays inside Termux. Your part: under 30 seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = P.TextSecondary)
            // Manual pairing is reachable WITHOUT a pipeline run or a
            // failure whenever a token already exists (device round 1:
            // the escape hatch must never be hostage to a slow wait).
            if (existingToken.isNotEmpty()) {
                TextButton(onClick = { showManualWelcome = !showManualWelcome }) {
                    Text(if (showManualWelcome) "Hide manual pairing"
                         else "Pair manually instead (last resort)")
                }
                if (showManualWelcome) {
                    ManualPairing(token = existingToken,
                                  onRetry = onVerifyManualPairing)
                }
            }
            if (setupLog.isNotBlank()) {
                LogSection(setupLog = setupLog)
            }
        } else {
            PipelineBody(
                state = state,
                onSkipPythonWait = onSkipPythonWait,
                onRetry = onRetry,
                onReset = onReset,
                onVerifyManualPairing = onVerifyManualPairing,
                setupLog = setupLog,
            )
        }
    }
}

// ---- checklist ------------------------------------------------------------

@Composable
private fun ChecklistCard(
    checks: SetupChecks,
    onOpenRunCommandSettings: () -> Unit,
    onOpenTermuxApp: () -> Unit,
    onConfirmAllowExternal: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    TrmxCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.m)) {
            Text("Before we start", fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.titleMedium)
            Text("Two things only I can't do myself — everything else is automatic.",
                 style = MaterialTheme.typography.bodySmall,
                 color = P.TextSecondary)

            CheckRow(
                title = "Termux is installed",
                done = checks.termuxInstalled,
                waiting = false,
                hint = if (!checks.termuxInstalled) "See above — install it, then come back."
                       else null,
            )

            CheckRow(
                title = "Run Command permission",
                done = checks.runCommandGranted,
                waiting = checks.resolving == "runcommand" && !checks.runCommandGranted,
                hint = "App info → Permissions → Additional permissions → " +
                    "allow \"Termux:Run Command\" for TRMX.",
                actionLabel = if (checks.runCommandGranted) null else "Open settings",
                onAction = onOpenRunCommandSettings,
            )

            CheckRow(
                title = "allow-external-apps in Termux",
                done = checks.allowExternalConfirmed,
                waiting = checks.resolving == "allowexternal" && !checks.allowExternalConfirmed,
                hint = "In Termux, add this line to ~/.termux/termux.properties, " +
                    "then run termux-reload-settings.",
                actionLabel = if (checks.allowExternalConfirmed) null else "Open Termux",
                onAction = onOpenTermuxApp,
                extra = {
                    if (!checks.allowExternalConfirmed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                            TrmxButton(
                                label = "Copy the line",
                                onClick = {
                                    clipboard.setText(
                                        AnnotatedString("allow-external-apps=true"))
                                },
                                kind = TrmxButtonKind.Secondary,
                            )
                            TrmxButton(
                                label = "I've enabled it",
                                onClick = onConfirmAllowExternal,
                                kind = TrmxButtonKind.Secondary,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun CheckRow(
    title: String,
    done: Boolean,
    waiting: Boolean,
    hint: String?,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (done) P.Accent else P.TextMuted,
                        CircleShape))
            Spacer(Modifier.width(Sp.s))
            Text(title, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.weight(1f))
            if (done) {
                Text("done", style = MaterialTheme.typography.labelSmall,
                     color = P.TextSecondary)
            } else if (waiting) {
                Text("waiting for you", style = MaterialTheme.typography.labelSmall,
                     color = P.TextSecondary)
            }
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = P.TextSecondary)
        }
        if (actionLabel != null && onAction != null) {
            TrmxButton(
                label = actionLabel,
                onClick = onAction,
                kind = TrmxButtonKind.Secondary,
            )
        }
        extra?.invoke()
    }
}

// ---- pipeline ---------------------------------------------------------------

/** Four honest stages (STOP+PAIR+START fold into "Pairing"). */
private fun stageOf(step: WizardStep): Pair<Int, String> = when (step) {
    WizardStep.INSTALL_PY -> 1 to "Installing Python"
    WizardStep.INSTALL -> 2 to "Downloading the bridge"
    WizardStep.PAIR -> 3 to "Pairing"
    WizardStep.START -> 3 to "Pairing"
    WizardStep.HANDSHAKE -> 4 to "Waiting for the bridge"
    else -> 4 to "Finishing"
}

@Composable
private fun PipelineBody(
    state: WizardState,
    onSkipPythonWait: () -> Unit,
    onRetry: () -> Unit,
    onReset: () -> Unit,
    onVerifyManualPairing: () -> Unit = {},
    setupLog: String = "",
) {
    var showManual by remember { mutableStateOf(false) }
    val (index, name) = stageOf(state.step)
    val completed = if (state.busy) index - 1 else index

    // Elapsed seconds for the CURRENT stage, shown while busy — a wait
    // that counts is a wait that is visibly alive (round-1 lesson).
    var elapsedSec by remember { mutableStateOf(0) }
    LaunchedEffect(state.step, state.busy) {
        elapsedSec = 0
        if (!state.busy) return@LaunchedEffect
        while (true) {
            delay(1000)
            elapsedSec++
        }
    }

    Text("Step $index of 4 — $name", fontWeight = FontWeight.Bold)
    LinearProgressIndicator(
        progress = { completed / 4f },
        modifier = Modifier.fillMaxWidth(),
        color = P.Accent,
        trackColor = P.SurfaceHighest,
    )

    if (state.busy) {
        Text(state.detail.ifEmpty { "working…" },
             style = MaterialTheme.typography.bodyMedium,
             color = P.TextSecondary)
        Text("${elapsedSec}s so far — if it gets stuck, the reason will appear right here.",
             style = MaterialTheme.typography.bodySmall,
             color = P.TextMuted)
    } else if (state.detail.isNotEmpty()) {
        Text(state.detail, style = MaterialTheme.typography.bodyMedium)
    }

    if (state.skippable && state.busy) {
        // A real button, and it answers on the tap itself: the ViewModel
        // fires SkipWait immediately, so this button disappears and the
        // stage text changes at once (round 1: this was a TextButton
        // whose tap changed nothing on screen — it looked dead).
        TrmxButton(
            label = "Python is already installed — continue",
            onClick = onSkipPythonWait,
            kind = TrmxButtonKind.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // The manual escape hatch is also reachable mid-pipeline — a slow
    // stage must never hold it hostage (device round 1, 2026-10-16).
    if (state.busy && state.token.isNotEmpty()) {
        TextButton(onClick = { showManual = !showManual }) {
            Text(if (showManual) "Hide manual pairing"
                 else "Pair manually instead (last resort)")
        }
        if (showManual) {
            ManualPairing(token = state.token, onRetry = onVerifyManualPairing)
        }
    }

    state.failure?.let { fail ->
        TrmxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Setup problem", fontWeight = FontWeight.Bold,
                     color = MaterialTheme.colorScheme.error)
                Text(fail, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrmxButton(label = "Retry this step", onClick = onRetry,
                               kind = TrmxButtonKind.Primary)
                    Spacer(Modifier.width(4.dp))
                    TrmxButton(label = "Start over", onClick = onReset,
                               kind = TrmxButtonKind.Secondary)
                }
                TextButton(onClick = { showManual = !showManual }) {
                    Text(if (showManual) "Hide manual pairing"
                         else "Pair manually instead (last resort)")
                }
                if (showManual && state.token.isNotEmpty()) {
                    ManualPairing(token = state.token,
                                  onRetry = onVerifyManualPairing)
                }
                if (setupLog.isNotBlank()) {
                    LogSection(setupLog = setupLog)
                }
            }
        }
    }
}

// ---- manual pairing (last resort) -------------------------------------------

@Composable
private fun ManualPairing(token: String, onRetry: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The easiest path first: ONE command in Termux opens this app
        // with the bridge's own token (pair-link) and the app pairs
        // itself — no token handling on this screen at all.
        Text(
            "Fastest way — one command in Termux. It opens TRMX with the " +
                "bridge's token, and the app pairs itself:",
            style = MaterialTheme.typography.bodySmall,
            color = P.TextSecondary)
        Text(
            "  ~/.trmx/trmx pair-link",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
        TrmxButton(
            label = "Copy the connect command",
            onClick = {
                clipboard.setText(AnnotatedString("~/.trmx/trmx pair-link"))
            },
            kind = TrmxButtonKind.Secondary,
        )
        Text(
            "If the app does not open by itself, use this one line in Termux instead:",
            style = MaterialTheme.typography.bodySmall,
            color = P.TextSecondary)
        Text(
            "  ~/.trmx/trmx pair <paste-token> --force",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
        TrmxButton(
            label = "Copy the whole line (with token)",
            onClick = {
                clipboard.setText(
                    AnnotatedString("~/.trmx/trmx pair $token --force"))
            },
            kind = TrmxButtonKind.Secondary,
        )
        TrmxButton(
            label = "Copy token only",
            onClick = { clipboard.setText(AnnotatedString(token)) },
            kind = TrmxButtonKind.Secondary,
        )
        Text(
            "No stop/start needed — a running bridge accepts the new " +
                "token on the very next check.",
            style = MaterialTheme.typography.bodySmall,
            color = P.TextSecondary)
        TrmxButton(
            label = "Check",
            onClick = onRetry,
            kind = TrmxButtonKind.Primary,
        )
    }
}

// ---- diagnostics log --------------------------------------------------------

@Composable
private fun LogSection(setupLog: String) {
    val context = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showLog = !showLog }) {
                Text(if (showLog) "Hide details / log" else "Details / log")
            }
            TextButton(onClick = {
                runCatching {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, setupLog)
                    context.startActivity(
                        Intent.createChooser(send, "Share log"))
                }
            }) {
                Text("Share log")
            }
        }
        if (showLog) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                Text(
                    setupLog,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextSecondary)
            }
        }
    }
}
