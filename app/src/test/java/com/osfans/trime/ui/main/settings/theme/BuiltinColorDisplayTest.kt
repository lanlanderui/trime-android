/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import com.osfans.trime.data.theme.ColorSemantic
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * What the built-in colour screen decides about the theme in front of it.
 *
 * These are pure and cheap to get wrong in a way nobody notices: a helper that lists one key too
 * few just shows a shorter list, and the editor cannot tell a missing row from a key it was right
 * to leave out.
 */
class BuiltinColorDisplayTest :
    BehaviorSpec({
        Given("the sections the screen shows") {
            val sections = semanticSections()

            Then("every item appears exactly once") {
                sections.flatMap { it.items }.sortedBy { it.ordinal } shouldBe ColorSemantic.entries.toList()
            }

            Then("the groups keep their declared order and none is empty") {
                sections.map { it.group } shouldBe sections.map { it.group }.sortedBy { it.ordinal }
                sections.filter { it.items.isEmpty() } shouldBe emptyList()
            }

            Then("items inside a group keep their declared order") {
                sections.forEach { section ->
                    section.items.map { it.ordinal } shouldBe section.items.map { it.ordinal }.sorted()
                }
            }
        }

        Given("the colour keys a theme declares") {
            // `bgn` is what this theme really uses, `key_back_color` is one the layer owns, and
            // `shadow_color` is owned by the shadow item.
            val declared = listOf("bgn", "key_back_color", "benter", "shadow_color", "bgn")

            When("it names keys the semantic layer does not own") {
                Then("only those are offered as per-key overrides, deduplicated and sorted") {
                    foreignKeys(declared) shouldBe listOf("benter", "bgn")
                }
            }

            When("it names keys this build reads nowhere") {
                val withDead = declared + "liquid_keyboard_background"

                Then("the semantic layer does not list them as overrides") {
                    foreignKeys(withDead).contains("liquid_keyboard_background") shouldBe false
                }

                Then("they are reported as ignored, with a reason") {
                    ineffectiveKeys(withDead) shouldBe
                        listOf("liquid_keyboard_background" to ColorSemantic.notSemantic.getValue("liquid_keyboard_background"))
                }
            }

            When("it declares none of the ignored keys") {
                Then("nothing is reported") {
                    ineffectiveKeys(listOf("bgn")) shouldBe emptyList()
                }
            }
        }

        Given("the full per-key override list") {
            // The size that makes this worth bounding: this theme's own vocabulary.
            val many = (1..200).map { "key_$it" }
            val few = (1..5).map { "key_$it" }

            When("the list has not been expanded") {
                Then("it is capped at nothing, because the screen is showing only overrides") {
                    overrideWindow(many, expanded = false, more = 0) shouldBe many.size
                }
            }

            When("it has been expanded") {
                Then("the first page is built, not the whole list") {
                    // The point of the cap: 200 keys is 800 views in a LinearLayout that does not
                    // recycle them, all created and measured on the main thread.
                    overrideWindow(many, expanded = true, more = 0) shouldBe OVERRIDE_PAGE_SIZE
                }

                Then("asking for more grows the window a page at a time") {
                    overrideWindow(many, expanded = true, more = 1) shouldBe OVERRIDE_PAGE_SIZE * 2
                    overrideWindow(many, expanded = true, more = 2) shouldBe OVERRIDE_PAGE_SIZE * 3
                }

                Then("it never exceeds the list, however many pages are asked for") {
                    overrideWindow(many, expanded = true, more = 99) shouldBe many.size
                }
            }

            When("the list is shorter than one page") {
                Then("it is shown whole, and there is nothing more to page to") {
                    overrideWindow(few, expanded = true, more = 0) shouldBe few.size
                    overrideWindow(few, expanded = true, more = 5) shouldBe few.size
                }
            }

            When("more is negative") {
                Then("it is treated as the first page rather than as a window before the start") {
                    // Reachable if the flag is ever restored from saved state; a negative window
                    // would silently show no overrides at all.
                    overrideWindow(many, expanded = true, more = -1) shouldBe OVERRIDE_PAGE_SIZE
                }
            }
        }

        Given("a colour") {
            Then("it is spelled the way the theme files do, zero-padded") {
                argbToHex(0x0F6E56FF) shouldBe "#0F6E56FF"
                argbToHex(0x00000001) shouldBe "#00000001"
            }
        }
    })
