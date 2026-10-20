/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.osfans.trime.data.prefs.AppPrefs
import java.util.Random

/**
 * The "frosted glass" veneer.
 *
 * This is deliberately *not* a blur. An IME window cannot sample the app behind it, and the only
 * platform blur that exists frosts the whole window rather than the panel (tried, removed). So what
 * is painted here is the set of cues that make a surface read as etched glass, all of which are
 * local to the keyboard:
 *
 * - a faint vertical sheen: a lit top fading into a slightly shaded bottom, i.e. thickness;
 * - fine grain: real frosted glass is never mathematically smooth, and grain also kills the
 *   banding you get when a translucent colour is stretched over a large area;
 * - a lit top edge: the one line that separates "translucent rectangle" from "pane".
 *
 * Keys get the same treatment in [KeyGlassCap], which is what makes them read as glass caps
 * instead of flat tiles.
 *
 * Everything here is additive and colour-agnostic: it works on light themes (where the highlights
 * nearly disappear) and on dark ones (where they carry the effect), and it never touches the
 * theme's own colours.
 *
 * How opaque all of that ends up is one number, [strength]: the alphas below were tuned at
 * 100 %, and the setting scales every one of them, so the same material can stay a hint on a light
 * theme or be pushed until it is unmistakable on a dark one.
 */
internal object KeyboardGlass {
    /**
     * The share of the tuned look to paint, where 1 is 100 %.
     *
     * Zero means the switch is off, so callers test one number instead of two. Read live on every
     * draw: these are in-memory map lookups, and no painter here has a rebuild hook of its own --
     * a settings change only owes the surfaces a repaint.
     */
    fun strength(): Float {
        val prefs = prefs
        if (!prefs.glassKeyboard.getValue()) return 0f
        return prefs.glassStrength.getValue()
            .coerceIn(0, AppPrefs.Keyboard.GLASS_STRENGTH_MAX) / PERCENT
    }

    /**
     * Scales the alpha of an ARGB [color] by [strength], clamping at opaque.
     *
     * The gradients bake their alphas in, and a paint's own alpha can only take a colour *down*
     * from what the shader says -- it cannot push one past it. So a strength above 100 % has to be
     * applied where the shader is built, and the clamp is what keeps a doubled edge from wrapping
     * around into a transparent one.
     */
    fun scaleAlpha(color: Int, strength: Float): Int {
        val alpha = ((color ushr 24) * strength).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    /** Paint alpha of the panel grain at 100 %, before [strength] is applied. */
    const val PANEL_GRAIN = 0.045f

    /** Paint alpha of the key grain at 100 %: lighter than the panel, so a 40 dp cap is not sandpaper. */
    const val KEY_GRAIN = 0.035f

    /**
     * 96 x 96 of uniform noise, shared by every panel and key.
     *
     * Seeded, so the grain is identical across launches -- a texture that reshuffles itself after
     * every restart looks like a rendering fault rather than a material.
     */
    val grain: Bitmap by lazy {
        val size = 96
        val pixels = IntArray(size * size)
        val random = Random(7)
        for (i in pixels.indices) {
            val v = random.nextInt(256)
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private const val PERCENT = 100f

    private val prefs by lazy { AppPrefs.defaultInstance().keyboard }
}

/**
 * Paints the panel-level half of the veneer.
 *
 * Sits above the keyboard's background image and above the input bar -- the bar draws its own fill
 * and its candidates, and the grain has to reach them too -- but below the keys, so nothing that
 * has to stay crisp is ever covered by a texture.
 *
 * Only the cues live here. The pane's fill and its blur belong to the backdrop image view underneath
 * ([FrostedBackdrop]); washing the candidate text with an opaque tint from above was exactly the
 * unreadable bar this layer used to cost.
 */
internal class GlassPanelView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private var builtWidth = 0
    private var builtHeight = 0
    private var builtStrength = 0f

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        grainPaint.shader = BitmapShader(
            KeyboardGlass.grain,
            Shader.TileMode.REPEAT,
            Shader.TileMode.REPEAT,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val strength = KeyboardGlass.strength()
        if (strength <= 0f) return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        // Built here rather than in onSizeChanged: the strength can change while the size stays,
        // and both are baked into the gradients. The guard makes this a no-op on every other frame.
        if (w != builtWidth || h != builtHeight || strength != builtStrength) {
            builtWidth = w
            builtHeight = h
            builtStrength = strength
            rebuild(w, h, strength)
        }

        val wf = w.toFloat()
        val hf = h.toFloat()

        // Enough to see as tooth on a flat colour, not enough to read as noise.
        grainPaint.alpha = (255 * KeyboardGlass.PANEL_GRAIN * strength).toInt().coerceIn(0, 255)

        canvas.drawRect(0f, 0f, wf, hf, sheenPaint)
        canvas.drawRect(0f, 0f, wf, hf, grainPaint)
        canvas.drawLine(0f, edgePaint.strokeWidth / 2f, wf, edgePaint.strokeWidth / 2f, edgePaint)
    }

    private fun rebuild(w: Int, h: Int, strength: Float) {
        val hf = h.toFloat()

        // Lit at the top, neutral through the middle, a touch of weight at the bottom.
        // Both ends are present on purpose: a white-only sheen disappears on light themes, and a
        // dark-only one disappears on dark themes. Carrying a little of each keeps the material
        // readable whichever way the colour scheme goes.
        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(0x12FFFFFF, 0x06FFFFFF, 0x00000000, 0x12000000).map {
                KeyboardGlass.scaleAlpha(it, strength)
            }.toIntArray(),
            floatArrayOf(0f, 0.15f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        edgePaint.shader = LinearGradient(
            0f, 0f, 0f, hf * 0.30f,
            intArrayOf(0x66FFFFFF, 0x00FFFFFF).map {
                KeyboardGlass.scaleAlpha(it, strength)
            }.toIntArray(),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
}

/**
 * Paints the key-level half: a soft top-lit fill plus a bevelled edge, clipped to whatever shape
 * the theme already drew for the key.
 *
 * The gradients live in key-local coordinates and are rebuilt only when the key changes size or the
 * strength changes, so drawing a key costs two rounded-rect fills and no allocations.
 */
internal class KeyGlassCap {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(KeyboardGlass.grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val local = RectF()

    private var builtWidth = 0
    private var builtHeight = 0
    private var builtStrength = 0f

    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        pressed: Boolean,
        density: Float,
        strength: Float,
    ) {
        val w = (right - left).toInt()
        val h = (bottom - top).toInt()
        if (w <= 0 || h <= 0) return
        if (w != builtWidth || h != builtHeight || strength != builtStrength) {
            builtWidth = w
            builtHeight = h
            builtStrength = strength
            rebuild(w, h, density, strength)
        }

        // A pressed key sits in its socket: the lit edge dims, the fill flattens. Applied as the
        // paint's own alpha so it rides on top of whatever the shader was built with.
        val alpha = if (pressed) 150 else 255
        fillPaint.alpha = alpha
        edgePaint.alpha = alpha
        grainPaint.alpha = (255 * KeyboardGlass.KEY_GRAIN * strength).toInt().coerceIn(0, 255)

        canvas.save()
        canvas.translate(left, top)
        local.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawRoundRect(local, radius, radius, fillPaint)
        canvas.drawRoundRect(local, radius, radius, grainPaint)
        canvas.drawRoundRect(local, radius, radius, edgePaint)
        canvas.restore()
    }

    private fun rebuild(
        w: Int,
        h: Int,
        density: Float,
        strength: Float,
    ) {
        val hf = h.toFloat()
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, hf * 0.60f,
            intArrayOf(0x1CFFFFFF, 0x00FFFFFF).map {
                KeyboardGlass.scaleAlpha(it, strength)
            }.toIntArray(),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        edgePaint.strokeWidth = 1f * density
        // Lit top, dark underside: the pair reads as a bevelled edge on white keys *and* on black
        // ones, where either cue alone would vanish.
        edgePaint.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(0x66FFFFFF, 0x0FFFFFFF, 0x1F000000, 0x3D000000).map {
                KeyboardGlass.scaleAlpha(it, strength)
            }.toIntArray(),
            floatArrayOf(0f, 0.42f, 0.86f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
}
