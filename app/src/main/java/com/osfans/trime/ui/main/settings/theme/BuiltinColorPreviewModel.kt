/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.keyboard.KeyBehavior

/** One key of the preview: what it looks like and which palette it draws from. */
internal data class PreviewKey(
    /** Share of the row, in the theme's own width units (see [previewRows]). */
    val weight: Float,
    val label: String,
    val symbol: String,
    /**
     * The colour key the theme bound to this key, when it bound one.
     *
     * This is the single most informative thing the preview can carry. A theme that names its own
     * keys — this one paints shift, backspace, return and space with `bgn` / `bbs` / `benter` /
     * `bkg` — keeps those colours while the built-in palette is in charge, and without this the
     * preview would show a keyboard the user is not actually looking at.
     */
    val colorKey: String?,
    /** Whether this key types a letter or the space bar, rather than running a command. */
    val letter: Boolean,
)

internal data class PreviewRow(val keys: List<PreviewKey>)

/**
 * The theme's keyboard as rows, ready to be drawn.
 *
 * `TextKeyboard.keys` is a flat list: the row breaks are implicit, and the renderer derives them
 * by accumulating key widths until the row reaches the keyboard's total width. This repeats that
 * rule in the theme's own weight units rather than in pixels — the real one compares accumulated
 * pixels against the available width, which is the same comparison once both sides are expressed
 * as a share of [MAX_TOTAL_WEIGHT]. Doing it in weights is what lets the preview be laid out at
 * any size, including one much smaller than the keyboard.
 *
 * The row heights and the split/floating-keyboard geometry are deliberately *not* reproduced:
 * this is a colour preview, and guessing at geometry the theme computes from the screen would
 * make the preview disagree with the keyboard it is standing in for.
 */
internal fun previewRows(
    keyboard: TextKeyboard,
    presetLabels: Map<String, String> = emptyMap(),
): List<PreviewRow> {
    val maxColumns = if (keyboard.columns == -1) Int.MAX_VALUE else keyboard.columns
    val rows = mutableListOf<PreviewRow>()
    var current = mutableListOf<PreviewKey>()
    var columns = 0
    var accumulated = 0f

    keyboard.keys.forEach { key ->
        val click = key.behaviors[KeyBehavior.CLICK]
        // Mirrors Keyboard's own default: a full-width key that does something, and declares no
        // width of its own, takes the keyboard's default width.
        val weight = if (key.width == 0f && key.hasClickAction) keyboard.width else key.width
        if (columns >= maxColumns || accumulated + weight > MAX_TOTAL_WEIGHT) {
            if (current.isNotEmpty()) rows += PreviewRow(current)
            current = mutableListOf()
            columns = 0
            accumulated = 0f
        }
        current +=
            PreviewKey(
                weight = weight,
                label = previewLabel(key, click, presetLabels),
                symbol = key.labelSymbol,
                colorKey = key.keyBackColor.takeIf { it.isNotEmpty() },
                letter = previewIsLetterKey(click),
            )
        // Only keys that do something count toward the column limit, as in the renderer.
        if (key.hasClickAction) columns++
        accumulated += weight
    }
    if (current.isNotEmpty()) rows += PreviewRow(current)
    return rows
}

/**
 * The text to draw on a key: the key's own label, else whatever its click action carries.
 *
 * A theme usually leaves a key's label out and lets the name it types stand in, which is fine for
 * `q` and useless for `BackSpace` — that token is several times wider than the key, so it either
 * overflows onto its neighbour or has to be cut back to `c…`. The theme's `preset_keys` are where
 * the display label actually lives (`⊗`, `Shift`, `回车` in this theme), so those are consulted
 * before falling back to the token name.
 *
 * One value that is not a label is skipped: `enter_labels` is the sentinel telling the renderer to
 * use whichever word the enter key's mode calls for, and printing it literally would be worse than
 * printing the token.
 */
internal fun previewLabel(
    key: TextKeyboard.TextKey,
    click: KeyActionToken?,
    presetLabels: Map<String, String> = emptyMap(),
): String {
    if (key.label.isNotEmpty()) return key.label
    return when (click) {
        // An inline action carries its own display text; there is no preset name to look up.
        is KeyActionToken.Inline ->
            click.token.label ?: click.token.text ?: click.token.commit.orEmpty()

        is KeyActionToken.Plain -> {
            val preset = presetLabels[click.token]
            if (!preset.isNullOrEmpty() && preset != ENTER_LABELS_SENTINEL) preset else click.token
        }

        null -> ""
    }
}

/** The sentinel a theme writes as an enter key's label to mean "whatever the mode says". */
private const val ENTER_LABELS_SENTINEL = "enter_labels"

/** What a key commits, as far as its click action alone can say. */
private fun typedText(click: KeyActionToken?): String =
    when (click) {
        is KeyActionToken.Plain -> click.token
        is KeyActionToken.Inline -> click.token.text ?: click.token.commit.orEmpty()
        null -> ""
    }

/**
 * Whether a key is a letter key, for choosing between the two key palettes.
 *
 * An approximation of what `Key` decides: the renderer classifies keys by the text they commit
 * (and by key code) only while the dynamic scheme is in charge, and by `functional` / `sticky`
 * otherwise. Both need runtime actions built from the theme's `preset_keys`, which is more
 * machinery than a colour preview should carry — and the two agree on every ordinary letter,
 * digit and command key, which is what a preview is read for.
 *
 * A key whose theme bound a colour of its own never consults this: [PreviewKey.colorKey] wins
 * first, exactly as it does on the keyboard.
 */
internal fun previewIsLetterKey(click: KeyActionToken?): Boolean {
    val typed = typedText(click)
    if (typed == "space") return true
    return typed.length == 1 && (typed[0] in 'a'..'z' || typed[0] in 'A'..'Z')
}

/**
 * The width the renderer treats as a full row.
 *
 * The same constant `Keyboard` uses, so a theme's `width:` values mean the same thing in both
 * places: `width: 10` on ten keys is one row, and the eleventh key wraps.
 */
internal const val MAX_TOTAL_WEIGHT = 100f
