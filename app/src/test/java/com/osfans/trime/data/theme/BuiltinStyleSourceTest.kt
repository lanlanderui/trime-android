/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * The size-source gate and the one-time migration that decides where an existing install starts.
 *
 * What the source *means* is small enough to state in one line — the theme alone, or the theme
 * with the stored sizes on top — but it is the one rule two call sites in `ThemeManager` and a
 * settings screen all depend on, so it is pinned here rather than trusted.
 */
class BuiltinStyleSourceTest :
    BehaviorSpec({
        val theme = ThemeTestSupport.decodeBuiltinTheme("trime.yaml")
        val stored = mapOf("round_corner" to 9f, "shadow_radius" to 0f)

        Given("the size source is the theme's own") {
            Then("the stored sizes are skipped whole, and the theme itself comes back") {
                patchedStyle(theme, stored, overridesInCharge = false) shouldBeSameInstanceAs theme
            }

            Then("the values the theme declares are what a reader sees") {
                val patched = patchedStyle(theme, stored, overridesInCharge = false)
                patched.generalStyle.roundCorner shouldBe theme.generalStyle.roundCorner
                patched.generalStyle.shadowRadius shouldBe theme.generalStyle.shadowRadius
            }
        }

        Given("the size source is the built-in settings") {
            Then("each stored size lands on the theme, and only the sizes stored") {
                val patched = patchedStyle(theme, stored, overridesInCharge = true)
                patched.generalStyle.roundCorner shouldBe 9f
                // A stored zero must survive as a zero: "0" is exactly the override that is
                // supposed to win over whatever the theme declares.
                patched.generalStyle.shadowRadius shouldBe 0f
                patched.generalStyle.keyTextSize shouldBe theme.generalStyle.keyTextSize
            }

            Then("no stored sizes means the theme passes through, value for value") {
                val patched = patchedStyle(theme, emptyMap(), overridesInCharge = true)
                patched.generalStyle shouldBe theme.generalStyle
            }
        }

        Given("an install that predates the size source") {
            Then("one with stored sizes keeps them in charge, so it keeps its look") {
                migratedStyleSource(hasStoredSizes = true) shouldBe ThemePrefs.StyleSource.BUILTIN
            }

            Then("one with none follows the theme, like a fresh install") {
                migratedStyleSource(hasStoredSizes = false) shouldBe ThemePrefs.StyleSource.THEME
            }
        }
    })
