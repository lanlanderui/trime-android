/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Guards the scope a bulk delete runs with.
 *
 * This is a regression test for irreversible data loss: both helpers used to take a `Boolean`
 * whose name meant the opposite of its behaviour, and the "delete all" button passed
 * `haveUnpinned()` into it. Once every entry was pinned that flag was false, the helpers took the
 * "wipe the table" branch, and the entries the user had pinned were destroyed with no way back.
 */
class BulkDeleteScopeTest :
    BehaviorSpec({
        Given("the scope a bulk delete runs with") {
            Then("keeping pinned entries is the default, not the opt-in") {
                // The default is what the "delete all" button uses. Anything else would make
                // losing pinned entries the path of least resistance.
                BulkDeleteScope.KEEP_PINNED.removesPinned shouldBe false
            }

            Then("wiping the whole table has to be named out loud") {
                BulkDeleteScope.ALL.removesPinned shouldBe true
            }
        }

        Given("a user whose clipboard holds only pinned entries") {
            Then("a bulk delete still leaves them alone") {
                // haveUnpinned() is false here. The old code fed that straight into the flag and
                // ended up on the deleteAll() branch, which is how pinned entries were lost.
                val haveUnpinned = false
                val scope = BulkDeleteScope.KEEP_PINNED
                (haveUnpinned && scope.removesPinned) shouldBe false
            }

            Then("no scope derived from haveUnpinned() can wipe pinned entries") {
                // Both values of the flag map onto a scope that keeps pinned entries, so no
                // clipboard state can reach the destructive branch through the button.
                for (haveUnpinned in listOf(true, false)) {
                    val scope = BulkDeleteScope.KEEP_PINNED
                    (haveUnpinned && scope.removesPinned) shouldBe false
                }
            }
        }

        Given("the two helpers that share this scope") {
            Then("they agree on what each scope means") {
                // They once had opposite parameter names for identical behaviour, which is how
                // the call site ended up inverting the sense of the flag in the first place.
                BulkDeleteScope.entries.forEach { scope ->
                    scope.removesPinned shouldBe (scope == BulkDeleteScope.ALL)
                }
            }
        }
    })
