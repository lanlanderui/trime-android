/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Who owns the shift state: auto capitalisation, or the user.
 *
 * Auto capitalisation writes shift whenever the cursor moves to a sentence start, so it runs on
 * every selection update. That is exactly what broke "keep Shift for arrow keys": the first arrow
 * moved the cursor, the update saw a shift nobody had locked, and cleared it -- leaving the rest of
 * the selection unshifted. A shift the user pressed has to survive until typing consumes it.
 */
class AutoShiftTest :
    BehaviorSpec({
        Given("a shift the user pressed but did not lock") {
            Then("auto capitalisation leaves it alone, however often the cursor moves") {
                shouldAutoShift(
                    autoCaps = true,
                    asciiMode = true,
                    shiftLocked = false,
                    shiftOn = true,
                    fromAutoCaps = false,
                ) shouldBe false
            }
        }

        Given("a shift auto capitalisation itself applied") {
            Then("it may be taken back once the cursor leaves the sentence start") {
                shouldAutoShift(
                    autoCaps = true,
                    asciiMode = true,
                    shiftLocked = false,
                    shiftOn = true,
                    fromAutoCaps = true,
                ) shouldBe true
            }
        }

        Given("no shift at all") {
            Then("the sentence start may turn one on") {
                shouldAutoShift(
                    autoCaps = true,
                    asciiMode = true,
                    shiftLocked = false,
                    shiftOn = false,
                    fromAutoCaps = false,
                ) shouldBe true
            }
        }

        Given("a locked shift") {
            Then("auto capitalisation never overrides the lock") {
                shouldAutoShift(
                    autoCaps = true,
                    asciiMode = true,
                    shiftLocked = true,
                    shiftOn = true,
                    fromAutoCaps = false,
                ) shouldBe false
                shouldAutoShift(
                    autoCaps = true,
                    asciiMode = true,
                    shiftLocked = true,
                    shiftOn = true,
                    fromAutoCaps = true,
                ) shouldBe false
            }
        }

        Given("a setting or a mode that rules auto capitalisation out") {
            Then("nothing is written even with no shift on") {
                shouldAutoShift(
                    autoCaps = false,
                    asciiMode = true,
                    shiftLocked = false,
                    shiftOn = false,
                    fromAutoCaps = false,
                ) shouldBe false
                shouldAutoShift(
                    autoCaps = true,
                    asciiMode = false,
                    shiftLocked = false,
                    shiftOn = false,
                    fromAutoCaps = false,
                ) shouldBe false
            }
        }
    })
