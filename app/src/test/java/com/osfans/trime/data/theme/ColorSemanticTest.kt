/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

/**
 * The semantic layer has to be *total* and *non-overlapping*, and both are
 * properties of the data rather than of any code path, so they are asserted
 * here instead of being trusted to hold.
 *
 * A key two items claimed would resolve by whichever the merge happened to
 * reach last, and a key no item claimed would simply be impossible to control
 * from the built-in settings — silently, because nothing would look broken.
 */
class ColorSemanticTest :
    BehaviorSpec({
        Given("the derivation of the semantic items") {
            val owned = ColorSemantic.ownedKeys

            Then("no engine key is claimed by two items") {
                owned.groupingBy { it }.eachCount().filterValues { it > 1 } shouldBe emptyMap()
            }

            Then("every claimed key is a real colour key") {
                owned.filter { ColorKey.from(it) == null } shouldBe emptyList()
            }

            Then("no key is both claimed and declared unowned") {
                ColorSemantic.notSemantic.keys.intersect(owned.toSet()) shouldBe emptySet()
            }

            Then("every colour key is either claimed or explicitly unowned") {
                val unaccounted =
                    ColorKey.entries.map { it.key } - owned.toSet() - ColorSemantic.notSemantic.keys
                unaccounted shouldBe emptyList()
            }

            Then("every unowned key says why") {
                ColorSemantic.notSemantic.filterValues { it.isBlank() } shouldBe emptyMap()
            }

            Then("items the settings screen shows stay in the intended range") {
                // The user asked for a short list; a refactor that grows it past this
                // is a product decision, not an accident to be discovered later.
                ColorSemantic.entries.size shouldBe 22
            }
        }

        Given("the shipped built-in palette") {
            val default = BuiltinColorDefaults.DEFAULT

            Then("it names every item, in both day and night") {
                ColorSemantic.entries.filterNot { default.values(false).containsKey(it) } shouldBe emptyList()
                ColorSemantic.entries.filterNot { default.values(true).containsKey(it) } shouldBe emptyList()
            }

            Then("its day and night palettes cover the same items") {
                default.light.keys shouldBe default.dark.keys
            }

            Then("every semantic name it uses is one this build knows") {
                // A renamed or dropped enum entry would otherwise lose its colour in silence.
                default.unknownSemantics shouldBe emptyList()
            }

            Then("it ships with no per-key overrides") {
                default.overrides shouldBe emptyMap()
            }

            Then("every colour is a literal #RGB/#ARGB value") {
                (default.light.values + default.dark.values).forEach { value ->
                    value shouldMatch Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
                }
            }

            Then("the surfaces and their labels are not the same colour") {
                // The one mistake that makes a keyboard unreadable rather than ugly.
                listOf(false, true).forEach { isNight ->
                    val palette = default.values(isNight)
                    ColorSemantic.entries.forEach { item ->
                        val label =
                            when (item) {
                                ColorSemantic.KEY_SURFACE -> ColorSemantic.KEY_LABEL
                                ColorSemantic.CANDIDATE_SURFACE -> ColorSemantic.CANDIDATE_LABEL
                                ColorSemantic.FUNCTION_SURFACE -> ColorSemantic.FUNCTION_LABEL
                                ColorSemantic.POPUP_SURFACE -> ColorSemantic.POPUP_LABEL
                                ColorSemantic.PREEDIT_SURFACE -> ColorSemantic.PREEDIT_TEXT
                                ColorSemantic.KEY_PRESSED_SURFACE -> ColorSemantic.ON_ACCENT
                                ColorSemantic.FUNCTION_PRESSED_SURFACE -> ColorSemantic.FUNCTION_LABEL
                                ColorSemantic.ON_PRESSED_SURFACE -> ColorSemantic.ON_ACCENT
                                ColorSemantic.ACCENT -> ColorSemantic.ON_ACCENT
                                else -> null
                            } ?: return@forEach
                        palette[item] shouldNotBe palette[label]
                    }
                }
            }
        }

        Given("a palette stored by a newer build") {
            When("it names a semantic this build does not know") {
                Then("the unknown name is reported rather than silently dropped") {
                    val scheme =
                        BuiltinColorScheme(
                            id = "x",
                            name = "x",
                            light = mapOf("key_surface" to "#FFFFFF", "future_thing" to "#000000"),
                        )
                    scheme.unknownSemantics shouldBe listOf("future_thing")
                    scheme.values(false).keys shouldBe setOf(ColorSemantic.KEY_SURFACE)
                }
            }
        }

        Given("editing one day/night state") {
            val scheme = BuiltinColorDefaults.DEFAULT

            When("a value is written for the night state") {
                val edited = scheme.withValues(true, mapOf(ColorSemantic.ACCENT to "#123456"))

                Then("only the night palette moves") {
                    edited.values(true)[ColorSemantic.ACCENT] shouldBe "#123456"
                    edited.values(false)[ColorSemantic.ACCENT] shouldBe
                        scheme.values(false)[ColorSemantic.ACCENT]
                }
            }

            When("an item is cleared") {
                val cleared = scheme.withoutValues(false, listOf(ColorSemantic.OUTLINE))

                Then("it leaves the palette, so the key falls through to the scheme") {
                    cleared.values(false).containsKey(ColorSemantic.OUTLINE) shouldBe false
                    cleared.values(true).containsKey(ColorSemantic.OUTLINE) shouldBe true
                }
            }
        }

        Given("clearing the whole palette") {
            val edited =
                BuiltinColorDefaults.DEFAULT
                    .withValues(false, mapOf(ColorSemantic.ACCENT to "#123456"))
                    .withValues(true, mapOf(ColorSemantic.ACCENT to "#654321"))
                    .copy(overrides = mapOf("bgn" to "#FEDDBD"))

            val cleared = edited.cleared()

            Then("nothing is set in either day/night palette") {
                cleared.light shouldBe emptyMap()
                cleared.dark shouldBe emptyMap()
            }

            Then("the per-key overrides go too, or 'follow the theme' would be half true") {
                cleared.overrides shouldBe emptyMap()
            }

            Then("it does not touch the palette it was derived from") {
                edited.values(false).containsKey(ColorSemantic.ACCENT) shouldBe true
                edited.overrides.containsKey("bgn") shouldBe true
            }
        }
    })
