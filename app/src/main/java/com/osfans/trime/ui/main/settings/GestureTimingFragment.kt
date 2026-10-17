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
 * How the keys answer a finger: what a swipe must travel, how long a long press waits, how fast a
 * repeat fires, and how big a step a slide takes.
 *
 * These eight numbers were the tail of the keyboard page, below three settings about layout and
 * appearance. Nobody tuning a swipe scrolled that far, and nobody reading about floating keyboards
 * wanted them in the way.
 */
class GestureTimingFragment : PreferenceDelegateFragment(
    ProviderSections(
        AppPrefs.defaultInstance().keyboard,
        listOf(
            PrefSection(
                R.string.section_gesture_timing,
                listOf(
                    AppPrefs.Keyboard.POPUP_ON_KEY_PRESS,
                    AppPrefs.Keyboard.EXPAND_KEYPRESS_AREA,
                    AppPrefs.Keyboard.SWIPE_TRAVEL,
                    AppPrefs.Keyboard.SWIPE_VELOCITY,
                    AppPrefs.Keyboard.LONG_PRESS_TIMEOUT,
                    AppPrefs.Keyboard.REPEAT_INTERVAL,
                    AppPrefs.Keyboard.DOUBLE_TAP_TIMEOUT,
                    AppPrefs.Keyboard.SLIDE_STEP_SIZE,
                ),
            ),
        ),
        // The keyboard provider is split over three pages; the rows this page does not name are
        // shown by the other two, so they must not be appended here.
        includeUnlisted = false,
    ),
)
