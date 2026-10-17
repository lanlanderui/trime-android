/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import com.osfans.trime.data.theme.BuiltinColorDefaults
import com.osfans.trime.data.theme.BuiltinColorScheme
import com.osfans.trime.data.theme.BuiltinLayout
import com.osfans.trime.data.theme.BuiltinStyle
import com.osfans.trime.data.theme.ColorSemantic
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * What the screen claims is in effect.
 *
 * The number itself is trivial; what it is for is the case it makes visible. A palette with
 * nothing in it renders exactly like the theme's own scheme, so from the keyboard there is no way
 * to tell "I have not set anything" from "this is broken" — which is the mistake this exists to
 * stop being possible.
 */
class ActiveSummaryTest :
    BehaviorSpec({
        val empty = BuiltinColorScheme()

        fun summaryOf(
            scheme: BuiltinColorScheme = empty,
            style: BuiltinStyle = BuiltinStyle(),
            layout: BuiltinLayout = BuiltinLayout(),
            isNight: Boolean = false,
            colorsInCharge: Boolean = true,
            sizesInCharge: Boolean = true,
            layoutInCharge: Boolean = true,
        ) = activeSummary(scheme, style, layout, isNight, colorsInCharge, sizesInCharge, layoutInCharge)

        Given("a palette with nothing set") {
            Then("it reports no colours at all") {
                summaryOf().colors shouldBe 0
            }

            Then("that is the case the screen has to say out loud, not a count of zero") {
                // Zero is the only number here that means "you cannot tell from looking", which is
                // why the screen words it as a sentence instead.
                summaryOf().colors shouldBe 0
                summaryOf().sizes shouldBe 0
            }
        }

        Given("the shipped palette") {
            Then("every item counts") {
                val summary = summaryOf(BuiltinColorDefaults.DEFAULT)
                summary.colors shouldBe BuiltinColorDefaults.DEFAULT.values(false).size
            }
        }

        Given("a palette edited for one mode only") {
            val edited =
                empty
                    .withValues(false, mapOf(ColorSemantic.KEY_SURFACE to "#FFFFFF"))
                    .withValues(true, mapOf(ColorSemantic.ACCENT to "#000000"))

            Then("the light count is the light one, not both") {
                summaryOf(edited, isNight = false).colors shouldBe 1
            }

            Then("and the dark count is the dark one") {
                summaryOf(edited, isNight = true).colors shouldBe 1
            }
        }

        Given("per-key overrides") {
            Then("they count as colours, since they paint keys") {
                summaryOf(empty.copy(overrides = mapOf("bgn" to "#FEDDBD", "bbs" to "#123456"))).colors shouldBe 2
            }
        }

        Given("size overrides") {
            Then("they are counted separately from colours") {
                val summary = summaryOf(style = BuiltinStyle(mapOf("key_text_size" to 18f, "round_corner" to 9f)))
                summary.sizes shouldBe 2
                summary.colors shouldBe 0
            }
        }

        Given("colours switched off at the source") {
            Then("the flag is carried through, so the screen can say the changes do nothing") {
                summaryOf(
                    scheme = BuiltinColorDefaults.DEFAULT,
                    colorsInCharge = false,
                ).colorsInCharge shouldBe false
            }
        }

        Given("sizes switched off at the source") {
            Then("their flag is carried through the same way, since they have a source too") {
                summaryOf(
                    style = BuiltinStyle(mapOf("round_corner" to 6f)),
                    sizesInCharge = false,
                ).sizesInCharge shouldBe false
            }
        }

        Given("layout overrides") {
            Then("they are counted separately from colours and sizes") {
                val summary = summaryOf(layout = BuiltinLayout(mapOf("keyboard_height" to 300f)))
                summary.layout shouldBe 1
                summary.sizes shouldBe 0
            }
        }

        Given("layout switched off at the source") {
            Then("its flag is carried through the same way, since it has a source too") {
                summaryOf(
                    layout = BuiltinLayout(mapOf("vertical_gap" to 8f)),
                    layoutInCharge = false,
                ).layoutInCharge shouldBe false
            }
        }
    })