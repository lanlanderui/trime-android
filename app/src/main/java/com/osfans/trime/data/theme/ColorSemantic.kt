/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

/**
 * The semantic layer of the built-in colour settings: the ~20 named colours a
 * user actually edits, and the engine colour keys each of them paints.
 *
 * The point of the indirection is that a theme's colour vocabulary is far too
 * large to put in front of anyone. This theme's own file carries 13 schemes,
 * 62 distinct keys and 536 assignments, and the keys are not even independent:
 * `key_back_color` is reached by alias from the theme's own `bgn`/`bbs`/`benter`
 * and from 190-odd `*_custom_key_*`. Editing one colour therefore means knowing
 * which layer it really lands on (see [ColorTable.resolveRawSource]), which is
 * exactly what a user cannot be expected to work out.
 *
 * A semantic item is instead a *role* — "the colour behind the letter keys" —
 * and its [keys] list is the derivation: every engine key that role paints. The
 * derivation is deliberately one-to-many, and deliberately total: every key in
 * [ColorKey] is owned by exactly one item or is listed in [notSemantic], which
 * a unit test enforces. A key that two items claimed would resolve by iteration
 * order, and a key no item claimed would be silently uncontrollable.
 *
 * The derivation is honest about two deliberate simplifications:
 *
 * - The pressed-state *text* colours hang off [ON_ACCENT] rather than off the
 *   pressed surfaces, so a pressed key reads as "accent, with the accent's own
 *   foreground". A theme that wants a different pairing can say so per key in
 *   the advanced per-key overrides, which sit above the semantic layer.
 * - Every border, including the per-family and pressed ones, is [OUTLINE]. One
 *   line colour for the whole keyboard is the useful default; the per-key
 *   overrides are again the way out.
 */
enum class ColorSemantic(
    val group: Group,
    /** The engine colour keys this role paints, in [ColorKey] spelling. */
    val keys: List<String>,
) {
    KEYBOARD_SURFACE(Group.SURFACE, listOf("keyboard_back_color", "back_color")),
    KEY_SURFACE(Group.SURFACE, listOf("key_back_color")),
    CANDIDATE_SURFACE(Group.SURFACE, listOf("candidate_background")),
    PREEDIT_SURFACE(Group.SURFACE, listOf("text_back_color")),
    PREEDIT_SELECTION_SURFACE(Group.SURFACE, listOf("hilited_back_color")),
    POPUP_SURFACE(Group.SURFACE, listOf("popup_back_color")),

    KEY_LABEL(Group.TEXT, listOf("key_text_color")),
    KEY_SYMBOL(Group.TEXT, listOf("key_symbol_color")),
    CANDIDATE_LABEL(Group.TEXT, listOf("candidate_text_color", "label_color")),
    CANDIDATE_COMMENT(Group.TEXT, listOf("comment_text_color")),
    PREEDIT_TEXT(Group.TEXT, listOf("text_color")),
    PREEDIT_SELECTION_TEXT(Group.TEXT, listOf("hilited_text_color")),
    POPUP_LABEL(Group.TEXT, listOf("popup_text_color")),

    FUNCTION_SURFACE(Group.FUNCTION, listOf("off_key_back_color")),
    FUNCTION_LABEL(Group.FUNCTION, listOf("off_key_text_color", "off_key_symbol_color")),

    ACCENT(
        Group.ACCENT,
        listOf(
            "hilited_candidate_back_color",
            "hilited_candidate_button_color",
            "on_key_back_color",
            "hilited_popup_back_color",
        ),
    ),
    ON_ACCENT(
        Group.ACCENT,
        listOf(
            "hilited_candidate_text_color",
            "hilited_comment_text_color",
            "hilited_label_color",
            "on_key_text_color",
            "on_key_symbol_color",
            "hilited_popup_text_color",
            "hilited_key_text_color",
            "hilited_key_symbol_color",
            "hilited_off_key_text_color",
            "hilited_off_key_symbol_color",
            "hilited_on_key_text_color",
            "hilited_on_key_symbol_color",
        ),
    ),

    KEY_PRESSED_SURFACE(Group.PRESSED, listOf("hilited_key_back_color")),
    FUNCTION_PRESSED_SURFACE(Group.PRESSED, listOf("hilited_off_key_back_color")),
    ON_PRESSED_SURFACE(Group.PRESSED, listOf("hilited_on_key_back_color")),

    OUTLINE(
        Group.LINE,
        listOf(
            "key_border_color",
            "off_key_border_color",
            "on_key_border_color",
            "hilited_key_border_color",
            "hilited_off_key_border_color",
            "hilited_on_key_border_color",
            "border_color",
            "candidate_separator_color",
            "candidate_border_color",
        ),
    ),
    TEXT_SHADOW(Group.LINE, listOf("shadow_color")),
    ;

    /** Stable spelling for storage, the same convention as [ColorKey.key]. */
    val key: String = name.lowercase()

    /** How the settings screen groups the items. */
    enum class Group {
        SURFACE,
        TEXT,
        FUNCTION,
        ACCENT,
        PRESSED,
        LINE,
    }

    companion object {
        private val byKey = entries.associateBy { it.key }

        fun from(key: String): ColorSemantic? = byKey[key]

        /**
         * Engine keys no semantic item owns, each with the reason it is left out.
         *
         * The settings screen lists the entries that the *active theme* actually
         * declares, so a user is told which of their theme's knobs this build
         * will not honour instead of changing them and seeing nothing move.
         */
        val notSemantic: Map<String, String> =
            mapOf(
                "keyboard_background" to "Keyboard background image, not a colour",
                "liquid_keyboard_background" to "No consumer in this build",
                "long_text_color" to "No consumer in this build",
                "long_text_back_color" to "No consumer in this build",
                "root_background" to "No consumer in this build",
            )

        /** Every engine key the semantic layer owns, in declaration order. */
        internal val ownedKeys: List<String> = entries.flatMap { it.keys }
    }
}
