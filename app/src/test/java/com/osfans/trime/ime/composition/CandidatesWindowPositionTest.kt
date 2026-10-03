/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class CandidatesWindowPositionTest :
    BehaviorSpec({
        val spacing = 5f
        val selfHeight = 60f
        val minY = 5f

        Given("nothing for the candidate window to avoid") {
            Then("the placement asked for by the position preference is used as is") {
                listOf(minY, 0f, 123f, 1000f).forEach { positionedY ->
                    candidatesWindowTranslationY(
                        positionedY = positionedY,
                        obstructionTop = null,
                        selfHeight = selfHeight,
                        spacing = spacing,
                        minY = minY,
                    ) shouldBe positionedY
                }
            }
        }

        // A floating keyboard overlays the editor, so the cursor anchor can land inside it.
        Given("a floating keyboard whose top edge sits at 400") {
            val obstructionTop = 400f

            Then("the window is pinned just above the keyboard") {
                candidatesWindowTranslationY(
                    positionedY = 500f,
                    obstructionTop = obstructionTop,
                    selfHeight = selfHeight,
                    spacing = spacing,
                    minY = minY,
                ) shouldBe 335f
            }

            Then("its bottom edge stops one spacing above the keyboard, with no overlap") {
                val y =
                    candidatesWindowTranslationY(
                        positionedY = 500f,
                        obstructionTop = obstructionTop,
                        selfHeight = selfHeight,
                        spacing = spacing,
                        minY = minY,
                    )
                y + selfHeight shouldBe obstructionTop - spacing
            }

            Then("the placement asked for by the position preference no longer decides it") {
                // Whatever TOP_LEFT / BOTTOM_LEFT / FOLLOW would have produced, the outcome is
                // the same: pinning makes the window stop jumping while the cursor moves.
                val placements = listOf(minY, 0f, 123f, 500f, 1000f)
                val resolved =
                    placements.map {
                        candidatesWindowTranslationY(
                            positionedY = it,
                            obstructionTop = obstructionTop,
                            selfHeight = selfHeight,
                            spacing = spacing,
                            minY = minY,
                        )
                    }
                resolved.toSet() shouldBe setOf(335f)
            }
        }

        // The keyboard can be dragged up to within 16dp of the parent's top edge, which is less
        // than a candidate row needs.
        Given("a floating keyboard dragged up with no room left above it") {
            Then("the window is clamped to the top of the parent instead of going off screen") {
                candidatesWindowTranslationY(
                    positionedY = 300f,
                    obstructionTop = 40f,
                    selfHeight = selfHeight,
                    spacing = spacing,
                    minY = minY,
                ) shouldBe minY
            }
        }
    })
