/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class RightButtonTest :
    BehaviorSpec({
        Given("the preference's default value") {
            Then("every button is on") {
                RightButton.parse(RightButton.ALL_IDS) shouldBe RightButton.ALL.toSet()
            }

            Then("a round trip keeps it exactly") {
                RightButton.serialize(RightButton.parse(RightButton.ALL_IDS)) shouldBe RightButton.ALL_IDS
            }
        }

        Given("a blank value") {
            Then("no button is on -- that is 'show none', not 'the default'") {
                RightButton.parse("") shouldBe emptySet<RightButton>()
            }

            Then("writing the empty set back yields blank again") {
                RightButton.serialize(emptySet()) shouldBe ""
            }
        }

        Given("a value whose ids are in another order") {
            Then("the stored form still follows the enum order") {
                RightButton.serialize(RightButton.parse("floating,hide")) shouldBe
                    RightButton.serialize(listOf(RightButton.HIDE, RightButton.FLOATING))
            }
        }

        Given("an id this build does not know") {
            Then("it is dropped instead of failing the whole value") {
                RightButton.parse("hide,whatever,floating") shouldBe
                    setOf(RightButton.HIDE, RightButton.FLOATING)
            }
        }

        Given("the actions a theme declares") {
            Then("the three named buttons map to their ids") {
                RightButton.ofThemeAction("Hide") shouldBe RightButton.HIDE
                RightButton.ofThemeAction("clipboard_window") shouldBe RightButton.CLIPBOARD
                RightButton.ofThemeAction("Mode_switch") shouldBe RightButton.MODE_SWITCH
            }

            Then("a button the theme invents maps to nothing") {
                RightButton.ofThemeAction("liquid_keyboard_emoji") shouldBe null
            }
        }
    })
