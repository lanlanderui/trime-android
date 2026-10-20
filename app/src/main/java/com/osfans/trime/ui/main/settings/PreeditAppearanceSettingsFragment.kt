/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.preference.DialogPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PrefSection
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.prefs.ProviderSections
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ui.main.settings.theme.BuiltinColorDialog
import com.osfans.trime.ui.main.settings.theme.KeyColorEditorActivity
import com.osfans.trime.util.ColorUtils as TrimeColorUtils
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.startActivity
import java.util.Locale

/**
 * The encoding area as a whole: where it sits, how far it stands off the keys, and what it looks
 * like.
 *
 * The position and the gap used to live on the keyboard page, the corners and the fill here, and
 * the text size and colours in the built-in appearance screen — one object split across three
 * pages. The first five rows are now here; the last two stay where they can be previewed, behind
 * the jump row at the bottom, which says so instead of leaving the reader to find out.
 */
class PreeditAppearanceSettingsFragment :
    PreferenceDelegateFragment(
        listOf(
            ProviderSections(
                AppPrefs.defaultInstance().keyboard,
                listOf(
                    PrefSection(
                        R.string.section_preedit_position,
                        listOf(
                            AppPrefs.Keyboard.FLOATING_PREEDIT_OFFSET_X,
                            AppPrefs.Keyboard.PREEDIT_KEYBOARD_GAP,
                        ),
                    ),
                ),
                includeUnlisted = false,
            ),
            ProviderSections(
                AppPrefs.defaultInstance().preeditAppearance,
                listOf(
                    PrefSection(
                        R.string.section_preedit_shape,
                        listOf(AppPrefs.PreeditAppearance.CORNER_RADIUS),
                    ),
                    PrefSection(
                        R.string.section_color,
                        listOf(
                            AppPrefs.PreeditAppearance.BACK_COLOR,
                            AppPrefs.PreeditAppearance.BACK_OPACITY,
                        ),
                    ),
                ),
            ),
        ),
    ) {
    private val prefs = AppPrefs.defaultInstance().preeditAppearance

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        // The text size and the text colour are painted by the theme, and the built-in appearance
        // screen is the one place that can show them on a preview — so the row points there rather
        // than duplicating a slider here.
        screen.addPreference(
            R.string.preedit_more_in_builtin,
            R.string.preedit_more_in_builtin_summary,
        ) {
            startActivity<KeyColorEditorActivity>()
        }
        // The colour needs the ARGB picker, which lives above the preferences layer, so the row is
        // wired here: the tap opens the dialog, the summary keeps the value in view, and the
        // dialog's result is split between the colour and the opacity preference.
        screen.findPreference<Preference>(AppPrefs.PreeditAppearance.BACK_COLOR)?.apply {
            summaryProvider = Preference.SummaryProvider<Preference> { preference ->
                preeditColorLabel(preference.context, prefs.backColor.getValue())
            }
            setOnPreferenceClickListener {
                showBackColorDialog()
                true
            }
        }
        // The two sliders say what the number buys, in the dialog, where the number is chosen.
        screen.findPreference<DialogPreference>(AppPrefs.PreeditAppearance.CORNER_RADIUS)?.apply {
            setDialogMessage(R.string.preedit_corner_radius_summary)
        }
        screen.findPreference<DialogPreference>(AppPrefs.PreeditAppearance.BACK_OPACITY)?.apply {
            setDialogMessage(R.string.preedit_back_opacity_summary)
        }
    }

    private fun showBackColorDialog() {
        val context = requireContext()
        // Open on the colour the user is actually looking at: the scheme's fill while the row
        // follows it, at the alpha the chip currently draws with. The stored colour's own alpha is
        // not a display alpha -- the fill forces it opaque and leaves transparency to the opacity
        // row -- so it is forced here too, or an old RGB-only value would open the picker blank.
        val base = prefs.backColor.getValue().takeUnless { it == 0 }?.or(OPAQUE) ?: schemeTextBackColor()
        val shown = ColorUtils.setAlphaComponent(base, Color.alpha(base) * prefs.backOpacity.getValue() / 100)
        BuiltinColorDialog.build(
            context,
            context.getString(R.string.preedit_back_color),
            shown,
            onPicked = { hex ->
                runCatching { TrimeColorUtils.parseColor(hex) }.getOrNull()?.let { picked ->
                    // The dialog carries opacity, but its alpha belongs to the opacity preference:
                    // the colour itself is stored opaque so the two rows never fight over one byte.
                    // Opaque, not bare RGB, is also what keeps pure black distinguishable from the
                    // zero that means "follow the colour scheme".
                    prefs.backColor.setValue(picked or OPAQUE)
                    prefs.backOpacity.setValue((Color.alpha(picked) * 100 + 127) / 255)
                    rebindRows()
                }
            },
            onFollowScheme = {
                prefs.backColor.setValue(0)
                rebindRows()
            },
            followLabelRes = R.string.preedit_back_color_follow,
        ).show()
    }

    override fun onResume() {
        super.onResume()
        // A custom summary does not re-render when its preference was written while this screen sat
        // in the back stack -- the colour is picked over this very page -- so every return rebinds
        // the rows instead of trusting the change listener.
        rebindRows()
    }

    /** Re-evaluates every visible row, so summaries written behind their back are re-read. */
    private fun rebindRows() {
        // The opacity is written by the colour dialog as well, which leaves this row's cached
        // value behind; without adopting the stored one its summary -- and its own slider --
        // would keep offering the number from before.
        findPreference<DialogSeekBarPreference>(AppPrefs.PreeditAppearance.BACK_OPACITY)?.refreshValue()
        listView?.adapter?.notifyDataSetChanged()
    }

    /**
     * The colour the chip falls back to: the scheme's own `text_back_color`, resolved through the
     * theme so aliases (`back_color`) come out as concrete values. Black when the theme scope is
     * not built yet -- the picker still opens, and the value is only a starting point.
     */
    private fun schemeTextBackColor(): Int =
        runCatching { ColorManager.getColor("text_back_color") }.getOrDefault(Color.BLACK)

    private companion object {
        /** Picked colours are stored opaque -- see [AppPrefs.PreeditAppearance.backColor]. */
        val OPAQUE = 0xFF000000.toInt()
    }
}

/**
 * The colour row's summary: the follow label, or the picked colour as a hex value. Kept free of
 * Android types beyond the string lookup so the text itself stays testable.
 */
internal fun preeditColorLabel(
    context: Context,
    color: Int,
): String =
    if (color == 0) {
        context.getString(R.string.preedit_back_color_follow)
    } else {
        String.format(Locale.ROOT, "#%06X", color and 0x00FFFFFF)
    }
