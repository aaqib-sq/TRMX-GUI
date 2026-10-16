package dev.trmx.gui.ui

/*
 * Agent tab — the app's front door (revival). A plain chat surface over the
 * §5 agent protocol: the user writes an instruction, the agent narrates its
 * steps as plain rows, pauses with an Allow/Deny card on anything
 * destructive, and a visible Stop button cancels the running task.
 * The active AI backend (mode + model, free vs API cost) is always visible
 * at the top — no step ever spends money by surprise.
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trmx.gui.agent.AgentConfirm
import dev.trmx.gui.agent.AgentRow
import dev.trmx.gui.agent.AgentRowKind
import dev.trmx.gui.agent.AgentState
import dev.trmx.gui.ui.Tokens.Palette as P

@Composable
fun AgentScreen(
    state: AgentState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onStop: () -> Unit,
    onDismissSendError: () -> Unit,
    onDismissStreamError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        BackendBar(
            label = state.backendLabel,
            free = state.backendFree,
            configured = state.backendConfigured,
            loading = state.backendLoading,
        )

        val listState = rememberLazyListState()
        LaunchedEffect(state.rows.size) {
            if (state.rows.isNotEmpty()) {
                listState.animateScrollToItem(state.rows.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.s),
        ) {
            if (state.rows.isEmpty() && !state.active) {
                item {
                    TrmxCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                            Text(
                                "Tell me what to do.",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Text(
                                "Plain words — \"download this video's audio\", " +
                                    "\"back up last night's files\". " +
                                    "I'll show every step before and while it runs.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = P.TextSecondary)
                        }
                    }
                }
            }
            items(state.rows.size) { i ->
                AgentRowCard(state.rows[i])
            }
            state.confirm?.let { confirm ->
                item {
                    ConfirmCard(
                        confirm = confirm,
                        busy = state.confirmBusy,
                        onAllow = onAllow,
                        onDeny = onDeny,
                    )
                }
            }
            if (state.active) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = Sp.xs)) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = P.TextSecondary,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Sp.s))
                        Text("working…",
                             style = MaterialTheme.typography.bodySmall,
                             color = P.TextSecondary)
                    }
                }
            }
        }

        state.sendError?.let { err ->
            ErrorStrip(text = err, onDismiss = onDismissSendError)
        }
        state.streamError?.let { err ->
            ErrorStrip(text = err, onDismiss = onDismissStreamError)
        }

        InputBar(
            input = state.input,
            onInput = onInput,
            onSend = onSend,
            sending = state.sending,
            active = state.active,
            onStop = onStop,
        )
    }
}

/** Always-on backend strip: which AI answers, and whether it costs money. */
@Composable
private fun BackendBar(
    label: String?,
    free: Boolean?,
    configured: Boolean,
    loading: Boolean,
) {
    val (text, color) = when {
        loading -> "checking AI backend…" to P.TextMuted
        label == null -> "AI backend unknown" to P.TextMuted
        !configured -> "AI backend not configured — set it in Settings" to P.Danger
        free == true -> "$label · free (runs on this phone)" to P.TextSecondary
        free == false -> "$label · uses API — costs money" to P.Danger
        else -> label to P.TextSecondary
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Sp.m, vertical = Sp.xs),
    )
}

@Composable
private fun AgentRowCard(row: AgentRow) {
    val (titleColor, detailColor) = when (row.kind) {
        AgentRowKind.FAILED -> P.Danger to P.TextSecondary
        AgentRowKind.DONE -> P.TextPrimary to P.TextSecondary
        AgentRowKind.NOTE -> P.TextMuted to P.TextMuted
        AgentRowKind.BACKEND -> P.TextMuted to P.TextMuted
        AgentRowKind.RESULT_FAIL -> P.Danger to P.TextSecondary
        else -> P.TextPrimary to P.TextSecondary
    }
    TrmxCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = Sp.s,
    ) {
        Text(row.title,
             style = MaterialTheme.typography.bodyMedium,
             fontWeight = if (row.kind == AgentRowKind.DOING ||
                 row.kind == AgentRowKind.DONE) FontWeight.SemiBold else null,
             color = titleColor)
        row.detail?.let {
            Text(it,
                 style = MaterialTheme.typography.bodySmall,
                 color = detailColor,
                 modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The destructive pause (§5): plain description, Allow / Deny. */
@Composable
private fun ConfirmCard(
    confirm: AgentConfirm,
    busy: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    TrmxCard(
        modifier = Modifier.fillMaxWidth(),
        border = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
            Text("ये करने जा रहा हूँ:",
                 style = MaterialTheme.typography.labelSmall,
                 color = P.TextSecondary)
            Text(confirm.describe,
                 style = MaterialTheme.typography.bodyLarge,
                 fontWeight = FontWeight.SemiBold)
            if (confirm.why.isNotEmpty()) {
                Text("Needs your approval (${confirm.why})",
                     style = MaterialTheme.typography.bodySmall,
                     color = P.TextMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.s)) {
                TrmxButton(
                    label = if (busy) "…" else "Allow",
                    onClick = onAllow,
                    kind = TrmxButtonKind.Primary,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                TrmxButton(
                    label = "Deny",
                    onClick = onDeny,
                    kind = TrmxButtonKind.Secondary,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
            Text("No answer within 10 minutes counts as Deny.",
                 style = MaterialTheme.typography.labelSmall,
                 color = P.TextMuted)
        }
    }
}

@Composable
private fun ErrorStrip(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Sp.m, vertical = Sp.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text,
             style = MaterialTheme.typography.bodySmall,
             color = P.Danger,
             modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = onDismiss) {
            Text("dismiss", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun InputBar(
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    sending: Boolean,
    active: Boolean,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Sp.m),
        verticalArrangement = Arrangement.spacedBy(Sp.s),
    ) {
        if (active) {
            TrmxButton(
                label = "Stop",
                onClick = onStop,
                kind = TrmxButtonKind.Danger,
                leading = {
                    Icon(Icons.Filled.Stop, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            TrmxTextField(
                value = input,
                onValueChange = onInput,
                label = "What should I do?",
                modifier = Modifier.weight(1f),
                singleLine = false,
                minLines = 1,
                enabled = !sending && !active,
            )
            Spacer(Modifier.width(Sp.s))
            androidx.compose.material3.IconButton(
                onClick = onSend,
                enabled = input.isNotBlank() && !sending && !active,
                modifier = Modifier
                    .size(52.dp)
                    .padding(bottom = 2.dp),
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = P.TextSecondary,
                        modifier = Modifier.size(22.dp))
                } else {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "send",
                        tint = if (input.isNotBlank()) P.Accent else P.TextMuted,
                        modifier = Modifier.size(26.dp))
                }
            }
        }
        Text(
            "The agent only acts through the bridge's tools — no raw shell, " +
                "no new binaries, and it always asks before anything destructive.",
            style = MaterialTheme.typography.labelSmall,
            color = P.TextMuted,
        )
    }
}
