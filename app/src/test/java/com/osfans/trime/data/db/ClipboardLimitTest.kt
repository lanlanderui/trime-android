/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

// The clipboard history is trimmed to a limit. Which rows go is decided by one ordering and then
// acted on by another: the row to drop was picked by id while the delete was by time. Those two
// orders only agree while every copy is also a fresh row, which stops being true the moment an
// old entry is copied again -- its time moves to the front while its id stays where it was.
class ClipboardLimitTest :
    BehaviorSpec({
        fun bean(
            id: Int,
            time: Long,
            pinned: Boolean = false,
        ) = DatabaseBean(id = id, text = "text $id", time = time, pinned = pinned)

        Given("a history where an old entry was copied again") {
            // 1..5 inserted in that order, so id 2 is the oldest row but the newest text.
            val history =
                listOf(
                    bean(id = 1, time = 1_000),
                    bean(id = 2, time = 9_000),
                    bean(id = 3, time = 2_000),
                    bean(id = 4, time = 3_000),
                    bean(id = 5, time = 4_000),
                )

            Then("the row to drop is the oldest by time, not the lowest id") {
                // id 1 has the lowest id but its text is the second oldest; dropping it (which is
                // what sorting by id did) throws away a row the user still sees near the top.
                history.outdated(limit = 4).map { it.id } shouldContainExactlyInAnyOrder listOf(1)
            }

            Then("only as many rows go as the limit allows") {
                history.outdated(limit = 3).map { it.id } shouldContainExactlyInAnyOrder listOf(1, 3)
                history.outdated(limit = 2).map { it.id } shouldContainExactlyInAnyOrder listOf(1, 3, 4)
            }

            Then("a history within the limit is left alone") {
                history.outdated(limit = 5) shouldBe emptyList()
                history.outdated(limit = 99) shouldBe emptyList()
            }
        }

        Given("entries sharing a timestamp") {
            val history = listOf(bean(id = 7, time = 5_000), bean(id = 8, time = 5_000))

            Then("the count is still exact, because the rows are deleted by id") {
                // Deleting by the cutoff time instead would take both of them for one slot.
                history.outdated(limit = 1).map { it.id } shouldContainExactlyInAnyOrder listOf(7)
            }
        }
    })
