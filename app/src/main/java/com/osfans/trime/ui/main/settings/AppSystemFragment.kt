/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PrefSection
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.prefs.ProviderSections

/**
 * The settings that are about the app and the system rather than about the keyboard.
 *
 * Renamed from "Advanced", which said nothing about what was inside: the app's own light/dark
 * mode, the launcher icon, and whether the keyboard may cover the gesture inset. The page says so
 * in its sections, because an "app appearance" row sitting next to keyboard settings reads like it
 * restyles the keyboard — it does not.
 */
class AppSystemFragment : PreferenceDelegateFragment(
    ProviderSections(
        AppPrefs.defaultInstance().advanced,
        listOf(
            PrefSection(
                R.string.section_app_appearance,
                listOf(AppPrefs.Advanced.UI_MODE),
            ),
            PrefSection(
                R.string.section_launcher,
                listOf(AppPrefs.Advanced.SHOW_APP_ICON),
            ),
            PrefSection(
                R.string.section_system_integration,
                listOf(AppPrefs.Advanced.IGNORE_SYSTEM_GESTURE_INSETS),
            ),
        ),
    ),
) {
    private val uiMode = AppPrefs.defaultInstance().advanced.uiMode

    private val showAppIcon = AppPrefs.defaultInstance().advanced.showAppIcon

    @Keep
    private val onUiModeChange = PreferenceDelegate.OnChangeListener<AppPrefs.Advanced.UiMode> { _, v ->
        val mode = when (v) {
            AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    @Keep
    private val onShowAppIconChange = PreferenceDelegate.OnChangeListener<Boolean> { _, v ->
        showAppIcon(requireContext(), v)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiMode.registerOnChangeListener(onUiModeChange)
        showAppIcon.registerOnChangeListener(onShowAppIconChange)
    }

    override fun onDestroy() {
        uiMode.unregisterOnChangeListener(onUiModeChange)
        showAppIcon.unregisterOnChangeListener(onShowAppIconChange)
        super.onDestroy()
    }

    companion object {
        private const val SETTINGS_ACTIVITY_NAME = "com.osfans.trime.MainLauncherAlias"

        fun showAppIcon(
            context: Context,
            enable: Boolean,
        ) {
            val state =
                if (enable) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, SETTINGS_ACTIVITY_NAME),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
