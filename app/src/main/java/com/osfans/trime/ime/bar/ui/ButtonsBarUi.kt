/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.view.View
import androidx.annotation.DrawableRes
import androidx.core.view.children
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.ime.bar.RightButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    private val onButtonClick: ((String) -> Unit)? = null,
) : Ui {
    private val theme: Theme get() = scope.theme

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        // The switches share the strip evenly rather than packing to one end.
        justifyContent = JustifyContent.SPACE_EVENLY
        flexDirection = FlexDirection.ROW_REVERSE
    }

    val firstButton: ToolButton?

    private val defaultButtonSize = theme.generalStyle.run { candidateViewHeight + commentHeight }

    /** Adds a control to the evenly spaced row, sized like the theme's own buttons. */
    fun addButton(view: View, config: ToolBar.Button?) {
        val (width, height) = getButtonSize(config)
        root.addView(
            view,
            FlexboxLayout.LayoutParams(width, height).apply {
                marginEnd = ctx.dp(theme.toolBar.buttonSpacing)
            },
        )
    }

    private fun toolButton(
        buttonConfig: ToolBar.Button?,
        @DrawableRes icon: Int = 0,
    ): ToolButton = if (buttonConfig != null) {
        ToolButton(ctx, buttonConfig, scope).apply {
            setOnClickListener { onButtonClick?.invoke(buttonConfig.action) }
            val longPressAction = buttonConfig.longPressAction
            if (longPressAction.isNotEmpty()) {
                setOnLongClickListener {
                    onButtonClick?.invoke(longPressAction)
                    true
                }
            }
        }
    } else {
        ToolButton(ctx, icon, scope).apply {
            setOnClickListener { onButtonClick?.invoke("") }
        }
    }

    init {
        val buttons = theme.toolBar.buttons
        // A theme declares its own buttons, but which of them this app knows by name is a fixed
        // list: the three here are the ones the corner-button setting can switch off. A button
        // the theme invents has no name to configure, so it always rides along.
        val chosen = RightButton.parse(AppPrefs.defaultInstance().candidates.rightButtons.getValue())
        val kept = buttons.filter { config ->
            val known = RightButton.ofThemeAction(config.action)
            known == null || known in chosen
        }
        // The whole kept set rides the row: the first button used to sit apart as an alternate
        // face of the hide button, but the hide button's own spot is gone, so nothing holds it
        // back.
        firstButton = kept.firstOrNull()?.let { config ->
            toolButton(config).also { addButton(it, config) }
        }

        kept.drop(1).forEach { config ->
            addButton(toolButton(config), config)
        }
    }

    internal fun getButtonSize(config: ToolBar.Button?, customDefaultSize: Int = defaultButtonSize): Pair<Int, Int> {
        val sizeList = config?.size?.takeIf { it.size == 2 } ?: List(2) { customDefaultSize }
        val (width, height) = sizeList

        val finalWidth = if (width >= 0) ctx.dp(width) else width
        val finalHeight = if (height >= 0) ctx.dp(height) else height

        return finalWidth to finalHeight
    }

    fun updateStyle(option: String, enabled: Boolean) {
        root.children.forEach { (it as ToolButton).updateStyle(option, enabled) }
    }

    /** Restyles the toolbar buttons after a scheme switch. */
    fun refreshColors() {
        firstButton?.refreshColors()
        root.children.forEach { (it as ToolButton).refreshColors() }
    }
}
