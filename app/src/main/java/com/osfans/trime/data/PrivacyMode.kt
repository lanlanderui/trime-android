/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data

import android.view.inputmethod.EditorInfo

/**
 * Whether the IME is in a private session, meaning the editor asked it not to
 * learn from what is typed.
 *
 * Deliberately process-wide rather than owned by a view: the clipboard history
 * and the symbol history live in this package and outlive any single input
 * view, and both have to stop recording for as long as the session lasts.
 */
object PrivacyMode {
    /**
     * Rime runtime option carrying the same request down to librime, which
     * suspends user-dictionary learning and lookups while it is set. The name is
     * shared with the librime side (`kNoPersonalizedLearningOption`).
     */
    const val RIME_OPTION = "_no_personalized_learning"

    @Volatile
    var isActive: Boolean = false
        private set

    /** Returns whether the value actually changed. */
    fun setActive(value: Boolean): Boolean {
        if (isActive == value) return false
        isActive = value
        return true
    }
}

/**
 * Whether this editor asked the IME not to learn from this session.
 *
 * Browsers raise [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] on the text
 * fields of a private/incognito tab (Chrome has done it since 59, Firefox too),
 * and that flag is the platform's only way of telling an IME to stop
 * personalizing. It cannot otherwise tell where its input is going, which is
 * why this is the flag to follow and not, say, the window's secure flag.
 */
internal fun isPrivacyModeRequested(imeOptions: Int): Boolean =
    (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
