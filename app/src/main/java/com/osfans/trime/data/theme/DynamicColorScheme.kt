/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.ColorRes
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.util.appContext
import java.util.Locale

/**
 * The built-in "dynamic" colour scheme, derived from the Material You palettes the system
 * generates from the user's wallpaper (`android.R.color.system_accent*` and
 * `android.R.color.system_neutral*`).
 *
 * It deliberately is *not* a theme preset. [Theme.colorSchemes] only ever holds what
 * `preset_color_schemes` declares, so this scheme is appended to the selectable list at the
 * point of use (see [ColorManager.availableColorSchemes]) under the reserved id [ID]. That way
 * a theme can define whatever schemes it likes without hiding the dynamic one — and, because
 * the id is reserved, without shadowing it either.
 *
 * The system palettes exist from Android 12 on, so [isSupported] gates every entry point.
 */
object DynamicColorScheme {
    /** Reserved id. It is persisted in `normalModeColor`, so it must stay stable. */
    const val ID = "dynamic"

    /**
     * Annotated so lint can tell that guarding on this property also guards the
     * [android.R.color.system_accent1_600]-style reads below.
     */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private const val TONE_0 = 0
    private const val TONE_50 = 50
    private const val TONE_100 = 100
    private const val TONE_200 = 200
    private const val TONE_300 = 300
    private const val TONE_600 = 600
    private const val TONE_700 = 700
    private const val TONE_800 = 800
    private const val TONE_900 = 900

    /**
     * Builds the scheme for the current day/night state.
     *
     * The system palettes carry no day/night variants of their own — which tone is picked does
     * the work instead — so the caller passes the night state and the tones below mirror
     * Material 3's light and dark roles.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun create(isNight: Boolean): ColorScheme = ColorScheme(ID, palette(isNight))

    private fun palette(isNight: Boolean): Map<String, String> {
        fun tone(
            light: Int,
            dark: Int,
        ): Int = if (isNight) dark else light

        val surface = argb(neutral1(tone(TONE_50, TONE_800)))
        val surfaceVariant = argb(neutral1(tone(TONE_100, TONE_700)))
        val onSurface = argb(neutral1(tone(TONE_900, TONE_100)))
        val onSurfaceVariant = argb(neutral1(tone(TONE_700, TONE_200)))
        val outline = argb(neutral1(tone(TONE_300, TONE_600)))
        val primary = argb(accent1(tone(TONE_600, TONE_200)))
        val onPrimary = argb(accent1(tone(TONE_0, TONE_900)))
        val primaryContainer = argb(accent1(tone(TONE_100, TONE_700)))
        val onPrimaryContainer = argb(accent1(tone(TONE_900, TONE_100)))

        return mapOf(
            // Both links point back at this scheme. With "follow system day/night" enabled,
            // ColorSchemeResolver treats a scheme declaring neither link as one to defer from and
            // falls through to the theme default. The dynamic palette already tracks day/night by
            // itself (see `create`), so it points at itself to survive that branch.
            ColorSchemeResolver.LIGHT_SCHEME_KEY to ID,
            ColorSchemeResolver.DARK_SCHEME_KEY to ID,
            // Backgrounds.
            "back_color" to surface,
            "root_background" to surface,
            "text_back_color" to surface,
            "long_text_back_color" to surface,
            "candidate_background" to surfaceVariant,
            "keyboard_back_color" to surfaceVariant,
            "key_back_color" to surface,
            "off_key_back_color" to surface,
            "popup_back_color" to surfaceVariant,
            // Lines.
            "border_color" to outline,
            "key_border_color" to outline,
            "off_key_border_color" to outline,
            "candidate_separator_color" to outline,
            // Text and symbols.
            "key_text_color" to onSurface,
            "candidate_text_color" to onSurface,
            "long_text_color" to onSurface,
            "popup_text_color" to onSurface,
            "key_symbol_color" to onSurfaceVariant,
            "comment_text_color" to onSurfaceVariant,
            "label_color" to onSurfaceVariant,
            "off_key_text_color" to onSurfaceVariant,
            "off_key_symbol_color" to onSurfaceVariant,
            "text_color" to primary,
            // Highlighted rows and keys.
            "hilited_back_color" to primaryContainer,
            "hilited_text_color" to onPrimaryContainer,
            "hilited_candidate_back_color" to primaryContainer,
            "hilited_candidate_text_color" to onPrimaryContainer,
            "hilited_candidate_button_color" to primary,
            "hilited_comment_text_color" to onPrimaryContainer,
            "hilited_label_color" to onPrimaryContainer,
            "hilited_key_back_color" to primary,
            "hilited_key_border_color" to primary,
            "hilited_key_text_color" to onPrimary,
            "hilited_key_symbol_color" to onPrimary,
            "hilited_popup_back_color" to primary,
            "hilited_popup_text_color" to onPrimary,
            // Toggle keys.
            "on_key_back_color" to primaryContainer,
            "on_key_border_color" to primary,
            "on_key_text_color" to onPrimaryContainer,
            "on_key_symbol_color" to onPrimaryContainer,
            "hilited_on_key_back_color" to primary,
            "hilited_on_key_border_color" to primary,
            "hilited_on_key_text_color" to onPrimary,
            "hilited_on_key_symbol_color" to onPrimary,
            "hilited_off_key_back_color" to primaryContainer,
            "hilited_off_key_border_color" to primary,
            "hilited_off_key_text_color" to onPrimaryContainer,
            "hilited_off_key_symbol_color" to onPrimaryContainer,
        )
    }

    @ColorRes
    private fun accent1(tone: Int): Int =
        when (tone) {
            TONE_0 -> android.R.color.system_accent1_0
            TONE_50 -> android.R.color.system_accent1_50
            TONE_100 -> android.R.color.system_accent1_100
            TONE_200 -> android.R.color.system_accent1_200
            TONE_300 -> android.R.color.system_accent1_300
            TONE_600 -> android.R.color.system_accent1_600
            TONE_700 -> android.R.color.system_accent1_700
            TONE_800 -> android.R.color.system_accent1_800
            TONE_900 -> android.R.color.system_accent1_900
            else -> error("No accent1 tone $tone")
        }

    @ColorRes
    private fun neutral1(tone: Int): Int =
        when (tone) {
            TONE_50 -> android.R.color.system_neutral1_50
            TONE_100 -> android.R.color.system_neutral1_100
            TONE_200 -> android.R.color.system_neutral1_200
            TONE_300 -> android.R.color.system_neutral1_300
            TONE_600 -> android.R.color.system_neutral1_600
            TONE_700 -> android.R.color.system_neutral1_700
            TONE_800 -> android.R.color.system_neutral1_800
            TONE_900 -> android.R.color.system_neutral1_900
            else -> error("No neutral1 tone $tone")
        }

    private fun argb(
        @ColorRes id: Int,
    ): String = String.format(Locale.ROOT, "#%08X", ContextCompat.getColor(appContext, id))
}
