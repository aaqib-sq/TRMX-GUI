package dev.trmx.gui.ui

/*
 * TRMX design tokens (redesign v2: AMOLED black + amber identity) — the
 * SINGLE source of truth for every visual constant in the app. Screens and
 * components may not hardcode colors, radii or spacing; they reference
 * Tokens.
 *
 * Palette: true-black AMOLED ground, warm dark-amber-tinted surfaces,
 * one amber accent for primary actions. Chosen by the owner on
 * 2026-10-18 (preview-approved): NO white background, NO blue/navy,
 * minimal and calm. Warm off-white primary text, warm grey metadata,
 * semantic red ONLY for critical failures.
 */

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Tokens {

    /** Ground + elevated surfaces (AMOLED black, warm amber tint). */
    object Palette {
        val Background = Color(0xFF000000)     // true AMOLED black (ground)
        val Surface = Color(0xFF100D07)        // cards
        val FieldFill = Color(0xFF0A0803)      // input field fill
        val SurfaceHigh = Color(0xFF171208)    // sheets / secondary surfaces
        val SurfaceHighest = Color(0xFF201809) // menus, pressed states
        val Border = Color(0xFF2C2312)         // 1 px outlines
        val BorderStrong = Color(0xFF54431C)   // focused/active outlines

        /** Text. */
        val TextPrimary = Color(0xFFF7F3EA)    // warm off-white
        val TextSecondary = Color(0xFFC0B49A)  // warm grey
        val TextMuted = Color(0xFF877A5F)      // faint metadata

        /** Accent — amber. Primary actions are amber-on-black, the ONE
         *  loud element per screen. (Owner's pick, 2026-10-18.) */
        val Accent = Color(0xFFF2B33D)
        val OnAccent = Color(0xFF1D1303)
        val AccentContainer = Color(0xFF2A2110)
        val OnAccentContainer = Color(0xFFFFC95E)

        /** Warm grey — calm secondary / completed. */
        val Steel = Color(0xFFCBBB9E)
        val SteelDim = Color(0xFF97876A)
        val SteelContainer = Color(0xFF1D1810)
        val OnSteelContainer = Color(0xFFEFE6D2)

        /** Semantic red — critical failures ONLY. */
        val Danger = Color(0xFFFF8A7A)
        val OnDanger = Color(0xFF2B0B06)
        val DangerContainer = Color(0xFF381A15)
        val OnDangerContainer = Color(0xFFFFB4A6)
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
