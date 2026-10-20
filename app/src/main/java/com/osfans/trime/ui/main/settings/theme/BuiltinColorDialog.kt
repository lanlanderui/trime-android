/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.osfans.trime.R
import com.osfans.trime.ui.common.materialAlertDialogBuilder
import com.osfans.trime.util.ColorUtils
import kotlin.math.roundToInt
import splitties.dimensions.dp

/**
 * Picks one ARGB colour for the built-in palette.
 *
 * Four gradient tracks (hue, saturation, brightness, opacity) plus a hex field, because the two
 * ways of choosing want different things: the tracks are for finding a colour by eye, the field
 * for pasting a value read off a theme file or another app. Both stay in step, and a swatch at
 * the top shows the result while it is built.
 *
 * The tracks are drawn as real gradients rather than tinted bars — a hue slider that is not a
 * rainbow is the one thing that would make this worse than typing a hex value.
 */
internal object BuiltinColorDialog {
    fun build(
        context: Context,
        title: String,
        current: Int,
        onPicked: (String) -> Unit,
        onFollowScheme: (() -> Unit)?,
        @StringRes followLabelRes: Int = R.string.builtin_color_follow_scheme,
        onPreview: ((String) -> Unit)? = null,
    ): AlertDialog {
        val hsv = FloatArray(3)
        Color.colorToHSV(current, hsv)
        var alpha = Color.alpha(current)

        fun color(): Int = Color.HSVToColor(alpha, hsv)

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(24))
            }

        val preview = View(context).apply { layoutParams = matchWidth(context.dp(48), bottom = context.dp(16)) }
        root.addView(preview)

        val hexField =
            EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSingleLine = true
                hint = context.getString(R.string.builtin_color_hex)
                layoutParams = matchWidth(ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        root.addView(hexField)

        /** One editable channel: its bar, its range and how it reads and writes the state. */
        class Channel(
            val max: Int,
            val labelRes: Int,
            val read: () -> Int,
            val write: (Int) -> Unit,
            /** The gradient the bar shows, given the state as it is right now. */
            val stops: () -> IntArray,
        )

        val channels =
            listOf(
                Channel(
                    max = 360,
                    labelRes = R.string.builtin_color_hue,
                    read = { (hsv[0] * 3.6f).roundToInt() },
                    write = { hsv[0] = it / 3.6f },
                    stops = { RAINBOW },
                ),
                Channel(
                    max = 100,
                    labelRes = R.string.builtin_color_saturation,
                    read = { (hsv[1] * 100f).roundToInt() },
                    write = { hsv[1] = it / 100f },
                    stops = {
                        intArrayOf(
                            Color.HSVToColor(floatArrayOf(hsv[0], 0f, 1f)),
                            Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)),
                        )
                    },
                ),
                Channel(
                    max = 100,
                    labelRes = R.string.builtin_color_brightness,
                    read = { (hsv[2] * 100f).roundToInt() },
                    write = { hsv[2] = it / 100f },
                    stops = {
                        intArrayOf(
                            Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 0f)),
                            Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)),
                        )
                    },
                ),
                Channel(
                    max = 255,
                    labelRes = R.string.builtin_color_alpha,
                    read = { alpha },
                    write = { alpha = it },
                    stops = { intArrayOf(color() and 0x00FFFFFF, color() or ALPHA_OPAQUE) },
                ),
            )

        val bars = mutableListOf<Pair<SeekBar, Channel>>()
        channels.forEach { channel ->
            val row =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
            row.addView(
                TextView(context).apply {
                    setText(channel.labelRes)
                    layoutParams = LinearLayout.LayoutParams(context.dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
                },
            )
            val bar =
                SeekBar(context).apply {
                    max = channel.max
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            row.addView(bar)
            root.addView(row)
            bars += bar to channel
        }

        /** Pushes the state back into the bars, for a change that did not come from one of them. */
        fun syncBars() {
            bars.forEach { (bar, channel) -> bar.progress = channel.read() }
        }

        /** Rebuilds every track, because saturation/brightness/opacity ramps follow the hue. */
        fun rebuildTracks() {
            bars.forEach { (bar, channel) -> bar.applyTrack(channel.stops(), context) }
        }

        /**
         * Whether the initial state has been pushed into the widgets yet.
         *
         * The first [refresh] is that initial push — it runs before the dialog is shown, and it is
         * not a change the user made. Reporting it as a preview would have the keyboard repaint
         * once with the colour it already had, and, worse, would make a screen that shows a live
         * preview revert an unsaved edit the moment the dialog opened.
         */
        var userHasChanged = false

        fun refresh() {
            preview.background = swatch(color(), context)
            // Never fight the user's cursor: only rewrite the field while it is not being edited.
            if (!hexField.hasFocus()) hexField.setText(argbToHex(color()))
            rebuildTracks()
            if (userHasChanged) onPreview?.invoke(argbToHex(color()))
        }

        bars.forEach { (bar, channel) ->
            bar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        if (!fromUser) return
                        channel.write(progress)
                        userHasChanged = true
                        refresh()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }

        hexField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val parsed = runCatching { ColorUtils.parseColor(hexField.text.toString().trim()) }.getOrNull()
                ?: return@setOnFocusChangeListener
            Color.colorToHSV(parsed, hsv)
            // A colour that carries no alpha means "#RRGGBB": keep the opacity the user set rather
            // than silently clearing it to transparent.
            if (Color.alpha(parsed) != 0) alpha = Color.alpha(parsed)
            syncBars()
            userHasChanged = true
            refresh()
        }

        syncBars()
        hexField.setText(argbToHex(color()))
        refresh()

        return context
            .materialAlertDialogBuilder()
            .setTitle(title)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPicked(argbToHex(color())) }
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (onFollowScheme != null) {
                    setNeutralButton(followLabelRes) { _, _ -> onFollowScheme() }
                }
            }.create()
    }

    private const val ALPHA_OPAQUE = 0xFF000000.toInt()

    private val RAINBOW =
        intArrayOf(
            0xFFFF0000.toInt(),
            0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(),
            0xFF00FFFF.toInt(),
            0xFF0000FF.toInt(),
            0xFFFF00FF.toInt(),
            0xFFFF0000.toInt(),
        )

    private fun matchWidth(
        height: Int,
        bottom: Int = 0,
    ) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { bottomMargin = bottom }

    /** A rounded swatch, stroked so a colour close to the surface still reads as a swatch. */
    private fun swatch(
        color: Int,
        context: Context,
    ): Drawable =
        GradientDrawable().apply {
            cornerRadius = context.dp(8).toFloat()
            setColor(color)
            setStroke(context.dp(1), 0x33000000)
        }

    /**
     * Fills a bar with [stops].
     *
     * The full-width copy goes in the background and a clipped copy in the progress drawable: a
     * SeekBar draws `progressDrawable` clipped to the thumb, so without the background the part
     * to the right of the thumb — most of the ramp — would not be visible at all.
     */
    private fun SeekBar.applyTrack(
        stops: IntArray,
        context: Context,
    ) {
        val height = context.dp(12)
        background = track(stops, height, context)
        progressDrawable = ClipDrawable(track(stops, height, context), Gravity.START, ClipDrawable.HORIZONTAL)
    }

    private fun track(
        stops: IntArray,
        height: Int,
        context: Context,
    ): Drawable =
        ShapeDrawable(RoundRectShape(FloatArray(8) { context.dp(6).toFloat() }, null, null)).apply {
            intrinsicHeight = height
            shaderFactory =
                object : ShapeDrawable.ShaderFactory() {
                    override fun resize(
                        width: Int,
                        height: Int,
                    ): Shader = LinearGradient(0f, 0f, width.toFloat(), 0f, stops, null, Shader.TileMode.CLAMP)
                }
        }
}
