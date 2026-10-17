package dev.trmx.gui.ui

/*
 * One-screen setup, kept deliberately phone-simple (2026-10-17 redesign,
 * approved preview): one big "कनेक्ट करो" button, a self-checking
 * checklist, one honest progress line in plain words, and errors as ONE
 * short line + one repair button. Technical detail lives only under
 * "विवरण / लॉग" (the shareable diagnostics log). Rules for this screen:
 * every button visibly responds the moment it is tapped, a disabled
 * button always says why, and manual pairing is a last-resort link that
 * needs ONE pasted line in Termux — never a stop/start recital.
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
        Text("टर्मक्स ब्रिज से कनेक्शन",
             style = MaterialTheme.typography.bodyLarge)

        if (!checks.termuxInstalled) {
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("टर्मक्स इंस्टॉल नहीं है", fontWeight = FontWeight.Bold,
                         color = MaterialTheme.colorScheme.error)
                    Text(
                        "टर्मक्स GitHub Releases (termux.dev) से इंस्टॉल करो, " +
                            "एक बार खोलो, फिर यहाँ वापस आओ। सोर्स मिलाना मत — " +
                            "GitHub और F-Droid वाले बिल्ड कभी साथ मत मिलाओ, " +
                            "दोनों के सिग्नेचर अलग होते हैं।")
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
                label = "कनेक्ट करो",
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
                    "पहले ऊपर की बाकी चीज़ें पूरी करो — फिर यह बटन चालू होगा।",
                    style = MaterialTheme.typography.bodySmall,
                    color = P.TextSecondary)
            }
            state.failure?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "टैप करते ही सब अपने आप होगा — पाइथन, ब्रिज, पेयरिंग। " +
                    "कोई कमांड नहीं लिखनी, कोई टोकन कॉपी-पेस्ट नहीं करना।",
                style = MaterialTheme.typography.bodySmall,
                color = P.TextSecondary)
            // Manual pairing is reachable WITHOUT a pipeline run or a
            // failure whenever a token already exists (device round 1:
            // the escape hatch must never be hostage to a slow wait).
            if (existingToken.isNotEmpty()) {
                TextButton(onClick = { showManualWelcome = !showManualWelcome }) {
                    Text(if (showManualWelcome) "मैन्युअल पेयरिंग छिपाओ"
                         else "मैन्युअल पेयरिंग (सिर्फ़ ज़रूरत पड़ने पर)")
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
            Text("पहले ये तीन चीज़ें", fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.titleMedium)
            Text("सिर्फ़ ये दो-तीन चीज़ें मैं खुद नहीं कर सकता — बाकी सब अपने आप होगा।",
                 style = MaterialTheme.typography.bodySmall,
                 color = P.TextSecondary)

            CheckRow(
                title = "टर्मक्स इंस्टॉल है",
                done = checks.termuxInstalled,
                waiting = false,
                hint = if (!checks.termuxInstalled) "ऊपर देखो — इंस्टॉल करके वापस आओ।"
                       else null,
            )

            CheckRow(
                title = "रन कमांड परमिशन",
                done = checks.runCommandGranted,
                waiting = checks.resolving == "runcommand" && !checks.runCommandGranted,
                hint = "App info → Permissions → Additional permissions में जाकर " +
                    "TRMX के लिए \"Termux:Run Command\" चालू करो।",
                actionLabel = if (checks.runCommandGranted) null else "सेटिंग्स खोलो",
                onAction = onOpenRunCommandSettings,
            )

            CheckRow(
                title = "टर्मक्स सेटिंग (allow-external-apps)",
                done = checks.allowExternalConfirmed,
                waiting = checks.resolving == "allowexternal" && !checks.allowExternalConfirmed,
                hint = "टर्मक्स में ~/.termux/termux.properties फ़ाइल में यह लाइन जोड़ो, " +
                    "फिर termux-reload-settings चलाओ।",
                actionLabel = if (checks.allowExternalConfirmed) null else "टर्मक्स खोलो",
                onAction = onOpenTermuxApp,
                extra = {
                    if (!checks.allowExternalConfirmed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                            TrmxButton(
                                label = "लाइन कॉपी करो",
                                onClick = {
                                    clipboard.setText(
                                        AnnotatedString("allow-external-apps=true"))
                                },
                                kind = TrmxButtonKind.Secondary,
                            )
                            TrmxButton(
                                label = "हो गया — कर दिया",
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
                Text("हो गया", style = MaterialTheme.typography.labelSmall,
                     color = P.TextSecondary)
            } else if (waiting) {
                Text("तुम्हारा इंतज़ार है", style = MaterialTheme.typography.labelSmall,
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

/** Four honest stages (STOP+PAIR+START fold into "पेयरिंग"). */
private fun stageOf(step: WizardStep): Pair<Int, String> = when (step) {
    WizardStep.INSTALL_PY -> 1 to "तैयारी — पाइथन चेक हो रहा है"
    WizardStep.INSTALL -> 2 to "ब्रिज डाउनलोड हो रहा है"
    WizardStep.PAIR -> 3 to "पेयरिंग हो रही है"
    WizardStep.START -> 3 to "पेयरिंग हो रही है"
    WizardStep.HANDSHAKE -> 4 to "कनेक्शन चेक हो रहा है"
    else -> 4 to "आखिरी चेक हो रहा है"
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

    Text("स्टेप $index / 4 — $name", fontWeight = FontWeight.Bold)
    LinearProgressIndicator(
        progress = { completed / 4f },
        modifier = Modifier.fillMaxWidth(),
        color = P.Accent,
        trackColor = P.SurfaceHighest,
    )

    if (state.busy) {
        Text(state.detail.ifEmpty { "काम चल रहा है…" },
             style = MaterialTheme.typography.bodyMedium,
             color = P.TextSecondary)
        Text("$elapsedSec सेकंड हुए — अटके तो यहीं साफ़ लिखा आएगा, घुमाव नहीं।",
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
            label = "पाइथन पहले से है — आगे बढ़ो",
            onClick = onSkipPythonWait,
            kind = TrmxButtonKind.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // The manual escape hatch is also reachable mid-pipeline — a slow
    // stage must never hold it hostage (device round 1, 2026-10-16).
    if (state.busy && state.token.isNotEmpty()) {
        TextButton(onClick = { showManual = !showManual }) {
            Text(if (showManual) "मैन्युअल पेयरिंग छिपाओ"
                 else "मैन्युअल पेयरिंग (सिर्फ़ ज़रूरत पड़ने पर)")
        }
        if (showManual) {
            ManualPairing(token = state.token, onRetry = onVerifyManualPairing)
        }
    }

    state.failure?.let { fail ->
        TrmxCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("कनेक्ट नहीं हुआ", fontWeight = FontWeight.Bold,
                     color = MaterialTheme.colorScheme.error)
                Text(fail, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrmxButton(label = "फिर से कोशिश करो", onClick = onRetry,
                               kind = TrmxButtonKind.Primary)
                    Spacer(Modifier.width(4.dp))
                    TrmxButton(label = "नए सिरे से शुरू करो", onClick = onReset,
                               kind = TrmxButtonKind.Secondary)
                }
                TextButton(onClick = { showManual = !showManual }) {
                    Text(if (showManual) "मैन्युअल पेयरिंग छिपाओ"
                         else "मैन्युअल पेयरिंग (सिर्फ़ ज़रूरत पड़ने पर)")
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

// ---- manual pairing (last resort, ONE line) ---------------------------------

@Composable
private fun ManualPairing(token: String, onRetry: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "सिर्फ़ तब, जब टर्मक्स हमारे कमांड न चला रहा हो। " +
                "टर्मक्स में बस ये एक लाइन पेस्ट करके चलाओ:",
            style = MaterialTheme.typography.bodySmall,
            color = P.TextSecondary)
        Text(
            "  ~/.trmx/trmx pair <टोकन> --force",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
        TrmxButton(
            label = "पूरी लाइन कॉपी करो (टोकन के साथ)",
            onClick = {
                clipboard.setText(
                    AnnotatedString("~/.trmx/trmx pair $token --force"))
            },
            kind = TrmxButtonKind.Secondary,
        )
        TrmxButton(
            label = "सिर्फ़ टोकन कॉपी करो",
            onClick = { clipboard.setText(AnnotatedString(token)) },
            kind = TrmxButtonKind.Secondary,
        )
        Text(
            "ब्रिज चल रहा हो तो भी यही काफी है — स्टॉप/स्टार्ट की ज़रूरत नहीं, " +
                "नया टोकन अगले ही चेक में मान लिया जाएगा।",
            style = MaterialTheme.typography.bodySmall,
            color = P.TextSecondary)
        TrmxButton(
            label = "चेक करो",
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
                Text(if (showLog) "विवरण / लॉग छिपाओ" else "विवरण / लॉग")
            }
            TextButton(onClick = {
                runCatching {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, setupLog)
                    context.startActivity(
                        Intent.createChooser(send, "लॉग भेजो"))
                }
            }) {
                Text("लॉग भेजो")
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
