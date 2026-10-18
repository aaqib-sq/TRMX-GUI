package dev.trmx.gui.ui

/*
 * Tools (redesign v3): the approved final look — BIG neutral cards, one
 * vector icon tile per tool, name + one program line, a READY pill or an
 * Install button. No description paragraphs (the form itself explains the
 * tool). Order: make-a-tool, the tools, custom command, recipes, services.
 * No duplicate heading (the shell top bar already says Tools).
 */

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.model.ToolStatus
import dev.trmx.gui.store.Recipe
import dev.trmx.gui.ui.Tokens.Palette as P

@Composable
fun ToolboxScreen(
    state: dev.trmx.gui.ToolsState,
    recipes: List<Recipe>,
    submitForm: dev.trmx.gui.SubmitFormState,
    onRefresh: () -> Unit,
    onOpenTool: (String) -> Unit,
    onInstall: (ToolStatus) -> Unit,
    onOpenRecipe: (String) -> Unit,
    onDeleteRecipe: (String) -> Unit,
    onShareRecipe: (Recipe) -> Unit,
    onImportRecipe: (android.net.Uri) -> Unit,
    onOpenServices: () -> Unit,
    onOpenSchemaBuilder: () -> Unit,
    onSubmitName: (String) -> Unit,
    onSubmitArgv: (String) -> Unit,
    onSubmitCwd: (String) -> Unit,
    onSubmitTimeout: (String) -> Unit,
    onSubmitJob: () -> Unit,
    onDismissSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportRecipe(uri)
    }
    var confirmInstall by remember { mutableStateOf<ToolStatus?>(null) }
    // Manual argv submission lives here (expert action, not a primary goal).
    var showSubmit by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = Sp.m),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Spacer(Modifier.weight(1f))
            if (state.loading) CircularProgressIndicator(strokeWidth = 3.dp)
            Spacer(Modifier.width(8.dp))
            TrmxButton(label = "Scan",
                       leading = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                       onClick = onRefresh, kind = TrmxButtonKind.Secondary)
        }

        state.notice?.let {
            Text(it, fontSize = 14.sp, color = P.TextSecondary)
        }
        state.error?.let {
            Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
        }
        if (state.schemaErrors.isNotEmpty()) {
            TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 20.dp) {
                Column {
                    Text("Some user schemas in ~/.trmx/tools/ were skipped:",
                         fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    state.schemaErrors.take(3).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                             fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                             fontSize = 10.sp)
                    }
                }
            }
        }

        // Make-a-tool first (the approved layout): describe it, AI builds it.
        NavCard(
            icon = Icons.Outlined.AutoAwesome,
            title = "Make a new tool",
            sub = "Describe it in plain words",
            onClick = onOpenSchemaBuilder,
        )

        if (!state.loading && state.tools.isEmpty() && state.error == null) {
            Text("No tools yet — tap Scan.", fontSize = 15.sp, color = P.TextSecondary)
        }

        state.tools.forEach { t -> ToolCard(t, onOpenTool) { confirmInstall = t } }

        NavCard(
            icon = Icons.Outlined.Terminal,
            title = "Custom command",
            sub = "Run any command directly",
            onClick = { showSubmit = true },
        )

        // ---- recipes ------------------------------------------------------
        TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 20.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(Sp.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recipes", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(Sp.s))
                    Text("(${recipes.size})", fontSize = 15.sp, color = P.TextMuted)
                    Spacer(Modifier.weight(1f))
                    TrmxButton(label = "Import",
                               onClick = { importPicker.launch(arrayOf("application/json", "*/*")) },
                               kind = TrmxButtonKind.Secondary)
                }
                if (recipes.isEmpty()) {
                    Text("Save a filled tool form as a recipe — it becomes a " +
                         "one-tap shortcut.",
                         fontSize = 14.sp, color = P.TextSecondary)
                }
                recipes.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenRecipe(r.id) }) {
                            Text(r.title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(r.toolId, fontSize = 13.sp, color = P.TextMuted)
                        }
                        TextButton(onClick = { onShareRecipe(r) }) { Text("Share") }
                        TextButton(onClick = { onDeleteRecipe(r.id) }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }

        TrmxSectionLabel("Services")
        NavCard(
            icon = Icons.Outlined.Dns,
            title = "Services",
            sub = "Long-running tools, on/off",
            onClick = onOpenServices,
        )
    }

    if (showSubmit) {
        SubmitDialog(
            state = submitForm,
            onName = onSubmitName,
            onArgv = onSubmitArgv,
            onCwd = onSubmitCwd,
            onTimeout = onSubmitTimeout,
            onSubmit = onSubmitJob,
            onDismiss = {
                showSubmit = false
                onDismissSubmit()
            },
        )
    }

    confirmInstall?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmInstall = null },
            title = { Text("Install ${t.schema.pkg ?: t.schema.binary}?") },
            text = {
                Text("Runs `pkg install -y ${t.schema.pkg ?: t.schema.binary}` as a " +
                     "normal job with a live console. The toolbox rescans " +
                     "automatically when it finishes.")
            },
            confirmButton = {
                TextButton(onClick = { onInstall(t); confirmInstall = null }) {
                    Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = null }) { Text("Cancel") }
            },
        )
    }
}

/** Big navigation card: icon tile + title + one line + chevron. */
@Composable
private fun NavCard(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    TrmxCard(modifier = Modifier.fillMaxWidth(), contentPadding = 20.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Sp.m)) {
            TrmxIconTile(icon)
            Column(modifier = Modifier.weight(1f),
                   verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(sub, fontSize = 14.sp, color = P.TextSecondary)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                 tint = P.TextMuted, modifier = Modifier.size(26.dp))
        }
    }
}

private fun toolIcon(t: ToolStatus): ImageVector {
    val key = (t.schema.binary + " " + t.schema.id + " " + t.schema.name).lowercase()
    return when {
        "yt-dlp" in key || "youtube" in key -> Icons.Outlined.Download
        "aria2" in key -> Icons.Outlined.Bolt
        "ffmpeg" in key -> Icons.Outlined.Movie
        "git" in key -> Icons.Outlined.Folder
        "ai" in key -> Icons.Outlined.AutoAwesome
        else -> Icons.Outlined.Build
    }
}

/** One tool = one big card: icon tile, name, program line, READY or Install. */
@Composable
private fun ToolCard(t: ToolStatus, onOpenTool: (String) -> Unit, onInstall: () -> Unit) {
    TrmxCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 20.dp,
        onClick = if (t.installed) ({ onOpenTool(t.schema.id) }) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Sp.m)) {
            TrmxIconTile(toolIcon(t))
            Column(modifier = Modifier.weight(1f),
                   verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                Text(t.schema.name, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                val program = buildString {
                    append(t.schema.binary)
                    t.version?.takeIf { it.isNotEmpty() }?.let { append(" · "); append(it) }
                }
                Text(program, fontSize = 14.sp, color = P.TextSecondary,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t.installed) {
                TrmxBadge("READY", BadgeTone.Neutral)
            } else {
                TrmxButton(label = "Install", onClick = onInstall)
            }
        }
    }
}
