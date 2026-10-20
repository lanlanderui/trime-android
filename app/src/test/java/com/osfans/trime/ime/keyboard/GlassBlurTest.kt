/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The separable box blur behind the frosted backdrop.
 *
 * What matters here is only the shape of the maths: a flat pane has to stay flat (or the panel
 * would show its own edges through the frost), a single point has to spread without gaining or
 * losing light (or the pane would get brighter or darker wherever the image has detail), and a
 * radius that arrives as zero -- dp times a density that rounded down -- has to be a copy rather
 * than a division by a kernel of nothing.
 */
class GlassBlurTest :
    StringSpec({
        fun buffer(
            w: Int,
            h: Int,
            color: Int,
        ) = IntArray(w * h) { color }

        val translucentGrey = 0x80336699.toInt()

        "a flat pane stays flat" {
            val src = buffer(9, 7, translucentGrey)
            val dst = IntArray(src.size)
            GlassBlur.gaussian(src, dst, 9, 7, radius = 3)
            dst.forEach { it shouldBe translucentGrey }
        }

        "a radius below one is a plain copy" {
            val src = buffer(4, 4, translucentGrey).also { it[5] = 0xFFFFFFFF.toInt() }
            val dst = IntArray(src.size)
            GlassBlur.gaussian(src, dst, 4, 4, radius = 0)
            dst.toList() shouldBe src.toList()
        }

        "a buffer without a neighbourhood is a plain copy" {
            val src = buffer(4, 1, translucentGrey)
            val dst = IntArray(src.size)
            GlassBlur.gaussian(src, dst, 4, 1, radius = 3)
            dst.toList() shouldBe src.toList()
        }

        "a point spreads without gaining or losing light" {
            // A single lit pixel in the middle of black. Two 3-wide box passes are one 3x3 box, so
            // every pixel of that neighbourhood carries a ninth of it -- and the total is still the
            // one pixel's worth of light (an averaging kernel moves light, it does not create it).
            val w = 5
            val h = 5
            val src = buffer(w, h, 0xFF000000.toInt()).also { it[2 * w + 2] = 0xFFFFFFFF.toInt() }
            val dst = IntArray(src.size)
            GlassBlur.gaussian(src, dst, w, h, radius = 1)

            val red = { i: Int -> (dst[i] shr 16) and 0xFF }
            red(2 * w + 2) shouldBe 28
            red(2 * w + 1) shouldBe 28
            red(1 * w + 1) shouldBe 28
            red(0) shouldBe 0
            // 9 x 28: the last three units are lost to the truncation in each pass.
            (0 until dst.size).sumOf { red(it) } shouldBe 252
        }

        "the alpha channel is blurred along with the colour" {
            val w = 5
            val h = 5
            val src = buffer(w, h, 0x00000000).also { it[2 * w + 2] = 0xFFFFFFFF.toInt() }
            val dst = IntArray(src.size)
            GlassBlur.gaussian(src, dst, w, h, radius = 1)

            dst[2 * w + 2] ushr 24 shouldBe 28
            // An edge outside the neighbourhood never sees the point at this radius.
            dst[0] shouldBe 0
        }
    })
