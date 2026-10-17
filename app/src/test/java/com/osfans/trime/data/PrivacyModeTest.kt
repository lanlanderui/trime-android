// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data

import android.view.inputmethod.EditorInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PrivacyModeTest :
    StringSpec({
        "an editor that asks for no personalized learning starts a private session" {
            isPrivacyModeRequested(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) shouldBe true
        }

        "an editor that asks for nothing does not" {
            isPrivacyModeRequested(0) shouldBe false
        }

        "the request is picked out of a realistic imeOptions word" {
            // What a browser's private tab tends to send: the request alongside
            // the enter action and the no-fullscreen bit, neither of which may be
            // mistaken for it.
            val requested =
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN or
                    EditorInfo.IME_ACTION_GO
            isPrivacyModeRequested(requested) shouldBe true

            val notRequested = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_GO
            isPrivacyModeRequested(notRequested) shouldBe false
        }

        "activating reports only the calls that actually change the state" {
            val previous = PrivacyMode.isActive
            try {
                PrivacyMode.setActive(false)
                PrivacyMode.setActive(false) shouldBe false
                PrivacyMode.setActive(true) shouldBe true
                PrivacyMode.isActive shouldBe true
                PrivacyMode.setActive(true) shouldBe false
            } finally {
                // Process-wide state: leave it as it was for the next spec.
                PrivacyMode.setActive(previous)
            }
        }
    })
