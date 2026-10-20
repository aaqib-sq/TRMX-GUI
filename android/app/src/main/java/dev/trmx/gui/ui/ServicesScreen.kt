package dev.trmx.gui.ui

/*
 * Services (protocol 1.1, PROTOCOL §14): named long-running tools with
 * lifecycle management. Revival face: quiet switch rows (name + status +
 * switch), autostart as a second switch, restart / view-job / delete as
 * calm text actions. Composed from the foundation (Tokens, TrmxCard).
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trmx.gui.ServicesState
import dev.trmx.gui.model.ServiceStatus
import dev.trmx.gui.ui.Tokens.Palette as P

@Composable
fun ServicesScreen(
    state: ServicesState,
    onStart: (String) -> Unit,
    onStop: (String) -> Unit,
    onRestart: (String) -> Unit,
    onToggleAutostart: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onOpenJob: (String) -> Unit,
    onOpenToolbox: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf<ServiceStatus?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // (The tab scaffold's top bar carries this screen's title + refresh.)

        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error,
                 modifier = Modifier.padding(horizontal = Sp.m, vertical = Sp.xs))
        }
        state.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = P.TextSecondary,
                 modifier = Modifier.padding(horizontal = Sp.m, vertical = Sp.xs))
        }

        when {
            // honest capability gate: an older bridge cannot serve §14
            state.supported == false -> CapabilityCard()
            state.services.isEmpty() && !state.loading -> EmptyCard(onOpenToolbox)
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Sp.m),
            ) {
                items(state.services.size) { i ->
                    ServiceRow(
                        s = state.services[i],
                        onStart = { onStart(state.services[i].id) },
                        onStop = { onStop(state.services[i].id) },
                        onRestart = { onRestart(state.services[i].id) },
                        onToggleAutostart = { onToggleAutostart(state.services[i].id, it) },
                        onDelete = { confirmDelete = state.services[i] },
                        onOpenJob = onOpenJob,
                    )
                    if (i < state.services.size - 1) {
                        HorizontalDivider(color = P.Border)
                    }
                }
            }
        }
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${s.name}?") },
            text = {
                // Scrollable body: actions stay pinned at large fonts.
                Text("The definition is removed from ~/.trmx/services/. " +
                     "The tool itself stays installed.",
                     modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(s.id)
                    confirmDelete = null
                }) { Text("delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("cancel") }
            },
        )
    }
}

/** One service = one quiet switch row (name + status + switch). */
@Composable
private fun ServiceRow(
    s: ServiceStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onToggleAutostart: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpenJob: (String) -> Unit,
) {
    val running = s.state == "running"
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = P.Accent,
        checkedTrackColor = P.SteelDim,
        uncheckedThumbColor = P.TextMuted,
        uncheckedTrackColor = P.SurfaceHighest,
    )
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = Sp.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp)
                .background(if (running) P.Accent else P.TextMuted, CircleShape))
            Spacer(Modifier.width(Sp.s))
            Column(modifier = Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge,
                     fontWeight = FontWeight.SemiBold)
                Text(when {
                         running -> "running"
                         s.last_status == null -> "stopped"
                         else -> "stopped · last run ${s.last_status.lowercase()}"
                     },
                     style = MaterialTheme.typography.bodySmall,
                     color = P.TextSecondary)
            }
            Switch(checked = running,
                   onCheckedChange = { checked ->
                       if (checked) onStart() else onStop()
                   },
                   colors = switchColors)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Start on boot",
                 style = MaterialTheme.typography.bodySmall,
                 color = P.TextSecondary,
                 modifier = Modifier.weight(1f))
            Switch(checked = s.autostart,
                   onCheckedChange = onToggleAutostart,
                   colors = switchColors)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRestart) { Text("restart") }
            s.job_id?.let { id ->
                TextButton(onClick = { onOpenJob(id) }) { Text("view job") }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) {
                Text("delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmptyCard(onOpenToolbox: () -> Unit) {
    TrmxCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            Icon(Icons.Outlined.Dns, contentDescription = null, tint = P.Accent,
                 modifier = Modifier.size(22.dp))
            Text("No services yet", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Text(
                "Open a long-running tool (e.g. Local HTTP Server), fill its " +
                "form, then tap the service icon in its header to save it as a " +
                "service — start, stop and autostart it from here.",
                style = MaterialTheme.typography.bodySmall, color = P.TextSecondary)
            TrmxButton(label = "Open Toolbox", onClick = onOpenToolbox)
        }
    }
}

@Composable
private fun CapabilityCard() {
    TrmxCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
            Text("Bridge update needed", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Text(
                "Service management needs bridge v0.5.0+ (protocol 1.1). " +
                "Re-run the TRMX installer from Diagnostics, then reopen this tab.",
                style = MaterialTheme.typography.bodySmall, color = P.TextSecondary)
        }
    }
}
