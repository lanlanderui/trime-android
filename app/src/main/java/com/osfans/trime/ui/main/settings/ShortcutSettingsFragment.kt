/*
 * SPDX-FileCopyrightText: 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PrefSection
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.prefs.ProviderSections

/**
 * Which key combinations the keyboard keeps for itself.
 *
 * Split out of the keyboard page because these eight switches are all the same kind of decision and
 * were buried at the end of a page dominated by layout and gesture settings.
 *
 * Two sections, because the two halves answer different questions: the Ctrl chords decide whether
 * a command reaches the app, the Shift rows only decide whether a locked Shift survives one key.
 */
class ShortcutSettingsFragment : PreferenceDelegateFragment(
    ProviderSections(
        AppPrefs.defaultInstance().shortcut,
        listOf(
            PrefSection(
                R.string.section_ctrl_chords,
                listOf(
                    AppPrefs.Shortcut.HOOK_CTRL_A,
                    AppPrefs.Shortcut.HOOK_CTRL_CV,
                    AppPrefs.Shortcut.HOOK_CTRL_LR,
                    AppPrefs.Shortcut.HOOK_CTRL_ZY,
                ),
            ),
            PrefSection(
                R.string.section_shift_state,
                listOf(
                    AppPrefs.Shortcut.HOOK_SHIFT_SPACE,
                    AppPrefs.Shortcut.HOOK_SHIFT_NUM,
                    AppPrefs.Shortcut.HOOK_SHIFT_SYMBOL,
                    AppPrefs.Shortcut.HOOK_SHIFT_ARROW,
                ),
            ),
        ),
    ),
)
