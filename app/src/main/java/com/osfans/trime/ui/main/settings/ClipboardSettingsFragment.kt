/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PrefSection
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.prefs.ProviderSections

class ClipboardSettingsFragment : PreferenceDelegateFragment(
    ProviderSections(
        AppPrefs.defaultInstance().clipboard,
        listOf(
            PrefSection(
                R.string.section_clipboard_record,
                listOf(
                    AppPrefs.Clipboard.CLIPBOARD_LISTENING,
                    AppPrefs.Clipboard.CLIPBOARD_LIMIT,
                    AppPrefs.Clipboard.CLIPBOARD_COMPARE_RULES,
                    AppPrefs.Clipboard.CLIPBOARD_OUTPUT_RULES,
                ),
            ),
            PrefSection(
                R.string.section_clipboard_hint,
                listOf(
                    AppPrefs.Clipboard.CLIPBOARD_SUGGESTION,
                    AppPrefs.Clipboard.CLIPBOARD_SUGGESTION_TIMEOUT,
                ),
            ),
            PrefSection(
                R.string.section_clipboard_paste,
                listOf(AppPrefs.Clipboard.CLIPBOARD_RETURN_AFTER_PASTE),
            ),
        ),
    ),
)
