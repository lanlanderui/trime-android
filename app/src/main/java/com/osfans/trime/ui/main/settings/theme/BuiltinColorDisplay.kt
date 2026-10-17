/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import com.osfans.trime.data.theme.BuiltinColorScheme
import com.osfans.trime.data.theme.BuiltinLayout
import com.osfans.trime.data.theme.BuiltinStyle
import com.osfans.trime.data.theme.ColorSemantic
import com.osfans.trime.data.theme.StyleSemantic
import java.util.Locale

/** One group of the built-in colour screen, in the order the screen shows them. */
internal fun semanticSections(): List<KnobSection<ColorSemantic.Group, ColorSemantic>> = colorSections()

/**
 * Colour keys the active scheme declares that no semantic item owns — a theme's own vocabulary.
 *
 * In this theme that is `bgn`, `bbs`, `benter`, `bkg` and the 190-odd `*_custom_key_*`. They are
 * what the semantic layer cannot reach, and for a reason worth spelling out: a theme binds them
 * per key in its keyboards, and an explicit value *in the scheme* beats the `fallback_colors`
 * alias that would otherwise point them at a key the palette does own. The per-key overrides are
 * the only way in, which is why this list is what that section shows.
 */
internal fun foreignKeys(schemeColors: Collection<String>): List<String> {
    val reached = ColorSemantic.ownedKeys.toSet() + ColorSemantic.notSemantic.keys
    return schemeColors.filter { it !in reached }.distinct().sorted()
}

/**
 * Keys the theme declares that this build reads nowhere, each with the reason.
 *
 * Reported rather than hidden: a user who sets them and sees nothing move has no way of telling
 * a broken theme from an unsupported setting, and the second is what this is.
 */
internal fun ineffectiveKeys(schemeColors: Collection<String>): List<Pair<String, String>> =
    ColorSemantic.notSemantic
        .filterKeys { it in schemeColors }
        .toList()
        .sortedBy { it.first }

/**
 * How many of the per-key overrides a screen build should actually create rows for.
 *
 * The list this bounds is the one place on the screen where the row count is not ours: this theme
 * declares ~200 `*_custom_key_*` entries, and every row is four views inside a `LinearLayout` that
 * does not recycle them. So "show all" meant building 800 views and measuring all of them, on the
 * main thread, to make the few keys the user wanted available behind a scroll.
 *
 * The cap keeps the first screen of the list cheap while still reaching every key: [more] is what
 * grows the window, and a list shorter than the cap is never cut — there is nothing to page to.
 *
 * @param more how many further rows the user has asked for by pressing "show more".
 */
internal const val OVERRIDE_PAGE_SIZE = 40

internal fun overrideWindow(
    all: List<String>,
    expanded: Boolean,
    more: Int,
): Int {
    if (!expanded) return all.size
    val wanted = OVERRIDE_PAGE_SIZE * (1 + more.coerceAtLeast(0))
    return wanted.coerceAtMost(all.size)
}

/** `#AARRGGBB`, the spelling the theme files and the stored palette use. */
internal fun argbToHex(argb: Int): String = String.format(Locale.ROOT, "#%08X", argb)

/**
 * The size knobs arranged for display, grouped the same way as [semanticSections] — both orders
 * come from the enum's declaration order, so moving an item moves it here too.
 */
internal fun styleSections(): List<KnobSection<StyleSemantic.Group, StyleSemantic>> = sizeSections()

/**
 * What the built-in settings are contributing to the keyboard right now.
 *
 * The screen used to say nothing about this, and an empty palette is indistinguishable from a
 * broken one: both look exactly like the theme's own scheme. Someone tuning colours needs to be
 * able to see, without counting rows, whether anything they set is actually in effect — and with
 * each knob domain having a source of its own, the same question is now asked once per domain.
 */
internal data class ActiveSummary(
    /** Semantic colour items set for the mode being edited, plus the per-key overrides. */
    val colors: Int,
    val sizes: Int,
    val layout: Int,
    /** Whether the colours are in charge at all; when they are not, counts are moot. */
    val colorsInCharge: Boolean,
    /** Same question for the sizes, which have a source of their own. */
    val sizesInCharge: Boolean,
    /** And for the layout. */
    val layoutInCharge: Boolean,
)

internal fun activeSummary(
    scheme: BuiltinColorScheme,
    style: BuiltinStyle,
    layout: BuiltinLayout,
    isNight: Boolean,
    colorsInCharge: Boolean,
    sizesInCharge: Boolean,
    layoutInCharge: Boolean,
): ActiveSummary =
    ActiveSummary(
        colors = scheme.values(isNight).size + scheme.overrides.size,
        sizes = style.values.size,
        layout = layout.values.size,
        colorsInCharge = colorsInCharge,
        sizesInCharge = sizesInCharge,
        layoutInCharge = layoutInCharge,
    )
