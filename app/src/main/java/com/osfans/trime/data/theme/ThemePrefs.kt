/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateEnum
import com.osfans.trime.data.prefs.PreferenceDelegateOwner

class ThemePrefs(
    sharedPrefs: SharedPreferences,
) : PreferenceDelegateOwner(sharedPrefs, R.string.theme) {
    val selectedTheme =
        string(
            R.string.selected_theme,
            SELECTED_THEME,
            "trime",
            R.string.selected_theme_summary,
        )

    /**
     * Where the IME's colours come from.
     *
     * [SCHEME] is the behaviour this app has always had: whatever the theme's
     * `preset_color_schemes` declare. [BUILTIN] layers
     * [com.osfans.trime.data.theme.BuiltinColorScheme] on top of the chosen
     * scheme instead, keeping every key the built-in palette does not name.
     */
    enum class ColorSource(
        override val stringRes: Int,
    ) : PreferenceDelegateEnum {
        SCHEME(R.string.color_source_scheme),
        BUILTIN(R.string.color_source_builtin),
    }

    val colorSource =
        enum(
            R.string.color_source,
            COLOR_SOURCE,
            ColorSource.SCHEME,
        )

    /**
     * Where the keyboard's *sizes* come from.
     *
     * [THEME] is the behaviour this app has always had: the theme's own `style` decides.
     * [BUILTIN] layers [com.osfans.trime.data.theme.BuiltinStyle] over it instead, keeping every
     * size the built-in settings do not name — the same overlay rule as [ColorSource], for the
     * numbers rather than the colours.
     *
     * While the source is [THEME] the stored sizes are skipped whole, not deleted: they stay on
     * disk, frozen, so that switching back is lossless.
     */
    enum class StyleSource(
        override val stringRes: Int,
    ) : PreferenceDelegateEnum {
        THEME(R.string.style_source_theme),
        BUILTIN(R.string.style_source_builtin),
    }

    val styleSource =
        enum(
            R.string.style_source,
            STYLE_SOURCE,
            StyleSource.THEME,
        )

    /**
     * Where the keyboard's *layout* comes from.
     *
     * [THEME] is the behaviour this app has always had: the theme's own `style` decides. [BUILTIN]
     * layers [com.osfans.trime.data.theme.BuiltinLayout] over it — heights and gaps, and only the
     * ones the built-in settings name. While the source is [THEME] the stored layout is skipped
     * whole, exactly like the sizes next to it; no migration is needed here, because the layout
     * store itself only exists from the build that also introduced this source.
     */
    enum class LayoutSource(
        override val stringRes: Int,
    ) : PreferenceDelegateEnum {
        THEME(R.string.style_source_theme),
        BUILTIN(R.string.layout_source_builtin),
    }

    val layoutSource =
        enum(
            R.string.layout_source,
            LAYOUT_SOURCE,
            LayoutSource.THEME,
        )

    val normalModeColor =
        string(
            R.string.normal_mode_color,
            NORMAL_MODE_COLOR,
            "default",
            R.string.normal_mode_color_summary,
        )

    enum class NavbarBackground(
        override val stringRes: Int,
    ) : PreferenceDelegateEnum {
        NONE(R.string.navbar_bkg_none),
        COLOR_ONLY(R.string.navbar_bkg_color_only),
        FULL(R.string.navbar_bkg_full),
    }

    val navbarBackground =
        enum(
            R.string.navbar_background,
            NAVBAR_BACKGROUND,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                NavbarBackground.FULL
            } else {
                NavbarBackground.COLOR_ONLY
            },
            enableUiOn = { Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM },
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                sharedPreferences.edit {
                    remove(this@apply.key)
                }
            }
        }

    val followSystemDayNight =
        switch(
            R.string.follow_system_day_night_color,
            FOLLOW_SYSTEM_DAY_NIGHT,
            false,
        )

    init {
        migrateStyleSource()
    }

    /**
     * Settles [styleSource] for installs that predate it.
     *
     * Before the source existed, whatever [BuiltinStyleStore] held was always applied — an
     * install with stored sizes looks the way it does *because* they were in charge. Reading
     * such an install as "follow the theme" would silently change it, so the first construction
     * of this class writes down what was already true: stored sizes ⇒ built-in; none ⇒ theme.
     *
     * Runs in the constructor rather than at a call site because every reader — the settings
     * row, the size patch in `ThemeManager`, whatever comes next — goes through this object
     * first, and the constructor is the one moment that precedes all of them. No change
     * listeners exist yet either, so the write cannot loop back into a half-built theme.
     */
    private fun migrateStyleSource() {
        if (sharedPreferences.contains(STYLE_SOURCE)) return
        styleSource.setValue(migratedStyleSource(BuiltinStyleStore.current().values.isNotEmpty()))
    }

    companion object {
        const val SELECTED_THEME = "selected_theme"
        const val NORMAL_MODE_COLOR = "normal_mode_color"
        const val FOLLOW_SYSTEM_DAY_NIGHT = "follow_system_day_night"
        const val NAVBAR_BACKGROUND = "navbar_background"
        const val COLOR_SOURCE = "color_source"
        const val STYLE_SOURCE = "style_source"
        const val LAYOUT_SOURCE = "layout_source"
    }
}
