/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class CandidateSlotsTest :
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

        Given("a highlighted candidate inside the first row") {
            Then("the row starts at the beginning of the page") {
                listOf(0, 1, 2).forEach { highlighted ->
                    slotOffsetFor(highlighted, perLine = 3) shouldBe 0
                }
            }
        }

        Given("a highlighted candidate further down the page") {
            Then("the row starts at the window holding it") {
                slotOffsetFor(3, perLine = 3) shouldBe 3
                slotOffsetFor(4, perLine = 3) shouldBe 3
                slotOffsetFor(5, perLine = 3) shouldBe 3
                slotOffsetFor(5, perLine = 2) shouldBe 4
            }

            Then("a negative highlight -- nothing is highlighted -- starts at the first row") {
                slotOffsetFor(-1, perLine = 3) shouldBe 0
            }
        }

        Given("a row that shows the whole page anyway") {
            Then("there is no offset to speak of") {
                slotOffsetFor(2, perLine = 0) shouldBe 0
                lastSlotOffset(pageCount = 2, perLine = 3) shouldBe 0
                lastSlotOffset(pageCount = 0, perLine = 3) shouldBe 0
            }
        }

        Given("a page of 7 candidates and a row of 3") {
            Then("the last row starts at the last whole window") {
                lastSlotOffset(pageCount = 7, perLine = 3) shouldBe 6
            }

            Then("the row steps forward while it stays inside the page") {
                steppedSlotOffset(offset = 0, perLine = 3, pageCount = 7, forward = true) shouldBe 3
                steppedSlotOffset(offset = 3, perLine = 3, pageCount = 7, forward = true) shouldBe 6
            }

            Then("the row at the end asks for the next page instead") {
                steppedSlotOffset(offset = 6, perLine = 3, pageCount = 7, forward = true) shouldBe null
            }

            Then("the row steps backwards while it stays inside the page") {
                steppedSlotOffset(offset = 6, perLine = 3, pageCount = 7, forward = false) shouldBe 3
                steppedSlotOffset(offset = 3, perLine = 3, pageCount = 7, forward = false) shouldBe 0
            }

            Then("the row at the start asks for the previous page instead") {
                steppedSlotOffset(offset = 0, perLine = 3, pageCount = 7, forward = false) shouldBe null
            }
        }

        Given("a window that is not a row at all") {
            Then("stepping is left to Rime") {
                steppedSlotOffset(offset = 0, perLine = 0, pageCount = 7, forward = true) shouldBe null
                steppedSlotOffset(offset = 6, perLine = 0, pageCount = 7, forward = false) shouldBe null
            }
        }

        Given("a row that holds fewer candidates than the page has") {
            Then("it slices the page rather than dropping what does not fit") {
                val window = windowOf(page(count = 7, highlighted = 0), offset = 3, perLine = 3)
                window.candidates.map { it.text } shouldBe listOf("c3", "c4", "c5")
            }

            Then("the highlight follows the slice") {
                windowOf(page(count = 7, highlighted = 4), offset = 3, perLine = 3).highlighted shouldBe 1
            }

            Then("a highlight left outside the row is reported as none") {
                windowOf(page(count = 7, highlighted = 0), offset = 3, perLine = 3).highlighted shouldBe -1
            }

            Then("the arrows stay lit while there is more to see, in this page or another") {
                windowOf(page(count = 7), offset = 3, perLine = 3).hasPrevPage shouldBe true
                windowOf(page(count = 7), offset = 3, perLine = 3).hasNextPage shouldBe true
                windowOf(page(count = 7), offset = 0, perLine = 3).hasPrevPage shouldBe false
                windowOf(page(count = 7), offset = 6, perLine = 3).hasNextPage shouldBe false
            }

            Then("a page beyond the page's edges is still handed to Rime") {
                windowOf(page(count = 7, hasPrevPage = true), offset = 0, perLine = 3).hasPrevPage shouldBe true
                windowOf(page(count = 7, hasNextPage = true), offset = 6, perLine = 3).hasNextPage shouldBe true
            }
        }

        Given("a page that fits one row") {
            Then("the page itself is shown, unchanged") {
                val whole = page(count = 3, highlighted = 2, hasPrevPage = true, hasNextPage = true)
                windowOf(whole, offset = 0, perLine = 3) shouldBe whole
            }

            Then("a row that is not a row shows the page as well") {
                val whole = page(count = 7, highlighted = 3)
                windowOf(whole, offset = 3, perLine = 0) shouldBe whole
            }
        }

        Given("every window of a page, walked with the row arrows") {
            Then("no candidate is skipped, and stepping ends exactly at the last one") {
                val count = 7
                val perLine = 3
                val seen = mutableListOf<String>()
                var offset = 0
                while (true) {
                    windowOf(page(count = count), offset = offset, perLine = perLine).candidates.forEach {
                        seen += it.text
                    }
                    offset = steppedSlotOffset(offset, perLine, count, forward = true) ?: break
                }
                seen shouldBe (0 until count).map { "c$it" }
                offset shouldBe lastSlotOffset(count, perLine)
            }
        }

        Given("a row whose candidates all fit at their natural width") {
            Then("each one gets the widest natural width, so nothing is truncated") {
                balancedItemWidth(listOf(180, 120, 150), availableWidth = 620f, paginationWidth = 0) shouldBe 180
            }
        }

        Given("a row whose candidates do not all fit") {
            Then("only as many as fit are kept, and the widest of them sets the width") {
                // 4 x 200 = 800 > 600, but 3 x 200 = 600 fits.
                balancedItemWidth(listOf(200, 200, 200, 200), availableWidth = 600f, paginationWidth = 0) shouldBe 200
            }

            Then("the kept candidates are a prefix, so the row stays in Rime's order") {
                // 300 + 100 would both fit at 300 (600 <= 400 is false), so only the first is
                // kept: trimming happens at the end, never in the middle.
                balancedItemWidth(listOf(300, 100), availableWidth = 400f, paginationWidth = 0) shouldBe 300
            }

            Then("the arrows are paid for out of the same width") {
                balancedItemWidth(listOf(200, 200, 200), availableWidth = 620f, paginationWidth = 20) shouldBe 200
            }
        }

        Given("a width that is not known yet") {
            Then("the candidates keep their natural size") {
                balancedItemWidth(listOf(180), availableWidth = 0f, paginationWidth = 0) shouldBe 0
                balancedItemWidth(emptyList(), availableWidth = 620f, paginationWidth = 0) shouldBe 0
            }

            Then("a width the arrows already ate up does not go negative") {
                balancedItemWidth(listOf(50), availableWidth = 10f, paginationWidth = 20) shouldBe 0
            }
        }

        Given("a single candidate wider than the whole row") {
            Then("it is given the entire row rather than a negative width") {
                balancedItemWidth(listOf(900), availableWidth = 400f, paginationWidth = 0) shouldBe 400
            }
        }

        Given("a measurement that came back empty") {
            Then("the candidates size themselves instead of collapsing to hairlines") {
                // A zero width would ellipsize every candidate to nothing at all.
                balancedItemWidth(listOf(0, 0), availableWidth = 620f, paginationWidth = 0) shouldBe 0
            }
        }
    })
