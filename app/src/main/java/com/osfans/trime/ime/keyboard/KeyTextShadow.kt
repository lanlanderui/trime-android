/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

/**
 * The shadow drawn behind a run of text.
 *
 * Built from the two things a theme has to say about it — `shadow_color` for the colour and
 * `style.shadow_radius` for the blur — so that a theme setting one and not the other reads as
 * "no shadow" rather than as a shadow nothing can see.
 *
 * There is no offset: the theme's vocabulary has no `shadow_offset`, and a blur with no direction
 * is a halo around the glyph, which is what makes text stay readable on a busy background.
 */
internal data class KeyTextShadow(
    val radiusPx: Float,
    val dxPx: Float,
    val dyPx: Float,
    val color: Int,
) {
    companion object {
        /** The shadow [radiusPx] and [color] describe, or null when either of them asks for none. */
        fun of(
            radiusPx: Float,
            color: Int,
        ): KeyTextShadow? {
            if (radiusPx <= 0f) return null
            // Read the alpha out of the top byte rather than through a colour helper: this stays a
            // plain function, so the rule is testable without an Android runtime.
            if ((color ushr 24) and 0xFF == 0) return null
            return KeyTextShadow(radiusPx = radiusPx, dxPx = 0f, dyPx = 0f, color = color)
        }
    }
}
