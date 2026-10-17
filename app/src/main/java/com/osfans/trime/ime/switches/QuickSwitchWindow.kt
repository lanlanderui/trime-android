/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import android.view.View
import android.view.ViewGroup
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.window.BoardWindow
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp

/**
 * The quick switches on a window of their own, for when the strip cannot hold them.
 *
 * The strip shares one row with the candidates, so the switches have to fit beside them. Past that
 * point Flexbox squeezes every button narrower than its icon: the row still "shows" them all and
 * none of them can be read or tapped. Rather than shrink them, the stow toggle then behaves like
 * the menu button -- one tap and the controls come up over the keyboard, in a wrap that takes as
 * many rows as the count needs and as wide as the screen allows.
 *
 * The buttons themselves are moved over rather than rebuilt: they are the same views with the same
 * listeners, so a switch that repaints itself (the floating one flips its icon) keeps doing so, and
 * nothing has to be wired twice.
 */
class QuickSwitchWindow(
    di: DI,
    private val switches: () -> ViewGroup,
) : BoardWindow.BarBoardWindow(di) {
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme

    private val container by lazy {
        FlexboxLayout(context).apply {
            flexWrap = FlexWrap.WRAP
            justifyContent = JustifyContent.CENTER
            alignItems = AlignItems.CENTER
            val pad = context.dp(theme.generalStyle.candidatePadding)
            setPadding(pad, pad, pad, pad)
        }
    }

    override fun onCreateView(): View = container

    override fun onAttached() {
        val strip = switches()
        // Take the buttons away from the strip, in order, keeping their own layout params: both
        // rows are FlexboxLayouts, so the buttons measure the same wherever they sit.
        while (strip.childCount > 0) {
            val button = strip.getChildAt(0)
            strip.removeViewAt(0)
            container.addView(button)
        }
    }

    override fun onDetached() {
        val strip = switches()
        // Whatever is here goes back, even if the window is torn down without a matching attach
        // (a theme switch can rebuild the whole bar while this is open).
        while (container.childCount > 0) {
            val button = container.getChildAt(0)
            container.removeViewAt(0)
            strip.addView(button)
        }
    }
}
