/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import com.osfans.trime.data.prefs.AppPrefs
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The two overrides the preedit's look adds on top of the theme: its corners and its fill.
 *
 * Both have a "nothing chosen" value that has to reproduce the theme exactly -- zero for the
 * radius, zero for the colour, and 100% for the opacity. Those three defaults are the whole reason
 * the page is safe to open: if any of them drifted, merely visiting the settings screen would
 * change how the keyboard looks.
 */
class PreeditAppearanceTest :
    BehaviorSpec({
        // The alpha byte, read without android.graphics.Color: this suite runs on the JVM.
        fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF

        Given("the theme's own shape, with the radius left alone") {
            Then("the two top radii are kept and the bottom stays square, left to right") {
                preeditCornerRadii(
                    topStartDp = 4f,
                    topEndDp = 6f,
                    overrideDp = 0,
                    ltr = true,
                    density = 2f,
                ).toList() shouldBe listOf(8f, 8f, 12f, 12f, 0f, 0f, 0f, 0f)
            }

            Then("a right-to-left layout mirrors the two top corners") {
                // `top_start_radius` is a start, not a left: the theme's own naming says so.
                preeditCornerRadii(
                    topStartDp = 4f,
                    topEndDp = 6f,
                    overrideDp = 0,
                    ltr = false,
                    density = 2f,
                ).toList() shouldBe listOf(12f, 12f, 8f, 8f, 0f, 0f, 0f, 0f)
            }
        }

        Given("a radius chosen in the settings") {
            Then("all four corners take it, whatever the theme had") {
                preeditCornerRadii(
                    topStartDp = 4f,
                    topEndDp = 6f,
                    overrideDp = 8,
                    ltr = true,
                    density = 2.5f,
                ).toList() shouldBe List(8) { 20f }
            }

            Then("the dp value is converted with the display density") {
                preeditCornerRadii(0f, 0f, overrideDp = 6, ltr = true, density = 1f).toList() shouldBe
                    List(8) { 6f }
            }

            Then("a value past the slider's maximum is clamped, not drawn") {
                preeditCornerRadii(
                    topStartDp = 0f,
                    topEndDp = 0f,
                    overrideDp = 400,
                    ltr = true,
                    density = 1f,
                ).toList() shouldBe List(8) { AppPrefs.PreeditAppearance.CORNER_RADIUS_MAX_DP.toFloat() }
            }
        }

        Given("the theme's own fill") {
            Then("100% opacity leaves its alpha untouched") {
                // The scheme hands the chip a translucent colour (0xcc and friends); the default
                // must not flatten that, or opening the page would change the look on its own.
                preeditFillColor(
                    themeColor = 0xCC112233.toInt(),
                    pickedColor = 0,
                    opacityPercent = 100,
                ) shouldBe 0xCC112233.toInt()
            }

            Then("half opacity halves that alpha and keeps the hue") {
                val fill = preeditFillColor(0xFF112233.toInt(), pickedColor = 0, opacityPercent = 50)
                alphaOf(fill) shouldBe 128
                fill and 0x00FFFFFF shouldBe 0x112233
            }

            Then("zero opacity drops the fill altogether") {
                preeditFillColor(0xCC112233.toInt(), pickedColor = 0, opacityPercent = 0) shouldBe 0x00112233
            }

            Then("a translucent theme colour is rounded, never ratcheted down") {
                // 204 * 50% is 102 exactly; the +50 in the helper is what keeps a non-round alpha
                // (0xCD, 0x7F) from losing a step every time the value is confirmed.
                alphaOf(preeditFillColor(0xCC112233.toInt(), pickedColor = 0, opacityPercent = 50)) shouldBe 102
                alphaOf(preeditFillColor(0xCD112233.toInt(), pickedColor = 0, opacityPercent = 100)) shouldBe 205
            }
        }

        Given("a picked colour") {
            Then("the fill takes it and ignores the theme") {
                preeditFillColor(0xFF112233.toInt(), pickedColor = 0xFFABCDEF.toInt(), opacityPercent = 100) shouldBe
                    0xFFABCDEF.toInt()
            }

            Then("its own transparency is the opacity row's business, not the colour's") {
                // The colour is stored as RGB; the byte that means "how opaque" lives in one place.
                val fill = preeditFillColor(0xFF112233.toInt(), pickedColor = 0x80ABCDEF.toInt(), opacityPercent = 100)
                fill shouldBe 0xFFABCDEF.toInt()
            }

            Then("a picked black is still a colour, not the follow-scheme sentinel") {
                // Zero is the sentinel, so a picked black has to arrive opaque or it would silently
                // fall back to the theme the moment someone chose the darkest swatch.
                preeditFillColor(0xCC112233.toInt(), pickedColor = 0xFF000000.toInt(), opacityPercent = 100) shouldBe
                    0xFF000000.toInt()
            }
        }
    })
