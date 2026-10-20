/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.osfans.trime.data.prefs.AppPrefs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns the keyboard's own backdrop into a frosted pane.
 *
 * A keyboard cannot see the app behind it, so nothing here is a cross-window blur: the material is
 * built from the pieces the keyboard owns. That is the same five-layer recipe GlassKit uses, with
 * the backdrop pointed at the one thing this module actually has -- the theme's keyboard background:
 *
 * 1. backdrop: the theme's `keyboard_background` image, or the back colour when it has none;
 * 2. blur: two box passes on a third-size copy ([GlassBlur]);
 * 3. tint: the back colour, lightened a touch, at [ALPHA] -- which is also what lets the app show
 *    through the panel;
 * 4. edge and 5. grain: already painted by [KeyboardGlass] on top of this.
 *
 * With no image the first two layers collapse into the flat colour, and what is left is a
 * translucent, faintly milky pane -- which is what a solid-colour theme can honestly become.
 *
 * [strength] scales all of it at once: how much colour is left, how much white goes into it, how
 * hard the image is blurred and how much saturation is put back. 100 % is the tuned look, 0 % is
 * the theme's own opaque colour (the effect driven to nothing), and 200 % is as see-through as the
 * range goes.
 */
internal object FrostedBackdrop {
    /** Pane opacity at 100 %: the rest is the app showing through. */
    const val ALPHA = 0.72f

    /** White mixed into the back colour at 100 %: the "milk" that makes a flat pane read as frost. */
    const val TINT_WHITE = 0.06f

    /** Blur radius in dp at 100 %, measured on the panel. GlassKit's useful range is 12-30. */
    const val BLUR_RADIUS_DP = 18f

    /** The blur runs on a copy this fraction of the panel's size: a tenth of the pixels. */
    const val SCALE = 0.30f

    /** Saturation put back at 100 %: blurring greys a picture out, this restores the colour. */
    const val SATURATION = 1.35f

    /** Floor of the computed opacity, so a hand-edited value cannot dissolve the panel entirely. */
    private const val MIN_ALPHA = 0.30f

    private const val PERCENT = 100f

    /**
     * The share of the effect to paint, where 1 is 100 %.
     *
     * Zero means the switch is off, so callers test one number instead of two. Read live on every
     * use: these are in-memory map lookups, and the surfaces that consume it are rebuilt through
     * [com.osfans.trime.ime.core.InputView.refreshKeyboardBackdrop] when the value changes.
     */
    fun strength(): Float {
        val prefs = prefs
        if (!prefs.glassBackdrop.getValue()) return 0f
        return prefs.glassBackdropStrength.getValue()
            .coerceIn(0, AppPrefs.Keyboard.GLASS_BACKDROP_STRENGTH_MAX) / PERCENT
    }

    /**
     * Opacity of the pane for [strength], 0-255.
     *
     * Linear in the strength: 0 % is the theme's opaque colour, 100 % is [ALPHA], and both ends
     * move by the same amount for the same step -- so dragging the slider fades the pane instead of
     * stepping it.
     */
    fun alpha(strength: Float): Int {
        val faded = 1f - (1f - ALPHA) * strength
        return (255 * faded.coerceIn(MIN_ALPHA, 1f)).roundToInt()
    }

    /** Blur radius in buffer pixels for [strength]; zero below a radius that would not change a pixel. */
    fun blurRadiusPx(
        density: Float,
        strength: Float,
    ): Int = (BLUR_RADIUS_DP * density * SCALE * strength).roundToInt().coerceAtLeast(0)

    /** Saturation multiplier for [strength]: no boost at 0 %, [SATURATION] at 100 %. */
    fun saturation(strength: Float): Float = 1f + (SATURATION - 1f) * strength

    /**
     * The colour the panel is painted with: the theme's back colour, lightened by [TINT_WHITE] and
     * made translucent, both scaled by [strength].
     *
     * The alpha is capped rather than overwritten, so a theme that was already more transparent
     * than this keeps its own value -- the switch is there to frost an opaque keyboard, not to make
     * a see-through one more solid.
     */
    fun tint(
        baseColor: Int,
        strength: Float,
    ): Int {
        val alpha = minOf(baseColor ushr 24, alpha(strength))
        val lit = blendTowardsWhite(baseColor, TINT_WHITE * strength)
        return (alpha shl 24) or (lit and 0x00FFFFFF)
    }

    /**
     * Moves [color] a [ratio] of the way to white, on the colour channels only.
     *
     * Written out rather than taken from `ColorUtils`: that helper reaches for `Color.alpha`, which
     * is not mocked in JVM unit tests, and this is three multiplications.
     */
    private fun blendTowardsWhite(
        color: Int,
        ratio: Float,
    ): Int {
        val keep = 1f - ratio
        fun channel(shift: Int): Int =
            (((color shr shift) and 0xFF) * keep + 255f * ratio).roundToInt().coerceIn(0, 255)
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** A blurring wrapper around the theme's background image, tinted with [baseColor]. */
    fun drawable(
        source: Drawable,
        baseColor: Int,
        density: Float,
        strength: Float,
    ): Drawable = FrostedBackdropDrawable(
        source = source,
        paneColor = tint(baseColor, strength) or 0xFF000000.toInt(),
        paneAlpha = alpha(strength),
        blurRadiusPx = blurRadiusPx(density, strength),
        saturation = saturation(strength),
    )

    private val prefs by lazy { AppPrefs.defaultInstance().keyboard }
}

/**
 * Draws [source] blurred, on a downscaled buffer, at the pane's opacity.
 *
 * The buffer is filled with the pane colour first, so the blur never sees transparent pixels --
 * averaging an alpha channel separately from the colour channels would otherwise ring dark halos
 * around the cut-out edges of an image.
 *
 * The bitmap is rebuilt only when the bounds change (a rotation, a keyboard resize). The strength
 * arrives through the constructor instead, because a different strength is a different drawable:
 * the radius and the saturation are baked into the filter, and re-blurring a panel that is already
 * on screen would buy nothing.
 */
internal class FrostedBackdropDrawable(
    private val source: Drawable,
    private val paneColor: Int,
    private val paneAlpha: Int,
    private val blurRadiusPx: Int,
    saturation: Float,
) : Drawable() {

    private val saturationFilter = ColorMatrixColorFilter(
        ColorMatrix().apply { setSaturation(saturation) },
    )

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = saturationFilter
        alpha = paneAlpha
    }

    private val dst = RectF()
    private val sourceBounds = Rect()

    private var bitmap: Bitmap? = null
    private var builtWidth = 0
    private var builtHeight = 0

    /** What [setAlpha] was given; folded into the pane's own opacity rather than replacing it. */
    private var viewAlpha = 255

    override fun draw(canvas: Canvas) {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return
        if (bitmap == null || w != builtWidth || h != builtHeight) rebuild(w, h)
        val pane = bitmap ?: return
        dst.set(bounds)
        canvas.drawBitmap(pane, null, dst, paint)
    }

    private fun rebuild(
        width: Int,
        height: Int,
    ) {
        builtWidth = width
        builtHeight = height

        val sw = max(1, (width * FrostedBackdrop.SCALE).roundToInt())
        val sh = max(1, (height * FrostedBackdrop.SCALE).roundToInt())
        val scaled = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaled)
        // Opaque, so the blur averages four real channels; the pane colour is also what an image
        // with holes in it should show through.
        canvas.drawColor(paneColor)
        sourceBounds.set(source.bounds)
        source.setBounds(0, 0, sw, sh)
        source.draw(canvas)
        source.bounds = sourceBounds

        if (blurRadiusPx > 0) {
            val pixels = IntArray(sw * sh)
            scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            val blurred = IntArray(sw * sh)
            GlassBlur.gaussian(pixels, blurred, sw, sh, blurRadiusPx)
            scaled.setPixels(blurred, 0, sw, 0, 0, sw, sh)
        }

        bitmap?.recycle()
        bitmap = scaled
    }

    override fun setAlpha(alpha: Int) {
        if (viewAlpha == alpha) return
        viewAlpha = alpha
        paint.alpha = (paneAlpha * alpha / 255f).roundToInt()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // Nothing in this build sets one, but the contract is the contract: a caller's filter wins
        // over the saturation boost instead of being silently dropped.
        paint.colorFilter = colorFilter ?: saturationFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
