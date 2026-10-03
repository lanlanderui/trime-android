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
                    HandleMargins(start = margin, end = 0, bottom = margin)
            }

            Then("the bottom-right handle keeps the margin on its own side only") {
                handleMargins(ActionHandleAnchor.SURFACE_END, margin) shouldBe
                    HandleMargins(start = 0, end = margin, bottom = margin)
            }

            Then("a chained handle keeps the margin on the bottom only") {
                chained.forEach { anchor ->
                    handleMargins(anchor, margin) shouldBe HandleMargins(start = 0, end = 0, bottom = margin)
                }
            }

            Then("every handle keeps the bottom margin, whatever its anchor") {
                ActionHandleAnchor.entries.forEach { anchor ->
                    handleMargins(anchor, margin).bottom shouldBe margin
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
                    handleMargins(anchor, 0) shouldBe HandleMargins(start = 0, end = 0, bottom = 0)
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
    })
