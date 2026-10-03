/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ActionHandleAnchorTest :
    BehaviorSpec({
        // Docked mode hides both grips, so its two bottom corners are free and the toggles can
        // spread out instead of queueing up in the bottom-left corner.
        Given("a docked keyboard, whose bottom corners are free") {
            val anchors = actionHandleAnchors(floating = false).toMap()

            Then("the candidate window toggle takes the bottom-left corner") {
                anchors[BottomToggle.CANDIDATES_WINDOW] shouldBe ActionHandleAnchor.SURFACE_START
            }

            Then("the floating keyboard toggle follows the candidate window toggle") {
                anchors[BottomToggle.FLOATING_KEYBOARD] shouldBe
                    ActionHandleAnchor.AFTER_CANDIDATES_WINDOW_TOGGLE
            }

            Then("the toolbar toggle moves to the bottom-right corner") {
                anchors[BottomToggle.TOOLBAR] shouldBe ActionHandleAnchor.SURFACE_END
            }

            Then("no toggle is anchored to the drag grip, since it is hidden") {
                anchors.values.none { it == ActionHandleAnchor.AFTER_DRAG_GRIP } shouldBe true
            }
        }

        // Floating mode shows both grips, and the resize grip keeps the bottom-right corner to
        // itself, so the whole row has to stay on the left.
        Given("a floating keyboard, whose resize grip owns the bottom-right corner") {
            val anchors = actionHandleAnchors(floating = true).toMap()

            Then("the toolbar toggle follows the drag grip") {
                anchors[BottomToggle.TOOLBAR] shouldBe ActionHandleAnchor.AFTER_DRAG_GRIP
            }

            Then("the floating keyboard toggle follows the toolbar toggle") {
                anchors[BottomToggle.FLOATING_KEYBOARD] shouldBe ActionHandleAnchor.AFTER_TOOLBAR_TOGGLE
            }

            Then("the candidate window toggle follows the floating keyboard toggle") {
                anchors[BottomToggle.CANDIDATES_WINDOW] shouldBe
                    ActionHandleAnchor.AFTER_FLOATING_KEYBOARD_TOGGLE
            }

            Then("no toggle claims a corner of its own") {
                val corners = listOf(ActionHandleAnchor.SURFACE_START, ActionHandleAnchor.SURFACE_END)
                anchors.values.none { it in corners } shouldBe true
            }
        }

        Given("the two keyboard modes") {
            Then("every bottom toggle is placed in both of them") {
                listOf(true, false).forEach { floating ->
                    actionHandleAnchors(floating).map { it.first }.toSet() shouldBe
                        BottomToggle.entries.toSet()
                }
            }

            Then("no two toggles share an anchor") {
                listOf(true, false).forEach { floating ->
                    val anchors = actionHandleAnchors(floating).map { it.second }
                    anchors.toSet().size shouldBe anchors.size
                }
            }
        }

        // Rounded screen corners clip the handle row where it is pinned to the bottom of the
        // screen, so the corner-most handles have to be pulled inwards. Regression guard for the
        // margin axis itself: the margin must land on the *relative* start/end properties, never
        // on the far side of a handle, and never be dropped from the bottom.
        Given("a bottom handle margin of 12px") {
            val margin = 12
            val chained =
                listOf(
                    ActionHandleAnchor.AFTER_DRAG_GRIP,
                    ActionHandleAnchor.AFTER_CANDIDATES_WINDOW_TOGGLE,
                    ActionHandleAnchor.AFTER_FLOATING_KEYBOARD_TOGGLE,
                    ActionHandleAnchor.AFTER_TOOLBAR_TOGGLE,
                )

            Then("the bottom-left handle keeps the margin on its own side only") {
                handleMargins(ActionHandleAnchor.SURFACE_START, margin) shouldBe
                    HandleMargins(start = margin, end = 0)
            }

            Then("the bottom-right handle keeps the margin on its own side only") {
                handleMargins(ActionHandleAnchor.SURFACE_END, margin) shouldBe
                    HandleMargins(start = 0, end = margin)
            }

            Then("a chained handle takes no margin of its own") {
                chained.forEach { anchor ->
                    handleMargins(anchor, margin) shouldBe HandleMargins(start = 0, end = 0)
                }
            }

            Then("no anchor puts a margin on both sides at once") {
                ActionHandleAnchor.entries.forEach { anchor ->
                    val m = handleMargins(anchor, margin)
                    (m.start > 0 && m.end > 0) shouldBe false
                }
            }

            Then("only the two corner anchors carry a horizontal margin") {
                val withHorizontalMargin =
                    ActionHandleAnchor.entries
                        .filter { it ->
                            val m = handleMargins(it, margin)
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

            Then("docked mode hands the horizontal margin to the two corner toggles") {
                val corners =
                    actionHandleAnchors(floating = false)
                        .filter { (_, anchor) ->
                            val m = handleMargins(anchor, margin)
                            m.start > 0 || m.end > 0
                        }.map { it.first }
                        .toSet()
                corners shouldBe setOf(BottomToggle.CANDIDATES_WINDOW, BottomToggle.TOOLBAR)
            }

            Then("floating mode gives the horizontal margin to the drag grip, not to a toggle") {
                val corners =
                    actionHandleAnchors(floating = true)
                        .filter { (_, anchor) ->
                            val m = handleMargins(anchor, margin)
                            m.start > 0 || m.end > 0
                        }
                corners.size shouldBe 0
            }
        }

        // The bottom bar exists so the toggles never reach into the keys, which is exactly what a
        // theme does when it reserves less than the toggle height for keyboard_padding_bottom.
        // Its floor is the handle size itself: the side margin moves handles sideways only, so it
        // must not make the strip taller -- that is what made raising the margin feel like padding
        // all four edges at once.
        Given("a bottom bar whose toggles are 36dp tall") {
            val min = ACTION_HANDLE_SIZE_DP

            Then("the automatic height uses the theme padding when that is generous enough") {
                bottomBarHeightDp(configuredDp = 0, themePaddingDp = 60, minHeightDp = min) shouldBe 60
            }

            Then("the automatic height grows to fit the toggles when the theme is too small") {
                bottomBarHeightDp(configuredDp = 0, themePaddingDp = 35, minHeightDp = min) shouldBe 36
            }

            Then("a configured height above the toggles is used as it is") {
                bottomBarHeightDp(configuredDp = 72, themePaddingDp = 35, minHeightDp = min) shouldBe 72
            }

            Then("a configured height of exactly the handle size is kept") {
                bottomBarHeightDp(configuredDp = 36, themePaddingDp = 35, minHeightDp = min) shouldBe 36
            }

            Then("no configured height can push the bar below the toggles") {
                listOf(0, 1, 12, 35).forEach { configured ->
                    bottomBarHeightDp(configured, 35, min) shouldBe 36
                }
            }

            Then("a configured height replaces the theme padding, floor aside") {
                bottomBarHeightDp(configuredDp = 20, themePaddingDp = 80, minHeightDp = min) shouldBe 36
            }

            Then("a negative height is treated as automatic") {
                bottomBarHeightDp(configuredDp = -5, themePaddingDp = 40, minHeightDp = min) shouldBe 40
            }
        }
    })
