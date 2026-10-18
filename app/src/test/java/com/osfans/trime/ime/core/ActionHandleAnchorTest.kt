/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ActionHandleAnchorTest :
    BehaviorSpec({
        // Rounded screen corners clip the grip row where it is pinned to the bottom of the screen,
        // so the corner-most handles have to be pulled inwards. Regression guard for the margin
        // axis itself: the margin must land on the *relative* start/end properties, never on the
        // far side of a handle, and never be dropped from the bottom.
        Given("a bottom handle margin of 12px") {
            val margin = 12

            Then("the bottom-left handle keeps the margin on its own side only") {
                handleMargins(ActionHandleAnchor.SURFACE_START, margin) shouldBe
                    HandleMargins(start = margin, end = 0)
            }

            Then("the bottom-right handle keeps the margin on its own side only") {
                handleMargins(ActionHandleAnchor.SURFACE_END, margin) shouldBe
                    HandleMargins(start = 0, end = margin)
            }

            Then("only the two corner anchors carry a horizontal margin") {
                val withHorizontalMargin =
                    ActionHandleAnchor.entries
                        .filter { anchor ->
                            val m = handleMargins(anchor, margin)
                            m.start > 0 || m.end > 0
                        }.toSet()
                withHorizontalMargin shouldBe
                    setOf(ActionHandleAnchor.SURFACE_START, ActionHandleAnchor.SURFACE_END)
            }

            Then("no horizontal margin is invented when the setting is zero") {
                ActionHandleAnchor.entries.forEach { anchor ->
                    handleMargins(anchor, 0) shouldBe HandleMargins(start = 0, end = 0)
                }
            }
        }

        // The strip below the keys exists so the floating grips never reach into the keys, which is
        // exactly what a theme does when it reserves less than the grip height for
        // keyboard_padding_bottom. Its floor is the handle size itself: the side margin moves
        // handles sideways only, so it must not make the strip taller -- that is what made raising
        // the margin feel like padding all four edges at once.
        Given("a floating keyboard, where the grips live in the strip") {
            val handle = ACTION_HANDLE_SIZE_DP

            Then("the theme padding is used when it is generous enough") {
                reservedBottomStripDp(60, handle, needsHandleRoom = true) shouldBe 60
            }

            Then("the strip grows to fit the handles when the theme reserves too little") {
                reservedBottomStripDp(35, handle, needsHandleRoom = true) shouldBe 36
            }

            Then("a theme padding of exactly the handle height is kept") {
                reservedBottomStripDp(36, handle, needsHandleRoom = true) shouldBe 36
            }

            Then("no theme padding can push the strip below the handles") {
                listOf(0, 1, 12, 35).forEach { padding ->
                    reservedBottomStripDp(padding, handle, needsHandleRoom = true) shouldBe 36
                }
            }

            Then("a taller theme padding wins, so a theme can still raise the keyboard") {
                reservedBottomStripDp(96, handle, needsHandleRoom = true) shouldBe 96
            }

            Then("a negative theme padding still leaves room for the handles") {
                reservedBottomStripDp(-5, handle, needsHandleRoom = true) shouldBe 36
            }
        }

        // Docked, nothing lives down there: the grips are hidden and the toggles moved into the row
        // above the keys. The value is the user's, not a floor -- asking for 2dp to sit the keys
        // against the screen edge has to yield 2dp, or the setting is a silent no-op.
        Given("a docked keyboard, where the strip holds nothing") {
            Then("the padding is taken as written, down to zero") {
                listOf(0, 1, 2, 12, 35).forEach { padding ->
                    reservedBottomStripDp(padding, ACTION_HANDLE_SIZE_DP, needsHandleRoom = false) shouldBe padding
                }
            }

            Then("a generous padding is still honoured") {
                reservedBottomStripDp(96, ACTION_HANDLE_SIZE_DP, needsHandleRoom = false) shouldBe 96
            }
        }
    })
