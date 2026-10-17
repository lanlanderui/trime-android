/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PrefSection
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.prefs.ProviderSections

/**
 * How the keyboard itself is drawn and where it sits: floating or docked, one hand or two, and
 * what is printed on the keys.
 *
 * This is the page the IME's own "keyboard" action opens, so it keeps the route name it always
 * had. The gesture timings and the composition's position used to live here too; they are about
 * how the keyboard behaves and where the chip floats, which are different questions.
 */
class KeyboardLayoutFragment : PreferenceDelegateFragment(
    ProviderSections(
        AppPrefs.defaultInstance().keyboard,
        listOf(
            PrefSection(
                R.string.section_keyboard_layout,
                listOf(
                    AppPrefs.Keyboard.FLOATING_KEYBOARD,
                    AppPrefs.Keyboard.LANDSCAPE_MODE,
                    AppPrefs.Keyboard.SPLIT_SPACE_PERCENT,
                    AppPrefs.Keyboard.USE_SOFT_CURSOR,
                ),
            ),
            PrefSection(
                R.string.section_keyboard_display,
                listOf(
                    AppPrefs.Keyboard.HIDE_KEY_SYMBOL,
                    AppPrefs.Keyboard.HIDE_KEY_HINT,
                ),
            ),
        ),
        // The keyboard provider is split over three pages; the rows this page does not name are
        // shown by the other two, so they must not be appended here.
        includeUnlisted = false,
    ),
)
