/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlin.math.roundToInt

/**
 * The wiring between the size list and the style it writes into.
 *
 * Two things here fail silently rather than loudly, which is why they are asserted rather than
 * trusted: a misspelled style key would be skipped by the patch and simply never do anything, and
 * a size wired to the wrong field would move a knob the user did not touch.
 */
class StyleSemanticTest :
    BehaviorSpec({
        Given("every size in the list") {
            Then("it names a key the theme linter knows") {
                // KNOWN_KEYS is what ThemeDiagnostics uses to call a theme's own key a typo, so
                // sharing it is what keeps a name in the list and a name in the decoder in step.
                StyleSemantic.entries.map { it.key }.filterNot { it in GeneralStyle.KNOWN_KEYS } shouldBe emptyList()
            }

            Then("no two sizes share a key") {
                StyleSemantic.entries.map { it.key }.groupingBy { it }.eachCount().filterValues { it > 1 } shouldBe emptyMap()
            }

            Then("its range can actually be dragged") {
                StyleSemantic.entries.filter { it.min >= it.max } shouldBe emptyList()
            }

            Then("writing a value and reading it back returns the same number") {
                StyleSemantic.entries.forEach { semantic ->
                    val value = ((semantic.min + semantic.max) / 2f).roundToInt().toFloat()
                    GeneralStyle().withValue(semantic, value).valueOf(semantic) shouldBe value
                }
            }
        }

        Given("a set of overrides keyed the way the theme spells them") {
            When("a key in the set names a size") {
                Then("that size is applied") {
                    GeneralStyle().withStyleValues(mapOf("key_text_size" to 22f)).keyTextSize shouldBe 22f
                }
            }

            When("a key in the set names nothing this build knows") {
                Then("it is skipped, and the rest still applies") {
                    val style =
                        GeneralStyle().withStyleValues(
                            mapOf("future_size" to 99f, "key_text_size" to 22f),
                        )
                    style.keyTextSize shouldBe 22f
                }
            }

            When("the set names nothing") {
                Then("the style is untouched, which is what makes an empty override follow the theme") {
                    GeneralStyle().withStyleValues(emptyMap()) shouldBe GeneralStyle()
                }
            }

            When("the set names an integer field") {
                Then("the value is rounded into it rather than truncated away") {
                    GeneralStyle().withStyleValues(mapOf("key_border" to 2.6f)).keyBorder shouldBe 3
                }
            }
        }

        Given("the stored sizes") {
            val theme = GeneralStyle(keyTextSize = 18f, roundCorner = 5f)

            Then("a size with no override reads as the theme declares it") {
                BuiltinStyle().valueOf(StyleSemantic.KEY_TEXT_SIZE, theme) shouldBe 18f
                BuiltinStyle().isOverridden(StyleSemantic.KEY_TEXT_SIZE) shouldBe false
            }

            Then("an overridden size reads as the override") {
                val overridden = BuiltinStyle().with(StyleSemantic.KEY_TEXT_SIZE, 24f)
                overridden.valueOf(StyleSemantic.KEY_TEXT_SIZE, theme) shouldBe 24f
                overridden.isOverridden(StyleSemantic.KEY_TEXT_SIZE) shouldBe true
            }

            Then("clearing one puts it back under the theme") {
                val cleared =
                    BuiltinStyle()
                        .with(StyleSemantic.KEY_TEXT_SIZE, 24f)
                        .without(StyleSemantic.KEY_TEXT_SIZE)
                cleared.valueOf(StyleSemantic.KEY_TEXT_SIZE, theme) shouldBe 18f
                cleared.isOverridden(StyleSemantic.KEY_TEXT_SIZE) shouldBe false
            }

            Then("clearing one leaves the others alone") {
                val cleared =
                    BuiltinStyle()
                        .with(StyleSemantic.KEY_TEXT_SIZE, 24f)
                        .with(StyleSemantic.ROUND_CORNER, 12f)
                        .without(StyleSemantic.KEY_TEXT_SIZE)
                cleared.valueOf(StyleSemantic.ROUND_CORNER, theme) shouldBe 12f
            }

            Then("a name written by a newer build is reported, not applied") {
                BuiltinStyle(values = mapOf("future_size" to 1f)).unknownKeys shouldBe listOf("future_size")
            }
        }
    })
