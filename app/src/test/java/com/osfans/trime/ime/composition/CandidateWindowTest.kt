/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class CandidateWindowTest :
    BehaviorSpec({
        fun page(
            count: Int,
            highlighted: Int = 0,
            hasPrevPage: Boolean = false,
            hasNextPage: Boolean = false,
        ) = Candidates.Paged(
            hasPrevPage = hasPrevPage,
            hasNextPage = hasNextPage,
            highlighted = highlighted,
            candidates =
                Array(count) {
                    CandidateProto(text = "c$it", comment = "", label = "${it + 1}")
                },
        )

        /** A measurer that records how often it was asked, so "never measured" can be asserted. */
        fun measurer(width: Int = 200): Pair<MutableList<List<String>>, (List<CandidateProto>) -> List<Int>> {
            val calls = mutableListOf<List<String>>()
            return calls to { slot ->
                calls += slot.map { it.text }
                List(slot.size) { width }
            }
        }

        fun vertical(
            page: Candidates.Paged,
            offset: Int = 0,
            perLine: Int,
        ) = candidateWindow(
            page = page,
            offset = offset,
            perLine = perLine,
            horizontal = false,
            availableWidth = 900f,
            paginationWidth = 0,
            spacingWidth = 0,
            naturalWidths = { error("a vertical window must not measure candidates") },
        )

        Given("a vertical layout and a page longer than the setting allows") {
            Then("the stack is cut to the setting, and the rest waits behind the arrows") {
                val window = vertical(page(count = 9), perLine = 4)
                window.page.candidates.map { it.text } shouldBe listOf("c0", "c1", "c2", "c3")
                window.page.hasNextPage shouldBe true
                window.page.hasPrevPage shouldBe false
            }

            Then("nothing is measured, because a stack runs out of height rather than width") {
                val (calls, naturalWidths) = measurer()
                candidateWindow(
                    page = page(count = 9),
                    offset = 0,
                    perLine = 4,
                    horizontal = false,
                    availableWidth = 900f,
                    paginationWidth = 0,
                    spacingWidth = 40,
                    naturalWidths = naturalWidths,
                )
                calls shouldBe emptyList()
            }

            Then("no width is imposed, so each candidate spans the window and sizes itself") {
                vertical(page(count = 9), perLine = 4).itemWidth shouldBe 0
            }

            Then("the arrows step the stack, exactly as they step a row") {
                vertical(page(count = 9), offset = 4, perLine = 4).page.candidates.map { it.text } shouldBe
                    listOf("c4", "c5", "c6", "c7")
                val last = vertical(page(count = 9), offset = 8, perLine = 4)
                last.page.candidates.map { it.text } shouldBe listOf("c8")
                last.page.hasNextPage shouldBe false
                last.page.hasPrevPage shouldBe true
            }

            Then("the highlight is reported against the slice, not the page") {
                vertical(page(count = 9, highlighted = 5), offset = 4, perLine = 4).page.highlighted shouldBe 1
                vertical(page(count = 9, highlighted = 0), offset = 4, perLine = 4).page.highlighted shouldBe -1
            }
        }

        Given("a vertical layout and a page that the setting already covers") {
            Then("the whole page is shown, unchanged") {
                val whole = page(count = 3, highlighted = 2, hasPrevPage = true, hasNextPage = true)
                vertical(whole, perLine = 4).page shouldBe whole
            }
        }

        Given("a horizontal row whose candidates all fit") {
            Then("every one of them is kept, at the widest natural width") {
                val (_, naturalWidths) = measurer(width = 200)
                val window = candidateWindow(
                    page = page(count = 4),
                    offset = 0,
                    perLine = 4,
                    horizontal = true,
                    availableWidth = 900f,
                    paginationWidth = 0,
                    spacingWidth = 0,
                    naturalWidths = naturalWidths,
                )
                window.page.candidates.size shouldBe 4
                window.itemWidth shouldBe 200
            }
        }

        Given("a horizontal row whose candidates do not all fit") {
            Then("only as many as fit are shown, and the row is sized for those") {
                val (_, naturalWidths) = measurer(width = 200)
                val window = candidateWindow(
                    page = page(count = 4),
                    offset = 0,
                    perLine = 4,
                    horizontal = true,
                    availableWidth = 500f,
                    paginationWidth = 0,
                    spacingWidth = 0,
                    naturalWidths = naturalWidths,
                )
                // 3 x 200 = 600 > 500, but 2 x 200 = 400 fits.
                window.page.candidates.map { it.text } shouldBe listOf("c0", "c1")
                window.itemWidth shouldBe 200
            }

            Then("the gaps between candidates come out of the same budget") {
                val (_, naturalWidths) = measurer(width = 200)
                val window = candidateWindow(
                    page = page(count = 4),
                    offset = 0,
                    perLine = 4,
                    horizontal = true,
                    availableWidth = 900f,
                    paginationWidth = 0,
                    spacingWidth = 200,
                    naturalWidths = naturalWidths,
                )
                // 700 usable: 3 x 200 fits, 4 x 200 does not.
                window.page.candidates.size shouldBe 3
            }

            Then("the measurer is handed the window's slice, never the whole page") {
                val (calls, naturalWidths) = measurer(width = 200)
                candidateWindow(
                    page = page(count = 20),
                    offset = 4,
                    perLine = 3,
                    horizontal = true,
                    availableWidth = 900f,
                    paginationWidth = 0,
                    spacingWidth = 0,
                    naturalWidths = naturalWidths,
                )
                calls shouldBe listOf(listOf("c4", "c5", "c6"))
            }
        }

        Given("a horizontal row before anything has been laid out") {
            Then("a failed measurement leaves each candidate to size itself") {
                val (_, naturalWidths) = measurer(width = 0)
                val window = candidateWindow(
                    page = page(count = 4),
                    offset = 0,
                    perLine = 4,
                    horizontal = true,
                    availableWidth = 500f,
                    paginationWidth = 0,
                    spacingWidth = 0,
                    naturalWidths = naturalWidths,
                )
                window.itemWidth shouldBe 0
            }
        }

        Given("a window capacity") {
            Then("it is the smaller of the setting and what was measured") {
                windowCapacity(perLine = 4, fit = 4) shouldBe 4
                windowCapacity(perLine = 4, fit = 2) shouldBe 2
                windowCapacity(perLine = 4, fit = 9) shouldBe 4
            }

            Then("it never collapses to zero, which would stop the arrows dead") {
                windowCapacity(perLine = 0, fit = 0) shouldBe 1
                windowCapacity(perLine = 4, fit = 0) shouldBe 1
            }
        }

        Given("a row that fits fewer candidates than the setting allows") {
            Then("walking it with the arrows still reaches every candidate, in order") {
                val count = 7
                val (_, naturalWidths) = measurer(width = 200)
                // 500 usable / 200 per candidate = 2 per window, against a setting of 4.
                var offset = 0
                val seen = mutableListOf<String>()
                val capacities = mutableListOf<Int>()
                while (true) {
                    val window = candidateWindow(
                        page = page(count = count),
                        offset = offset,
                        perLine = 4,
                        horizontal = true,
                        availableWidth = 500f,
                        paginationWidth = 0,
                        spacingWidth = 0,
                        naturalWidths = naturalWidths,
                    )
                    window.page.candidates.forEach { seen += it.text }
                    capacities += window.capacity
                    offset = steppedSlotOffset(offset, window.capacity, count, forward = true) ?: break
                }
                capacities.first() shouldBe 2
                seen shouldBe (0 until count).map { "c$it" }
            }
        }
    })
