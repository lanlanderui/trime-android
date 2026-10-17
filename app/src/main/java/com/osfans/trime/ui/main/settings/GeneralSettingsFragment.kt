/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment

/**
 * What the keyboard shows while you type, and which system services it borrows.
 *
 * Four rows, so no sections: grouping them would put a heading over every other row. The page is
 * named after the question rather than after the subsystem, which is why it is no longer "常规".
 */
class GeneralSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().general)
