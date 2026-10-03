/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.graphics.Color
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Which candidate keeps the prominent colour, and which one merely gets a fill.
 *
 * The requirement these encode: the highlighted candidate is the one the next space bar commits,
 * so its colour is the one that has to stay unmistakable. Filling the rest of the row is a
 * deliberate extra, and it must never be able to take that colour over.
 */
class CandidateItemBackColorTest :
    BehaviorSpec({
        val highlight = 0xff513613.toInt() // hilited_candidate_back_color in the test theme
        val item = 0xff2d2d2d.toInt() // candidate_separator_color in the test theme

        Given("a candidate that is not highlighted") {
            Then("it has no fill while the setting is off") {
                candidateItemBackColor(
                    highlighted = false,
                    fillUnhighlighted = false,
                    highlightColor = highlight,
                    itemColor = item,
                ) shouldBe Color.TRANSPARENT
            }

            Then("it gets the item colour while the setting is on") {
                candidateItemBackColor(
                    highlighted = false,
                    fillUnhighlighted = true,
                    highlightColor = highlight,
                    itemColor = item,
                ) shouldBe item
            }
        }

        Given("the highlighted candidate") {
            Then("it keeps the highlight colour with the setting off") {
                candidateItemBackColor(
                    highlighted = true,
                    fillUnhighlighted = false,
                    highlightColor = highlight,
                    itemColor = item,
                ) shouldBe highlight
            }

            Then("turning the setting on does not take its colour away") {
                // This is the whole point of the feature: the fill is for the *other* candidates.
                candidateItemBackColor(
                    highlighted = true,
                    fillUnhighlighted = true,
                    highlightColor = highlight,
                    itemColor = item,
                ) shouldBe highlight
            }
        }

        Given("a row where every candidate is filled") {
            Then("the highlighted one is still a different colour from the rest") {
                // Otherwise the row reads as "all of these are selected" and the standout is gone.
                val rest =
                    candidateItemBackColor(
                        highlighted = false,
                        fillUnhighlighted = true,
                        highlightColor = highlight,
                        itemColor = item,
                    )
                rest shouldNotBe highlight
            }
        }

        Given("a themed fill that already carries its own opacity") {
            // 0x80 in the top byte: a theme is free to pick a translucent fill on purpose.
            val translucentItem = 0x802d2d2d.toInt()

            Then("the setting dims it further instead of replacing its alpha") {
                val half =
                    candidateItemBackColor(
                        highlighted = false,
                        fillUnhighlighted = true,
                        highlightColor = highlight,
                        itemColor = translucentItem,
                        itemAlpha = 128,
                    )
                // RGB preserved; alpha is the theme's 0x80 scaled by 128/255, not overwritten.
                (half and 0x00FFFFFF) shouldBe (translucentItem and 0x00FFFFFF)
                (half ushr 24 and 0xFF) shouldBe (0x80 * 128 / 255)
            }

            Then("100% leaves the theme's own translucency alone") {
                // A theme that meant to be see-through must not be forced opaque by a setting the
                // user never touched.
                candidateItemBackColor(
                    highlighted = false,
                    fillUnhighlighted = true,
                    highlightColor = highlight,
                    itemColor = translucentItem,
                    itemAlpha = 255,
                ) shouldBe translucentItem
            }

            Then("an opaque theme colour is what the setting dims") {
                val opaque = 0xff2d2d2d.toInt()
                val dimmed =
                    candidateItemBackColor(
                        highlighted = false,
                        fillUnhighlighted = true,
                        highlightColor = highlight,
                        itemColor = opaque,
                        itemAlpha = 128,
                    )
                (dimmed and 0x00FFFFFF) shouldBe (opaque and 0x00FFFFFF)
                (dimmed ushr 24 and 0xFF) shouldBe 128
            }
        }

        Given("an opacity outside 0..100") {
            Then("it is clamped instead of wrapping the colour around") {
                // 300% would otherwise shift the alpha byte out of the colour entirely.
                val opaque = 0xff2d2d2d.toInt()
                val over =
                    candidateItemBackColor(
                        highlighted = false,
                        fillUnhighlighted = true,
                        highlightColor = highlight,
                        itemColor = opaque,
                        itemAlpha = 765,
                    )
                (over ushr 24 and 0xFF) shouldBe 255
                val under =
                    candidateItemBackColor(
                        highlighted = false,
                        fillUnhighlighted = true,
                        highlightColor = highlight,
                        itemColor = opaque,
                        itemAlpha = -255,
                    )
                (under ushr 24 and 0xFF) shouldBe 0
            }
        }

        Given("the highlighted candidate while the opacity is turned down") {
            Then("it keeps the theme's full strength") {
                // Fading the selected candidate too would erase the very distinction the two
                // settings exist to create.
                candidateItemBackColor(
                    highlighted = true,
                    fillUnhighlighted = true,
                    highlightColor = highlight,
                    itemColor = item,
                    itemAlpha = 0,
                ) shouldBe highlight
            }
        }
    })
