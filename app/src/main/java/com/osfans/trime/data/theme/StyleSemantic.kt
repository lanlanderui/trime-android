/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import kotlin.math.roundToInt

/**
 * The size knobs the built-in settings can adjust, and where each of them lands in [GeneralStyle].
 *
 * The counterpart of [ColorSemantic], for the numbers rather than the colours. They are kept
 * apart because they are different in kind: a colour is resolved through a four-level fallback
 * chain and a day/night switch, while a size is one value on a style the theme already declares —
 * so a size override is a *patch* (an absent entry leaves the theme's value alone) rather than a
 * palette the user has to fill in.
 *
 * The list is deliberately short. It covers what a user notices and cannot otherwise change
 * without editing the theme file: the text sizes, the two border widths, the corner radius and the
 * shadow blur. Layout — gaps, paddings, keyboard height — is left out for now, because getting it
 * wrong makes the keyboard unusable rather than merely ugly.
 *
 * [min] and [max] are the range the settings screen offers, not a claim about what a theme may
 * declare: a theme is free to write `key_text_size: 60` and the keyboard will honour it. They only
 * bound what a slider can do.
 */
enum class StyleSemantic(
    val group: Group,
    /** The style key as the theme spells it. */
    val key: String,
    val min: Float,
    val max: Float,
    /** Whether zero means "follow the theme's other size" rather than "nothing". */
    val zeroMeansInherit: Boolean = false,
) {
    CANDIDATE_TEXT_SIZE(Group.TEXT, "candidate_text_size", min = 8f, max = 40f),
    COMMENT_TEXT_SIZE(Group.TEXT, "comment_text_size", min = 6f, max = 30f),
    KEY_TEXT_SIZE(Group.TEXT, "key_text_size", min = 8f, max = 40f),
    KEY_LONG_TEXT_SIZE(Group.TEXT, "key_long_text_size", min = 8f, max = 40f),
    SYMBOL_TEXT_SIZE(Group.TEXT, "symbol_text_size", min = 0f, max = 30f, zeroMeansInherit = true),
    LABEL_TEXT_SIZE(Group.TEXT, "label_text_size", min = 0f, max = 40f, zeroMeansInherit = true),
    POPUP_TEXT_SIZE(Group.TEXT, "popup_text_size", min = 0f, max = 40f, zeroMeansInherit = true),

    KEY_BORDER(Group.SHAPE, "key_border", min = 0f, max = 8f),
    CANDIDATE_BORDER(Group.SHAPE, "candidate_border", min = 0f, max = 8f),
    ROUND_CORNER(Group.SHAPE, "round_corner", min = 0f, max = 24f),
    SHADOW_RADIUS(Group.SHAPE, "shadow_radius", min = 0f, max = 24f),
    ;

    enum class Group {
        TEXT,
        SHAPE,
    }

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun from(key: String): StyleSemantic? = byKey[key]
    }
}

/** The value [GeneralStyle] currently holds for [semantic]. */
internal fun GeneralStyle.valueOf(semantic: StyleSemantic): Float =
    when (semantic) {
        StyleSemantic.CANDIDATE_TEXT_SIZE -> candidateTextSize
        StyleSemantic.COMMENT_TEXT_SIZE -> commentTextSize
        StyleSemantic.KEY_TEXT_SIZE -> keyTextSize
        StyleSemantic.KEY_LONG_TEXT_SIZE -> keyLongTextSize
        StyleSemantic.SYMBOL_TEXT_SIZE -> symbolTextSize
        StyleSemantic.LABEL_TEXT_SIZE -> labelTextSize
        StyleSemantic.POPUP_TEXT_SIZE -> popupTextSize
        StyleSemantic.KEY_BORDER -> keyBorder.toFloat()
        StyleSemantic.CANDIDATE_BORDER -> candidateBorder.toFloat()
        StyleSemantic.ROUND_CORNER -> roundCorner
        StyleSemantic.SHADOW_RADIUS -> shadowRadius
    }

/**
 * A copy with [semantic] set to [value].
 *
 * Exhaustive on purpose: a new [StyleSemantic] then fails to compile until it is wired to a field,
 * which is the only thing keeping the list and the style from drifting apart.
 */
internal fun GeneralStyle.withValue(
    semantic: StyleSemantic,
    value: Float,
): GeneralStyle =
    when (semantic) {
        StyleSemantic.CANDIDATE_TEXT_SIZE -> copy(candidateTextSize = value)
        StyleSemantic.COMMENT_TEXT_SIZE -> copy(commentTextSize = value)
        StyleSemantic.KEY_TEXT_SIZE -> copy(keyTextSize = value)
        StyleSemantic.KEY_LONG_TEXT_SIZE -> copy(keyLongTextSize = value)
        StyleSemantic.SYMBOL_TEXT_SIZE -> copy(symbolTextSize = value)
        StyleSemantic.LABEL_TEXT_SIZE -> copy(labelTextSize = value)
        StyleSemantic.POPUP_TEXT_SIZE -> copy(popupTextSize = value)
        StyleSemantic.KEY_BORDER -> copy(keyBorder = value.roundToInt())
        StyleSemantic.CANDIDATE_BORDER -> copy(candidateBorder = value.roundToInt())
        StyleSemantic.ROUND_CORNER -> copy(roundCorner = value)
        StyleSemantic.SHADOW_RADIUS -> copy(shadowRadius = value)
    }

/**
 * The style with [values] applied, keyed by the style key as the theme spells it.
 *
 * A patch: an entry that is absent leaves the theme's own value alone, so clearing one item
 * restores exactly what the theme declares instead of guessing at a default. Keys this build does
 * not know are skipped — a file written by a newer build must not take the keyboard down.
 */
internal fun GeneralStyle.withStyleValues(values: Map<String, Float>): GeneralStyle =
    values.entries.fold(this) { style, (key, value) ->
        val semantic = StyleSemantic.from(key) ?: return@fold style
        style.withValue(semantic, value)
    }
