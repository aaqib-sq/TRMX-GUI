package dev.trmx.gui.ui

/*
 * Work (redesign v3): the approved final look — pure-black ground, neutral
 * grey BIG cards, one status pill per job, vector icons only. A slim status
 * strip on top (dot + word + repair), running jobs as cards with progress,
 * recent jobs as cards with DONE / FAILED / CANCELLED badges, files as one
 * big nav card. No duplicate heading (the shell top bar already says Work).
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
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
            .padding(horizontal = 20.dp, vertical = Sp.m),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusStrip(state = state, repair = repair, onRepair = onRepair)

        if (repair.running || repair.failure != null || repair.finished || state.error != null) {
            RepairCard(state = state, repair = repair, onRepair = onRepair)
        }

        val running = state.jobs.filter { it.status in ACTIVE }
        if (running.isNotEmpty()) {
            TrmxSectionLabel("Running now")
            running.forEach { job ->
                RunningJobCard(
                    job = job,
                    onOpen = { onJobClick(job.job_id) },
                    onCancel = { onCancelJob(job.job_id) },
                )
            }
        } else {
            TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 20.dp) {
                Text("Nothing running.",
                     fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Tap Ask the agent below to start something.",
                     fontSize = 14.sp, color = P.TextSecondary)
            }
        }

        TrmxButton(
            label = "Ask the agent",
            onClick = onNewJob,
            kind = TrmxButtonKind.Primary,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        )

        val recent = state.jobs.filter { it.status in TERMINAL }.take(5)
        if (recent.isNotEmpty()) {
            TrmxSectionLabel("Recent")
            recent.forEach { job ->
                RecentCard(job = job, onOpen = { onJobClick(job.job_id) })
            }
        }

        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }

        TrmxSectionLabel("Files")
        TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 20.dp, onClick = onOpenFiles) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Sp.m)) {
                TrmxIconTile(Icons.Outlined.Folder)
                Column(modifier = Modifier.weight(1f),
                       verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    Text("Browse files", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Termux home and shared storage.",
                         fontSize = 14.sp, color = P.TextSecondary)
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                     tint = P.TextMuted, modifier = Modifier.size(26.dp))
            }
        }
    }
}

/** Slim status strip: dot + word + bridge version / repair shortcut. */
@Composable
private fun StatusStrip(
    state: DashboardState,
    repair: RepairState,
    onRepair: () -> Unit,
) {
    val connected = state.error == null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(11.dp)
                .background(if (connected) P.Accent else P.Danger, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(
            // audit §5-H3: name the down state plainly (bridge killed /
            // phone rebooted) instead of a vague "not connected" — the
            // RepairCard below offers the fix.
            if (connected) "Connected" else "Bridge is down",
            fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        when {
            state.refreshing -> CircularProgressIndicator(
                strokeWidth = 2.dp, color = P.TextSecondary,
                modifier = Modifier.size(16.dp))
            else -> {
                if (connected) {
                    state.info?.bridge_version?.takeIf { it.isNotEmpty() }?.let { v ->
                        Text("bridge $v", fontSize = 13.sp, color = P.TextMuted)
                        Spacer(Modifier.width(4.dp))
                    }
                }
                // audit §1-L5: the Repair shortcut shows only when there
                // is something to repair — never as permanent clutter
                // while everything is healthy.
                if ((state.error != null || repair.failure != null) &&
                    !repair.running) {
                    TextButton(onClick = onRepair) {
                        Text("Repair", color = P.TextSecondary, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** Repair detail card — only shown while repair matters. */
@Composable
private fun RepairCard(
    state: DashboardState,
    repair: RepairState,
    onRepair: () -> Unit,
) {
    TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            when {
                repair.running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp, color = P.TextSecondary,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Sp.s))
                        Text(repair.label.ifEmpty { "Repairing…" },
                             fontSize = 14.sp, color = P.TextSecondary)
                    }
                }
                repair.failure != null -> {
                    Text(repair.failure, fontSize = 14.sp,
                         color = MaterialTheme.colorScheme.error)
                    TrmxButton(label = "Try repair again", onClick = onRepair,
                               kind = TrmxButtonKind.Secondary)
                }
                repair.finished -> {
                    Text("Connection repaired.", fontSize = 14.sp, color = P.TextSecondary)
                }
                else -> {
                    Text("The bridge is not answering.",
                         fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    TrmxButton(label = "Repair connection", onClick = onRepair,
                               kind = TrmxButtonKind.Secondary)
                }
            }
        }
    }
}

private fun badgeFor(status: String): Pair<String, BadgeTone> = when (status) {
    "COMPLETED" -> "DONE" to BadgeTone.Neutral
    "FAILED" -> "FAILED" to BadgeTone.Danger
    "CANCELLED" -> "CANCELLED" to BadgeTone.Muted
    "LOST" -> "LOST" to BadgeTone.Muted
    "RUNNING" -> "RUNNING" to BadgeTone.Neutral
    "QUEUED" -> "QUEUED" to BadgeTone.Muted
    "STARTING" -> "STARTING" to BadgeTone.Neutral
    "CANCELLING" -> "CANCELLING" to BadgeTone.Danger
    else -> status to BadgeTone.Muted
}

/** One running job = one big card: label, badge, live progress, cancel. */
@Composable
private fun RunningJobCard(
    job: JobSummary,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    TrmxCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 20.dp,
        onClick = onOpen,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    JobLabels.taskLabel(job),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val (label, tone) = badgeFor(job.status)
                TrmxBadge(label, tone)
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
                Text(it, fontSize = 14.sp, color = P.TextSecondary,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onCancel) {
                Text("Cancel", color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
            }
        }
    }
}

/** One recent job = one big card: title, one meta line, status badge. */
@Composable
private fun RecentCard(job: JobSummary, onOpen: () -> Unit) {
    TrmxCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 20.dp,
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f),
                   verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                Text(JobLabels.taskLabel(job),
                     fontSize = 18.sp, fontWeight = FontWeight.Bold,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(JobLabels.subtitle(job),
                     fontSize = 14.sp, color = P.TextSecondary,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(Sp.s))
            val (label, tone) = badgeFor(job.status)
            TrmxBadge(label, tone)
        }
    }
}
