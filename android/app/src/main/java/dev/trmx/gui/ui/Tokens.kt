package dev.trmx.gui.ui

/*
 * TRMX design tokens (revival: monochrome identity) — the SINGLE source of
 * truth for every visual constant in the app. Screens and components may not
 * hardcode colors, radii or spacing; they reference Tokens.
 *
 * Palette: True-OLED pitch black ground + deep-contrast elevated surfaces
 * with 1 px outlines. Monochrome identity: WHITE primary actions on black,
 * greys for secondary. NO cyan, NO blue, NO green/mint, NO orange/amber
 * anywhere. Crisp white primary text, muted grey metadata, semantic red
 * ONLY for critical failures.
 */

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Tokens {

    /** Ground + elevated surfaces (OLED). */
    object Palette {
        val Background = Color(0xFF000000)     // true OLED black (ground)
        val Surface = Color(0xFF0B111A)        // cards
        val FieldFill = Color(0xFF0A0E17)      // input field fill
        val SurfaceHigh = Color(0xFF121824)    // sheets / secondary surfaces
        val SurfaceHighest = Color(0xFF1A2232) // menus, pressed states
        val Border = Color(0xFF1E293B)         // 1 px outlines
        val BorderStrong = Color(0xFF2A3550)   // focused/active outlines

        /** Text. */
        val TextPrimary = Color(0xFFFFFFFF)    // crisp white
        val TextSecondary = Color(0xFF94A3B8)  // muted grey
        val TextMuted = Color(0xFF5F6B80)      // faint metadata

        /** Accent — white. Primary actions are white-on-black, the ONE loud
         *  element per screen. (The old ice-cyan is gone for good.) */
        val Accent = Color(0xFFFFFFFF)
        val OnAccent = Color(0xFF000000)
        val AccentContainer = Color(0xFF22262D)
        val OnAccentContainer = Color(0xFFFFFFFF)

        /** Grey — calm secondary / completed. (The old steel blue is gone.) */
        val Steel = Color(0xFFB9C1CC)
        val SteelDim = Color(0xFF78818D)
        val SteelContainer = Color(0xFF181C22)
        val OnSteelContainer = Color(0xFFE6EBF1)

        /** Semantic red — critical failures ONLY. */
        val Danger = Color(0xFFF87171)
        val OnDanger = Color(0xFF2B0B0B)
        val DangerContainer = Color(0xFF3F1D1D)
        val OnDangerContainer = Color(0xFFFECACA)
    }

    /** Spacing grid (4-based; the Ph 9.5 scale, unchanged). */
    object Space {
        val xs = 4.dp
        val s = 8.dp
        val m = 16.dp
        val l = 24.dp
        val xl = 32.dp
    }

    /** Corner radii. */
    object Radius {
        val s = 8.dp
        val m = 12.dp   // fields (the established FieldShape)
        val l = 16.dp   // cards
    }
}
