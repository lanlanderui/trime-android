/*
 * SPDX-FileCopyrightText: 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The candidate window's coordinate space, pinned down by the bug it caused.
 *
 * The window refuses to place itself while its space has no height — it would have no reference for
 * "where the bottom is", so it leaves the view at (0,0). That guard is right; the height fed to it
 * was not. A floating keyboard never fills `inputViewLocation`, so the value the height was read
 * from stayed at its initial zero, and *every* placement — follow-the-cursor included — silently
 * put the window in the corner.
 */
class CandidateSpaceHeightTest :
    BehaviorSpec({
        val screenHeight = 2120
        val keyboardTop = 1400

        Given("a docked virtual keyboard") {
            Then("the space stops at the keyboard's top edge") {
                // The point of this value: the candidate window measures its bottom limit against
                // it, so the window stays above the keys.
                candidateSpaceHeight(
                    useVirtualKeyboard = true,
                    floating = false,
                    keyboardTopInWindow = keyboardTop,
                    windowHeight = screenHeight,
                ) shouldBe keyboardTop.toFloat()
            }
        }

        Given("a floating keyboard") {
            Then("the space is the whole window, not the keyboard's stale top edge") {
                // The bug: this used to return the keyboard's top edge, and for a floating keyboard
                // that value is never written — it is the initial zero. The window then had no
                // height to measure against and parked itself at (0,0).
                candidateSpaceHeight(
                    useVirtualKeyboard = true,
                    floating = true,
                    keyboardTopInWindow = 0,
                    windowHeight = screenHeight,
                ) shouldBe screenHeight.toFloat()
            }

            Then("a stale edge left over from before the keyboard started floating is ignored") {
                // Docking and then floating leaves the last docked reading in place. Reading it here
                // would put the window's bottom limit somewhere in the middle of the editor.
                candidateSpaceHeight(
                    useVirtualKeyboard = true,
                    floating = true,
                    keyboardTopInWindow = keyboardTop,
                    windowHeight = screenHeight,
                ) shouldBe screenHeight.toFloat()
            }
        }

        Given("no virtual keyboard at all") {
            Then("the space is the whole window, which is all there is") {
                candidateSpaceHeight(
                    useVirtualKeyboard = false,
                    floating = false,
                    keyboardTopInWindow = 0,
                    windowHeight = screenHeight,
                ) shouldBe screenHeight.toFloat()
            }
        }

        Given("the guard the caller relies on") {
            Then("a height of zero is what stops the window from placing itself") {
                // Recorded so the link is visible: the value above must never be zero, or the guard
                // in `CandidatesView.updatePosition` turns it into a window stuck at the origin.
                val height =
                    candidateSpaceHeight(true, true, keyboardTopInWindow = 0, windowHeight = screenHeight)
                (height > 0f) shouldBe true
            }
        }
    })
