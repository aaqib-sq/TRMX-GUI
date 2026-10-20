package dev.trmx.gui.ui

/*
 * Job detail (revival): a clean reading card for the live output first —
 * big Cancel on top while the job is active — with the full KV/monospaced
 * record hidden under a "Details" toggle. No console-dump aesthetic.
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import dev.trmx.gui.ArtifactsState
import dev.trmx.gui.JobDetailState
import dev.trmx.gui.job.JobOutputState
import dev.trmx.gui.tools.Artifact
import dev.trmx.gui.tools.JobLabels
import dev.trmx.gui.ui.Tokens.Palette as P

private val ACTIVE = setOf("QUEUED", "RUNNING", "CANCELLING")

@Composable
fun JobDetailScreen(
    state: JobDetailState,
    output: JobOutputState?,
    artifacts: ArtifactsState,
    onBack: () -> Unit,
    onCancelJob: (String) -> Unit,
    onReplayOutput: () -> Unit,
    onOpenArtifact: (Artifact, Boolean) -> Unit,
) {
    var confirmCancel by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TrmxTopBar(
            title = state.job?.let { JobLabels.taskLabel(it) } ?: "Task",
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val job = state.job
            state.error?.let {
                TrmxCard(modifier = Modifier.fillMaxWidth()) {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }

            if (job != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(JobLabels.subtitle(job),
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.weight(1f))
                    Text(
                        JobLabels.statusVerb(job.status),
                        color = TrmxColors.status(job.status),
                        fontWeight = FontWeight.Bold)
                }

                if (job.status in ACTIVE) {
                    TrmxButton(
                        label = if (state.cancelling) "Cancelling…" else "Cancel job",
                        onClick = { confirmCancel = true },
                        kind = TrmxButtonKind.Danger,
                        enabled = !state.cancelling,
                        modifier = Modifier.fillMaxWidth())
                }

                output?.let { OutputCard(it, onReplayOutput) }

                if (artifacts.jobId == state.jobId &&
                    (artifacts.artifacts.isNotEmpty() || artifacts.loading || artifacts.error != null)) {
                    ArtifactsCard(artifacts, onOpenArtifact)
                }

                // The full record — hidden unless asked for.
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide details" else "Details")
                }
                if (showDetails) {
                    DetailsCard(state)
                }
            } else if (state.error == null) {
                Text("loading…")
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel job?") },
            text = {
                Text(
                    "The bridge sends SIGTERM to the process group, then SIGKILL " +
                        "after a 5 s grace period. The job record is kept.",
                    // Scrollable body: actions stay pinned at large fonts.
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    onCancelJob(state.jobId)
                }) { Text("Cancel job") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text("Keep running") }
            },
        )
    }
}

/** Live output as a clean reading card — no filter bar, no console chrome. */
@Composable
private fun OutputCard(
    output: JobOutputState,
    onReplay: () -> Unit,
) {
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    LaunchedEffect(output.lines.size, autoScroll) {
        if (autoScroll && output.lines.isNotEmpty()) {
            listState.animateScrollToItem(output.lines.size - 1)
        }
    }

    TrmxCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Output", style = MaterialTheme.typography.titleMedium,
                     fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val live = output.error == null && !output.ended
                Text(
                    when {
                        output.error != null -> "${output.error}"
                        output.ended -> "ended"
                        else -> "live"
                    },
                    color = if (live) TrmxColors.Running else P.TextMuted,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (output.evicted) {
                Text(
                    "Older output was trimmed — showing the recent window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)) {
                items(output.lines) { line ->
                    Text(
                        line.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        color = when {
                            line.isStderr -> P.Danger
                            line.kind == "status" -> MaterialTheme.colorScheme.secondary
                            else -> Color.Unspecified
                        })
                }
            }
            Row {
                TextButton(onClick = { autoScroll = !autoScroll }) {
                    Text(if (autoScroll) "auto-scroll on" else "auto-scroll off",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.width(Sp.s))
                TextButton(onClick = onReplay) {
                    Text("replay from start",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

/** The full job record (TRMX-P/1 §3.4 shape), monospaced, under the toggle. */
@Composable
private fun DetailsCard(state: JobDetailState) {
    val job = state.job ?: return
    TrmxCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
            CopyableId(state.jobId, modifier = Modifier.fillMaxWidth())
            Text("command",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.secondary)
            Text(
                job.argv?.joinToString(" ") ?: "—",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
            KV("type", job.type)
            job.cwd?.let { KV("cwd", it) }
            KV("created", job.created_at ?: "—")
            KV("started", job.started_at ?: "—")
            KV("ended", job.ended_at ?: "—")
            job.pid?.let { KV("pid", it.toString()) }
            job.exit_code?.let { KV("exit code", it.toString()) }
            job.signal?.let { KV("signal", it.toString()) }
            job.timeout_s?.let { KV("timeout (s)", it.toString()) }
            job.error?.let { KV("error", "${it.code}: ${it.message}") }
            if (job.status == "LOST") {
                // audit §5-H4: the bridge records why the job was lost in
                // cancel_reason — show the cause, not just the LOST badge
                job.cancel_reason?.let { KV("lost reason", it) }
            }
            KV("output bytes", "${job.stdout_bytes} / ${job.stderr_bytes}")
            KV("log frames", job.log_seq.toString() +
                if (job.log_truncated) " (older frames evicted)" else "")
            if (job.cancel_requested) {
                Text("cancel requested${job.cancel_reason?.let { " ($it)" } ?: ""}",
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row {
        Text(k, fontFamily = FontFamily.Monospace,
             modifier = Modifier.width(110.dp),
             color = MaterialTheme.colorScheme.secondary)
        // weight(1f) bounds the value to the remaining row width so it
        // WRAPS at large font scales instead of running off the edge.
        Text(v, fontFamily = FontFamily.Monospace,
             style = MaterialTheme.typography.bodySmall,
             modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ArtifactsCard(
    state: ArtifactsState,
    onOpenArtifact: (Artifact, Boolean) -> Unit,
) {
    val context = LocalContext.current
    TrmxCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            Text("Artifacts", style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.Bold)
            if (state.loading) {
                Text("collecting outputs…", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }
            state.artifacts.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            buildString {
                                if (a.size != null) append(Formatter.formatShortFileSize(context, a.size))
                                append(if (a.exact) "  · output" else "  · detected")
                                a.mtime?.let { append("  · ").append(it) }
                            },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onOpenArtifact(a, false) }) { Text("open") }
                    TextButton(onClick = { onOpenArtifact(a, true) }) { Text("share") }
                }
            }
        }
    }
}
