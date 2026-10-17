/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The rules for a library of palettes.
 *
 * The cases that matter are the ones with no obvious answer: which palette is in use when the
 * selected id names nothing, and what a delete leaves behind. Both used to be a single palette, so
 * there is no precedent in the app for either.
 */
class BuiltinColorLibraryTest :
    BehaviorSpec({
        fun palette(id: String, name: String = id) = BuiltinColorScheme(id = id, name = name)

        val a = palette("a")
        val b = palette("b")
        val c = palette("c")

        Given("an empty library") {
            Then("it paints the shipped palette rather than nothing") {
                BuiltinColorLibrary().current() shouldBe BuiltinColorDefaults.DEFAULT
            }
        }

        Given("a library whose selected id names nothing") {
            Then("the first palette is used, so a stale file still shows colours") {
                BuiltinColorLibrary(listOf(a, b), selectedId = "gone").current() shouldBe a
            }
        }

        Given("a library with a selection") {
            Then("that palette is the one in use") {
                BuiltinColorLibrary(listOf(a, b, c), selectedId = "b").current() shouldBe b
            }

            Then("switching to a name it does not know changes nothing") {
                BuiltinColorLibrary(listOf(a, b), selectedId = "a").select("typo") shouldBe
                    BuiltinColorLibrary(listOf(a, b), selectedId = "a")
            }

            Then("switching to one it knows moves the selection") {
                BuiltinColorLibrary(listOf(a, b), selectedId = "a").select("b").selectedId shouldBe "b"
            }
        }

        Given("adding a palette") {
            When("the id is new") {
                val added = BuiltinColorLibrary(listOf(a), selectedId = "a").with(palette("b", "Second"))

                Then("it is appended and becomes the one in use") {
                    // A new palette is one the user just made; leaving them on the old one would
                    // mean editing the wrong thing.
                    added.palettes.map { it.id } shouldBe listOf("a", "b")
                    added.selectedId shouldBe "b"
                }
            }

            When("the id is already there") {
                val replaced = BuiltinColorLibrary(listOf(a, b), selectedId = "b").with(palette("a", "Renamed"))

                Then("it replaces the entry in place and the selection stays put") {
                    replaced.palettes.map { it.name } shouldBe listOf("Renamed", "b")
                    replaced.selectedId shouldBe "b"
                }
            }
        }

        Given("deleting a palette") {
            When("it is not the one in use") {
                val left = BuiltinColorLibrary(listOf(a, b, c), selectedId = "b").without("a")

                Then("the selection is untouched") {
                    left.palettes.map { it.id } shouldBe listOf("b", "c")
                    left.selectedId shouldBe "b"
                }
            }

            When("it is the one in use") {
                val left = BuiltinColorLibrary(listOf(a, b, c), selectedId = "b").without("b")

                Then("the selection moves to what is left rather than dangling") {
                    left.palettes.map { it.id } shouldBe listOf("a", "c")
                    left.selectedId shouldBe "a"
                }
            }

            When("it is the last one") {
                val left = BuiltinColorLibrary(listOf(a), selectedId = "a").without("a")

                Then("the library is empty, and falls back to the shipped palette") {
                    // Deleting the last palette is allowed rather than blocked: landing on the
                    // default is a sensible place to be, not an error to prevent.
                    left.palettes shouldBe emptyList()
                    left.selectedId shouldBe ""
                    left.current() shouldBe BuiltinColorDefaults.DEFAULT
                }
            }
        }

        Given("the library a fresh install starts from") {
            Then("it holds the shipped palette, selected") {
                val library = defaultColorLibrary()
                library.palettes.map { it.id } shouldBe listOf(BuiltinColorDefaults.DEFAULT_ID)
                library.selectedId shouldBe BuiltinColorDefaults.DEFAULT_ID
                library.current() shouldBe BuiltinColorDefaults.DEFAULT
            }
        }
    })
