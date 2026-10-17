/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PrefSection
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.prefs.ProviderSections
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePrefs
import com.osfans.trime.ui.common.materialAlertDialogBuilder
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.startActivity
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch

private val navbarRowSupported: Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM

class ThemeSettingsFragment : PreferenceDelegateFragment(
    ProviderSections(
        ThemeManager.prefs,
        listOf(
            // Untitled: the page is already called "主题与配色", and a heading repeating it over
            // the two rows that carry the name would be one line of ink saying nothing.
            PrefSection(
                keys = listOf(ThemePrefs.SELECTED_THEME, ThemePrefs.NORMAL_MODE_COLOR),
            ),
            PrefSection(
                R.string.section_system_bars,
                buildList {
                    // The navigation bar has no background of its own from Android 15 on: the row
                    // is not greyed out there, it is gone, the way the preference itself is.
                    if (navbarRowSupported) add(ThemePrefs.NAVBAR_BACKGROUND)
                    add(ThemePrefs.FOLLOW_SYSTEM_DAY_NIGHT)
                },
            ),
            // Storage without a row of its own: the three source switches live at the head of the
            // domain they govern on the appearance screen, and the navigation bar's key leaves the
            // page together with its row. Claiming them here keeps them out of the "forgotten row"
            // tail.
            PrefSection(
                hidden = true,
                keys =
                    buildList {
                        add(ThemePrefs.COLOR_SOURCE)
                        add(ThemePrefs.STYLE_SOURCE)
                        add(ThemePrefs.LAYOUT_SOURCE)
                        if (!navbarRowSupported) add(ThemePrefs.NAVBAR_BACKGROUND)
                    },
            ),
        ),
    ),
) {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>("selected_theme")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ThemePickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
        findPreference<Preference>("normal_mode_color")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ColorPickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.addCategory(R.string.section_advanced_entries) {
            addPreference(
                R.string.builtin_colors,
                R.string.builtin_colors_summary,
            ) {
                startActivity<BuiltinColorsActivity>()
            }
            addPreference(
                R.string.source_batch_switch,
                R.string.source_batch_switch_summary,
            ) {
                showBatchSwitchDialog()
            }
            addPreference(
                R.string.theme_diagnostics,
                R.string.theme_diagnostics_summary,
            ) {
                startActivity<ThemeDiagnosticsActivity>()
            }
        }
    }

    /**
     * Points every source at one side in one action.
     *
     * The per-domain switches live with their items, on the appearance screen; this is the coarse
     * control for the two moments it is the right tool: "make everything follow my theme again"
     * when checking whether the theme is at fault, and "let the built-in settings handle
     * everything" when handing the keyboard to someone who will never edit a theme file.
     */
    private fun showBatchSwitchDialog() {
        val choices =
            arrayOf(
                getString(R.string.source_batch_all_builtin),
                getString(R.string.source_batch_all_theme),
            )
        requireContext()
            .materialAlertDialogBuilder()
            .setTitle(R.string.source_batch_switch)
            .setItems(choices) { _, which ->
                val builtin = which == 0
                // Each preference's listener re-derives its domain of the theme.
                ThemeManager.prefs.styleSource.setValue(
                    if (builtin) ThemePrefs.StyleSource.BUILTIN else ThemePrefs.StyleSource.THEME,
                )
                ThemeManager.prefs.layoutSource.setValue(
                    if (builtin) ThemePrefs.LayoutSource.BUILTIN else ThemePrefs.LayoutSource.THEME,
                )
                ThemeManager.prefs.colorSource.setValue(
                    if (builtin) ThemePrefs.ColorSource.BUILTIN else ThemePrefs.ColorSource.SCHEME,
                )
                requireContext().toast(R.string.source_batch_done)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
