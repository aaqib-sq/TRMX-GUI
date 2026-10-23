package dev.trmx.gui.ui

/*
 * ActivityScreen (Compact Redesign) — the live execution monitor and history.
 * Replaces DashboardScreen with a focused view:
 * 1. Active jobs with live indicator, elapsed timer, and 1-tap Stop action.
 * 2. Recent jobs with status badges (DONE / FAILED / CANCELLED) and duration.
 * 3. Direct navigation to live terminal output on card tap.
 */

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.DashboardState
import dev.trmx.gui.model.JobSummary
import dev.trmx.gui.tools.JobLabels
import dev.trmx.gui.ui.Tokens.Palette as P

private val ACTIVE = setOf("RUNNING", "STARTING", "QUEUED", "CANCELLING")
private val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", "LOST")

@Composable
fun ActivityScreen(
    state: DashboardState,
    onJobClick: (String) -> Unit,
    onCancelJob: (String) -> Unit,
    onGoToRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeJobs = state.jobs.filter { it.status in ACTIVE }
    val recentJobs = state.jobs.filter { it.status in TERMINAL }.take(15)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = Sp.m),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.notice?.let {
            Text(it, fontSize = 13.sp, color = P.TextSecondary)
        }
        state.error?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }

        // ---- 1. Active Jobs ----------------------------------------------
        if (activeJobs.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color(0xFF66BB6A), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Running now",
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
                        "${activeJobs.size}",
                        fontSize = 12.sp,
                        color = P.TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            activeJobs.forEach { job ->
                ActiveJobCard(
                    job = job,
                    onOpen = { onJobClick(job.job_id) },
                    onCancel = { onCancelJob(job.job_id) },
                )
            }
        }

        // ---- 2. Recent Jobs ----------------------------------------------
        if (recentJobs.isNotEmpty()) {
            Text(
                "Recent Jobs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = P.TextPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )

            recentJobs.forEach { job ->
                RecentJobCard(
                    job = job,
                    onOpen = { onJobClick(job.job_id) },
                )
            }
        }

        // ---- 3. Empty State ----------------------------------------------
        if (activeJobs.isEmpty() && recentJobs.isEmpty()) {
            TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 24.dp) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = P.SurfaceHigh,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.History,
                                contentDescription = null,
                                tint = P.TextMuted,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    Text(
                        "No jobs yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = P.TextPrimary,
                    )

                    Text(
                        "Execute a command or launch a tool from the Run tab to see output here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = P.TextSecondary,
                    )

                    TrmxButton(
                        label = "Open Run Tab",
                        onClick = onGoToRun,
                        kind = TrmxButtonKind.Secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveJobCard(
    job: JobSummary,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    TrmxCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp,
        onClick = onOpen,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF142417),
                    border = BorderStroke(1.dp, Color(0xFF285430)),
                ) {
                    Text(
                        job.status,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF66BB6A),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    JobLabels.elapsedOrDuration(job),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = P.TextSecondary,
                )

                Spacer(Modifier.weight(1f))

                TrmxButton(
                    label = "Stop",
                    leading = { Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    onClick = onCancel,
                    kind = TrmxButtonKind.Danger,
                )
            }

            Text(
                JobLabels.taskLabel(job),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = P.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = P.Accent,
                trackColor = P.SurfaceHigh,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Tap to watch live terminal output",
                    fontSize = 12.sp,
                    color = P.TextMuted,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = P.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentJobCard(
    job: JobSummary,
    onOpen: () -> Unit,
) {
    val completed = job.status == "COMPLETED"
    val failed = job.status == "FAILED"

    TrmxCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 14.dp,
        onClick = onOpen,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = when {
                    completed -> Color(0xFF142417)
                    failed -> Color(0xFF2C1515)
                    else -> P.SurfaceHigh
                },
                border = BorderStroke(
                    1.dp,
                    when {
                        completed -> Color(0xFF285430)
                        failed -> Color(0xFF5A2525)
                        else -> P.Border
                    }
                ),
            ) {
                Text(
                    when (job.status) {
                        "COMPLETED" -> "DONE"
                        "CANCELLED" -> "STOP"
                        else -> job.status
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        completed -> Color(0xFF66BB6A)
                        failed -> P.Danger
                        else -> P.TextSecondary
                    },
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    JobLabels.taskLabel(job),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = P.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    JobLabels.relativeTime(job.created_at)?.let { relTime ->
                        Text(
                            relTime,
                            fontSize = 11.sp,
                            color = P.TextMuted,
                        )
                        Text(" · ", fontSize = 11.sp, color = P.TextMuted)
                    }
                    Text(
                        JobLabels.elapsedOrDuration(job),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = P.TextMuted,
                    )
                    job.exit_code?.let { code ->
                        Text(" · ", fontSize = 11.sp, color = P.TextMuted)
                        Text(
                            "exit $code",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (code == 0) P.TextMuted else P.Danger,
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = P.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
