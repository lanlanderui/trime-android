/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe

class CandidateItemScaleTest :
    BehaviorSpec({
        Given("an item that already fits the space it was given") {
            Then("it keeps the theme's own sizes") {
                candidateItemScale(naturalWidth = 500, maxWidth = 500) shouldBe 1f
                candidateItemScale(naturalWidth = 500, maxWidth = 900) shouldBe 1f
            }
        }

        Given("a width that is not known yet") {
            Then("nothing is shrunk") {
                // Before the first layout the row reports 0, and a guess is not a reason to start
                // resizing text.
                candidateItemScale(naturalWidth = 500, maxWidth = 0) shouldBe 1f
                candidateItemScale(naturalWidth = 500, maxWidth = -1) shouldBe 1f
                candidateItemScale(naturalWidth = 0, maxWidth = 500) shouldBe 1f
            }
        }

        Given("an item wider than the space it was given") {
            Then("it is scaled down by the ratio between the two") {
                val scale = candidateItemScale(naturalWidth = 1000, maxWidth = 900)
                scale shouldBeGreaterThan 0.88f
                scale shouldBeLessThan 0.9f
            }

            Then("the result really does fit, which is the whole point") {
                val natural = 1000
                val max = 900
                val scale = candidateItemScale(natural, max)
                (natural * scale) shouldBeLessThan max.toFloat()
            }

            Then("it never goes below the point where the text stops being readable") {
                candidateItemScale(naturalWidth = 100_000, maxWidth = 10) shouldBe CANDIDATE_MIN_SCALE
            }

            Then("more room means less shrinking") {
                candidateItemScale(naturalWidth = 1000, maxWidth = 600) shouldBeGreaterThan
                    candidateItemScale(naturalWidth = 1000, maxWidth = 400)
            }

            Then("it is always a real shrink rather than a rounding artefact") {
                candidateItemScale(naturalWidth = 1001, maxWidth = 1000) shouldBeLessThan 1f
            }
        }

        Given("the sizes a candidate window meets in practice") {
            Then("a candidate a little too long for the row is shrunk only a little") {
                // The theme the window is used with draws candidates at 20sp; a row that has to give
                // up its gaps and its paging arrow lands around nine tenths of the width.
                val scale = candidateItemScale(naturalWidth = 1040, maxWidth = 950)
                scale shouldBeGreaterThan 0.85f
                scale shouldBeLessThan 0.95f
            }
        }
    })
