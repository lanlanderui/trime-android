/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

/**
 * A two-pass separable box blur over ARGB_8888 pixels.
 *
 * Ported from GlassKit's `Blur.java` (the module this keyboard's frosted backdrop is modelled on).
 * Two box passes are already indistinguishable from a gaussian at the radii a frosted panel uses,
 * and a sliding window makes each pass linear in the pixel count rather than `radius × pixels`.
 *
 * It runs on a downscaled copy of the backdrop -- a third of the panel's size -- so the cost is a
 * few milliseconds once per size change, not per frame.
 */
internal object GlassBlur {
    /**
     * Blurs [src] into [dst]; both are `width * height` pixels laid out row by row.
     *
     * A radius below one, or a buffer too small to have a neighbourhood, is a plain copy: callers
     * pass a radius derived from dp and density, and a zero would otherwise divide the kernel into
     * nonsense.
     */
    fun gaussian(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        if (radius < 1 || width < 2 || height < 2) {
            src.copyInto(dst, 0, 0, minOf(src.size, dst.size))
            return
        }
        val tmp = IntArray(width * height)
        passHorizontal(src, tmp, width, height, radius)
        passVertical(tmp, dst, width, height, radius)
    }

    private fun clamp(
        v: Int,
        lo: Int,
        hi: Int,
    ): Int = if (v < lo) lo else if (v > hi) hi else v

    private fun passHorizontal(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val inv = 1f / (2 * radius + 1)
        for (y in 0 until height) {
            val row = y * width
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val c = src[row + clamp(i, 0, width - 1)]
                a += c ushr 24
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            for (x in 0 until width) {
                dst[row + x] = ((a * inv).toInt() shl 24) or
                    ((r * inv).toInt() shl 16) or
                    ((g * inv).toInt() shl 8) or
                    (b * inv).toInt()
                val add = src[row + clamp(x + radius + 1, 0, width - 1)]
                val sub = src[row + clamp(x - radius, 0, width - 1)]
                a += (add ushr 24) - (sub ushr 24)
                r += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    private fun passVertical(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ) {
        val inv = 1f / (2 * radius + 1)
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val c = src[clamp(i, 0, height - 1) * width + x]
                a += c ushr 24
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            for (y in 0 until height) {
                dst[y * width + x] = ((a * inv).toInt() shl 24) or
                    ((r * inv).toInt() shl 16) or
                    ((g * inv).toInt() shl 8) or
                    (b * inv).toInt()
                val add = src[clamp(y + radius + 1, 0, height - 1) * width + x]
                val sub = src[clamp(y - radius, 0, height - 1) * width + x]
                a += (add ushr 24) - (sub ushr 24)
                r += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
