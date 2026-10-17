/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/**
 * Reading an appearance file somebody else wrote.
 *
 * The file is the one input in this feature that arrives from outside the app — through a chat, a
 * backup, a file manager — so what matters is what happens when it is wrong, and when it is
 * *older*. Format 1 carried a single palette; a file exported before palettes existed has to keep
 * importing, which is the case that decides whether [BuiltinAppearance.library] exists at all.
 */
class BuiltinAppearanceTest :
    BehaviorSpec({
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun palette(
            id: String,
            name: String = id,
            light: Map<String, String> = mapOf("key_surface" to "#FBFDFC"),
        ) = BuiltinColorScheme(id = id, name = name, light = light)

        Given("an appearance with several palettes") {
            val original =
                BuiltinAppearance(
                    palettes =
                        listOf(
                            palette("builtin-default", "Built-in default"),
                            palette("mine", "Mine", mapOf("key_surface" to "#232626")),
                        ),
                    selectedId = "mine",
                    style = BuiltinStyle(mapOf("key_text_size" to 22f, "round_corner" to 8f)),
                    layout = BuiltinLayout(mapOf("keyboard_height" to 300f)),
                )

            When("it is written and read back") {
                val text = json.encodeToString(BuiltinAppearance.serializer(), original)

                Then("nothing is lost, including which palette was in use") {
                    BuiltinAppearanceIO.parse(text) shouldBe BuiltinAppearanceIO.Parsed.Ok(original)
                }

                Then("it says what it carries") {
                    val summary = original.summary()
                    summary.palettes shouldBe 2
                    summary.colors shouldBe 2
                    summary.sizes shouldBe 2
                    summary.layout shouldBe 1
                    summary.unknown shouldBe 0
                }

                Then("the library it describes selects the palette it selected") {
                    val library = original.library()
                    library.palettes.map { it.id } shouldBe listOf("builtin-default", "mine")
                    library.current().id shouldBe "mine"
                }
            }
        }

        Given("a file from the previous format, which carried one palette") {
            // Verbatim the shape format 1 wrote, including the format number it wrote.
            val text =
                """{"format":1,"app":"3.3.14","theme":"theme",
                   "colors":{"id":"mine","name":"Mine","light":{"key_surface":"#FFFFFF"},
                             "dark":{},"overrides":{}},
                   "style":{"values":{"key_text_size":20.0}}}"""

            When("it is read") {
                val parsed = BuiltinAppearanceIO.parse(text)

                Then("it is accepted, not refused as an old format") {
                    (parsed is BuiltinAppearanceIO.Parsed.Ok) shouldBe true
                }

                Then("its single palette becomes a library of one") {
                    val library = (parsed as BuiltinAppearanceIO.Parsed.Ok).appearance.library()
                    library.palettes.map { it.id } shouldBe listOf("mine")
                    library.current().name shouldBe "Mine"
                }

                Then("and its sizes come with it") {
                    (parsed as BuiltinAppearanceIO.Parsed.Ok).appearance.style.values["key_text_size"] shouldBe 20f
                }
            }
        }

        Given("a file that is not an appearance at all") {
            Then("a text file is refused") {
                BuiltinAppearanceIO.parse("not json at all") shouldBe
                    BuiltinAppearanceIO.Parsed.Refused(BuiltinAppearanceIO.Parsed.Reason.MALFORMED)
            }

            Then("a truncated download is refused") {
                BuiltinAppearanceIO.parse("""{"format":2,"palettes":[{"id":"a"}""") shouldBe
                    BuiltinAppearanceIO.Parsed.Refused(BuiltinAppearanceIO.Parsed.Reason.MALFORMED)
            }
        }

        Given("a file written by a newer build") {
            Then("it is refused rather than decoded hopefully") {
                val text =
                    json.encodeToString(
                        BuiltinAppearance.serializer(),
                        BuiltinAppearance(
                            format = BuiltinAppearance.FORMAT + 1,
                            palettes = listOf(palette("x")),
                        ),
                    )
                BuiltinAppearanceIO.parse(text) shouldBe
                    BuiltinAppearanceIO.Parsed.Refused(BuiltinAppearanceIO.Parsed.Reason.NEWER_FORMAT)
            }
        }

        Given("a file that carries nothing") {
            Then("it is refused, because importing it would mean nothing happened") {
                val text = json.encodeToString(BuiltinAppearance.serializer(), BuiltinAppearance())
                BuiltinAppearanceIO.parse(text) shouldBe
                    BuiltinAppearanceIO.Parsed.Refused(BuiltinAppearanceIO.Parsed.Reason.EMPTY)
            }

            Then("sizes alone are enough to be worth importing") {
                val text =
                    json.encodeToString(
                        BuiltinAppearance.serializer(),
                        BuiltinAppearance(style = BuiltinStyle(mapOf("key_text_size" to 20f))),
                    )
                (BuiltinAppearanceIO.parse(text) is BuiltinAppearanceIO.Parsed.Ok) shouldBe true
            }

            Then("layout alone is enough too") {
                val text =
                    json.encodeToString(
                        BuiltinAppearance.serializer(),
                        BuiltinAppearance(layout = BuiltinLayout(mapOf("keyboard_height" to 300f))),
                    )
                (BuiltinAppearanceIO.parse(text) is BuiltinAppearanceIO.Parsed.Ok) shouldBe true
            }
        }

        Given("a file whose size is not a JSON number") {
            Then("it is refused instead of being coerced") {
                val text = """{"format":2,"style":{"values":{"key_text_size":"NaN"}}}"""
                BuiltinAppearanceIO.parse(text) shouldBe
                    BuiltinAppearanceIO.Parsed.Refused(BuiltinAppearanceIO.Parsed.Reason.MALFORMED)
            }
        }

        Given("a file written against another theme") {
            val file =
                BuiltinAppearance(
                    theme = "单手特化",
                    themeId = "单手特化.trime",
                    palettes = listOf(palette("mine")),
                )

            Then("the same theme is not a mismatch") {
                file.themeMismatch("单手特化.trime") shouldBe false
            }

            Then("another theme is, and the palette still imports") {
                // A mismatch is a note, not a refusal: the colours are the user's, not the theme's,
                // and a palette made elsewhere usually looks fine.
                file.themeMismatch("tongwenfeng.trime") shouldBe true
                (BuiltinAppearanceIO.parse(json.encodeToString(BuiltinAppearance.serializer(), file))
                    is BuiltinAppearanceIO.Parsed.Ok) shouldBe true
            }

            Then("a file that names no theme cannot be blamed on one") {
                // Written by hand, or by a build that did not record it: unlabelled, not wrong.
                BuiltinAppearance(palettes = listOf(palette("x"))).themeMismatch("anything") shouldBe false
                BuiltinAppearance(palettes = listOf(palette("x")), themeId = "  ")
                    .themeMismatch("anything") shouldBe false
            }

            Then("the theme id survives a round trip, so the note can name a theme whose name changed") {
                val text = json.encodeToString(BuiltinAppearance.serializer(), file)
                val parsed = BuiltinAppearanceIO.parse(text) as BuiltinAppearanceIO.Parsed.Ok
                parsed.appearance.themeId shouldBe "单手特化.trime"
                parsed.appearance.theme shouldBe "单手特化"
            }
        }

        Given("a file carrying names this build does not know") {
            val text =
                """{"format":2,"palettes":[{"id":"a","name":"A","light":{"key_surface":"#FFFFFF","future_surface":"#000000"}}],
                   "style":{"values":{"key_text_size":20.0,"future_size":3.0}}}"""

            Then("the file is accepted") {
                (BuiltinAppearanceIO.parse(text) is BuiltinAppearanceIO.Parsed.Ok) shouldBe true
            }

            Then("the unknown names are counted so the user is told") {
                val parsed = BuiltinAppearanceIO.parse(text) as BuiltinAppearanceIO.Parsed.Ok
                parsed.appearance.summary().unknown shouldBe 2
            }

            Then("the known ones are still applied") {
                val parsed = BuiltinAppearanceIO.parse(text) as BuiltinAppearanceIO.Parsed.Ok
                parsed.appearance.library().palettes[0].values(false)[ColorSemantic.KEY_SURFACE] shouldBe "#FFFFFF"
                parsed.appearance.style.values["key_text_size"] shouldBe 20f
            }
        }
    })