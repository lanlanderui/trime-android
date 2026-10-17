/*
 * SPDX-FileCopyrightText: 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import com.osfans.trime.R
import com.osfans.trime.util.toast

/**
 * Whether this device can actually speak, and what to do when it cannot.
 *
 * The keyboard's read-aloud feedback constructs a [TextToSpeech] with a `null` engine, which means
 * "use whatever the system default is". On a device with no speech engine — which is more common
 * than it sounds: the engine is a separate install, and a stock build can easily ship without one —
 * that constructor hands back an object whose every method is a no-op, and nothing anywhere reports
 * a failure. The user turns the setting on, presses a key, hears nothing, and has no way to tell a
 * broken keyboard from a missing system component.
 *
 * So the screen asks this first, and says so.
 */
object TtsAvailability {
    /**
     * Whether any app on this device offers a text-to-speech service.
     *
     * Asked through the [android.content.pm.PackageManager] rather than by constructing a
     * [TextToSpeech]: this is called while the preference screen is being built, and constructing one
     * binds a service (and would have to be shut down again) just to read a list. The intent query
     * answers the same question — "is there an engine at all" — with no connection to anything.
     *
     * It deliberately does not try to decide whether the *selected* engine has voice data for the
     * user's language: that needs a live engine to ask, and the answer can change without any change
     * here. A device with an engine but no voice data is out of scope for this hint.
     */
    fun canSpeak(context: Context): Boolean {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return runCatching {
            context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
        }.getOrDefault(true)
        // An unanswerable query assumes "yes", which means no hint is shown. That is the safe
        // default: the hint exists to explain a silence, and a wrong "no speech engine" on a device
        // that speaks perfectly well would send the user hunting for a problem they do not have.
    }

    /**
     * Opens the system's text-to-speech settings, where an engine can be installed or picked.
     *
     * Falls back to the general settings screen when a device ships no such activity, rather than
     * throwing — an unlaunchable intent is worse than no shortcut.
     */
    fun openSystemSettings(context: Context) {
        val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolvable =
            context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        val target =
            if (resolvable) {
                intent
            } else {
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        runCatching { context.startActivity(target) }
            .onFailure { context.toast(R.string.tts_settings_unavailable) }
    }
}
