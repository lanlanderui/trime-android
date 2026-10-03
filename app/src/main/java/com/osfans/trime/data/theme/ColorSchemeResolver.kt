/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.ColorScheme

/**
 * Pure scheme-selection logic: picks the active color scheme from the
 * selected scheme id, the follow-system-day-night preference and the current
 * night state. Extracted from ColorManager so it is unit-testable.
 */
internal object ColorSchemeResolver {
    private const val DEFAULT_SCHEME = "default"

    /** Scheme keys linking a scheme to its day/night counterpart; also written by [DynamicColorScheme]. */
    internal const val LIGHT_SCHEME_KEY = "light_scheme"
    internal const val DARK_SCHEME_KEY = "dark_scheme"

    /**
     * @param schemes color schemes of a theme that is known to be usable;
     *   [ThemeLoader] refuses a theme declaring none, so this list is never empty.
     */
    fun resolve(
        schemes: List<ColorScheme>,
        selectedSchemeId: String,
        followSystemDayNight: Boolean,
        isNightMode: Boolean,
    ): ColorScheme {
        require(schemes.isNotEmpty()) { "The theme defines no color scheme" }
        fun scheme(id: String): ColorScheme? = schemes.find { it.id == id }
        fun linkedScheme(source: ColorScheme): ColorScheme? {
            val linkKey = if (isNightMode) DARK_SCHEME_KEY else LIGHT_SCHEME_KEY
            return source.colors[linkKey]?.let { scheme(it) }
        }
        val defaultScheme = scheme(DEFAULT_SCHEME) ?: schemes.first()
        if (!followSystemDayNight) {
            return scheme(selectedSchemeId) ?: defaultScheme
        }
        val selected = scheme(selectedSchemeId)
        val resolved: ColorScheme? =
            if (selected == null) {
                linkedScheme(defaultScheme)
            } else {
                val lightSchemeId = selected.colors[LIGHT_SCHEME_KEY]
                val darkSchemeId = selected.colors[DARK_SCHEME_KEY]
                when {
                    lightSchemeId != null && darkSchemeId != null ->
                        // Both are set: pick by the current mode.
                        scheme(if (isNightMode) darkSchemeId else lightSchemeId)
                            ?: linkedScheme(defaultScheme)

                    lightSchemeId != null ->
                        // Light scheme only: this is a dark scheme.
                        if (isNightMode) selected else scheme(lightSchemeId) ?: linkedScheme(defaultScheme)

                    darkSchemeId != null ->
                        // Dark scheme only: this is a light scheme.
                        if (isNightMode) scheme(darkSchemeId) ?: linkedScheme(defaultScheme) else selected

                    else -> linkedScheme(defaultScheme)
                }
            }
        return resolved ?: defaultScheme
    }

    /**
     * The scheme a theme shows on its own for the given day/night state: its `default` preset,
     * following that preset's own day/night links.
     *
     * The built-in dynamic scheme is layered on top of this rather than standing alone, because a
     * theme is free to name colour keys of its own and bind them per key in its keyboards. Such a
     * reference only has a value while some scheme in play defines the key, and the built-in one
     * knows nothing about names it did not invent itself.
     *
     * @param schemes color schemes of a theme that is known to be usable; [ThemeLoader] refuses a
     *   theme declaring none, so this list is never empty.
     */
    fun themeBaseline(
        schemes: List<ColorScheme>,
        isNightMode: Boolean,
    ): ColorScheme = resolve(schemes, DEFAULT_SCHEME, followSystemDayNight = true, isNightMode)

    /**
     * The full list a picker offers: the theme's presets plus the built-in dynamic scheme.
     *
     * The dynamic scheme is not a preset, so this is the only thing that keeps it selectable
     * regardless of what a theme declares. It sorts last, and a preset reusing its reserved id is
     * dropped rather than duplicated — a theme can therefore neither hide it nor shadow it.
     *
     * Part of this object rather than of `ColorManager` for the same reason as [resolve]: it has
     * to stay unit-testable.
     *
     * @param dynamic the built-in scheme, or null on platforms without the system palettes; the
     *   presets then pass through untouched.
     */
    fun selectable(
        presets: List<ColorScheme>,
        dynamic: ColorScheme?,
    ): List<ColorScheme> =
        if (dynamic == null) {
            presets
        } else {
            presets.filterNot { it.id == dynamic.id } + dynamic
        }
}
