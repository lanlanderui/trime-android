/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils.setAlphaComponent
import com.google.android.material.color.MaterialColors
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.util.sp
import splitties.dimensions.dp
import kotlin.math.abs

/**
 * The keyboard itself, drawn from the layout the IME is using, with keys you can tap.
 *
 * This is not a picture of a keyboard: it is built from a real [Keyboard] — the same class the IME
 * instantiates — so every position, width, gap and corner here comes from the same arithmetic that
 * produced the keyboard on screen. A theme that reflows its keys or widens the space bar looks
 * wrong in a hand-drawn mock and right in this.
 *
 * What it does *not* do is type. Touch is turned into "which key is under the finger", and the
 * caller decides what a tap means; the keyboard's own press handling, popups and modifiers are
 * never involved. That is the whole point of the screen it lives on: the same pixels, one different
 * verb.
 *
 * Colours are read at draw time through [Key]'s own getters, so the per-key colours the user picks
 * — which live in that getter's resolution chain — show up here and on the keyboard behind this
 * screen at the same moment, with no second copy to keep in step.
 */
@SuppressLint("ClickableViewAccessibility")
class KeyColorBoardView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        private val line =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
            }

        private val labelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
            }

        private val symbolPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
            }

        /** The layout to draw. Setting it re-measures, because its height is the keyboard's. */
        var keyboard: Keyboard? = null
            set(value) {
                field = value
                requestLayout()
                invalidate()
            }

        /** The label and symbol sizes the theme draws keys with, in sp. */
        var keyTextSize = 0f
        var symbolTextSize = 0f

        /** Key indices that are currently picked. */
        private var selected: Set<Int> = emptySet()

        /**
         * Told when a key is tapped, or when the background is.
         *
         * [longPress] distinguishes "add this one" from "only this one": the second is what a user
         * reaches for when the selection has grown and they want to start over without hunting for
         * the clear button.
         */
        var onKeyTapped: ((index: Int, longPress: Boolean) -> Unit)? = null

        /** Told when the tap landed on the keyboard but not on any key. */
        var onBackgroundTapped: (() -> Unit)? = null

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var longPressFired = false
        private var movedAway = false

        /**
         * The colour of a picked key's outline: the app's primary, read once.
         *
         * A draw calls this for every key in the selection, and the answer cannot change while the
         * screen is up — the activity is recreated on a light/dark switch.
         */
        private val accent: Int by lazy {
            MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        }

        private val accentWash: Int get() = setAlphaComponent(accent, 0x33)

        fun setSelected(indices: Set<Int>) {
            // Copied rather than held by reference. The caller passes its own live selection set,
            // so storing that instance made the comparison below compare it with itself: always
            // equal, which meant every pick after the first skipped the repaint and the new key
            // was selected in the model without ever being drawn as selected.
            val snapshot = indices.toSet()
            if (selected == snapshot) return
            selected = snapshot
            invalidate()
        }

        /**
         * The height the keyboard wants, in pixels.
         *
         * Exposed so the screen can reserve room for this view before it is laid out — the
         * keyboard is built from the width, and the width is only known after layout, so the two
         * cannot be measured in one pass.
         */
        val keyboardHeightPx: Int get() = keyboard?.height ?: 0

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            // The keyboard's own height, not a guess: this view exists to show the keyboard as it
            // is, and a scaled-down copy would make the key shapes and the gaps between them a
            // different size from the ones the colours are being chosen for.
            val wanted = keyboard?.height?.takeIf { it > 0 } ?: dp(DEFAULT_HEIGHT_DP)
            setMeasuredDimension(width, wanted)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val kb = keyboard ?: return
            if (width <= 0 || height <= 0) return

            // The background the keys sit on, painted first and painted whole: it is the one area
            // on this screen a tap cannot reach, so the only way to judge its colour is to see it
            // exactly as it will look behind the keys.
            fill.color = runCatching { ColorManager.getColor("keyboard_back_color") }
                .getOrDefault(Color.TRANSPARENT)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)

            kb.keys.forEach { key ->
                drawKey(canvas, kb, key)
            }
        }

        private fun drawKey(
            canvas: Canvas,
            kb: Keyboard,
            key: Key,
        ) {
            val left = key.x + kb.horizontalGap / 2f + key.extraWidthLeft
            val top = key.y + kb.verticalGap / 2f
            val right = key.x + key.width - kb.horizontalGap / 2f - key.extraWidthRight
            val bottom = key.y + key.height - kb.verticalGap / 2f
            if (right <= left || bottom <= top) return

            val background = runCatching { key.getBackgroundDrawable() }.getOrNull()
            if (background != null) {
                // Same treatment KeyView gives it: the drawable only carries the colour, while the
                // shape and the outline come from the key's own geometry.
                if (background is GradientDrawable) {
                    if (key.roundCorner > 0f) background.cornerRadius = dp(key.roundCorner)
                    if (key.keyBorder > 0) {
                        background.setStroke(dp(key.keyBorder), key.getBorderColor())
                    }
                }
                background.setBounds(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
                background.draw(canvas)
            } else {
                // A key whose background resolved to nothing is still a key: draw its outline so
                // the tap target is visible, and let the colour be judged from the border alone.
                line.color = runCatching { key.getBorderColor() }.getOrDefault(Color.GRAY)
                line.strokeWidth = dp(1f)
                canvas.drawRect(left, top, right, bottom, line)
            }

            val band = bottom - top
            val label = labelOf(key)
            if (label.isNotEmpty()) {
                labelPaint.color = runCatching { key.getTextColor() }.getOrDefault(Color.BLACK)
                // Capped at the row height it has to live in. A theme may shave one row down to
                // half the others (this one does, for its function strip), and a label drawn at
                // the theme's size would then bleed over the row above — legible on the keyboard
                // only because there the strip sits at the top edge of the screen, which is not
                // true here. The cap is deliberately loose: it is there to stop a 60sp label
                // spilling out of a 20px row, not to second-guess a size the user just chose.
                val size = sp(key.keyTextSize.takeIf { it > 0f } ?: keyTextSize)
                labelPaint.textSize = minOf(size, band * 0.9f)
                val metrics = labelPaint.fontMetrics
                val baseline = (top + bottom) / 2f - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(
                    label,
                    (left + right) / 2f + sp(key.keyTextOffsetX),
                    baseline + sp(key.keyTextOffsetY),
                    labelPaint,
                )
            }

            val symbol = runCatching { key.symbolLabel }.getOrDefault("")
            if (symbol.isNotEmpty()) {
                symbolPaint.color = runCatching { key.getSymbolColor() }.getOrDefault(Color.GRAY)
                symbolPaint.textSize = minOf(sp(key.symbolTextSize.takeIf { it > 0f } ?: symbolTextSize), band * 0.28f)
                val metrics = symbolPaint.fontMetrics
                canvas.drawText(
                    symbol.substringBefore('\n'),
                    (left + right) / 2f + sp(key.keySymbolOffsetX),
                    top - metrics.top + sp(key.keySymbolOffsetY),
                    symbolPaint,
                )
            }

            if (key.hint.isNotEmpty()) {
                symbolPaint.color = runCatching { key.getSymbolColor() }.getOrDefault(Color.GRAY)
                symbolPaint.textSize = minOf(sp(key.symbolTextSize.takeIf { it > 0f } ?: symbolTextSize), band * 0.28f)
                val metrics = symbolPaint.fontMetrics
                canvas.drawText(
                    key.hint.substringBefore('\n'),
                    (left + right) / 2f + sp(key.keyHintOffsetX),
                    bottom - metrics.bottom + sp(key.keyHintOffsetY),
                    symbolPaint,
                )
            }

            if (key.index in selected) {
                val radius = dp(if (key.roundCorner > 0f) key.roundCorner else 5f)
                fill.color = accentWash
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, fill)
                line.color = accent
                line.strokeWidth = dp(2.5f)
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, line)
            }
        }

        /**
         * The label a key shows, read without asking rime.
         *
         * [Key.getLabel] consults the rime status — correctly, since it has to pick between the
         * Chinese and ASCII labels — but this screen can open with the IME never having run. The
         * theme's own label is what the layout says and is what this screen is here to colour;
         * the action's label is the fallback for keys that carry none.
         */
        private fun labelOf(key: Key): String {
            val own = key.config?.label.orEmpty()
            if (own.isNotEmpty()) {
                // `enter_labels` is the theme's placeholder for "whatever the current app calls
                // this key"; the real label is supplied by the IME, which is not running here.
                // A return arrow says the same thing without inventing a language.
                return if (own == "enter_labels") RETURN_GLYPH else own
            }
            val layout = keyboard ?: return ""
            val fallback = runCatching { key.click?.getLabel(layout) }.getOrNull().orEmpty()
            return if (fallback == "enter_labels") RETURN_GLYPH else fallback
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    longPressFired = false
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    // A finger that is travelling is scrolling or pointing somewhere else, not
                    // holding a key down; either way the tap it started must not fire.
                    if (moved(event.x, event.y)) {
                        removeCallbacks(longPress)
                        movedAway = true
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    if (longPressFired || movedAway || moved(event.x, event.y)) {
                        movedAway = false
                        return true
                    }
                    val key = keyAt(event.x, event.y)
                    if (key != null) {
                        performClick()
                        onKeyTapped?.invoke(key.index, false)
                    } else {
                        onBackgroundTapped?.invoke()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    movedAway = false
                    longPressFired = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun moved(
            x: Float,
            y: Float,
        ): Boolean = abs(x - downX) > touchSlop || abs(y - downY) > touchSlop

        /**
         * "Only this key", for a selection that has grown past what the user wanted.
         *
         * Deliberately not a second gesture on the panel: the key under the finger is the thing
         * being narrowed down to, and reaching for a button first would lose that.
         */
        private val longPress =
            Runnable {
                val key = keyAt(downX, downY) ?: return@Runnable
                longPressFired = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onKeyTapped?.invoke(key.index, true)
            }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        /** The key under a point, or null for the background. No two keys overlap, and a tap in
         *  the gap between two is allowed to miss both. */
        private fun keyAt(
            x: Float,
            y: Float,
        ): Key? {
            val kb = keyboard ?: return null
            // A plain rectangle test, not [Key.isInside]: that one deliberately extends an edge key
            // outwards to the screen edge so a thumb can reach it, and on a screen whose job is
            // "tap this one" that would hand every tap near the edge to the outermost key.
            return kb.keys.firstOrNull { key ->
                x >= key.x && x < key.x + key.width &&
                    y >= key.y && y < key.y + key.height
            }
        }

        private companion object {
            const val DEFAULT_HEIGHT_DP = 220
            const val RETURN_GLYPH = "\u23CE"
        }
    }
