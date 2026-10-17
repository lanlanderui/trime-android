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
import com.osfans.trime.data.theme.composeWithPalette
import com.osfans.trime.data.theme.model.ColorScheme
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * The one path every colour a user sees goes through, and the reset rules behind it.
 *
 * Both were untestable until recently — the first because it read the source preference, the store
 * and the night mode out of a singleton; the second because it lived in an Activity as a `when` over
 * an index. Neither is clever, and both are exactly where a quiet mistake would do the most damage:
 * one wrong scheme id, or one reset that takes the sizes with it, and the user cannot tell what
 * happened.
 */
class BuiltinColorCompositionTest :
    BehaviorSpec({
        // The engine's keys, not the semantic names: a palette is stored under
        // `ColorSemantic.key` but composed onto `ColorSemantic.keys`, and the two are not the same
        // string — KEY_SURFACE is `key_surface` inside the palette and `key_back_color` on the
        // keyboard. Asserting against the wrong one fails while the code is right, which is how this
        // test was wrong the first time.
        val themeScheme =
            ColorScheme(
                id = "trime_dark",
                colors = mapOf("key_back_color" to "#232626", "keyboard_back_color" to "#EEF2F1"),
            )

        Given("the built-in colours are switched off at the source") {
            val palette =
                BuiltinColorDefaults.DEFAULT.withValues(false, mapOf(ColorSemantic.KEY_SURFACE to "#123456"))

            Then("the scheme comes back untouched, not merely equal") {
                // Identity, not equality: a rebuilt scheme would look the same and yet cost a
                // repaint on every call.
                composeWithPalette(themeScheme, palette, isNight = false, colorsInCharge = false)
                    .shouldBeSameInstanceAs(themeScheme)
            }
        }

        Given("the built-in colours are in charge") {
            val palette =
                BuiltinColorDefaults.DEFAULT.withValues(false, mapOf(ColorSemantic.KEY_SURFACE to "#123456"))

            Then("the palette wins over the theme's value for the items it sets") {
                composeWithPalette(themeScheme, palette, isNight = false, colorsInCharge = true)
                    .colors["key_back_color"] shouldBe "#123456"
            }

            Then("every engine key the item names gets it, not just one") {
                // An item can stand for several keys; missing one would leave a view reading the
                // theme's colour, which is exactly what the palette is there to prevent.
                val many =
                    BuiltinColorDefaults.DEFAULT.withValues(false, mapOf(ColorSemantic.KEY_SURFACE to "#123456"))
                val composed = composeWithPalette(themeScheme, many, isNight = false, colorsInCharge = true)
                ColorSemantic.KEY_SURFACE.keys.forEach { composed.colors[it] shouldBe "#123456" }
            }

            Then("the items it does not set still come from the theme") {
                val composed =
                    composeWithPalette(themeScheme, palette, isNight = false, colorsInCharge = true)
                composed.colors["keyboard_back_color"] shouldBe themeScheme.colors["keyboard_back_color"]
            }

            Then("the scheme id survives, so day and night stay tellable apart") {
                // The candidate bar reads the id to tint itself; rebuilding under a different id
                // would quietly lose that.
                composeWithPalette(themeScheme, palette, isNight = false, colorsInCharge = true)
                    .id shouldBe themeScheme.id
            }

            Then("the dark half of the palette is used when it is night") {
                val night =
                    BuiltinColorDefaults.DEFAULT.withValues(true, mapOf(ColorSemantic.KEY_SURFACE to "#ABCDEF"))
                composeWithPalette(themeScheme, night, isNight = true, colorsInCharge = true)
                    .colors["key_back_color"] shouldBe "#ABCDEF"
            }

            Then("an empty palette composes to exactly the theme's colours") {
                // The case that made an untouched screen look broken: nothing set, nothing to show.
                val composed =
                    composeWithPalette(themeScheme, BuiltinColorScheme(), isNight = false, colorsInCharge = true)
                composed.colors shouldBe themeScheme.colors
            }

            Then("per-key overrides reach keys the semantic palette does not name") {
                val withOverrides = palette.copy(overrides = mapOf("bgn" to "#FEDDBD"))
                composeWithPalette(themeScheme, withOverrides, isNight = false, colorsInCharge = true)
                    .colors["bgn"] shouldBe "#FEDDBD"
            }
        }
    })

/** The three resets, and the one that must not touch the sizes or the layout. */
class BuiltinResetTest :
    BehaviorSpec({
        val editedScheme =
            BuiltinColorDefaults.DEFAULT.withValues(false, mapOf(ColorSemantic.ACCENT to "#123456"))
        val editedStyle = BuiltinStyle(mapOf("key_text_size" to 24f))
        val editedLayout = BuiltinLayout(mapOf("keyboard_height" to 300f))

        Given("a palette and some sizes the user has edited") {
            When("the default palette is restored") {
                Then("the shipped palette comes back") {
                    editedScheme.afterReset(ResetChoice.DEFAULT_PALETTE) shouldBe BuiltinColorDefaults.DEFAULT
                }

                Then("and the sizes and the layout are left exactly as they were") {
                    editedStyle.afterReset(ResetChoice.DEFAULT_PALETTE) shouldBe editedStyle
                    editedLayout.afterReset(ResetChoice.DEFAULT_PALETTE) shouldBe editedLayout
                    ResetChoice.DEFAULT_PALETTE.touchesBuiltinOverrides() shouldBe false
                }
            }

            When("the colours are told to follow the theme's scheme") {
                Then("every colour item is cleared") {
                    editedScheme.afterReset(ResetChoice.FOLLOW_SCHEME).values(false) shouldBe emptyMap()
                }

                Then("and the sizes and the layout are still not touched") {
                    // The rule worth a test: this is an answer about colours, and taking the sizes
                    // or the layout with it would discard work the user never mentioned.
                    editedStyle.afterReset(ResetChoice.FOLLOW_SCHEME) shouldBe editedStyle
                    editedLayout.afterReset(ResetChoice.FOLLOW_SCHEME) shouldBe editedLayout
                    ResetChoice.FOLLOW_SCHEME.touchesBuiltinOverrides() shouldBe false
                }
            }

            When("everything is told to follow the theme") {
                Then("colours are cleared and the sizes and the layout go with them") {
                    editedScheme.afterReset(ResetChoice.FOLLOW_THEME).values(false) shouldBe emptyMap()
                    editedStyle.afterReset(ResetChoice.FOLLOW_THEME).values shouldBe emptyMap()
                    editedLayout.afterReset(ResetChoice.FOLLOW_THEME).values shouldBe emptyMap()
                    ResetChoice.FOLLOW_THEME.touchesBuiltinOverrides() shouldBe true
                }
            }
        }

Given("a palette the user made") {
                Then("following the theme's scheme empties it rather than handing back a default") {
                    // The two "clear" resets must not look alike: one empties, one restores. The
                    // palette keeps its own name, so the list still shows which one this was.
                    val cleared = editedScheme.afterReset(ResetChoice.FOLLOW_SCHEME)
                    cleared.values(false) shouldBe emptyMap()
                    cleared.overrides shouldBe emptyMap()
                    cleared shouldBe editedScheme.cleared()
                }
            }
    })