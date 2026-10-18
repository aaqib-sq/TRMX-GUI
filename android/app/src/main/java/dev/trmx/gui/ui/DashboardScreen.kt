package dev.trmx.gui.ui

/*
 * Home (revival): "my work", not "the whole runtime". A monochrome status
 * card (connected / not, one-tap repair), big running-job cards with live
 * progress and one-tap cancel, one big white "+ New job" button, and a
 * quiet recent list. No intent grid, no console aesthetic.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.DashboardState
import dev.trmx.gui.model.JobSummary
import dev.trmx.gui.tools.JobLabels
import dev.trmx.gui.wizard.RepairState
import dev.trmx.gui.ui.Tokens.Palette as P

private val ACTIVE = setOf("RUNNING", "STARTING", "QUEUED", "CANCELLING")
private val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "LOST")

@Composable
fun DashboardScreen(
    state: DashboardState,
    repair: RepairState,
    onJobClick: (String) -> Unit,
    onCancelJob: (String) -> Unit,
    onRepair: () -> Unit,
    onNewJob: () -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Sp.m),
        verticalArrangement = Arrangement.spacedBy(Sp.m),
    ) {
        Text("TRMX", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        StatusCard(state = state, repair = repair, onRepair = onRepair)

        val running = state.jobs.filter { it.status in ACTIVE }
        if (running.isNotEmpty()) {
            Text("Running now",
                 style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.Bold)
            running.forEach { job ->
                RunningJobCard(
                    job = job,
                    onOpen = { onJobClick(job.job_id) },
                    onCancel = { onCancelJob(job.job_id) },
                )
            }
        } else {
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Text("Nothing running.",
                     style = MaterialTheme.typography.bodyMedium,
                     color = P.TextSecondary)
                Text("Start something from the Agent tab — or tap + New job.",
                     style = MaterialTheme.typography.bodySmall,
                     color = P.TextMuted)
            }
        }

        TrmxButton(
            label = "+ New job",
            onClick = onNewJob,
            kind = TrmxButtonKind.Primary,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        )

        val recent = state.jobs.filter { it.status in TERMINAL }.take(5)
        if (recent.isNotEmpty()) {
            Text("Recent",
                 style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.Bold)
            recent.forEach { job ->
                RecentRow(job = job, onOpen = { onJobClick(job.job_id) })
            }
        }

        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }

        // Files live here in the redesign (Work tab) — no separate tab.
        Text("Files",
             style = MaterialTheme.typography.titleMedium,
             fontWeight = FontWeight.Bold)
        TrmxCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenFiles) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Sp.m)) {
                Icon(Icons.Outlined.Folder, contentDescription = null,
                     tint = P.Accent, modifier = Modifier.size(22.dp))
                Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    Text("Browse files",
                         style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                    Text("Termux home and shared storage.",
                         style = MaterialTheme.typography.bodySmall,
                         color = P.TextSecondary)
                }
            }
        }
    }
}

/** Monochrome connection card: state + one-tap repair. */
@Composable
private fun StatusCard(
    state: DashboardState,
    repair: RepairState,
    onRepair: () -> Unit,
) {
    val connected = state.error == null
    TrmxCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (connected) P.Accent else P.Danger,
                            CircleShape))
                Spacer(Modifier.width(Sp.s))
                Text(
                    if (connected) "Connected" else "Not connected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (state.refreshing) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = P.TextSecondary,
                        modifier = Modifier.size(16.dp))
                }
            }
            state.info?.let { info ->
                val running = info.load?.jobs_running ?: 0
                if (running > 0) {
                    Text("$running job${if (running == 1) "" else "s"} running",
                         style = MaterialTheme.typography.bodySmall,
                         color = P.TextSecondary)
                }
            }
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
                    TrmxButton(label = "Try repair again", onClick = onRepair,
                               kind = TrmxButtonKind.Secondary)
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
}

/** One running job = one big card: label, live progress, one-tap cancel. */
@Composable
private fun RunningJobCard(
    job: JobSummary,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    TrmxCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    JobLabels.taskLabel(job),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    JobLabels.statusVerb(job.status),
                    color = TrmxColors.status(job.status),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
            if (job.progress_pct != null) {
                LinearProgressIndicator(
                    progress = { (job.progress_pct / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = P.Accent, trackColor = P.SurfaceHighest)
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = P.TextMuted, trackColor = P.SurfaceHighest)
            }
            job.progress_detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = P.TextSecondary,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onCancel) {
                Text("Cancel", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Quiet recent row: name + status, nothing more. */
@Composable
private fun RecentRow(job: JobSummary, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = Sp.xs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(JobLabels.taskLabel(job),
                 style = MaterialTheme.typography.bodyMedium,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(JobLabels.subtitle(job),
                 style = MaterialTheme.typography.bodySmall,
                 color = P.TextSecondary,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Sp.s))
        Text(JobLabels.statusVerb(job.status),
             style = MaterialTheme.typography.labelSmall,
             color = TrmxColors.status(job.status))
    }
}
