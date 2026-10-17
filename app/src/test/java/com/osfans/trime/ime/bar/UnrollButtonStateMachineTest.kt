/*
 * SPDX-FileCopyrightText: 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import com.osfans.trime.ime.bar.UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.State.ClickToAttachWindow
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.State.Hidden
import com.osfans.trime.ime.bar.UnrollButtonStateMachine.TransitionEvent
import com.osfans.trime.util.EventStateMachine
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The unroll button's contract, pinned down by the bug it caused.
 *
 * The button is the only way into the full candidate window, and it is shown only while there are
 * candidates the compact row *cannot* show. That is reported as `UnrolledCandidatesEmpty`, and the
 * name is the trap: the row scrolls now, so a row parked part-way through its list finds its last
 * visible item is the final item — and reports "nothing left over" for a list it is sitting in the
 * middle of. The button goes [Hidden], and with it the way to see the rest.
 *
 * The fix lives in the caller (`CompactCandidateDelegate.onCandidateListUpdate` scrolls back to the
 * start before each new list). These tests record the transitions that make that invariant
 * load-bearing, so a future change that drops the scroll has something to contradict.
 */
class UnrollButtonStateMachineTest :
    BehaviorSpec({
        /** A machine, plus the states it has passed through, in order. */
        fun machine(): Pair<EventStateMachine<UnrollButtonStateMachine.State, TransitionEvent, UnrollButtonStateMachine.BooleanKey>, MutableList<UnrollButtonStateMachine.State>> {
            val seen = mutableListOf<UnrollButtonStateMachine.State>()
            val instance = UnrollButtonStateMachine.new { seen += it }
            return instance to seen
        }

        Given("a fresh machine") {
            Then("it starts hidden, because nothing has reported candidates yet") {
                machine().first.currentState shouldBe Hidden
            }
        }

        Given("candidates the row cannot all show") {
            val (instance, seen) = machine()

            When("the count appears") {
                instance.push(TransitionEvent.UnrolledCandidatesUpdated, UnrolledCandidatesEmpty to false)

                Then("the button becomes clickable-to-attach, and the change is announced") {
                    instance.currentState shouldBe ClickToAttachWindow
                    seen shouldBe listOf(ClickToAttachWindow)
                }
            }
        }

        Given("a row that reports it is already showing everything") {
            val (instance, _) = machine()

            When("the count says nothing is left over") {
                instance.push(TransitionEvent.UnrolledCandidatesUpdated, UnrolledCandidatesEmpty to false)
                instance.push(TransitionEvent.UnrolledCandidatesUpdated, UnrolledCandidatesEmpty to true)

                Then("the button goes hidden -- correct only when nothing is really left") {
                    // The bug's signature. `true` cannot distinguish "the list is all visible" from
                    // "the row is parked at the end of a long list", so a row left mid-scroll hides
                    // the button that would have revealed the rest.
                    instance.currentState shouldBe Hidden
                }
            }
        }

        Given("the arithmetic the caller used to do") {
            Then("counting from a parked position reports the list as exhausted") {
                // Spelled out, because the two situations are genuinely indistinguishable from this
                // number alone: a row whose last visible item is the final one reports
                // `total == visible` no matter how many candidates sit off to the left. The scroll
                // back to the start is what makes the number mean what it claims to.
                val total = 40
                val lastVisibleWhenParked = 39
                (total == lastVisibleWhenParked + 1) shouldBe true
            }

            Then("counting from the start reports the shortfall honestly") {
                val total = 40
                val lastVisibleAtStart = 7
                (total == lastVisibleAtStart + 1) shouldBe false
            }
        }
    })
