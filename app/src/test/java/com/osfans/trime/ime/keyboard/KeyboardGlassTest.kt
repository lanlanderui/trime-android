/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * How a colour's alpha survives the glass-strength setting.
 *
 * The veneer's gradients carry their alphas baked in, so "make it stronger" means rewriting those
 * alphas -- and the one way that can go wrong is silent: an alpha that is doubled past 255 wraps
 * around and paints a *transparent* edge where an opaque one was asked for. That is what these
 * cases pin down.
 */
class KeyboardGlassTest :
    StringSpec({
        val halfTranslucentWhite = 0x80FFFFFF.toInt()

        "full strength leaves the tuned colour alone" {
            KeyboardGlass.scaleAlpha(halfTranslucentWhite, 1f) shouldBe halfTranslucentWhite
        }

        "strength scales only the alpha, never the colour" {
            KeyboardGlass.scaleAlpha(halfTranslucentWhite, 0.5f) shouldBe 0x40FFFFFF
            KeyboardGlass.scaleAlpha(0x12000000, 0.5f) shouldBe 0x09000000
            KeyboardGlass.scaleAlpha(0x1F000000, 2f) shouldBe 0x3E000000
        }

        "zero paints nothing at all" {
            // Zero alpha, whatever the colour was: only the top byte is touched, so the channels
            // still describe the colour the gradient's transparent end was written with.
            KeyboardGlass.scaleAlpha(halfTranslucentWhite, 0f) shouldBe 0x00FFFFFF
            KeyboardGlass.scaleAlpha(0x66FFFFFF, 0f) shouldBe 0x00FFFFFF
            KeyboardGlass.scaleAlpha(0x66FF0000, 0f) shouldBe 0x00FF0000
        }

        "a doubled alpha stops at opaque instead of wrapping around" {
            // 0x80 * 2 = 256, which would be 0 once masked into a byte -- i.e. an invisible edge.
            KeyboardGlass.scaleAlpha(halfTranslucentWhite, 2f) shouldBe 0xFFFFFFFF.toInt()
            KeyboardGlass.scaleAlpha(0x66FFFFFF, 2f) shouldBe 0xCCFFFFFF.toInt()
            KeyboardGlass.scaleAlpha(0x3D000000, 2f) shouldBe 0x7A000000
        }

        "the clamp holds however far the stored value is pushed" {
            KeyboardGlass.scaleAlpha(0x66FFFFFF, 5f) shouldBe 0xFFFFFFFF.toInt()
            KeyboardGlass.scaleAlpha(0x1CFFFFFF, 10f) shouldBe 0xFFFFFFFF.toInt()
        }
    })
