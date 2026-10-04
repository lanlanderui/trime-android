/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

// The picker lists every preset of the theme plus the built-in dynamic scheme, and the applied
// one used to be marked by its radio button alone -- which is easy to miss, and which a theme
// without a single-choice item layout does not draw at all.
class ColorSchemeRowLabelTest :
    BehaviorSpec({
        Given("a color picker row for the scheme that is applied") {
            Then("the row is ticked in front of the name") {
                colorSchemeRowLabel("靛夜星河 / Indigo Night", active = true) shouldBe
                    "✓ 靛夜星河 / Indigo Night"
            }

            Then("the tick comes first, since a long name wraps onto a second line") {
                val long = "Material You 收藏 2026-09-28 00:26:36（浅色）"
                colorSchemeRowLabel(long, active = true).startsWith("✓ ") shouldBe true
            }
        }

        Given("a color picker row for any other scheme") {
            Then("the name is left exactly as the theme wrote it") {
                colorSchemeRowLabel("动态配色", active = false) shouldBe "动态配色"
            }
        }

        Given("two rows that carry the same scheme name") {
            Then("only the applied one differs, which is what tells them apart") {
                val name = "Material You（浅色）"
                (colorSchemeRowLabel(name, active = true) != colorSchemeRowLabel(name, active = false)) shouldBe
                    true
            }
        }
    })
