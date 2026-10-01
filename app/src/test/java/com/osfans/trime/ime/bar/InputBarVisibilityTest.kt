/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class InputBarVisibilityTest :
    BehaviorSpec({
        Given("the input toolbar is hidden") {
            Then("the idle toolbar is removed from layout") {
                shouldShowInputBar(true, QuickBarStateMachine.State.Always) shouldBe false
            }

            Then("the compact candidates remain visible") {
                shouldShowInputBar(true, QuickBarStateMachine.State.Candidate) shouldBe true
            }

            Then("a board window keeps its navigation tab visible") {
                shouldShowInputBar(true, QuickBarStateMachine.State.Tab) shouldBe true
            }
        }

        Given("the input toolbar is enabled") {
            Then("every input bar state remains visible") {
                QuickBarStateMachine.State.entries.forEach { state ->
                    shouldShowInputBar(false, state) shouldBe true
                }
            }
        }
    })
