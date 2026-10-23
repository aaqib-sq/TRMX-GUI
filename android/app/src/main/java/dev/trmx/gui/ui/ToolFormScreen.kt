package dev.trmx.gui.ui

/*
 * Dynamic tool form (Compact Redesign). Schema-driven fields with
 * M3 floating labels ON the field outline, FilterChip enums, bounded
 * sliders, x_trmx secrets/units, vector icons — and a sticky
 * full-width primary Run action. Errors only after touch; the argv
 * preview stays the centerpiece.
 */

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trmx.gui.ToolFormState
import dev.trmx.gui.model.ToolArg
import dev.trmx.gui.model.ToolExample
import dev.trmx.gui.tools.FieldValue
import dev.trmx.gui.tools.FormEngine
import dev.trmx.gui.ui.Tokens.Palette as P

@Composable
fun ToolFormScreen(
    state: ToolFormState,
    onBack: () -> Unit,
    onEdit: (String, FieldValue) -> Unit,
    onExample: (ToolExample) -> Unit,
    onSubmit: () -> Unit,
) {
    val schema = state.schema ?: return
    var confirmRun by remember { mutableStateOf(false) }

    val preview = FormEngine.previewArgv(schema, state.values)

    Column(modifier = Modifier.fillMaxSize()) {
        TrmxTopBar(
            title = schema.name.ifBlank { schema.id },
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(Sp.m),
            verticalArrangement = Arrangement.spacedBy(Sp.m),
        ) {
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.primary)
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            }

            // Description / overview card
            schema.description?.takeIf { it.isNotBlank() }?.let { desc ->
                TrmxCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = P.TextSecondary,
                    )
                }
            }

            // Example chips (1 tap fills the form)
            if (schema.examples.isNotEmpty()) {
                TrmxCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                        Text("Presets", style = MaterialTheme.typography.labelSmall,
                             color = P.TextSecondary)
                        ActionFlowRow(horizontalGap = Sp.xs, verticalGap = Sp.xs) {
                            schema.examples.forEach { ex ->
                                FilterChip(
                                    selected = false,
                                    onClick = { onExample(ex) },
                                    label = { Text(ex.label, style = MaterialTheme.typography.bodySmall) },
                                    colors = TrmxChipColors(),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = false,
                                        borderColor = P.Border,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            // Generated form fields
            schema.args.forEach { a ->
                FormField(
                    a = a,
                    v = state.values[a.name] ?: FieldValue(),
                    touched = a.name in state.touched,
                    onEdit = { onEdit(a.name, it) },
                )
            }

            // Argv preview strip
            TrmxCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    Text("Command Preview", style = MaterialTheme.typography.labelSmall,
                         color = P.TextSecondary)
                    Text(
                        preview.joinToString(" ") { shellWord(it) },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = P.TextPrimary,
                    )
                }
            }
        }

        // Sticky Run Action
        Surface(
            color = P.Background,
            border = BorderStroke(1.dp, P.Border),
            modifier = Modifier.fillMaxWidth().imePadding(),
        ) {
            Box(Modifier.padding(horizontal = Sp.m, vertical = Sp.s)) {
                TrmxButton(
                    label = when {
                        state.submitting -> "Starting…"
                        schema.risk_tier == "confirm" -> "Run with confirmation"
                        else -> "Run Tool"
                    },
                    leading = if (state.submitting) {
                        { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                    } else {
                        { Icon(Icons.Filled.PlayArrow, contentDescription = null) }
                    },
                    onClick = {
                        if (schema.risk_tier == "confirm") confirmRun = true
                        else onSubmit()
                    },
                    kind = TrmxButtonKind.Primary,
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                )
            }
        }
    }

    if (confirmRun) {
        AlertDialog(
            onDismissRequest = { confirmRun = false },
            title = { Text("Run ${schema.name}?") },
            text = {
                Text("This tool is marked risk_tier=confirm: it may modify or overwrite files in Termux.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRun = false
                    onSubmit()
                }) { Text("Run") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRun = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormField(
    a: ToolArg,
    v: FieldValue,
    touched: Boolean,
    onEdit: (FieldValue) -> Unit,
) {
    val err = FormEngine.visibleError(a, v, touched)
    val widget = FormEngine.widgetFor(a)
    val unit = a.x_trmx?.unit
    val helpText = a.help
    var secretRevealed by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
        when (widget) {
            "toggle" -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall)
                        if (helpText != null) {
                            Text(helpText, style = MaterialTheme.typography.bodySmall,
                                 color = P.TextSecondary)
                        }
                    }
                    Switch(
                        checked = v.bool ?: false,
                        onCheckedChange = { onEdit(v.copy(bool = it)) },
                    )
                }
            }

            "chips", "segmented" -> {
                Text(a.name, style = MaterialTheme.typography.titleSmall)
                ActionFlowRow(horizontalGap = Sp.xs, verticalGap = Sp.xs) {
                    (a.enum ?: emptyList()).forEach { opt ->
                        FilterChip(
                            selected = v.text == opt,
                            onClick = { onEdit(v.copy(text = opt)) },
                            label = { Text(opt) },
                            colors = TrmxChipColors(),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = v.text == opt,
                                borderColor = if (v.text == opt) P.BorderStrong else P.Border,
                            ),
                        )
                    }
                }
            }

            "slider" -> {
                val min = (a.min ?: 0.0).toFloat()
                val max = (a.max ?: 100.0).toFloat()
                val cur = v.text.toFloatOrNull() ?: min
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, style = MaterialTheme.typography.titleSmall,
                         modifier = Modifier.weight(1f))
                    Text(FormEngine.sliderValueText(a.type, cur) + (unit?.let { " $it" } ?: ""),
                         style = MaterialTheme.typography.labelMedium)
                }
                Slider(
                    value = cur.coerceIn(min, max),
                    onValueChange = { onEdit(v.copy(text = FormEngine.sliderValueText(a.type, it))) },
                    valueRange = min..max,
                )
            }

            else -> TrmxTextField(
                value = v.text,
                onValueChange = { onEdit(v.copy(text = it)) },
                label = a.name + if (a.required) " *" else "",
                isError = err != null,
                supportingText = if (err != null || helpText != null) {
                    {
                        if (err != null) {
                            Text(err, color = MaterialTheme.colorScheme.error)
                        } else if (helpText != null) {
                            Text(helpText)
                        }
                    }
                } else null,
                trailingIcon = when {
                    a.x_trmx?.secret == true -> {
                        {
                            IconButton(onClick = { secretRevealed = !secretRevealed }) {
                                Icon(
                                    if (secretRevealed) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = "reveal",
                                    tint = P.TextSecondary)
                            }
                        }
                    }
                    else -> null
                },
                visualTransformation =
                    if (a.x_trmx?.secret == true && !secretRevealed) PasswordVisualTransformation()
                    else VisualTransformation.None,
                suffix = unit?.let { u -> { Text(u) } },
                keyboardOptions = when (a.type) {
                    "int", "float" -> KeyboardOptions(keyboardType = KeyboardType.Number)
                    "url" -> KeyboardOptions(keyboardType = KeyboardType.Uri)
                    else -> KeyboardOptions.Default
                },
            )
        }

        if (widget != "field") {
            if (err != null) {
                Text(err, style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error)
            } else if (helpText != null) {
                Text(helpText, style = MaterialTheme.typography.bodySmall,
                     color = P.TextSecondary)
            }
        }
    }
}

@Composable
fun TrmxChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = P.FieldFill,
    labelColor = P.TextPrimary,
    selectedContainerColor = P.AccentContainer,
    selectedLabelColor = P.OnAccentContainer,
    selectedLeadingIconColor = P.Accent,
)

private fun shellWord(w: String): String =
    if (w.contains(' ') || w.contains('\"')) "\"$w\"" else w
