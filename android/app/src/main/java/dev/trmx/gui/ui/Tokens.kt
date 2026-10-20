package dev.trmx.gui.ui

/*
 * TRMX design tokens (redesign v2: AMOLED black + white-neutral
 * identity) — the SINGLE source of truth for every visual constant in
 * the app. Screens and components may not hardcode colors, radii or
 * spacing; they reference Tokens.
 *
 * Palette: true-black AMOLED ground, neutral grey surfaces (no tint),
 * one white accent for primary actions. Chosen by the owner on
 * 2026-10-19 (amber rejected; preview-approved): NO white background,
 * NO blue/navy, minimal and calm. Pure-white primary text, neutral grey
 * metadata, semantic red ONLY for critical failures.
 */

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Tokens {

    /** Ground + elevated surfaces (AMOLED black, neutral grey, no tint). */
    object Palette {
        val Background = Color(0xFF000000)     // true AMOLED black (ground)
        val Surface = Color(0xFF161616)        // cards (neutral grey, no tint)
        val FieldFill = Color(0xFF0A0A0A)      // input field fill
        val SurfaceHigh = Color(0xFF1C1C1C)    // sheets / secondary surfaces
        val SurfaceHighest = Color(0xFF262626) // menus, pressed states
        val Border = Color(0xFF2B2B2B)         // 1 px outlines
        val BorderStrong = Color(0xFF454545)   // focused/active outlines

        /** Text. */
        val TextPrimary = Color(0xFFFFFFFF)    // pure white
        val TextSecondary = Color(0xFFC4C4C4)  // neutral grey
        val TextMuted = Color(0xFF8A8A8A)      // faint metadata

        /** Accent — white. Primary actions are white-on-black, the ONE
         *  loud element per screen. (Owner's pick, 2026-10-19: white
         *  neutral identity; the earlier amber direction was rejected.) */
        val Accent = Color(0xFFFFFFFF)
        val OnAccent = Color(0xFF000000)
        val AccentContainer = Color(0xFF242424)
        val OnAccentContainer = Color(0xFFFFFFFF)

        /** Neutral grey — calm secondary / completed. */
        val Steel = Color(0xFFC4C4C4)
        val SteelDim = Color(0xFF8A8A8A)
        val SteelContainer = Color(0xFF1E1E1E)
        val OnSteelContainer = Color(0xFFF2F2F2)

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
        val l = 16.dp   // cards (neutral grey, no tint)
    }
}
