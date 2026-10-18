package dev.trmx.gui.ui

/*
 * TRMX theme (revival: monochrome identity).
 *
 * Every constant comes from Tokens — this file only MAPS tokens onto the
 * Material 3 scheme and the legacy compat surface (Sp, FieldShape,
 * TrmxColors, TrmxFieldColors). Dark is the identity (no toggle).
 */

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.trmx.gui.ui.Tokens.Palette as P

private val TrmxScheme = darkColorScheme(
    primary = P.Accent,
    onPrimary = P.OnAccent,
    primaryContainer = P.AccentContainer,
    onPrimaryContainer = P.OnAccentContainer,
    secondary = P.Steel,
    onSecondary = Color(0xFF0A0E14),
    secondaryContainer = P.SteelContainer,
    onSecondaryContainer = P.OnSteelContainer,
    tertiary = P.Steel,
    onTertiary = Color(0xFF0A0E14),
    tertiaryContainer = P.SteelContainer,
    onTertiaryContainer = P.OnSteelContainer,
    background = P.Background,
    onBackground = P.TextPrimary,
    surface = P.Surface,
    onSurface = P.TextPrimary,
    surfaceVariant = P.SurfaceHigh,
    onSurfaceVariant = P.TextSecondary,
    surfaceContainer = P.SurfaceHigh,
    surfaceContainerHigh = P.SurfaceHighest,
    surfaceContainerHighest = P.SurfaceHighest,
    outline = P.BorderStrong,
    outlineVariant = P.Border,
    error = P.Danger,
    onError = P.OnDanger,
    errorContainer = P.DangerContainer,
    onErrorContainer = P.OnDangerContainer,
)

// ---- compat surface (Ph 9.5 names; now token-driven) ----------------------

/** Spacing grid — delegates to Tokens.Space. */
val Sp = Tokens.Space

/** Shared field corner — delegates to Tokens.Radius.m. */
val FieldShape = RoundedCornerShape(Tokens.Radius.m)

private val TrmxShapes = Shapes(
    extraSmall = RoundedCornerShape(Tokens.Radius.s),
    small = RoundedCornerShape(Tokens.Radius.s),
    medium = RoundedCornerShape(Tokens.Radius.m),
    large = RoundedCornerShape(Tokens.Radius.l),
    extraLarge = RoundedCornerShape(Tokens.Radius.l),
)

/**
 * Semantic status/tier colors — ONE source, token-driven. Monochrome:
 * running = white (active/focus), completed = grey, queued = dim grey,
 * failed = semantic red, cancelled = muted grey.
 */
object TrmxColors {
    val Running = P.Accent
    val Completed = P.Steel
    val Failed = P.Danger
    val Cancelled = P.TextMuted
    val Queued = P.SteelDim
    val TierSafe = P.Steel
    val TierConfirm = P.Accent
    val TierDestructive = P.Danger
    val Symlink = P.Steel

    fun status(s: String): Color = when (s) {
        "RUNNING", "STARTING" -> Running
        "COMPLETED" -> Completed
        "FAILED" -> Failed
        "CANCELLED", "LOST" -> Cancelled
        "QUEUED" -> Queued
        "CANCELLING" -> Failed
        else -> Cancelled
    }

    fun tier(t: String): Color = when (t) {
        "safe" -> TierSafe
        "confirm" -> TierConfirm
        "destructive" -> TierDestructive
        else -> Cancelled
    }
}

/**
 * One source of truth for every raw text field: explicit dark containers,
 * border, text and cursor from Tokens (belt-and-braces on top of the dark
 * XML platform theme — see themes.xml).
 */
@Composable
fun TrmxFieldColors() = OutlinedTextFieldDefaults.colors(
    // Monochrome: white focus ring on the dark fill.
    focusedContainerColor = P.FieldFill,
    unfocusedContainerColor = P.FieldFill,
    disabledContainerColor = P.FieldFill,
    focusedTextColor = P.TextPrimary,
    unfocusedTextColor = P.TextPrimary,
    focusedBorderColor = P.Accent,
    unfocusedBorderColor = P.Border,
    focusedLabelColor = P.Accent,
    unfocusedLabelColor = P.TextSecondary,
    focusedSupportingTextColor = P.TextSecondary,
    unfocusedSupportingTextColor = P.TextSecondary,
    cursorColor = P.Accent,
)

@Composable
fun TRMXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TrmxScheme,
        typography = Typography(),
        shapes = TrmxShapes,
    ) {
        // A bare MaterialTheme sets NO content color: any Text composed
        // outside a Card/Scaffold then falls back to LocalContentColor's
        // default — black — and renders invisible on the black window
        // background (device round 2, 2026-10-17: the setup screen's
        // header, stage title and status lines were all present but
        // black-on-black; only card contents showed). The theme itself
        // must own the base surface so no screen can repeat this.
        Surface(
            color = P.Background,
            contentColor = P.TextPrimary,
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}
