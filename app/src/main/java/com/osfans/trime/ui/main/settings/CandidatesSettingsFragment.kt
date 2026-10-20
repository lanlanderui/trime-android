/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PrefSection
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.prefs.ProviderSections
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.bar.RightButton
import com.osfans.trime.ui.main.settings.theme.BuiltinColorDialog
import com.osfans.trime.util.ColorUtils as TrimeColorUtils
import java.util.Locale

class CandidatesSettingsFragment : PreferenceDelegateFragment(
    listOf(
        ProviderSections(
            AppPrefs.defaultInstance().candidates,
            listOf(
                PrefSection(
                    R.string.section_candidates_display,
                    listOf(
                        AppPrefs.Candidates.MODE,
                        AppPrefs.Candidates.LAYOUT,
                        AppPrefs.Candidates.POSITION,
                        AppPrefs.Candidates.BACKGROUND_ALPHA,
                    ),
                ),
                PrefSection(
                    R.string.section_candidates_strip,
                    listOf(
                        AppPrefs.Candidates.STRIP_BACKGROUND,
                        AppPrefs.Candidates.STRIP_COLOR,
                        AppPrefs.Candidates.STRIP_OPACITY,
                        AppPrefs.Candidates.STRIP_LENGTH,
                        AppPrefs.Candidates.STRIP_GAP,
                    ),
                ),
                PrefSection(
                    R.string.section_candidates_toggles,
                    listOf(
                        AppPrefs.Candidates.RIGHT_BUTTONS,
                        AppPrefs.Candidates.SWITCHES_DEFAULT_OPEN,
                        AppPrefs.Candidates.CORNER_TOGGLES_COMPOSING,
                        AppPrefs.Candidates.SWITCHES_IN_WINDOW,
                    ),
                ),
                PrefSection(
                    R.string.section_candidates_items,
                    listOf(
                        AppPrefs.Candidates.TWO_ROW_FONT,
                        AppPrefs.Candidates.ITEM_BACKGROUND,
                        AppPrefs.Candidates.ITEM_SPACING,
                        AppPrefs.Candidates.ITEM_BACKGROUND_ALPHA,
                    ),
                ),
                PrefSection(
                    R.string.section_candidates_row,
                    listOf(
                        AppPrefs.Candidates.HORIZONTAL_CANDIDATE_MODE,
                        AppPrefs.Candidates.MAX_SPAN_COUNT,
                        AppPrefs.Candidates.MAX_SPAN_COUNT_LANDSCAPE,
                    ),
                ),
            ),
        ),
        // The bar the candidates sit in is part of this object, not of the keyboard's layout: the
        // row that hides it entirely belongs next to the rows that decide what it looks like.
        ProviderSections(
            AppPrefs.defaultInstance().keyboard,
            listOf(
                PrefSection(
                    R.string.section_input_bar,
                    listOf(AppPrefs.Keyboard.HIDE_INPUT_BAR),
                ),
            ),
            includeUnlisted = false,
        ),
    ),
) {
    private val prefs = AppPrefs.defaultInstance().candidates

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        // The strip's colour needs the ARGB picker, which lives above the preferences layer, so
        // the row is wired here: the tap opens the dialog, the summary keeps the value in view,
        // and the dialog's result is split between the colour and the opacity preference.
        screen.findPreference<Preference>(AppPrefs.Candidates.STRIP_COLOR)?.apply {
            summaryProvider = Preference.SummaryProvider<Preference> { preference ->
                stripColorLabel(preference.context, prefs.stripColor.getValue())
            }
            setOnPreferenceClickListener {
                showStripColorDialog()
                true
            }
        }
        // Which switches the row carries is a set, and the framework's own list rows are
        // single-choice, so the row gets a multi-choice dialog wired the same way as the colour.
        screen.findPreference<Preference>(AppPrefs.Candidates.RIGHT_BUTTONS)?.apply {
            summaryProvider = Preference.SummaryProvider<Preference> { preference ->
                rightButtonsLabel(preference.context, prefs.rightButtons.getValue())
            }
            setOnPreferenceClickListener {
                showRightButtonsDialog()
                true
            }
        }
    }

    /**
     * The set of buttons the strip row carries.
     *
     * Confirming writes the ids, which is what rebuilds the input view -- the row cannot grow or
     * lose a button in place, since the trailing slot's shape depends on the count.
     */
    private fun showRightButtonsDialog() {
        val context = requireContext()
        val buttons = RightButton.ALL
        val chosen = RightButton.parse(prefs.rightButtons.getValue()).toMutableSet()
        val labels = buttons.map { context.getString(it.label) }.toTypedArray()
        val checked = buttons.map { it in chosen }.toBooleanArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.candidates_right_buttons)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) chosen += buttons[which] else chosen -= buttons[which]
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.rightButtons.setValue(RightButton.serialize(chosen))
                rebindRows()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showStripColorDialog() {
        val context = requireContext()
        // Open on the colour the user is actually looking at: the automatic shade when the
        // colour is automatic, at the alpha the fill currently carries.
        val rgb = prefs.stripColor.getValue().takeUnless { it == 0 } ?: automaticStripRgb()
        val alpha = prefs.stripOpacity.getValue() * 255 / 100
        BuiltinColorDialog.build(
            context,
            context.getString(R.string.candidates_strip_color),
            ColorUtils.setAlphaComponent(rgb, alpha),
            onPicked = { hex ->
                runCatching { TrimeColorUtils.parseColor(hex) }.getOrNull()?.let { picked ->
                    // The dialog carries opacity, but the fill's alpha is the opacity
                    // preference's, so the picked alpha lands there instead of in the colour.
                    // Rounded, not floored: the percent to alpha trip floors as well, and a
                    // floor on both legs would walk the value down on every confirmation.
                    prefs.stripColor.setValue(picked and 0x00FFFFFF or ALPHA_OPAQUE)
                    prefs.stripOpacity.setValue((Color.alpha(picked) * 100 + 127) / 255)
                    rebindRows()
                }
            },
            onFollowScheme = {
                prefs.stripColor.setValue(0)
                rebindRows()
            },
            followLabelRes = R.string.candidates_strip_color_auto,
        ).show()
    }

    override fun onResume() {
        super.onResume()
        // A custom summary does not re-render when its preference was written while this screen
        // sat in the back stack -- the colour is picked over this very page -- so every return
        // rebinds the rows instead of trusting the change listener.
        rebindRows()
    }

    /**
     * Re-evaluates every visible row.
     *
     * The summary providers are only consulted on a rebind, and a preference written elsewhere
     * leaves the list showing the value it had when it was built.
     */
    private fun rebindRows() {
        listView?.adapter?.notifyDataSetChanged()
    }

    /**
     * What the automatic shade resolves to right now: the opposite of the screen's own
     * luminance. Falls back to black when the scheme does not hand the colour over plainly.
     */
    private fun automaticStripRgb(): Int {
        val screen = ColorManager.activeColorScheme.colors["candidate_background"]
        val parsed = screen?.let { runCatching { TrimeColorUtils.parseColor(it) }.getOrNull() }
            ?: return Color.BLACK
        return if (ColorUtils.calculateLuminance(parsed) > 0.5) Color.BLACK else Color.WHITE
    }

    private companion object {
        const val ALPHA_OPAQUE = 0xFF000000.toInt()
    }
}

/**
 * The colour row's summary: the automatic label, or the picked colour as a hex value. Kept free
 * of Android types beyond the string lookup so the text itself stays testable.
 */
internal fun stripColorLabel(context: Context, color: Int): String =
    if (color == 0) {
        context.getString(R.string.candidates_strip_color_auto)
    } else {
        String.format(Locale.ROOT, "#%06X", color and 0x00FFFFFF)
    }

/**
 * The corner-button row's summary: the names of the buttons left in, in the dialog's own order.
 * Kept free of Android types beyond the string lookups so the text itself stays testable.
 */
internal fun rightButtonsLabel(context: Context, raw: String): String {
    val chosen = RightButton.parse(raw)
    if (chosen.isEmpty()) return context.getString(R.string.right_buttons_none)
    val names = chosen.joinToString(context.getString(R.string.right_buttons_separator)) {
        context.getString(it.label)
    }
    return context.getString(R.string.right_buttons_selected, names)
}
