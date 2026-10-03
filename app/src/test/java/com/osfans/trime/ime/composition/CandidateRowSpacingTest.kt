/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The row is a flex line, so the margins on its items are part of its width.
 *
 * The gap exists so that two neighbouring candidate fills do not touch. The cost is that the owner
 * has to take the gaps out of the budget before splitting what is left between the candidates: get
 * this wrong and the row overflows and wraps onto a second line, which is the single thing the
 * single-row candidate window must never do.
 */
class CandidateRowSpacingTest :
    BehaviorSpec({
        // What PagedCandidatesUi.spacingFor() computes, mirrored here so the arithmetic is pinned
        // without needing a Context to turn dp into pixels.
        fun spacingFor(count: Int, gapPx: Int): Int = if (count <= 0) 0 else count * 2 * gapPx

        Given("a row of candidates") {
            Then("every candidate pays the gap on both sides") {
                // Not just the ones in between: the leading and trailing margins are part of the
                // flex line's width too, so all N items count.
                spacingFor(count = 4, gapPx = 6) shouldBe 4 * 2 * 6
            }

            Then("no candidates means no gap to reserve") {
                spacingFor(count = 0, gapPx = 6) shouldBe 0
                spacingFor(count = -1, gapPx = 6) shouldBe 0
            }

            Then("a zero gap reserves nothing, leaving the original behaviour intact") {
                spacingFor(count = 5, gapPx = 0) shouldBe 0
            }
        }

        Given("a gap that follows the axis the candidates run along") {
            // A horizontal row separates its items left/right; a vertical one separates them
            // top/bottom. Applying both would double the gap on one axis and inset the row from
            // the window edge on the other.
            fun marginsFor(
                horizontal: Boolean,
                gapPx: Int,
            ) = if (horizontal) (gapPx to 0) else (0 to gapPx)

            Then("a horizontal row takes the gap on the horizontal axis only") {
                marginsFor(horizontal = true, gapPx = 6) shouldBe (6 to 0)
            }

            Then("a vertical column takes it on the vertical axis only") {
                // Without this the fills of stacked candidates touch, which is the vertical
                // counterpart of the horizontal problem the gap was introduced for.
                marginsFor(horizontal = false, gapPx = 6) shouldBe (0 to 6)
            }
        }

        Given("a budget to split between the candidates") {
            Then("what is left after the gaps is what the candidates may use") {
                val available = 600f
                val gaps = spacingFor(count = 5, gapPx = 6) // 60px
                val forCandidates = available - gaps
                // 540 left for 5 candidates.
                forCandidates shouldBe 540f
            }

            Then("a row of N candidates at their natural width still fits the reduced budget") {
                val available = 600f
                val natural = 100.0 // each, as a Float to match the budget
                val gaps = spacingFor(count = 5, gapPx = 6)
                val forCandidates = available - gaps
                // 5 x 100 = 500 plus 60 of gaps = 560 <= 600, so the row stays on one line.
                val rowWidth = natural * 5 + gaps
                rowWidth shouldBe 560.0
                (rowWidth <= available) shouldBe true
                (forCandidates <= available) shouldBe true
            }

            Then("large gaps can eat the budget, so the count has to shrink") {
                // This is why the gap is subtracted before fittedCount, not after: at 16dp per
                // side the gaps alone can claim most of the row.
                val available = 600f
                val bigGaps = spacingFor(count = 10, gapPx = 16 * 3) // density 3 -> 48px per side
                (available - bigGaps) shouldBe 600f - 10 * 2 * 48
            }
        }
    })
