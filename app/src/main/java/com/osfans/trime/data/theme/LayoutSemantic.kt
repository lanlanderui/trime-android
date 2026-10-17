/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import kotlin.math.roundToInt

/**
 * The layout knobs the built-in settings can adjust, and where each lands in [GeneralStyle].
 *
 * The third domain after the colours and the sizes. It is kept apart from [StyleSemantic] because
 * it is risky in a different way: a wrong size makes the keyboard ugly, while a wrong height or
 * gap can make it unusable (the same reason the original size list deliberately left these out),
 * so the ranges below are conservative on purpose and the editor treats the whole domain as
 * read-only until its source is switched to the built-in settings.
 *
 * Two candidates are deliberately not here:
 *  - `key_height` is a row's *relative weight*, not a height: every row is scaled to fill
 *    `keyboard_height`, so with rows of equal weight the value cancels out entirely. A slider for
 *    it would move and change nothing, which is the kind of silent no-op this screen refuses.
 *  - the paddings (`keyboard_padding*`) interact with the reserved strip for the bottom toggles
 *    and deserve a review of their own; they are left to a later pass.
 *
 * The bar heights ([CANDIDATE_VIEW_HEIGHT], [COMMENT_HEIGHT]) are the two knobs behind what a
 * theme calls the toolbar height: several pieces of chrome — the toolbar buttons, the tab strip,
 * the clipboard title, the unrolled candidate window's items — are sized as the *sum* of the two,
 * and the candidate area itself by the first alone. They are here rather than among the sizes
 * because they set how much vertical space the chrome takes, and the sum follows whatever the
 * two say; a theme may still declare anything, but a zero would collapse those views entirely,
 * which is why the slider floors are positive.
 *
 * The values are dp, as the theme spells them. [min]/[max] bound what a slider offers — a theme
 * may declare anything. A keyboard may also override these per keyboard (the resolution chain is
 * keyboard config → theme style), in which case this domain simply has nothing to say about that
 * particular keyboard — the stock `mini` keyboard sets its own `keyboard_height` that way.
 */
enum class LayoutSemantic(
    /** The style key as the theme spells it. */
    val key: String,
    val min: Float,
    val max: Float,
    val unit: String,
) {
    KEYBOARD_HEIGHT("keyboard_height", min = 120f, max = 400f, unit = "dp"),
    KEYBOARD_HEIGHT_LAND("keyboard_height_land", min = 100f, max = 320f, unit = "dp"),
    HORIZONTAL_GAP("horizontal_gap", min = 0f, max = 12f, unit = "dp"),
    VERTICAL_GAP("vertical_gap", min = 0f, max = 12f, unit = "dp"),
    CANDIDATE_VIEW_HEIGHT("candidate_view_height", min = 20f, max = 60f, unit = "dp"),
    COMMENT_HEIGHT("comment_height", min = 6f, max = 30f, unit = "dp"),
    ;

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun from(key: String): LayoutSemantic? = byKey[key]
    }
}

/** The value [GeneralStyle] currently holds for [semantic]. */
internal fun GeneralStyle.valueOf(semantic: LayoutSemantic): Float =
    when (semantic) {
        LayoutSemantic.KEYBOARD_HEIGHT -> keyboardHeight.toFloat()
        LayoutSemantic.KEYBOARD_HEIGHT_LAND -> keyboardHeightLand.toFloat()
        LayoutSemantic.HORIZONTAL_GAP -> horizontalGap.toFloat()
        LayoutSemantic.VERTICAL_GAP -> verticalGap.toFloat()
        LayoutSemantic.CANDIDATE_VIEW_HEIGHT -> candidateViewHeight.toFloat()
        LayoutSemantic.COMMENT_HEIGHT -> commentHeight.toFloat()
    }

/**
 * A copy with [semantic] set to [value].
 *
 * Exhaustive on purpose, like its size twin: a new [LayoutSemantic] then fails to compile until
 * it is wired to a field, which is the only thing keeping the list and the style from drifting.
 */
internal fun GeneralStyle.withValue(
    semantic: LayoutSemantic,
    value: Float,
): GeneralStyle =
    when (semantic) {
        LayoutSemantic.KEYBOARD_HEIGHT -> copy(keyboardHeight = value.roundToInt())
        LayoutSemantic.KEYBOARD_HEIGHT_LAND -> copy(keyboardHeightLand = value.roundToInt())
        LayoutSemantic.HORIZONTAL_GAP -> copy(horizontalGap = value.roundToInt())
        LayoutSemantic.VERTICAL_GAP -> copy(verticalGap = value.roundToInt())
        LayoutSemantic.CANDIDATE_VIEW_HEIGHT -> copy(candidateViewHeight = value.roundToInt())
        LayoutSemantic.COMMENT_HEIGHT -> copy(commentHeight = value.roundToInt())
    }

/**
 * The style with [values] applied, keyed by the style key as the theme spells it.
 *
 * A patch, like [withStyleValues]: an absent entry leaves the theme's own value alone, and a key
 * this build does not know is skipped rather than taking the keyboard down.
 */
internal fun GeneralStyle.withLayoutValues(values: Map<String, Float>): GeneralStyle =
    values.entries.fold(this) { style, (key, value) ->
        val semantic = LayoutSemantic.from(key) ?: return@fold style
        style.withValue(semantic, value)
    }
