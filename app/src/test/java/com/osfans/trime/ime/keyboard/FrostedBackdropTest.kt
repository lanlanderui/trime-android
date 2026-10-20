/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * The strength dial behind the frosted backdrop.
 *
 * One number drives four things at once -- opacity, the white in the tint, the blur radius and the
 * saturation -- and the two ends are what can silently break: at 0 % the pane has to be the theme's
 * own opaque colour (nothing changed), and at the top of the range it must not go so transparent
 * that the keyboard cannot be read.
 */
class FrostedBackdropTest :
    StringSpec({
        val darkBack = 0xFF383635.toInt()

        "zero strength leaves the theme's colour alone" {
            FrostedBackdrop.alpha(0f) shouldBe 255
            FrostedBackdrop.tint(darkBack, 0f) shouldBe darkBack
            FrostedBackdrop.blurRadiusPx(density = 2.5f, strength = 0f) shouldBe 0
            FrostedBackdrop.saturation(0f) shouldBe 1f
        }

        "the tuned look sits at 100 %" {
            FrostedBackdrop.alpha(1f) shouldBe 184
            FrostedBackdrop.saturation(1f) shouldBe (1.35f plusOrMinus 0.0001f)
            // 18 dp at 2.5x density, computed on the 0.3x buffer: 13.5 px, rounded up.
            FrostedBackdrop.blurRadiusPx(density = 2.5f, strength = 1f) shouldBe 14
        }

        "the top of the range is more transparent, not less" {
            FrostedBackdrop.alpha(2f) shouldBe 112
            FrostedBackdrop.alpha(2f) shouldBeLessThan FrostedBackdrop.alpha(1f)
            FrostedBackdrop.blurRadiusPx(density = 2.5f, strength = 2f) shouldBe 27
        }

        "every step fades the pane by the same amount" {
            // Opacity is linear in the strength, which is what makes the slider fade rather than
            // step: the same slider step costs the same number of alpha levels all the way across
            // (7 or 8 of 255 -- the odd one is the rounding, not a curve).
            val steps = (0..20).map { FrostedBackdrop.alpha(it / 10f) }
            steps.first() shouldBe 255
            steps.last() shouldBe 112
            steps.zipWithNext { a, b ->
                (a >= b) shouldBe true
                (a - b) shouldBeInRange 7..8
            }
        }

        "the tint lightens the colour and caps its alpha" {
            val tinted = FrostedBackdrop.tint(darkBack, 1f)
            (tinted ushr 24) shouldBe 184
            ((tinted shr 16) and 0xFF) shouldBeGreaterThan((darkBack shr 16) and 0xFF)
        }

        "a theme that is already see-through keeps its own alpha" {
            val alreadyFaint = 0x20336599
            (FrostedBackdrop.tint(alreadyFaint, 1f) ushr 24) shouldBe 0x20
        }
    })
