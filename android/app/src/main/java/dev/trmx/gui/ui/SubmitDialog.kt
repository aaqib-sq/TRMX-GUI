package dev.trmx.gui.ui

/*
 * New-job dialog. The argv editor is deliberately line-based — one line =
 * one argument. There is no shell parsing, quoting or escaping: no
 * injection surface by construction (argv-first, ADR-001).
 *
 * Raw argv always runs at tier "destructive" (PROTOCOL §3.2, audit
 * §4-H3): "Run" opens a second-stage "Run this command?" confirmation —
 * the same flow tool submits use for confirm/destructive tiers — which
 * additionally shows the destructive-pattern warning when the argv
 * matches the agent's verb list (SubmitValidator.looksDestructive).
 * Only that confirmation invokes onSubmit, and AppViewModel marks the
 * request confirm=true.
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.trmx.gui.SubmitFormState
import dev.trmx.gui.job.SubmitValidator

@Composable
fun SubmitDialog(
    state: SubmitFormState,
    onName: (String) -> Unit,
    onArgv: (String) -> Unit,
    onCwd: (String) -> Unit,
    onTimeout: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmRun by remember { mutableStateOf(false) }
    val argv = remember(state.argvText) { SubmitValidator.parseArgvText(state.argvText) }
    val destructive = remember(argv) { SubmitValidator.looksDestructive(argv) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New job") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TrmxTextField(
                    value = state.name,
                    onValueChange = onName,
                    label = "Name (optional)",
                    modifier = Modifier.fillMaxWidth())
                TrmxTextField(
                    value = state.argvText,
                    onValueChange = onArgv,
                    label = "Arguments — one per line",
                    placeholder = {
                        Text("ffmpeg\n-i ~/in.mkv\n~/out.mp4", fontFamily = FontFamily.Monospace)
                    },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth())
                TrmxTextField(
                    value = state.cwd,
                    onValueChange = onCwd,
                    label = "Working directory (optional, default ~)",
                    modifier = Modifier.fillMaxWidth())
                TrmxTextField(
                    value = state.timeoutText,
                    onValueChange = onTimeout,
                    label = "Timeout in seconds (optional)",
                    modifier = Modifier.fillMaxWidth())
                Text(
                    "Each line is passed to the bridge as exactly one argument — " +
                        "no shell is involved.",
                    style = MaterialTheme.typography.bodySmall)
                state.errors.forEach { err ->
                    Text(
                        "${err.field}: ${err.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirmRun = true }, enabled = !state.submitting) {
                Text(if (state.submitting) "Submitting…" else "Run")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    // second stage — raw argv is tier destructive (§3.2), so it gets the
    // same explicit confirmation as a destructive tool submit
    if (confirmRun) {
        AlertDialog(
            onDismissRequest = { confirmRun = false },
            title = { Text("Run this command?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "The phone will execute exactly these arguments — " +
                            "a raw command runs with no tool safety net:")
                    Text(argv.joinToString("\n"), fontFamily = FontFamily.Monospace)
                    if (destructive) {
                        Text(
                            "This matches a destructive pattern — it can " +
                                "delete, kill or overwrite. Double-check " +
                                "every argument before running.",
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmRun = false; onSubmit() }) { Text("run it") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRun = false }) { Text("cancel") }
            },
        )
    }
}
