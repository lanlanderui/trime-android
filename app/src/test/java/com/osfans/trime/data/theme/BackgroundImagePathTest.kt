/*
 * SPDX-FileCopyrightText: 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * How a themed background image's path is assembled.
 *
 * Written as a mirror of the private [ColorManager.resolveImageFilePath], because that function
 * needs a real `DataManager.userDataDir` on disk. Keep the two in step.
 *
 * The case that motivated this: `background_folder` defaults to `"backgrounds"` — the same word as
 * the folder it lives in — so concatenating produced `backgrounds/backgrounds/<file>`, a path no
 * user has ever created. Every themed background silently failed to load, and the only symptom was
 * a keyboard with no picture on it.
 */
class BackgroundImagePathTest :
    BehaviorSpec({
        val root = "/rime"

        /** The candidate paths the resolver considers, in order, for a folder and a file name. */
        fun candidates(
            folder: String,
            value: String,
        ): List<String> {
            val flat = "$root/backgrounds/$value"
            val nested = if (folder == "backgrounds") flat else "$root/backgrounds/$folder/$value"
            return listOf(flat, nested)
        }

        /** The first candidate that exists, or the flat one — what the resolver returns. */
        fun resolve(
            folder: String,
            value: String,
            existing: Set<String>,
        ): String {
            val (flat, nested) = candidates(folder, value)
            return listOf(flat, nested).firstOrNull { it in existing } ?: flat
        }

        Given("the default background folder, which repeats the containing folder's name") {
            Then("no candidate path doubles the segment") {
                // Flat and nested collapse to one path, which is the point: there is no location
                // for a theme author to put a file that the resolver will not look at.
                candidates("backgrounds", "frost_bg.jpg").distinct() shouldBe
                    listOf("$root/backgrounds/frost_bg.jpg")
            }

            Then("the file is found where it was actually pushed") {
                val existing = setOf("$root/backgrounds/frost_bg.jpg")
                resolve("backgrounds", "frost_bg.jpg", existing) shouldBe
                    "$root/backgrounds/frost_bg.jpg"
            }
        }

        Given("a per-scheme folder, which is what background_folder is for") {
            Then("both locations are candidates, flat first") {
                candidates("dark", "a.jpg") shouldBe
                    listOf("$root/backgrounds/a.jpg", "$root/backgrounds/dark/a.jpg")
            }

            Then("the flat file still wins when it exists") {
                // A theme may keep shared art flat and per-scheme art nested; whichever is present
                // should be used, and flat is checked first so adding a folder cannot shadow it.
                val existing = setOf("$root/backgrounds/a.jpg", "$root/backgrounds/dark/a.jpg")
                resolve("dark", "a.jpg", existing) shouldBe "$root/backgrounds/a.jpg"
            }

            Then("the nested file is used when the flat one is absent") {
                val existing = setOf("$root/backgrounds/dark/a.jpg")
                resolve("dark", "a.jpg", existing) shouldBe "$root/backgrounds/dark/a.jpg"
            }
        }

        Given("no candidate exists") {
            Then("the flat path is reported so the log names the obvious location") {
                resolve("dark", "missing.jpg", emptySet()) shouldBe "$root/backgrounds/missing.jpg"
            }

            Then("a doubled path is never reported, since it never exists for anyone") {
                val reported = resolve("backgrounds", "missing.jpg", emptySet())
                (reported.contains("backgrounds/backgrounds")) shouldBe false
            }
        }
    })
