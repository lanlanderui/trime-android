/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import com.osfans.trime.data.prefs.AppPrefs
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The preedit's clearance above the keyboard: what the stored dp value turns into, and why it
 * cannot be taken at face value.
 *
 * The value ends up as a layout margin between the preedit and the keyboard, so both ends of the
 * range matter. Zero has to stay *exactly* zero, because that is the flush layout every theme's
 * square-bottomed preedit background was drawn against; a pixel of rounding there would leave a
 * visible seam between two views that are supposed to share an edge. And a value from outside the
 * slider's range must not reach the layout at all.
 */
class PreeditGapTest :
    BehaviorSpec({
        Given("the default preference value") {
            Then("the preedit stays flush with the keyboard, at any density") {
                preeditGapPx(gapDp = 0, density = 1f) shouldBe 0
                preeditGapPx(gapDp = 0, density = 2.5f) shouldBe 0
                preeditGapPx(gapDp = 0, density = 4f) shouldBe 0
            }
        }

        Given("a gap in the slider's range") {
            Then("it becomes the same physical distance on screens of different density") {
                preeditGapPx(gapDp = 12, density = 1f) shouldBe 12
                preeditGapPx(gapDp = 12, density = 2.5f) shouldBe 30
                preeditGapPx(gapDp = 12, density = 3f) shouldBe 36
            }

            Then("a fractional pixel is rounded to the nearer one") {
                // 1 dp at 2.75 is 2.75 px; truncating would shave a pixel off every such value.
                preeditGapPx(gapDp = 1, density = 2.75f) shouldBe 3
            }
        }

        Given("a value outside the slider's range") {
            Then("a negative gap cannot pull the preedit into the keys") {
                // Overlap is the one outcome the layout must never produce: the two edges are
                // anchored to each other, so a negative margin would fold one into the other.
                preeditGapPx(gapDp = -8, density = 2f) shouldBe 0
            }

            Then("an oversized gap is clamped to the slider's maximum") {
                preeditGapPx(gapDp = 400, density = 1f) shouldBe AppPrefs.Keyboard.PREEDIT_KEYBOARD_GAP_MAX_DP
            }
        }

        Given("the range the settings row offers") {
            Then("the largest value the slider offers survives the clamp unchanged") {
                // Both read the same constant, so a value the user can reach is never silently
                // shrunk on its way to the layout -- which would read as a slider that stops
                // doing anything near its top end.
                preeditGapPx(AppPrefs.Keyboard.PREEDIT_KEYBOARD_GAP_MAX_DP, density = 1f) shouldBe
                    AppPrefs.Keyboard.PREEDIT_KEYBOARD_GAP_MAX_DP
            }
        }

        Given("a floating card with the chip sitting flush on it") {
            Then("the chip still overlaps it by a dp, whatever the density") {
                // The overlap hides the hairline the rounded card and the square chip leave
                // between them; it is what "flush" looks like while floating.
                floatingPreeditOverlapPx(gapDp = 0, density = 1f) shouldBe 1
                floatingPreeditOverlapPx(gapDp = 0, density = 2.5f) shouldBe 3
            }

            Then("a lifted chip takes no overlap, so the gap is exactly the setting") {
                // One dp of constant overlap would turn a 12 dp setting into 11 dp of visible
                // clearance -- and the merge of the two vertical knobs is precisely about the
                // number on the row matching what is on screen.
                floatingPreeditOverlapPx(gapDp = 1, density = 2.5f) shouldBe 0
                floatingPreeditOverlapPx(gapDp = 12, density = 2.5f) shouldBe 0
            }
        }
    })
