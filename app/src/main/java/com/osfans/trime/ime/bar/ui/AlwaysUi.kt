/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.view.View
import android.widget.ViewAnimator
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.ToolBar
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import timber.log.Timber

class AlwaysUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    private val onButtonClick: ((String) -> Unit)? = null,
) : Ui {
    private val theme: Theme get() = scope.theme

    enum class State {
        Toolbar,
        Clipboard,
        InlineSuggestion,
    }

    var currentState = State.Toolbar
        private set

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

    val buttonsUi = ButtonsBarUi(ctx, scope, onButtonClick)

    val clipboardUi = ClipboardSuggestionUi(ctx, scope)

    val inlineSuggestionsUi = InlineSuggestionsUi(ctx)

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_drop_down_24, scope)
    val floatingKeyboardButton = ToolButton(ctx, R.drawable.ic_floating_keyboard_24, scope)

    /**
     * Badge for a private session: the editor asked the IME not to learn from it,
     * so the user gets to see that the words they are about to type are not being
     * remembered.
     *
     * Passive -- it reports the state and does nothing when tapped, which is why
     * it takes no click listener and stays out of [toggleOptions].
     */
    private val privacyButton = ToolButton(ctx, R.drawable.ic_baseline_lock_24, scope).apply {
        contentDescription = ctx.getString(R.string.privacy_mode_indicator)
        visibility = View.GONE
    }

    /** Shows or hides the private-session badge. */
    fun setPrivacyMode(enabled: Boolean) {
        privacyButton.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private val rightMostButton =
        ViewAnimator(ctx).apply {
            add(hideKeyboardButton, lParams(matchParent, matchParent))
        }

    /** The primary (menu) button; the resident strip row's start flank borrows it. */
    internal val leftMostButton = toolButton(
        theme.toolBar.primaryButton,
        R.drawable.ic_baseline_more_horiz_24,
    )

    init {
        // The floating-keyboard toggle is one of the quick switches now: it rides the resident
        // strip row with the theme's buttons instead of the (retired) toolbar row.
        buttonsUi.addButton(floatingKeyboardButton, null)
    }

    /**
     * Collapses every toolbar control behind one button.
     *
     * The toolbar is a row of controls the user reaches for now and then, and while they are not
     * reaching for them they are clutter above the keys -- so the strip starts as this single
     * button and unfolds on demand. The state lives here rather than in a preference: it is a
     * momentary action ("let me at the clipboard"), not a way to configure the keyboard, and the
     * view is rebuilt rarely enough that it keeps its state across the keyboard being hidden.
     */
    private val collapseToggle = ToolButton(ctx, R.drawable.ic_baseline_more_horiz_24, scope).apply {
        setOnClickListener {
            toolbarCollapsed = !toolbarCollapsed
            applyToolbarCollapsed()
        }
    }

    /** Whether the toolbar controls are folded behind [collapseToggle]. */
    private var toolbarCollapsed = true

    private val animator =
        ViewAnimator(ctx).apply {
            // The toolbar buttons live in the resident strip row now; this first slot is only
            // kept so the [State] ordinals still line up.
            add(view(::View), lParams(matchParent, matchParent))
            add(clipboardUi.root, lParams(matchParent, matchParent))
            add(inlineSuggestionsUi.root, lParams(matchParent, matchParent))
        }

    override val root: ConstraintLayout = constraintLayout {
        val (privacyWidth, privacyHeight) = buttonsUi.getButtonSize(null)

        // The badge leads whatever this row carries, so a GONE badge collapses and leaves the
        // content at the parent's start. The row itself is the clipboard / inline-suggestion
        // takeover; the resident strip row is a sibling in the bar's animator.
        add(
            privacyButton,
            lParams(privacyWidth, privacyHeight) {
                startOfParent()
                centerVertically()
            },
        )
        add(
            animator,
            lParams(matchConstraints, matchParent) {
                after(privacyButton)
                endOfParent()
                centerVertically()
            },
        )
    }.apply {
        updateRightMostButton(State.Toolbar)
        applyToolbarCollapsed()
    }

    /**
     * Folds or unfolds the takeover row's own controls.
     *
     * Only the plain toolbar was foldable and that row is retired -- the buttons ride the
     * resident strip now, where the strip's own stow toggle handles them. What is left here is
     * the takeover content itself: the clipboard and inline-suggestion states take the row over
     * with content the user just asked for, and hiding that would be hiding the answer.
     */
    private fun applyToolbarCollapsed() {
        val folded = toolbarCollapsed && currentState == State.Toolbar
        rightMostButton.visibility = if (folded) View.GONE else View.VISIBLE
        animator.visibility = if (folded) View.GONE else View.VISIBLE
        collapseToggle.setIcon(
            if (toolbarCollapsed) {
                R.drawable.ic_baseline_more_horiz_24
            } else {
                R.drawable.ic_baseline_expand_less_24
            },
        )
        collapseToggle.contentDescription =
            ctx.getString(
                if (toolbarCollapsed) R.string.toolbar_show_buttons else R.string.toolbar_hide_buttons,
            )
    }

    fun updateButtonsStyle(option: String, enabled: Boolean) {
        leftMostButton.updateStyle(option, enabled)
        buttonsUi.firstButton?.updateStyle(option, enabled)
        buttonsUi.updateStyle(option, enabled)
    }

    fun toggleOptions(): Set<String> = buildSet {
        leftMostButton.option?.let { add(it) }
        buttonsUi.firstButton?.option?.let { add(it) }
        buttonsUi.root.children.forEach { (it as ToolButton).option?.let { add(it) } }
    }

    fun updateState(state: State) {
        Timber.d("Switch always ui to $state")
        animator.displayedChild = state.ordinal
        currentState = state
        updateRightMostButton(state)
        updateLeftMostButton(state)
        applyToolbarCollapsed()
    }

    /** Restyles the toolbar area after a scheme switch. */
    fun refreshColors() {
        collapseToggle.refreshColors()
        leftMostButton.refreshColors()
        hideKeyboardButton.refreshColors()
        floatingKeyboardButton.refreshColors()
        privacyButton.refreshColors()
        buttonsUi.refreshColors()
        clipboardUi.refreshColors()
    }

    private fun updateRightMostButton(state: State) {
        val hasFirstButton = buttonsUi.firstButton != null
        val showFirst = hasFirstButton && (theme.toolBar.buttons.isNotEmpty() || state != State.Toolbar)
        rightMostButton.displayedChild = if (showFirst) 1 else 0
    }

    private fun updateLeftMostButton(state: State) {
        val buttonConfig =
            if (state == State.Toolbar) {
                theme.toolBar.primaryButton
            } else {
                theme.toolBar.buttons.firstOrNull()
            }

        val (buttonWidth, buttonHeight) = buttonsUi.getButtonSize(buttonConfig)
        leftMostButton.layoutParams = leftMostButton.layoutParams.apply {
            width = buttonWidth
            height = buttonHeight
        }
    }
}
