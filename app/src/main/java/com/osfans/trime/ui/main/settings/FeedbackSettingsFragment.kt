/*
 * SPDX-FileCopyrightText: 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PrefSection
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.prefs.ProviderSections
import com.osfans.trime.ime.keyboard.TtsAvailability
import kotlinx.coroutines.launch

/**
 * Sound, vibration and speech — everything the keyboard does back at the user.
 *
 * Split out of the keyboard page because those three were interleaved with the keyboard's layout
 * and gesture settings, and someone who wants to turn the beep off should not have to scroll past
 * the vibration timings to reach it.
 */
class FeedbackSettingsFragment : PreferenceDelegateFragment(
    ProviderSections(
        AppPrefs.defaultInstance().feedback,
        listOf(
            PrefSection(
                R.string.section_sound,
                listOf(
                    AppPrefs.Feedback.SOUND_ON_KEYPRESS,
                    AppPrefs.Feedback.KEY_SOUND_VOLUME,
                    AppPrefs.Feedback.USE_CUSTOM_SOUND_EFFECT,
                    AppPrefs.Feedback.CUSTOM_SOUND_EFFECT,
                ),
            ),
            PrefSection(
                R.string.section_vibration,
                listOf(
                    AppPrefs.Feedback.VIBRATE_ON_KEY_PRESS,
                    AppPrefs.Feedback.VIBRATE_ON_KEY_RELEASE,
                    AppPrefs.Feedback.VIBRATE_ON_KEY_REPEAT,
                    AppPrefs.Feedback.VIBRATION_DURATION,
                    AppPrefs.Feedback.VIBRATION_AMPLITUDE,
                ),
            ),
            PrefSection(
                R.string.section_speak,
                listOf(
                    AppPrefs.Feedback.SPEAK_ON_KEYPRESS,
                    AppPrefs.Feedback.SPEAK_ON_COMMIT,
                ),
            ),
        ),
    ),
) {

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.findPreference<Preference>("custom_sound_effect_name")?.apply {
            setOnPreferenceClickListener {
                lifecycleScope.launch {
                    SoundEffectPickerDialog.build(lifecycleScope, requireContext())
                        .show()
                }
                true
            }
        }
        // The read-aloud switches are where a missing speech engine is discovered, so they are also
        // where the way out belongs: one tap reaches the system screen that installs or picks an
        // engine. Harmless on a device that has one — the switch still toggles normally.
        listOf("speak_on_keypress", "speak_on_commit").forEach { key ->
            screen.findPreference<Preference>(key)?.setOnPreferenceClickListener {
                TtsAvailability.openSystemSettings(requireContext())
                true
            }
        }
    }
}
