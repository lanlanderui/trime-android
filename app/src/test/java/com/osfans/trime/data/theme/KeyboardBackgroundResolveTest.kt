/*
 * SPDX-FileCopyrightText: 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.ColorScheme
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The `keyboard_background` resolution path, end to end through the table.
 *
 * This exists because the frosted keyboard spent three rounds "not working" for a reason no amount
 * of tuning would have fixed: the key resolved to something other than an image, so the bitmap was
 * never decoded at all. The two ways it can go wrong are worth pinning down separately — a value
 * silently *replaced* (a duplicate `fallback_colors` block in the theme file, where YAML keeps the
 * last) and a value simply *absent* — because both look identical on screen: no background.
 *
 * Assertions compare against the expected [ColorTable.Value] directly rather than narrowing with
 * `shouldBeInstanceOf`, so a failure message shows the value that was actually produced.
 */
class KeyboardBackgroundResolveTest :
    BehaviorSpec({
        fun scheme(vararg pairs: Pair<String, String>) = ColorScheme("test", pairs.toMap())

        /**
         * Fake ColorUtils.parseColor: supports #RRGGBB / #AARRGGBB and the 0x forms.
         * An image path is not hex, so it parses to null — which is what keeps it out of the colour
         * branch and lets `isImageValue` decide instead.
         */
        val parseHex: (String) -> Int? = { s ->
            val digits =
                when {
                    s.startsWith("#") -> s.substring(1)
                    s.startsWith("0x", ignoreCase = true) -> s.substring(2)
                    else -> null
                }
            digits?.let { runCatching { java.lang.Long.parseLong(it, 16).toInt() }.getOrNull() }
        }

        val KEY = ColorKey.KEYBOARD_BACKGROUND

        Given("an image path in the theme's fallback_colors") {
            val table =
                ColorTable.resolve(
                    scheme(),
                    mapOf(KEY.key to "frost_bg.jpg"),
                    parseHex,
                )

            Then("the raw walk finds the value") {
                // Isolate which half is at fault: the walk, or the table build that follows it.
                ColorTable.resolveRaw(KEY.key, emptyMap(), mapOf(KEY.key to "frost_bg.jpg")) shouldBe
                    "frost_bg.jpg"
            }

            Then("it resolves to an Image, not a colour") {
                // The assertion that would have caught the bug on the first attempt.
                table[KEY] shouldBe ColorTable.Value.Image("frost_bg.jpg")
            }

            Then("the key is not reported unresolved") {
                table.unresolvedKeys shouldNotContain KEY
            }
        }

        Given("the key is absent from both scheme and theme") {
            Then("it resolves to None rather than inventing a value") {
                ColorTable.resolve(scheme(), emptyMap(), parseHex)[KEY] shouldBe ColorTable.Value.None
            }
        }

        Given("the built-in chain hop is taken") {
            val table =
                ColorTable.resolve(scheme(), mapOf(KEY.key to "key_back_color"), parseHex)

            Then("it lands on the terminal the chain names, as a colour") {
                // KEYBOARD_BACKGROUND -> KEYBOARD_BACK_COLOR -> BORDER_COLOR -> BACK_COLOR, and
                // `text_color` is the terminal the scheme actually defines. An unconfigured theme
                // correctly gets a solid colour here; this is only a bug when a theme author
                // believes they configured an image and this is what they silently get instead.
                table[KEY] shouldBe
                    ColorTable.resolve(scheme("text_color" to "#ff123456"), emptyMap(), parseHex)[KEY]
            }
        }

        Given("a fallback entry that names a key nothing defines") {
            Then("it resolves to None instead of hanging or inventing") {
                // The walk terminates at a key with no value anywhere; the loop must not spin.
                ColorTable.resolve(scheme(), mapOf(KEY.key to "no_such_key"), parseHex)[KEY] shouldBe
                    ColorTable.Value.None
            }
        }

        Given("a scheme value overrides the theme's fallback image") {
            val table =
                ColorTable.resolve(
                    scheme(KEY.key to "#ff0000"),
                    mapOf(KEY.key to "frost_bg.jpg"),
                    parseHex,
                )

            Then("the scheme wins, which is how a duplicate block silently loses an image") {
                // Scheme entries are checked before `fallback_colors`, so this has the same shape as
                // the duplicate-block bug: a later definition quietly replaced the image value.
                table[KEY] shouldBe ColorTable.Value.Color(parseHex("#ff0000")!!)
            }
        }

        Given("image values by extension") {
            Then("every extension the resolver accepts stays an image") {
                for (name in listOf("a.png", "a.webp", "a.jpg", "a.gif")) {
                    ColorTable.resolve(scheme(), mapOf(KEY.key to name), parseHex)[KEY] shouldBe
                        ColorTable.Value.Image(name)
                }
            }
        }
    })
