package dev.trmx.gui.ui

/*
 * Agent tab — the app's front door (revival). A plain chat surface over the
 * §5 agent protocol: the user writes an instruction, the agent narrates its
 * steps as plain rows, pauses with an Allow/Deny card on anything
 * destructive, and while a task runs the send button becomes
 * the stop button (same place — the chat-app pattern).
 * The active AI backend (mode + model, free vs API cost) is always visible
 * at the top — no step ever spends money by surprise.
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
    onClear: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        BackendBar(
            label = state.backendLabel,
            free = state.backendFree,
            configured = state.backendConfigured,
            loading = state.backendLoading,
            canClear = state.rows.isNotEmpty() && !state.active,
            onClear = onClear,
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
                                "In plain words — \"extract the audio from this " +
                                    "video\", \"back up last night's files\". " +
                                    "I show every step as it happens.",
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
                        Text("Working…",
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
            // Don't let a tap vanish into a doomed task: the backend strip
            // above already explains how to configure it.
            backendBlocked = !state.backendConfigured && !state.backendLoading,
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
    canClear: Boolean = false,
    onClear: () -> Unit = {},
) {
    val (text, color) = when {
        loading -> "Checking the AI…" to P.TextMuted
        label == null -> "AI backend unknown" to P.TextMuted
        !configured -> "AI is not set up — configure it in Settings" to P.Danger
        free == true -> "$label · free (runs on this phone)" to P.TextSecondary
        free == false -> "$label · API — costs money" to P.Danger
        else -> label to P.TextSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Sp.m, vertical = Sp.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (canClear) {
            TextButton(
                onClick = onClear,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Sp.xs, vertical = 0.dp),
            ) {
                Text(
                    "New chat",
                    style = MaterialTheme.typography.labelSmall,
                    color = P.Accent,
                )
            }
        }
    }
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
        // Result excerpts can run to 1500 chars (§2-M7) — render them
        // capped at ~300 with a tap to expand, never as a wall of text.
        // Display-only: the stored row keeps the full text.
        val isResult = row.kind == AgentRowKind.RESULT_OK ||
            row.kind == AgentRowKind.RESULT_FAIL
        val capped = isResult && row.title.length > RESULT_CAP
        var expanded by remember(row.title) { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Proper vector icons instead of ✓/✗ text glyphs (audit
            // §1-L2): material-icons-extended already provides Check /
            // Close equivalents, used across the app.
            when (row.kind) {
                AgentRowKind.RESULT_OK -> {
                    Icon(Icons.Filled.Check, contentDescription = "done",
                         tint = titleColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Sp.xs))
                }
                AgentRowKind.RESULT_FAIL -> {
                    Icon(Icons.Filled.Close, contentDescription = "failed",
                         tint = titleColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Sp.xs))
                }
                else -> Unit
            }
            Text(if (capped && !expanded) row.title.take(RESULT_CAP) + "…" else row.title,
                 style = MaterialTheme.typography.bodyMedium,
                 fontWeight = if (row.kind == AgentRowKind.DOING ||
                     row.kind == AgentRowKind.DONE) FontWeight.SemiBold else null,
                 color = titleColor)
        }
        if (capped) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Show more",
                     style = MaterialTheme.typography.labelSmall)
            }
        }
        row.detail?.let {
            Text(it,
                 style = MaterialTheme.typography.bodySmall,
                 color = detailColor,
                 modifier = Modifier.padding(top = 2.dp))
        }
        // Copy (user request, 2026-10-20): every answer/step card can be
        // copied like in a chat app — the FULL uncapped text, not the
        // 300-char display cap. Uses the design system's CopyButton
        // ("Copied ✓" feedback built in).
        CopyButton(
            label = "Copy",
            textToCopy = listOfNotNull(row.title, row.detail)
                .joinToString("\n"),
            kind = TrmxButtonKind.Ghost,
            modifier = Modifier.padding(top = Sp.xs),
        )
    }
}

private const val RESULT_CAP = 300

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
            Text("I'm about to do this:",
                 style = MaterialTheme.typography.labelSmall,
                 color = P.TextSecondary)
            Text(confirm.describe,
                 style = MaterialTheme.typography.bodyLarge,
                 fontWeight = FontWeight.SemiBold)
            if (confirm.why.isNotEmpty()) {
                val whyText = when (confirm.why) {
                    "destructive" -> "destructive work"
                    "risk-tier" -> "risky work"
                    else -> confirm.why
                }
                Text("Your permission is needed ($whyText)",
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
            Text("No answer in 10 minutes = treated as No.",
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
            Text("Dismiss", style = MaterialTheme.typography.labelSmall)
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
    backendBlocked: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // imePadding: the pinned input bar (and Stop) ride ABOVE the
            // keyboard — the message list above shrinks, the bar is
            // never covered by the IME at any font scale.
            .imePadding()
            .padding(Sp.m),
        verticalArrangement = Arrangement.spacedBy(Sp.s),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            TrmxTextField(
                value = input,
                onValueChange = onInput,
                label = "What should I do?",
                modifier = Modifier.weight(1f),
                singleLine = false,
                minLines = 1,
                enabled = !sending && !active && !backendBlocked,
            )
            Spacer(Modifier.width(Sp.s))
            // While a task runs, the send button IS the stop button —
            // same place, same button (the pattern every chat app uses;
            // device request, 2026-10-20). No separate Stop control.
            androidx.compose.material3.IconButton(
                onClick = if (active) onStop else onSend,
                enabled = active ||
                    (input.isNotBlank() && !sending && !backendBlocked),
                modifier = Modifier
                    .size(52.dp)
                    .padding(bottom = 2.dp),
            ) {
                when {
                    active -> Icon(
                        Icons.Filled.Stop,
                        contentDescription = "stop",
                        tint = P.Danger,
                        modifier = Modifier.size(26.dp))
                    sending -> CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = P.TextSecondary,
                        modifier = Modifier.size(22.dp))
                    else -> Icon(
                        Icons.Filled.Send,
                        contentDescription = "send",
                        tint = if (input.isNotBlank()) P.Accent else P.TextMuted,
                        modifier = Modifier.size(26.dp))
                }
            }
        }
        Text(
            "The agent only uses bridge tools — no raw commands, and it " +
                "always asks before deleting or changing anything.",
            style = MaterialTheme.typography.labelSmall,
            color = P.TextMuted,
        )
    }
}
