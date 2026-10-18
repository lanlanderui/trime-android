/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils.setAlphaComponent
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.keyboard.KeyTextShadow

/**
 * A miniature of the keyboard, painted from the colours the keyboard is using right now.
 *
 * It exists because choosing a colour in a list and then going back to the keyboard to look at it
 * is the loop this screen was built to remove: the palette is a set of roles, and a role only
 * means something once it is seen next to the roles it has to live with.
 *
 * Every value is read at draw time through [ColorManager], so the preview is live by construction
 * — no snapshot to keep in step, no second place that could disagree with the keyboard. What it
 * does *not* reproduce is geometry the theme computes from the screen (row heights, the split and
 * floating keyboards); the rows, the key widths and the labels are the theme's own, and that is
 * what a colour preview is read for.
 */
class BuiltinColorPreviewView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
            }
        private val text =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
            }

        /** The key kinds the theme's labels identify, kept so the preview can show both states. */
        private var rows: List<PreviewRow> = emptyList()

        /** The keyboard's own corner/border, or null when it leaves them to the style. */
        private var keyboardRoundCorner: Float? = null
        private var keyboardKeyBorder: Int? = null

        /**
         * Height of one key row on the real keyboard, in pixels; 0 when unknown.
         *
         * The preview is a fraction of the keyboard's size, but dp values are not: a 12 dp corner
         * drawn literally on a 16 dp-high preview key turns it into a pill, while the same key on
         * the keyboard is a rounded rectangle. Scaling every dp by the row-height ratio is what
         * makes the shapes agree.
         */
        private var referenceRowHeightPx = 0f

        /**
         * The real keyboard's height-to-width ratio, or 0 when unknown.
         *
         * The preview keeps the keyboard's proportions instead of a fixed guess: a theme can be
         * short and wide or tall and narrow, and a preview that always comes out squat is exactly
         * the "this is not my keyboard" the preview exists to avoid.
         */
        private var keyboardAspect = 0f
        private var roundCorner = 5f
        private var keyBorder = 0
        private var shadowRadiusDip = 0f
        private var keyTextScale = 1f
        private var candidateTextScale = 1f
        private var pressedLetter: Pair<Int, Int>? = null
        private var pressedFunction: Pair<Int, Int>? = null
        private var toggledKey: Pair<Int, Int>? = null

        private val density get() = resources.displayMetrics.density

        /**
         * Sets the keyboard to draw. Called once per theme, not per colour edit.
         *
         * The two demo keys the preview paints as pressed, and the one it paints as toggled, are
         * picked here rather than in the drawing loop so a repaint does not re-scan the keyboard.
         */
        fun setKeyboard(
            keyboard: TextKeyboard?,
            presetLabels: Map<String, String> = emptyMap(),
        ) {
            rows = keyboard?.let { previewRows(it, presetLabels) }.orEmpty()
            keyboardRoundCorner = keyboard?.roundCorner?.takeIf { it >= 0f }
            keyboardKeyBorder = keyboard?.keyBorder?.takeIf { it >= 0 }
            // Nothing to compare against: the theme config carries no rendered row height, so the
            // dp values are drawn as they are.
            referenceRowHeightPx = 0f
            pressedLetter = findKey(matching = { index, key -> index > 0 && key.letter })
            pressedFunction = findKey(matching = { _, key -> !key.letter })
            toggledKey =
                findKey { _, key ->
                    key.label.lowercase().let { it == "shift" || it == "caps" || it == "\u21e7" }
                }
            requestLayout()
            invalidate()
        }

        /**
         * Sets the preview to the keyboard that is actually on screen.
         *
         * [referenceRowHeightPx] is that keyboard's own row height, which is what lets the
         * preview draw the theme's corners and borders at the size they are on the keyboard
         * rather than at their literal dp size. See [referenceRowHeightPx].
         */
        internal fun setKeyboard(
            rows: List<PreviewRow>,
            roundCorner: Float?,
            keyBorder: Int?,
            referenceRowHeightPx: Int,
            aspect: Float = 0f,
        ) {
            this.rows = rows
            keyboardRoundCorner = roundCorner
            keyboardKeyBorder = keyBorder
            this.referenceRowHeightPx = referenceRowHeightPx.toFloat()
            keyboardAspect = aspect
            pressedLetter = findKey(matching = { index, key -> index > 0 && key.letter })
            pressedFunction = findKey(matching = { _, key -> !key.letter })
            toggledKey =
                findKey { _, key ->
                    key.label.lowercase().let { it == "shift" || it == "caps" || it == "\u21e7" }
                }
            requestLayout()
            invalidate()
        }

        /**
         * Takes the style the preview draws with.
         *
         * Separate from [setKeyboard] because this is what changes while a slider is dragged: the
         * keyboard's rows do not move, only the metrics do.
         *
         * The text scales are read against the style's own defaults rather than converted to
         * pixels — the preview is a fraction of the keyboard's size, so a literal 15sp label in it
         * would fill the whole key. Scaling by how far the theme is from the default keeps the
         * preview proportional while still showing that a size went up or down.
         */
        fun setStyle(style: GeneralStyle) {
            // The keyboard's own declaration wins over the style, exactly as `Keyboard` resolves it.
            roundCorner = (keyboardRoundCorner ?: style.roundCorner).coerceAtLeast(0f)
            keyBorder = (keyboardKeyBorder ?: style.keyBorder).coerceAtLeast(0)
            shadowRadiusDip = style.shadowRadius.coerceAtLeast(0f)
            keyTextScale = (style.keyTextSize / GeneralStyle.DEFAULTS.keyTextSize).coerceIn(0.4f, 2.5f)
            candidateTextScale = (style.candidateTextSize / GeneralStyle.DEFAULTS.candidateTextSize).coerceIn(0.4f, 2.5f)
            invalidate()
        }

        /** The first key matching [matching], as (row, index); the early rows read first. */
        private fun findKey(matching: (Int, PreviewKey) -> Boolean): Pair<Int, Int>? {
            rows.forEachIndexed { rowIndex, row ->
                row.keys.forEachIndexed { keyIndex, key ->
                    if (key.weight > 0f && matching(keyIndex, key)) return rowIndex to keyIndex
                }
            }
            return null
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            // The keyboard's own proportions: the key block is about a third of the width once the
            // code line, the candidate row and the bottom strip have taken their share, which is
            // what four or five rows of keys come to.
            //
            // Capped, because that proportion is of the *screen* width and this screen is not a
            // phone: on a tablet the uncapped height came to more than half the display, which is
            // the opposite of a thumbnail. A preview that crowds out the list it exists to explain
            // is worse than a slightly squat one.
            val height = minOf(width * (keyboardAspect.takeIf { it in 0.3f..1.6f } ?: DEFAULT_ASPECT), dp(MAX_HEIGHT_DP))
            setMeasuredDimension(width, height.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val radius = dp(12f)
            fill.color = color("keyboard_back_color", 0xFFEEF2F1.toInt())
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, fill)

            val pad = w * 0.02f
            val preeditHeight = h * 0.095f
            val candidateHeight = h * 0.12f
            val bottomHeight = h * 0.09f

            var top = pad
            drawPreedit(canvas, pad, top, w * 0.32f, preeditHeight)
            top += preeditHeight + pad
            drawCandidates(canvas, pad, top, w - pad, candidateHeight)
            top += candidateHeight + pad
            drawKeys(canvas, pad, top, w - pad, h - bottomHeight - pad)
            drawBottomBar(canvas, pad, h - bottomHeight, w - pad, bottomHeight - pad)
        }

        /** The code line, with one run selected, so the selection colours are visible too. */
        private fun drawPreedit(
            canvas: Canvas,
            left: Float,
            top: Float,
            width: Float,
            height: Float,
        ) {
            val radius = dp(4f)
            fill.color = color("text_back_color", Color.TRANSPARENT)
            canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, fill)

            val size = height * 0.52f
            val sample = PREEDIT_SAMPLE
            val selected = SAMPLE_SELECTED_CHARS
            text.textSize = size
            val full = text.measureText(sample)
            val selectedWidth = text.measureText(sample.takeLast(selected))
            var x = left + (width - full) / 2f
            val baseline = top + height / 2f - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f

            text.color = color("text_color", Color.BLACK)
            canvas.drawText(sample.take(sample.length - selected), x, baseline, text)
            x += text.measureText(sample.take(sample.length - selected))
            val saved = canvas.save()
            canvas.clipRect(x, top, x + selectedWidth, top + height)
            fill.color = color("hilited_back_color", Color.TRANSPARENT)
            canvas.drawRect(x, top, x + selectedWidth, top + height, fill)
            text.color = color("hilited_text_color", Color.BLACK)
            canvas.drawText(sample.takeLast(selected), x, baseline, text)
            canvas.restoreToCount(saved)
        }

        /** The candidate row, with the first candidate selected. */
        private fun drawCandidates(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            height: Float,
        ) {
            val radius = dp(4f)
            fill.color = color("candidate_background", Color.TRANSPARENT)
            canvas.drawRoundRect(left, top, right, top + height, radius, radius, fill)

            val size = height * 0.46f * candidateTextScale
            val cellWidth = (right - left) / CANDIDATES.size
            CANDIDATES.forEachIndexed { index, candidate ->
                val cellLeft = left + index * cellWidth
                val cellRight = cellLeft + cellWidth
                val selected = index == 0
                if (selected) {
                    fill.color = color("hilited_candidate_back_color", Color.TRANSPARENT)
                    canvas.drawRoundRect(cellLeft, top, cellRight, top + height, radius, radius, fill)
                }
                val saved = canvas.save()
                canvas.clipRect(cellLeft, top, cellRight, top + height)
                drawCentered(
                    canvas,
                    candidate,
                    (cellLeft + cellRight) / 2f,
                    top + height / 2f,
                    size,
                    color(if (selected) "hilited_candidate_text_color" else "candidate_text_color", Color.BLACK),
                )
                canvas.restoreToCount(saved)
                if (index > 0) {
                    stroke.color = color("candidate_separator_color", Color.TRANSPARENT)
                    stroke.strokeWidth = dp(1f)
                    canvas.drawLine(cellLeft, top + height * 0.2f, cellLeft, top + height * 0.8f, stroke)
                }
            }
        }

        private fun drawKeys(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ) {
            if (rows.isEmpty() || bottom <= top) return
            val rowHeight = (bottom - top) / rows.size
            val gap = rowHeight * 0.08f
            rows.forEachIndexed { rowIndex, row ->
                val rowTop = top + rowIndex * rowHeight
                val totalWeight = row.keys.sumOf { it.weight.toDouble() }.toFloat().takeIf { it > 0f } ?: return@forEachIndexed
                var x = left
                row.keys.forEachIndexed { keyIndex, key ->
                    if (key.weight <= 0f) return@forEachIndexed
                    val keyWidth = (right - left) * key.weight / totalWeight
                    val pressed =
                        (rowIndex to keyIndex) == pressedLetter || (rowIndex to keyIndex) == pressedFunction
                    val toggled = (rowIndex to keyIndex) == toggledKey
                    drawKey(canvas, x, rowTop + gap / 2f, x + keyWidth, rowTop + rowHeight - gap / 2f, key, pressed, toggled)
                    x += keyWidth
                }
            }
        }

        private fun drawKey(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            key: PreviewKey,
            pressed: Boolean,
            toggled: Boolean,
        ) {
            // The key's own colour key first, then the two palettes — the same order Key resolves
            // in, so a theme that paints a key itself still shows that colour here.
            val own = key.colorKey?.let { colorOrNull(it) }
            val background =
                own ?: color(
                    when {
                        toggled -> "on_key_back_color"
                        key.letter -> "key_back_color"
                        else -> "off_key_back_color"
                    },
                    Color.TRANSPARENT,
                )
            val foreground =                when {
                    pressed ->
                        color(
                            when {
                                toggled -> "hilited_on_key_back_color"
                                key.letter -> "hilited_key_back_color"
                                else -> "hilited_off_key_back_color"
                            },
                            background,
                        )

                    else -> background
                }
            val labelColor =
                color(
                    when {
                        pressed ->
                            when {
                                toggled -> "hilited_on_key_text_color"
                                key.letter -> "hilited_key_text_color"
                                else -> "hilited_off_key_text_color"
                            }

                        toggled -> "on_key_text_color"
                        key.letter -> "key_text_color"
                        else -> "off_key_text_color"
                    },
                    Color.BLACK,
                )
            val symbolColor =
                color(
                    when {
                        pressed ->
                            when {
                                toggled -> "hilited_on_key_symbol_color"
                                key.letter -> "hilited_key_symbol_color"
                                else -> "hilited_off_key_symbol_color"
                            }

                        toggled -> "on_key_symbol_color"
                        key.letter -> "key_symbol_color"
                        else -> "off_key_symbol_color"
                    },
                    labelColor,
                )
            val borderColor =
                color(
                    when {
                        pressed ->
                            when {
                                toggled -> "hilited_on_key_border_color"
                                key.letter -> "hilited_key_border_color"
                                else -> "hilited_off_key_border_color"
                            }

                        else -> if (key.letter) "key_border_color" else "off_key_border_color"
                    },
                    Color.TRANSPARENT,
                )

            val keyHeightPx = bottom - top
            // Scale every dp by how much shorter this key is than the keyboard's own row, so a
            // corner that is a third of a key there stays a third of a key here.
            val shapeScale =
                if (referenceRowHeightPx > 0f && keyHeightPx > 0f) {
                    (keyHeightPx / referenceRowHeightPx).coerceIn(0.08f, 2f)
                } else {
                    1f
                }
            val radius =
                (dp(roundCorner.coerceIn(0f, 24f)) * shapeScale)
                    .coerceAtMost(keyHeightPx / 2f)
            fill.color = foreground
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, fill)
            if (keyBorder > 0) {
                stroke.color = borderColor
                stroke.strokeWidth =
                    (keyBorder.coerceAtMost(3) * density * shapeScale).coerceAtLeast(1f)
                canvas.drawRoundRect(left, top, right, bottom, radius, radius, stroke)
            }

            val height = bottom - top
            val saved = canvas.save()
            canvas.clipRect(left + dp(1f), top, right - dp(1f), bottom)
            val showSymbol = key.symbol.isNotEmpty() && height > dp(26f)
            val labelSize = height * (if (showSymbol) 0.34f else 0.40f) * keyTextScale
            val centerY = if (showSymbol) top + height * 0.58f else top + height * 0.5f
            val room = right - left - dp(6f)
            drawCentered(canvas, key.label, (left + right) / 2f, centerY, labelSize, labelColor, room)
            if (showSymbol) {
                drawCentered(canvas, key.symbol, (left + right) / 2f, top + height * 0.24f, height * 0.20f, symbolColor, room)
            }
            canvas.restoreToCount(saved)
        }

        /** The strip the bottom toggles sit in, which the theme fills with the keyboard colour. */
        private fun drawBottomBar(
            canvas: Canvas,
            left: Float,
            top: Float,
            right: Float,
            height: Float,
        ) {
            fill.color = color("keyboard_back_color", Color.TRANSPARENT)
            canvas.drawRoundRect(left, top + dp(2f), right, top + height, dp(4f), dp(4f), fill)
            val dot = dp(3f)
            val y = top + height / 2f + dp(1f)
            fill.color = setAlphaComponent(color("key_symbol_color", Color.BLACK), 153)
            listOf(0.06f, 0.10f, 0.45f, 0.92f, 0.96f).forEach { fraction ->
                canvas.drawCircle(left + (right - left) * fraction, y, dot, fill)
            }
        }

        /**
         * Draws [value] centred on ([centerX], [centerY]).
         *
         * Given a [maxWidth], a label that does not fit is first shrunk and only then cut short.
         * A theme often leaves a key's label empty, in which case the name it types — `return`,
         * `BackSpace`, `Shift_L` — is what there is to show, and those are far wider than the key.
         * Clipping them at the key's edge is what makes a preview look cramped: the overflow lands
         * on the neighbour and reads as one smear rather than two labels.
         */
        private fun drawCentered(
            canvas: Canvas,
            value: String,
            centerX: Float,
            centerY: Float,
            size: Float,
            color: Int,
            maxWidth: Float? = null,
        ) {
            if (value.isEmpty() || size <= 0f) return
            text.textSize = size
            text.color = color
            applyShadow(text)
            val shown = maxWidth?.takeIf { it > 0f }?.let { fitted(value, size, it) } ?: value
            canvas.drawText(shown, centerX, centerY - (text.fontMetrics.ascent + text.fontMetrics.descent) / 2f, text)
        }

        /** [value] at a size that fits [maxWidth], shortened only if it still cannot fit. */
        private fun fitted(
            value: String,
            size: Float,
            maxWidth: Float,
        ): String {
            val measured = text.measureText(value)
            if (measured <= maxWidth) return value
            text.textSize = (size * maxWidth / measured).coerceAtLeast(size * MIN_LABEL_SCALE)
            // Below the floor the glyphs stop being readable, and a short label says more than a
            // grey smudge of six pixels does.
            return if (text.measureText(value) > maxWidth) {
                TextUtils.ellipsize(value, text, maxWidth, TextUtils.TruncateAt.END).toString()
            } else {
                value
            }
        }

        /**
         * The theme's text shadow, on the preview's own text.
         *
         * Part of what the palette shows, so leaving it out would make the preview a slightly
         * different keyboard from the one behind it. It is also the only place the shadow can be
         * seen without a text field to type into.
         */
        private fun applyShadow(paint: Paint) {
            val shadow = KeyTextShadow.of(radiusPx = dp(shadowRadiusDip), color = color("shadow_color", Color.TRANSPARENT))
            if (shadow == null) {
                paint.clearShadowLayer()
            } else {
                paint.setShadowLayer(shadow.radiusPx, shadow.dxPx, shadow.dyPx, shadow.color)
            }
        }

        private fun color(
            key: String,
            fallback: Int,
        ): Int = colorOrNull(key) ?: fallback

        private fun colorOrNull(key: String): Int? = runCatching { ColorManager.getColor(key) }.getOrNull()

        private fun dp(value: Float) = value * density

        private companion object {
            /** The same samples the keyboard's own screenshot uses, so the preview reads familiar. */
            const val PREEDIT_SAMPLE = "yjui"
            const val SAMPLE_SELECTED_CHARS = 2
            val CANDIDATES = listOf("演示", "掩饰", "岩石", "延时", "眼屎")

            /** How far a label may be shrunk to fit before it is shortened instead. */
            const val MIN_LABEL_SCALE = 0.62f

            /** The tallest the preview may be, in dp — see [onMeasure]. */
            const val MAX_HEIGHT_DP = 240f

            /** Height-to-width ratio assumed when the real keyboard's is unknown. */
            const val DEFAULT_ASPECT = 0.58f
        }
    }
