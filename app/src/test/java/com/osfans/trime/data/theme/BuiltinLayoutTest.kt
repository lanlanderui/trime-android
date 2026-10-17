/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.math.roundToInt

/**
 * The layout knobs, the store behind them, and the gate in front of both.
 *
 * The same wiring tests as the size list, for the same reasons: a misspelled key is skipped
 * silently, a knob wired to the wrong field moves something the user did not touch, and the gate
 * is the one place where "who wins" is decided.
 */
class BuiltinLayoutTest :
    BehaviorSpec({
        Given("every layout knob in the list") {
            Then("it names a key the theme linter knows") {
                LayoutSemantic.entries.map { it.key }.filterNot { it in GeneralStyle.KNOWN_KEYS } shouldBe
                    emptyList()
            }

            Then("no two knobs share a key") {
                LayoutSemantic.entries.map { it.key }.groupingBy { it }.eachCount().filterValues { it > 1 } shouldBe
                    emptyMap()
            }

            Then("its range can actually be dragged") {
                LayoutSemantic.entries.filter { it.min >= it.max } shouldBe emptyList()
            }

            Then("writing a value and reading it back returns the same number") {
                LayoutSemantic.entries.forEach { semantic ->
                    val value = ((semantic.min + semantic.max) / 2f).roundToInt().toFloat()
                    GeneralStyle().withValue(semantic, value).valueOf(semantic) shouldBe value
                }
            }
        }

        Given("a set of layout overrides keyed the way the theme spells them") {
            Then("a known key is applied, rounded into the integer field") {
                GeneralStyle().withLayoutValues(mapOf("keyboard_height" to 300.4f)).keyboardHeight shouldBe 300
            }

            Then("an unknown key is skipped, and the rest still applies") {
                val style =
                    GeneralStyle().withLayoutValues(
                        mapOf("future_layout_key" to 99f, "vertical_gap" to 6f),
                    )
                style.verticalGap shouldBe 6
            }

            Then("an empty set leaves the style untouched") {
                GeneralStyle().withLayoutValues(emptyMap()) shouldBe GeneralStyle()
            }
        }

        Given("the stored layout") {
            val theme = GeneralStyle(keyboardHeight = 250, horizontalGap = 2)

            Then("a knob with no override reads as the theme declares it") {
                BuiltinLayout().valueOf(LayoutSemantic.KEYBOARD_HEIGHT, theme) shouldBe 250f
                BuiltinLayout().isOverridden(LayoutSemantic.KEYBOARD_HEIGHT) shouldBe false
            }

            Then("an overridden knob reads as the override") {
                val overridden = BuiltinLayout().with(LayoutSemantic.KEYBOARD_HEIGHT, 300f)
                overridden.valueOf(LayoutSemantic.KEYBOARD_HEIGHT, theme) shouldBe 300f
                overridden.isOverridden(LayoutSemantic.KEYBOARD_HEIGHT) shouldBe true
            }

            Then("clearing one puts it back under the theme") {
                val cleared =
                    BuiltinLayout()
                        .with(LayoutSemantic.KEYBOARD_HEIGHT, 300f)
                        .without(LayoutSemantic.KEYBOARD_HEIGHT)
                cleared.valueOf(LayoutSemantic.KEYBOARD_HEIGHT, theme) shouldBe 250f
                cleared.isOverridden(LayoutSemantic.KEYBOARD_HEIGHT) shouldBe false
            }

            Then("a name written by a newer build is reported, not applied") {
                BuiltinLayout(values = mapOf("future_layout_key" to 1f)).unknownKeys shouldBe
                    listOf("future_layout_key")
            }
        }

        Given("the layout-source gate") {
            val theme = ThemeTestSupport.decodeBuiltinTheme("trime.yaml")
            val stored = mapOf("keyboard_height" to 300f, "vertical_gap" to 8f)

            Then("with the theme in charge the stored layout is skipped whole") {
                patchedLayout(theme, stored, overridesInCharge = false) shouldBeSameInstanceAs theme
            }

            Then("with the built-in layout in charge each stored knob lands, and only those") {
                val patched = patchedLayout(theme, stored, overridesInCharge = true)
                patched.generalStyle.keyboardHeight shouldBe 300
                patched.generalStyle.verticalGap shouldBe 8
                patched.generalStyle.horizontalGap shouldBe theme.generalStyle.horizontalGap
            }

            Then("an empty store passes the theme through even in charge") {
                patchedLayout(theme, emptyMap(), overridesInCharge = true).generalStyle shouldBe
                    theme.generalStyle
            }
        }
    })
