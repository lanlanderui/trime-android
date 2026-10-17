/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.ColorScheme
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

/**
 * The precedence the built-in palette is layered with.
 *
 * Lowest first: the scheme it sits on, then the semantic palette, then the
 * per-key overrides. The base is kept rather than replaced, because that is
 * what keeps a theme's own colour keys — `bgn`, `bbs` and the
 * `*_custom_key_*` family in this theme — resolving while the built-in
 * palette is in charge of everything it *does* name.
 */
class BuiltinColorComposerTest :
    BehaviorSpec({
        val base =
            ColorScheme(
                id = "default",
                colors =
                    mapOf(
                        "key_back_color" to "#111111",
                        "text_color" to "#222222",
                        // A key of the theme's own invention: the built-in palette has no
                        // vocabulary for it, which is exactly why the base must survive.
                        "bgn" to "#333333",
                    ),
            )

        Given("a palette layered on a scheme") {
            When("it names a key the scheme also defines") {
                Then("the palette wins") {
                    val colors = composeBuiltinColors(base, mapOf(ColorSemantic.KEY_SURFACE to "#AAAAAA"), emptyMap())
                    colors["key_back_color"] shouldBe "#AAAAAA"
                }
            }

            When("the scheme defines a key the palette never names") {
                Then("the scheme's value is kept") {
                    val colors = composeBuiltinColors(base, mapOf(ColorSemantic.KEY_SURFACE to "#AAAAAA"), emptyMap())
                    colors["bgn"] shouldBe "#333333"
                    colors["text_color"] shouldBe "#222222"
                }
            }

            When("one item is set") {
                Then("every key that item derives is painted") {
                    val colors = composeBuiltinColors(base, mapOf(ColorSemantic.OUTLINE to "#BBBBBB"), emptyMap())
                    ColorSemantic.OUTLINE.keys.forEach { colors[it] shouldBe "#BBBBBB" }
                }
            }

            When("an item is left out of the palette") {
                Then("its keys fall through to the scheme untouched") {
                    val colors = composeBuiltinColors(base, mapOf(ColorSemantic.KEY_SURFACE to "#AAAAAA"), emptyMap())
                    colors["key_back_color"] shouldBe "#AAAAAA"
                    colors["text_color"] shouldBe "#222222"
                }
            }

            When("an item is set to blank") {
                Then("it counts as unset and falls through") {
                    val colors = composeBuiltinColors(base, mapOf(ColorSemantic.KEY_SURFACE to ""), emptyMap())
                    colors["key_back_color"] shouldBe "#111111"
                }
            }
        }

        Given("the per-key overrides") {
            When("an override names a key an item already painted") {
                Then("the override wins over the item") {
                    val colors =
                        composeBuiltinColors(
                            base,
                            mapOf(ColorSemantic.KEY_SURFACE to "#AAAAAA"),
                            mapOf("key_back_color" to "#CCCCCC"),
                        )
                    colors["key_back_color"] shouldBe "#CCCCCC"
                }
            }

            When("an override names a key of the theme's own") {
                Then("it takes effect, which is how a theme's custom key is reached") {
                    val colors = composeBuiltinColors(base, emptyMap(), mapOf("bgn" to "#DDDDDD"))
                    colors["bgn"] shouldBe "#DDDDDD"
                }
            }

            When("an override names a key nothing else defines") {
                Then("it is added") {
                    val colors = composeBuiltinColors(base, emptyMap(), mapOf("brand_new_key" to "#EEEEEE"))
                    colors shouldContainKey "brand_new_key"
                }
            }

            When("an override is blank") {
                Then("it is ignored rather than erasing the colour") {
                    // Blank means "not overriding"; erasing would be a silently invisible key.
                    val colors = composeBuiltinColors(base, emptyMap(), mapOf("key_back_color" to ""))
                    colors["key_back_color"] shouldBe "#111111"
                }
            }
        }

        Given("no scheme to layer on") {
            Then("the palette stands alone") {
                val colors = composeBuiltinColors(null, mapOf(ColorSemantic.KEY_SURFACE to "#AAAAAA"), emptyMap())
                colors shouldBe mapOf("key_back_color" to "#AAAAAA")
            }
        }

        Given("the shipped default palette") {
            Then("it leaves no colour key of the theme untouched") {
                // Every key the palette owns must actually be written, so a user who
                // switches to the built-in source sees a complete palette and not a
                // half-themed keyboard.
                val colors =
                    composeBuiltinColors(
                        base,
                        BuiltinColorDefaults.DEFAULT.values(false),
                        BuiltinColorDefaults.DEFAULT.overrides,
                    )
                ColorSemantic.ownedKeys.forEach { colors shouldContainKey it }
            }
        }
    })
