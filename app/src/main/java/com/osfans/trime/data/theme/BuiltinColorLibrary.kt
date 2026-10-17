/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import kotlinx.serialization.Serializable

/**
 * The user's palettes, and which one is in use.
 *
 * More than one palette because a look is not one thing: a light one and a dark one, a loud one and
 * a quiet one, and switching between them is the point. A single palette made that impossible — the
 * only way back to a previous look was to re-pick every colour, which is what the reset option is
 * for but which nobody wants to need.
 *
 * The sizes are deliberately *not* per palette. A palette is a set of colours; how large the keys
 * are is a property of the keyboard, not of the colours on it, and duplicating the size overrides
 * into every palette would mean re-setting them each time one is added.
 */
@Serializable
data class BuiltinColorLibrary(
    val palettes: List<BuiltinColorScheme> = emptyList(),
    val selectedId: String = "",
) {
    /**
     * The palette in use.
     *
     * Falls back to the first one, and then to the shipped palette, so an empty library still
     * paints something: a user who deletes every palette should get the default back, not a
     * keyboard with no colours.
     */
    fun current(): BuiltinColorScheme =
        palettes.firstOrNull { it.id == selectedId }
            ?: palettes.firstOrNull()
            ?: BuiltinColorDefaults.DEFAULT

    /** Switches to [id], or stays put when the library has never heard of it. */
    fun select(id: String): BuiltinColorLibrary = if (palettes.any { it.id == id }) copy(selectedId = id) else this

    /**
     * Replaces the palette that carries [palette]'s id, or adds it and switches to it.
     *
     * Adding is what makes "save this as a new palette" work: give the copy a fresh id and an
     * unseen name, and it lands at the end of the list ready to be edited.
     */
    fun with(palette: BuiltinColorScheme): BuiltinColorLibrary =
        if (palettes.any { it.id == palette.id }) {
            copy(palettes = palettes.map { if (it.id == palette.id) palette else it })
        } else {
            copy(palettes = palettes + palette, selectedId = palette.id)
        }

    /** Drops the palette with [id], moving the selection to whatever is left. */
    fun without(id: String): BuiltinColorLibrary {
        val remaining = palettes.filterNot { it.id == id }
        return copy(
            palettes = remaining,
            selectedId = if (selectedId == id) remaining.firstOrNull()?.id.orEmpty() else selectedId,
        )
    }
}

/** The library a fresh install starts from: one palette, the shipped one, ready to edit. */
internal fun defaultColorLibrary(): BuiltinColorLibrary =
    BuiltinColorLibrary(
        palettes = listOf(BuiltinColorDefaults.DEFAULT),
        selectedId = BuiltinColorDefaults.DEFAULT_ID,
    )
