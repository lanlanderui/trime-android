/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import androidx.annotation.StringRes
import com.osfans.trime.R

/**
 * One button the strip row can carry in its trailing slot.
 *
 * The six are the quick switches the row holds: three of them come from the theme's
 * `tool_bar/buttons` list (matched by the action they declare) and three are built by this app
 * (the floating-keyboard toggle, the input bar toggle and the candidate window toggle). The
 * order of the constants is the order the settings dialog lists them in; it is *not* the order
 * they appear on screen, which the row decides and which a theme may reorder.
 *
 * The trailing slot reads the size of the visible set: one button is shown on its own, two or
 * more fold into the stow toggle, none leaves the slot empty.
 */
enum class RightButton(
    /** Stable id written to the preference; never localise it. */
    val id: String,
    @StringRes val label: Int,
    /** Action a theme has to declare for this button to exist there, if the theme owns it. */
    val themeAction: String? = null,
) {
    HIDE("hide", R.string.right_button_hide, "Hide"),
    CLIPBOARD("clipboard", R.string.right_button_clipboard, "clipboard_window"),
    MODE_SWITCH("mode_switch", R.string.right_button_mode_switch, "Mode_switch"),
    FLOATING("floating", R.string.right_button_floating),
    HIDE_INPUT_BAR("hide_input_bar", R.string.right_button_hide_input_bar),
    CANDIDATES_WINDOW("candidates_window", R.string.right_button_candidates_window),
    ;

    companion object {
        /** Everything on, which is what the row has always shown. */
        val ALL: List<RightButton> = entries.toList()

        /** [ALL] as the preference's stored form; the default of the preference. */
        val ALL_IDS: String = serialize(ALL)

        /**
         * The button a theme's action stands for, or null for a button this app knows nothing
         * about -- a theme is free to bring its own, and those always ride along.
         */
        fun ofThemeAction(action: String): RightButton? =
            entries.firstOrNull { it.themeAction != null && it.themeAction == action }

        /**
         * The chosen set, in [entries] order.
         *
         * A blank value is the empty set -- "show none" -- not "the default": the preference
         * stores [ALL_IDS] by default, so the two cases never have to be told apart.
         */
        fun parse(raw: String): Set<RightButton> {
            if (raw.isBlank()) return emptySet()
            val ids = raw.split(',').map { it.trim() }.toSet()
            return entries.filterTo(LinkedHashSet()) { it.id in ids }
        }

        /** The stored form of [buttons]; unknown ids never survive a round trip. */
        fun serialize(buttons: Collection<RightButton>): String =
            entries.filter { it in buttons }.joinToString(",") { it.id }
    }
}
