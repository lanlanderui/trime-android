/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow

class TouchEventReceiverWindow(
    private val contentView: View,
) {
    private val ctx = contentView.context

    private val window =
        PopupWindow(
            object : View(ctx) {
                @SuppressLint("ClickableViewAccessibility")
                override fun onTouchEvent(event: MotionEvent): Boolean = contentView.dispatchTouchEvent(event)
            },
        ).apply {
            // disable animation
            animationStyle = 0
        }

    private var isWindowShowing = false

    private val cachedLocation = intArrayOf(0, 0)

    // Geometry last pushed to the popup. [showAt] is called from the floating keyboard's
    // per-keystroke position sync, and moving the popup costs a window manager transaction,
    // so an unchanged geometry must not reach the window manager at all.
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private var lastWidth = 0
    private var lastHeight = 0

    fun showAt(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        isWindowShowing = true
        if (window.isShowing) {
            if (x == lastX && y == lastY && w == lastWidth && h == lastHeight) return
            window.update(x, y, w, h)
        } else {
            window.width = w
            window.height = h
            window.showAtLocation(contentView, Gravity.TOP or Gravity.START, x, y)
        }
        lastX = x
        lastY = y
        lastWidth = w
        lastHeight = h
    }

    fun show() {
        val (x, y) = cachedLocation.also { contentView.getLocationInWindow(it) }
        val width = contentView.width
        val height = contentView.height
        showAt(x, y, width, height)
    }

    fun dismiss() {
        if (isWindowShowing) {
            isWindowShowing = false
            window.dismiss()
        }
        // Force the next show to re-apply its geometry even if it matches the cached one.
        lastX = Int.MIN_VALUE
        lastY = Int.MIN_VALUE
    }
}
