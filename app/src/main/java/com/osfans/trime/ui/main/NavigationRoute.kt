/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Parcelable
import androidx.navigation.NavController
import androidx.navigation.createGraph
import androidx.navigation.fragment.fragment
import com.osfans.trime.R
import com.osfans.trime.ui.main.settings.AppSystemFragment
import com.osfans.trime.ui.main.settings.CandidatesSettingsFragment
import com.osfans.trime.ui.main.settings.ClipboardSettingsFragment
import com.osfans.trime.ui.main.settings.FeedbackSettingsFragment
import com.osfans.trime.ui.main.settings.GeneralSettingsFragment
import com.osfans.trime.ui.main.settings.GestureTimingFragment
import com.osfans.trime.ui.main.settings.KeyboardLayoutFragment
import com.osfans.trime.ui.main.settings.PreeditAppearanceSettingsFragment
import com.osfans.trime.ui.main.settings.ProfileSettingsFragment
import com.osfans.trime.ui.main.settings.ShortcutSettingsFragment
import com.osfans.trime.ui.main.settings.schema.SchemaListFragment
import com.osfans.trime.ui.main.settings.theme.ThemeSettingsFragment
import com.osfans.trime.ui.main.settings.userdict.UserDictionaryFragment
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
sealed class NavigationRoute : Parcelable {

    @Serializable
    data object Main : NavigationRoute()

    @Serializable
    data object SchemaList : NavigationRoute()

    @Serializable
    data object UserDict : NavigationRoute()

    @Serializable
    data object Profile : NavigationRoute()

    @Serializable
    data object General : NavigationRoute()

    @Serializable
    data object VirtualKeyboard : NavigationRoute()

    /**
     * Gestures and timings, split out of the keyboard's layout page.
     *
     * Deliberately its own route rather than a tab inside [VirtualKeyboard]: the IME's "键盘" action
     * deep-links to the layout page, and that page should open on what it is named for.
     */
    @Serializable
    data object GestureTiming : NavigationRoute()

    @Serializable
    data object PreeditAppearance : NavigationRoute()

    @Serializable
    data object Feedback : NavigationRoute()

    @Serializable
    data object Shortcut : NavigationRoute()

    @Serializable
    data object CandidatesWindow : NavigationRoute()

    @Serializable
    data object Theme : NavigationRoute()

    @Serializable
    data object Clipboard : NavigationRoute()

    @Serializable
    data object AppSystem : NavigationRoute()

    @Serializable
    data object Developer : NavigationRoute()

    @Serializable
    data object About : NavigationRoute()

    @Serializable
    data object License : NavigationRoute()

    companion object {
        fun createGraph(controller: NavController) = controller.createGraph(Main) {
            val ctx = controller.context

            fragment<MainFragment, Main> {
                label = ctx.getString(R.string.trime_app_name)
            }

            fragment<SchemaListFragment, SchemaList> {
                label = ctx.getString(R.string.schemata)
            }
            fragment<UserDictionaryFragment, UserDict> {
                label = ctx.getString(R.string.user_dictionary)
            }
            fragment<ProfileSettingsFragment, Profile> {
                label = ctx.getString(R.string.profile)
            }

            fragment<GeneralSettingsFragment, General> {
                label = ctx.getString(R.string.section_input_behavior)
            }
            fragment<KeyboardLayoutFragment, VirtualKeyboard> {
                label = ctx.getString(R.string.page_keyboard_layout)
            }
            fragment<GestureTimingFragment, GestureTiming> {
                label = ctx.getString(R.string.section_gesture_timing)
            }
            fragment<PreeditAppearanceSettingsFragment, PreeditAppearance> {
                label = ctx.getString(R.string.page_preedit)
            }
            fragment<FeedbackSettingsFragment, Feedback> {
                label = ctx.getString(R.string.feedback)
            }
            fragment<ShortcutSettingsFragment, Shortcut> {
                label = ctx.getString(R.string.shortcut)
            }
            fragment<CandidatesSettingsFragment, CandidatesWindow> {
                label = ctx.getString(R.string.page_candidate_area)
            }
            fragment<ThemeSettingsFragment, Theme> {
                label = ctx.getString(R.string.section_theme_colors)
            }
            fragment<ClipboardSettingsFragment, Clipboard> {
                label = ctx.getString(R.string.clipboard)
            }
            fragment<AppSystemFragment, AppSystem> {
                label = ctx.getString(R.string.page_app_system)
            }
            fragment<DeveloperFragment, Developer> {
                label = ctx.getString(R.string.developer)
            }
            fragment<AboutFragment, About> {
                label = ctx.getString(R.string.about)
            }
            fragment<LicenseFragment, License> {
                label = ctx.getString(R.string.license)
            }
        }
    }
}
