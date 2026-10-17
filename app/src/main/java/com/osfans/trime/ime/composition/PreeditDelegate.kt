/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewOutlineProvider
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.horizontalPadding
import kotlin.math.roundToInt

class PreeditDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {

    private val context: ContextThemeWrapper by instance()
    private val scope: ThemeScope by instance()
    private val rime: RimeSession by instance()

    private val theme: Theme
        get() = scope.theme

    private val appearance = AppPrefs.defaultInstance().preeditAppearance

    /**
     * Draws the chip's background: the theme's tab shape, unless the user has overridden it.
     *
     * Three overrides live on top of the theme and each one falls back to it on its own: the corner
     * radius (zero keeps the theme's two top radii and the square bottom), the fill colour (zero
     * keeps the scheme's `text_back_color`) and the opacity (a scale on whatever alpha the fill
     * already carries). Re-run on every colour-scheme switch and on every change of the three
     * preferences, so what is drawn is always the current combination rather than a snapshot.
     */
    private fun setupPreeditBackground(view: View) {
        val fill =
            preeditFillColor(
                themeColor = scope.colors.textBackColor,
                pickedColor = appearance.backColor.getValue(),
                opacityPercent = appearance.backOpacity.getValue(),
            )
        val radii =
            preeditCornerRadii(
                topStartDp = theme.preedit.topStartRadius,
                topEndDp = theme.preedit.topEndRadius,
                overrideDp = appearance.cornerRadius.getValue(),
                ltr = view.layoutDirection == View.LAYOUT_DIRECTION_LTR,
                density = view.resources.displayMetrics.density,
            )
        view.background = GradientDrawable().apply {
            setColor(fill)
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
        }
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    val ui =
        PreeditUi(
            context,
            scope,
            setupPreeditView = {
                setupPreeditBackground(this)
                horizontalPadding = dp(theme.preedit.horizontalPadding)
            },
            onMoveCursor = { pos -> rime.launchOnReady { it.moveCursorPos(pos) } },
        ).apply {
            root.alpha = theme.preedit.alpha
            root.visibility = View.INVISIBLE
        }

    /** Restyles the text and its background after a scheme switch. */
    fun refreshColors() {
        ui.refreshColors()
        setupPreeditBackground(ui.preedit)
    }

    /** Re-applies the appearance overrides after a settings change, without rebuilding the view. */
    fun refreshAppearance() {
        setupPreeditBackground(ui.preedit)
    }

    /** Keeps the transparent touch receiver aligned while the floating keyboard moves. */
    fun updateTouchReceiverPosition() {
        if (ui.visible) {
            touchEventReceiverWindow.show()
        }
    }

    private val touchEventReceiverWindow = TouchEventReceiverWindow(ui.root)

    init {
        // The touch receiver is a PopupWindow anchored to the preedit view, so it
        // has to go away when that view leaves the window. Relying only on the
        // composition emptying out is not enough: a theme switch, a floating
        // keyboard toggle or a rotation rebuilds the input view mid-composition
        // and would leave the popup anchored to a detached view.
        ui.root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    touchEventReceiverWindow.dismiss()
                }
            },
        )
    }

    override fun onCompositionUpdate(data: CompositionProto) {
        ui.update(data)
        ui.root.visibility = if (ui.visible) View.VISIBLE else View.INVISIBLE
        if (data.length > 0) {
            touchEventReceiverWindow.show()
        } else {
            touchEventReceiverWindow.dismiss()
        }
    }
}

/**
 * Corner radii of the preedit background, in pixels, in the order GradientDrawable reads them:
 * top-left, top-right, bottom-right, bottom-left.
 *
 * `overrideDp` at zero means "whatever the theme drew": its two top radii in reading order, and a
 * square bottom -- the tab that sits flush on the keyboard. Any other value replaces all four
 * corners with one radius, which is what makes a lifted chip look deliberate. A layout direction
 * of RTL mirrors the two top radii, as the theme's names (`top_start` / `top_end`) already say.
 */
internal fun preeditCornerRadii(
    topStartDp: Float,
    topEndDp: Float,
    overrideDp: Int,
    ltr: Boolean,
    density: Float,
): FloatArray {
    val override = overrideDp.coerceIn(0, AppPrefs.PreeditAppearance.CORNER_RADIUS_MAX_DP)
    if (override > 0) {
        val radius = (override * density).roundToInt().toFloat()
        return floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
    }
    val start = (topStartDp * density).roundToInt().toFloat()
    val end = (topEndDp * density).roundToInt().toFloat()
    return if (ltr) {
        floatArrayOf(start, start, end, end, 0f, 0f, 0f, 0f)
    } else {
        floatArrayOf(end, end, start, start, 0f, 0f, 0f, 0f)
    }
}

/**
 * The fill of the preedit background, as ARGB.
 *
 * A picked colour is stored as RGB, so it is made opaque here and its transparency comes from
 * [opacityPercent] alone -- one writer per byte. The theme colour keeps whatever alpha the scheme
 * gives it (0xcc and friends), and the percentage scales *that*: 100 leaves the theme's own
 * translucency untouched, which is what keeps the default look identical to before the setting
 * existed. Rounding, not flooring, so the 255-to-100-to-255 round trip cannot walk the value down.
 */
internal fun preeditFillColor(
    themeColor: Int,
    pickedColor: Int,
    opacityPercent: Int,
): Int {
    val base = if (pickedColor == 0) themeColor else pickedColor or 0xFF000000.toInt()
    val percent = opacityPercent.coerceIn(0, 100)
    // Plain bit arithmetic rather than Color.alpha / setAlphaComponent: this is the pixel the
    // screen will show, and keeping it free of Android calls is what lets a JVM test pin it.
    val alpha = (((base ushr 24) and 0xFF) * percent + 50) / 100
    return (base and 0x00FFFFFF) or (alpha shl 24)
}
