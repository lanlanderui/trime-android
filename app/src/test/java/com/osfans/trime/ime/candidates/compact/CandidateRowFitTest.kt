/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Which companions the row keeps, and how far it moves to put the highlighted candidate in the
 * middle.
 *
 * The rule the row follows: the highlighted candidate's centre is the strip's centre, and its
 * neighbours are laid out around it. A companion therefore may not use more than half of the width
 * left over once the highlighted candidate has taken its own -- anything wider would have to be
 * paid for by the *other* flank, which is what used to drag the highlighted candidate off the
 * middle (a 7-character candidate with one wide neighbour beside it was the report).
 */
class CandidateRowFitTest :
    BehaviorSpec({
        Given("a row with room for everything the setting allows") {
            Then("all five companions stay, and the row leans by what the flanks differ") {
                // 840 wide, 120 for the highlighted candidate: 360 per flank. Two on the left and
                // three on the right of 120 each weigh 240 and 360, so the row moves by 60.
                fitRow(
                    leadWidth = 120,
                    otherWidths = List(5) { 120 },
                    usable = 840,
                    room = 5,
                ) shouldBe RowLayout(kept = 5, shift = 60)
            }

            Then("the setting still caps how many are laid out") {
                fitRow(
                    leadWidth = 120,
                    otherWidths = List(9) { 120 },
                    usable = 2_000,
                    room = 3,
                ) shouldBe RowLayout(kept = 3, shift = 60)
            }
        }

        Given("a companion wider than the half left for its flank") {
            Then("it is not shown at all, and the candidates already there stay centred") {
                // The reported case: a seven-character candidate takes 441 of a 768 px row, so each
                // flank has 163 to spend; the next candidate asks for 266.
                fitRow(
                    leadWidth = 441,
                    otherWidths = listOf(266, 120, 120, 120),
                    usable = 768,
                    room = 5,
                ) shouldBe RowLayout(kept = 0, shift = 0)
            }

            Then("narrower ones beside it still fit, one on each side so nothing has to lean") {
                fitRow(
                    leadWidth = 441,
                    otherWidths = listOf(120, 120),
                    usable = 768,
                    room = 5,
                ) shouldBe RowLayout(kept = 2, shift = 0)
            }
        }

        Given("a row narrower than the highlighted candidate itself") {
            Then("nothing else is kept") {
                fitRow(leadWidth = 900, otherWidths = List(5) { 120 }, usable = 840, room = 5) shouldBe
                    RowLayout(kept = 0, shift = 0)
            }
        }

        Given("a row that has not been measured yet") {
            Then("it shows what the setting asks for instead of blinking empty") {
                fitRow(leadWidth = 0, otherWidths = List(5) { 120 }, usable = 0, room = 5) shouldBe
                    RowLayout(kept = 5, shift = 0)
            }
        }

        Given("no room at all") {
            Then("an empty room keeps nothing") {
                fitRow(leadWidth = 120, otherWidths = List(5) { 120 }, usable = 840, room = 0) shouldBe
                    RowLayout(kept = 0, shift = 0)
            }
        }
    })
