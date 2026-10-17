/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorSemantic
import com.osfans.trime.data.theme.LayoutSemantic
import com.osfans.trime.data.theme.StyleSemantic

/**
 * How the semantic items get their names, and how they get grouped on screen.
 *
 * All three of these used to be scattered: a name per item, a name per group, and the grouping
 * itself, each written out again for colours and for sizes. Two parallel copies of a table this
 * long is two places to forget — and forgetting is silent, because a missing name is a raw resource
 * id in a title rather than a compile error.
 *
 * The maps below are therefore exhaustive on purpose in the same way the enums are: a new
 * [ColorSemantic] or [StyleSemantic] has no entry here, and the `getValue` throws in development
 * rather than showing the user a number. The companion test walks the enums and checks that every
 * item is named, so the two lists cannot drift apart either.
 *
 * Kept out of the enums themselves on purpose. Those live in the data layer and know nothing about
 * screens or resources; a `labelRes()` on [ColorSemantic] would make the derivation table — the
 * thing the unit tests pin down — depend on the app's string resources.
 */
internal object BuiltinLabels {
    private val colorItems: Map<ColorSemantic, Int> =
        mapOf(
            ColorSemantic.KEYBOARD_SURFACE to R.string.color_semantic_keyboard_surface,
            ColorSemantic.KEY_SURFACE to R.string.color_semantic_key_surface,
            ColorSemantic.CANDIDATE_SURFACE to R.string.color_semantic_candidate_surface,
            ColorSemantic.PREEDIT_SURFACE to R.string.color_semantic_preedit_surface,
            ColorSemantic.PREEDIT_SELECTION_SURFACE to R.string.color_semantic_preedit_selection_surface,
            ColorSemantic.POPUP_SURFACE to R.string.color_semantic_popup_surface,
            ColorSemantic.KEY_LABEL to R.string.color_semantic_key_label,
            ColorSemantic.KEY_SYMBOL to R.string.color_semantic_key_symbol,
            ColorSemantic.CANDIDATE_LABEL to R.string.color_semantic_candidate_label,
            ColorSemantic.CANDIDATE_COMMENT to R.string.color_semantic_candidate_comment,
            ColorSemantic.PREEDIT_TEXT to R.string.color_semantic_preedit_text,
            ColorSemantic.PREEDIT_SELECTION_TEXT to R.string.color_semantic_preedit_selection_text,
            ColorSemantic.POPUP_LABEL to R.string.color_semantic_popup_label,
            ColorSemantic.FUNCTION_SURFACE to R.string.color_semantic_function_surface,
            ColorSemantic.FUNCTION_LABEL to R.string.color_semantic_function_label,
            ColorSemantic.ACCENT to R.string.color_semantic_accent,
            ColorSemantic.ON_ACCENT to R.string.color_semantic_on_accent,
            ColorSemantic.KEY_PRESSED_SURFACE to R.string.color_semantic_key_pressed_surface,
            ColorSemantic.FUNCTION_PRESSED_SURFACE to R.string.color_semantic_function_pressed_surface,
            ColorSemantic.ON_PRESSED_SURFACE to R.string.color_semantic_on_pressed_surface,
            ColorSemantic.OUTLINE to R.string.color_semantic_outline,
            ColorSemantic.TEXT_SHADOW to R.string.color_semantic_text_shadow,
        )

    private val colorGroups: Map<ColorSemantic.Group, Int> =
        mapOf(
            ColorSemantic.Group.SURFACE to R.string.color_group_surface,
            ColorSemantic.Group.TEXT to R.string.color_group_text,
            ColorSemantic.Group.FUNCTION to R.string.color_group_function,
            ColorSemantic.Group.ACCENT to R.string.color_group_accent,
            ColorSemantic.Group.PRESSED to R.string.color_group_pressed,
            ColorSemantic.Group.LINE to R.string.color_group_line,
        )

    private val styleItems: Map<StyleSemantic, Int> =
        mapOf(
            StyleSemantic.CANDIDATE_TEXT_SIZE to R.string.style_candidate_text_size,
            StyleSemantic.COMMENT_TEXT_SIZE to R.string.style_comment_text_size,
            StyleSemantic.KEY_TEXT_SIZE to R.string.style_key_text_size,
            StyleSemantic.KEY_LONG_TEXT_SIZE to R.string.style_key_long_text_size,
            StyleSemantic.SYMBOL_TEXT_SIZE to R.string.style_symbol_text_size,
            StyleSemantic.LABEL_TEXT_SIZE to R.string.style_label_text_size,
            StyleSemantic.POPUP_TEXT_SIZE to R.string.style_popup_text_size,
            StyleSemantic.KEY_BORDER to R.string.style_key_border,
            StyleSemantic.CANDIDATE_BORDER to R.string.style_candidate_border,
            StyleSemantic.ROUND_CORNER to R.string.style_round_corner,
            StyleSemantic.SHADOW_RADIUS to R.string.style_shadow_radius,
        )

    private val styleGroups: Map<StyleSemantic.Group, Int> =
        mapOf(
            StyleSemantic.Group.TEXT to R.string.style_group_text,
            StyleSemantic.Group.SHAPE to R.string.style_group_shape,
        )

    private val layoutItems: Map<LayoutSemantic, Int> =
        mapOf(
            LayoutSemantic.KEYBOARD_HEIGHT to R.string.layout_keyboard_height,
            LayoutSemantic.KEYBOARD_HEIGHT_LAND to R.string.layout_keyboard_height_land,
            LayoutSemantic.HORIZONTAL_GAP to R.string.layout_horizontal_gap,
            LayoutSemantic.VERTICAL_GAP to R.string.layout_vertical_gap,
            LayoutSemantic.CANDIDATE_VIEW_HEIGHT to R.string.layout_candidate_view_height,
            LayoutSemantic.COMMENT_HEIGHT to R.string.layout_comment_height,
        )

    /** The label for a colour item, as a resource id the caller resolves against its own context. */
    fun colorItem(item: ColorSemantic): Int =
        requireNotNull(colorItems[item]) { "ColorSemantic.$item has no label" }

    fun colorGroup(group: ColorSemantic.Group): Int =
        requireNotNull(colorGroups[group]) { "ColorSemantic.Group.$group has no label" }

    fun styleItem(item: StyleSemantic): Int =
        requireNotNull(styleItems[item]) { "StyleSemantic.$item has no label" }

    fun styleGroup(group: StyleSemantic.Group): Int =
        requireNotNull(styleGroups[group]) { "StyleSemantic.Group.$group has no label" }

    fun layoutItem(item: LayoutSemantic): Int =
        requireNotNull(layoutItems[item]) { "LayoutSemantic.$item has no label" }

    /**
     * Every item is named, and every group too.
     *
     * The single test over both enums: it cannot tell you a name is *right*, but it does refuse to
     * let a new item reach a screen without one, which is the failure that would otherwise only show
     * up on a device as a number in a heading.
     */
    fun missingLabels(): List<String> =
        ColorSemantic.entries.filterNot { colorItems.containsKey(it) }.map { "ColorSemantic.$it" } +
            ColorSemantic.Group.entries.filterNot { colorGroups.containsKey(it) }.map { "ColorSemantic.Group.$it" } +
            StyleSemantic.entries.filterNot { styleItems.containsKey(it) }.map { "StyleSemantic.$it" } +
            StyleSemantic.Group.entries.filterNot { styleGroups.containsKey(it) }.map { "StyleSemantic.Group.$it" } +
            LayoutSemantic.entries.filterNot { layoutItems.containsKey(it) }.map { "LayoutSemantic.$it" }
}

/** One group of related knobs, in the order the screen shows them. */
internal data class KnobSection<G : Enum<G>, I : Enum<I>>(
    val group: G,
    val items: List<I>,
    val groupLabelRes: Int,
    val itemLabelRes: (I) -> Int,
)

/**
 * Groups [items] by their own `group`, in declaration order.
 *
 * Both the colour and the size list come from their enum's declaration order, so moving an item in
 * [ColorSemantic] or [StyleSemantic] moves it on screen too — the screen has no second list to keep
 * in step. Empty groups are dropped rather than shown as a heading with nothing under it, which is
 * what happens as soon as a group loses its last item.
 */
internal fun <G : Enum<G>, I : Enum<I>> groupIntoSections(
    groups: List<G>,
    items: List<I>,
    groupOf: (I) -> G,
    groupLabelRes: (G) -> Int,
    itemLabelRes: (I) -> Int,
): List<KnobSection<G, I>> =
    groups
        .map { group -> KnobSection(group, items.filter { groupOf(it) == group }, groupLabelRes(group), itemLabelRes) }
        .filter { it.items.isNotEmpty() }

internal fun colorSections(): List<KnobSection<ColorSemantic.Group, ColorSemantic>> =
    groupIntoSections(
        groups = ColorSemantic.Group.entries,
        items = ColorSemantic.entries,
        groupOf = { it.group },
        groupLabelRes = BuiltinLabels::colorGroup,
        itemLabelRes = BuiltinLabels::colorItem,
    )

internal fun sizeSections(): List<KnobSection<StyleSemantic.Group, StyleSemantic>> =
    groupIntoSections(
        groups = StyleSemantic.Group.entries,
        items = StyleSemantic.entries,
        groupOf = { it.group },
        groupLabelRes = BuiltinLabels::styleGroup,
        itemLabelRes = BuiltinLabels::styleItem,
    )