/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.animation.PathInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.util.roundedRippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.wrapContent

/**
 * The resident row above the keys: [menubutton] | strip | [switchToggle].
 *
 * The strip no longer comes and goes with the candidates -- it lives in the toolbar the whole
 * time, held between the primary (menu) button at the start and the stow toggle at the end, and
 * only as wide as the length preference says. The candidates appear and disappear inside it as
 * the composition comes and goes.
 *
 * What the strip *holds* can change: normally the composing candidates, but the stow toggle
 * flips it over to the quick switches (every toolbar button in an evenly spaced row), so the
 * controls stay one tap away without ever stacking a second row above the keys.
 *
 * Two layers, because that is what reads as a display rather than as a bar with a label:
 *
 * - the **screen**, painted with the scheme's candidate background, border and corner radius --
 *   the same keys the plain candidate bar used, so a theme restyles this without knowing about it;
 * - the **inset strip** inside it, carrying its content on one rounded bar, held off both
 *   screen edges. (The preedit is deliberately not in here: a long composition would take the
 *   strip over and squeeze the candidates into a sliver.) Its colour is the screen's own
 *   luminance, pushed the other way (see [stripBackground]), so it reads as recessed in any
 *   scheme. The fill can be switched off in the candidate settings, which leaves the bar's
 *   earlier look: candidates straight on the screen.
 *
 * The privacy badge -- the one control that is about the state of the session rather than about
 * the candidates -- rides at the candidates' trailing end.
 */
class MonitorCandidateUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    private val candidateView: View,
    private val menuButton: View,
    private val switchesBar: View,
    private val flankWidthPx: Int,
    private val flankHeightPx: Int,
    onSwipeDown: ((KeyBehavior) -> Unit)? = null,
    /** Opens the quick switches on a window, for when the strip cannot hold them all. */
    private val onSwitchesRequested: (() -> Unit)? = null,
    /**
     * The one switch the corner-button setting leaves, if it leaves exactly one.
     *
     * With a single switch there is nothing to stow, so showing the stow toggle would make the
     * user tap through a fold to reach the only control in it. The button takes the slot itself.
     */
    private val singleSwitch: View? = null,
) : Ui {
    private val theme: Theme get() = scope.theme

    private val sideMarginPx = ctx.dp(theme.generalStyle.candidatePadding / 2)

    /** Gap between the screen's edge and the inset strip, on both sides. */
    private val stripInsetPx = ctx.dp(STRIP_INSET_DP)

    // Held as a delegate and read per application rather than captured with `by`, which would
    // freeze the value at construction and leave the switch looking like it did nothing.
    private val stripBackgroundEnabled = AppPrefs.defaultInstance().candidates.stripBackground
    private val stripColor = AppPrefs.defaultInstance().candidates.stripColor
    private val stripOpacity = AppPrefs.defaultInstance().candidates.stripOpacity
    private val stripLengthPercent = AppPrefs.defaultInstance().candidates.stripLength
    private val stripGapDp = AppPrefs.defaultInstance().candidates.stripGap
    private val cornerToggles = AppPrefs.defaultInstance().candidates.cornerTogglesWhileComposing
    private val switchesInWindow = AppPrefs.defaultInstance().candidates.switchesInWindow

    val privacyButton =
        ToolButton(ctx, R.drawable.ic_baseline_lock_24, scope).apply {
            visibility = View.GONE
        }

    val unrollButton =
        ToolButton(ctx, R.drawable.ic_baseline_expand_more_24, scope).apply {
            visibility = View.INVISIBLE
        }

    private val screenBackground
        get() = scope.decorDrawable(
            "candidate_background",
            "candidate_border_color",
            ctx.dp(theme.generalStyle.candidateBorder),
            ctx.dp(theme.generalStyle.candidateBorderRound),
        )

    /**
     * The inset strip reads as recessed rather than as another surface: a touch of the opposite
     * of the screen's own luminance laid over it, so a light scheme gets a slightly darker bar and
     * a dark scheme a slightly lighter one, without asking the theme for a second colour.
     *
     * That shade is only the automatic case. A colour picked in the settings takes over the hue
     * (the shade still applies when it is left automatic), and the opacity preference is the
     * fill's alpha for both. The ripple follows the fill, so pressing the strip does not flash a
     * colour the user did not choose.
     */
    private val stripBackground: Drawable
        get() {
            val screen = scope.colors.candidateBackground
            val auto =
                if (ColorUtils.calculateLuminance(screen) > 0.5) Color.BLACK else Color.WHITE
            val base = stripColor.getValue().takeUnless { it == 0 } ?: auto
            val fill = ColorUtils.setAlphaComponent(base, stripOpacity.getValue() * 255 / 100)
            return roundedRippleDrawable(
                fill,
                ctx.dp(theme.generalStyle.candidateCornerRadius),
                fill,
            )
        }

    /**
     * The stow toggle at the row's trailing end: flips the strip between the candidates and the
     * quick switches. Open shows a left chevron ("put it back"), closed a right one.
     */
    private val switchToggle =
        ToolButton(ctx, R.drawable.ic_chevron_right_row_24, scope).apply {
            onSwipe = onSwipeDown
            setOnClickListener {
                // When the switches cannot share the row with the candidates, the toggle does what
                // the menu button does: it brings them up as a window instead of squeezing them.
                if ((switchesInWindow.getValue() || switchesOverflow()) && onSwitchesRequested != null) {
                    onSwitchesRequested.invoke()
                } else {
                    setSwitchesShown(!switchesShown)
                }
            }
        }

    /**
     * Whether the switches are wider than the strip can hold.
     *
     * Measured when the toggle is tapped rather than when the row is built: the strip's width
     * follows the screen, the rotation and the length preference, so the answer can change while
     * the buttons stay the same.
     */
    private fun switchesOverflow(): Boolean {
        val bar = switchesBar as? ViewGroup ?: return false
        val available = contentStrip.width - contentStrip.paddingStart - contentStrip.paddingEnd
        if (available <= 0 || bar.childCount == 0) return false
        var needed = 0
        for (i in 0 until bar.childCount) {
            val params = bar.getChildAt(i).layoutParams as? MarginLayoutParams
            val width = params?.width?.takeIf { it > 0 } ?: bar.getChildAt(i).measuredWidth
            needed += width + (params?.marginEnd ?: 0)
        }
        return needed > available
    }

    /** Whether the strip is showing the quick switches instead of the candidates. */
    private var switchesShown = false

    private fun setSwitchesShown(shown: Boolean, animate: Boolean = true) {
        switchesShown = shown
        // Cross-fade rather than swap: both layers stay laid out for the length of the fade, so
        // the row reads as flipping over instead of blinking. A newer flip bumps the token, and
        // the older animation's clean-up is then dropped with it.
        val token = ++switchFadeToken
        val incoming = if (shown) switchesBar else candidatesCluster
        val outgoing = if (shown) candidatesCluster else switchesBar
        listOf(incoming, outgoing).forEach { it.animate().cancel() }
        if (!animate) {
            incoming.alpha = 1f
            incoming.visibility = View.VISIBLE
            outgoing.alpha = 1f
            outgoing.visibility = View.GONE
        }
        if (animate) {
            incoming.alpha = 0f
            incoming.visibility = View.VISIBLE
            incoming.animate().alpha(1f).setDuration(SWITCH_FADE_MS).start()
            outgoing.animate()
                .alpha(0f)
                .setDuration(SWITCH_FADE_MS)
                .withEndAction {
                    if (token != switchFadeToken) return@withEndAction
                    outgoing.visibility = View.GONE
                    outgoing.alpha = 1f
                }.start()
        }
        switchToggle.setIcon(
            if (shown) R.drawable.ic_chevron_left_row_24 else R.drawable.ic_chevron_right_row_24,
        )
        switchToggle.contentDescription =
            ctx.getString(
                if (shown) R.string.toolbar_hide_buttons else R.string.toolbar_show_buttons,
            )
    }

    /** Bumped per flip so a superseded fade does not hide the layer now on screen. */
    private var switchFadeToken = 0

    /**
     * The row's end slot, as two views that never show at once: the expand toggle while a
     * composition is running, the stow toggle when the row is idle.
     *
     * They share one slot because they answer the same question -- "what can this row show me" --
     * and showing both was the conflict: the expand chevron sat at the end of the strip with the
     * stow chevron immediately outside it, two arrows pointing at two different things one tap
     * apart. The hidden one is INVISIBLE rather than GONE so the strip keeps its span.
     */
    private var composing = false
    private var unrollAvailable = false

    /** While typing, the row ends in the expand toggle; idle, it ends in the stow toggle. */
    fun setComposing(composing: Boolean) {
        if (this.composing == composing) return
        val hadCorners = !this.composing || cornerToggles.getValue()
        this.composing = composing
        // The switches row is reachable only through the stow toggle, which is not on screen while
        // typing -- so typing takes the row back to the candidates rather than leaving it stranded.
        if (composing && switchesShown) setSwitchesShown(false)
        val hasCorners = !composing || cornerToggles.getValue()
        // The only moment the strip changes width is when the corner toggles step aside, which is
        // the state "hide the corner toggles while composing" describes. Animating it is what turns
        // the jump into the bar growing out from under the candidates and shrinking back when the
        // composition ends. With the fill switched off there is no bar to grow, and animating the
        // width would only reflow the row for nothing.
        if (hadCorners != hasCorners && stripFill() != null) animateStripWidth(hasCorners)
        applyEndSlot()
    }

    /** Cancels and releases a width animation; the strip goes back to its constrained width. */
    private var stripWidthAnimator: ValueAnimator? = null

    /**
     * Grows (or shrinks) the strip to the width the new row layout gives it.
     *
     * The corner toggles hand their two slots to the strip, and the strip's slot expands by the
     * same amount on both sides, so its centre does not move: animating the width alone reads as
     * the bar extending outwards from where it was. The width is taken over from the constraint
     * for the length of the run and handed back at the end, so a later length change still works.
     */
    private fun animateStripWidth(cornersVisible: Boolean) {
        val inner = root.width - root.paddingStart - root.paddingEnd
        if (inner <= 0) return
        val trackWidth = inner - if (cornersVisible) flankWidthPx * 2 else 0
        val target = (trackWidth * stripLengthPercent.getValue() / 100f).toInt()
        val from = contentStrip.width
        if (from <= 0 || target <= 0 || from == target) return
        stripWidthAnimator?.cancel()
        val params = contentStrip.layoutParams as? ConstraintLayout.LayoutParams ?: return
        params.width = from
        contentStrip.layoutParams = params
        stripWidthAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = STRIP_WIDTH_MS
            interpolator = STRIP_INTERPOLATOR
            addUpdateListener { animator ->
                val lp = contentStrip.layoutParams as? ConstraintLayout.LayoutParams
                    ?: return@addUpdateListener
                lp.width = animator.animatedValue as Int
                contentStrip.layoutParams = lp
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (stripWidthAnimator !== animation) return
                        stripWidthAnimator = null
                        releaseStripWidth()
                    }
                },
            )
            start()
        }
    }

    /** Hands the width back to the length constraint, at whatever length it now says. */
    private fun releaseStripWidth() {
        val lp = contentStrip.layoutParams as? ConstraintLayout.LayoutParams ?: return
        lp.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
        applyStripLength(lp)
        contentStrip.layoutParams = lp
    }

    /** Whether the expand toggle has anything left to expand; the bar's state machine decides. */
    fun setUnrollAvailable(available: Boolean) {
        if (unrollAvailable == available) return
        unrollAvailable = available
        applyEndSlot()
    }

    private fun applyEndSlot() {
        // The two corner toggles can step aside for the composition entirely, which hands their
        // width to the candidates: they go GONE rather than INVISIBLE so the strip closes in on
        // the space they were holding.
        val corners = !composing || cornerToggles.getValue()
        menuButton.visibility = if (corners) View.VISIBLE else View.GONE
        val showUnroll = corners && composing && unrollAvailable
        unrollButton.visibility = when {
            showUnroll -> View.VISIBLE
            corners -> View.INVISIBLE
            else -> View.GONE
        }
        // The trailing slot has three shapes, decided by how many switches are left:
        // one button -> that button; none -> no slot at all; two or more -> the stow toggle.
        val single = singleSwitch
        if (single != null) {
            // Typing hands the slot to the expand toggle above. The button keeps its span as
            // INVISIBLE so the strip does not slide under the chevron for the length of a
            // composition -- the same reason the stow toggle goes INVISIBLE rather than GONE.
            single.visibility = when {
                !corners -> View.GONE
                composing -> View.INVISIBLE
                else -> View.VISIBLE
            }
            switchToggle.visibility = View.GONE
            return
        }
        val nothingToStow = (switchesBar as? ViewGroup)?.childCount == 0
        switchToggle.visibility = when {
            !corners -> View.GONE
            composing -> View.INVISIBLE
            nothingToStow -> View.GONE
            else -> View.VISIBLE
        }
    }

    /** Re-applies the corner toggles after their switch changed, without a rebuild. */
    fun refreshCornerToggles() {
        applyEndSlot()
    }

    /**
     * Moves the one configured switch into the trailing slot.
     *
     * The button is re-parented rather than rebuilt: same view, same listener, same colours, so a
     * theme button keeps the look it has in the switches row and the floating toggle keeps
     * flipping its own icon. Its own width and height come along, which keeps it the size the
     * theme gives that button rather than the size of the flank it now sits in.
     *
     * The strip's end is re-anchored to this button. It used to end at the stow toggle, which is
     * GONE here -- and a GONE anchor sits at the row's end with no width, so the strip would run
     * underneath the button (the overlap this slot exists to avoid).
     */
    private fun attachSingleSwitch() {
        val button = singleSwitch ?: return
        val params = button.layoutParams
        val width = params?.width?.takeIf { it > 0 } ?: flankWidthPx
        val height = params?.height?.takeIf { it > 0 } ?: flankHeightPx
        (button.parent as? ViewGroup)?.removeView(button)
        if (button.id == View.NO_ID) button.id = View.generateViewId()
        root.addView(
            button,
            ConstraintLayout.LayoutParams(width, height).apply {
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            },
        )
        stripTrack.updateLayoutParams<ConstraintLayout.LayoutParams> {
            endToStart = button.id
            endToEnd = ConstraintLayout.LayoutParams.UNSET
        }
    }

    /**
     * The composing cluster: the candidates own the whole strip -- the highlighted one rides its
     * middle -- with the privacy badge by its end.
     */
    private val candidatesCluster = constraintLayout {
        add(
            candidateView,
            lParams(matchConstraints, matchConstraints) {
                // The preedit lives above the keyboard, so the candidates get the whole strip:
                // sharing it meant a long composition squeezed them into a sliver on the right.
                // It spans the strip's full width on purpose -- the row itself is padded clear
                // of the badge, which is what puts the row's centre on the strip's centre.
                startOfParent()
                endOfParent()
                centerVertically()
            },
        )
        add(
            privacyButton,
            lParams(dp(22), dp(22)) {
                endOfParent()
                centerVertically()
            },
        )
    }

    /** The inset bar: it carries the candidates, or the quick switches when stowed open. */
    private val contentStrip = constraintLayout {
        add(
            candidatesCluster,
            lParams(matchConstraints, matchConstraints) {
                startOfParent()
                endOfParent()
                topOfParent()
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            },
        )
        add(
            switchesBar,
            lParams(matchConstraints, matchConstraints) {
                startOfParent()
                endOfParent()
                centerVertically()
            },
        )
    }.apply {
        // Not [refreshStripBackground]: this runs while `contentStrip` is still being assigned,
        // so the method would dereference a null. The fill comes from [stripFill] instead.
        background = stripFill()
        setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
    }

    /** The slot between the two flanking buttons; the strip is a centred share of it. */
    private val stripTrack = constraintLayout {
        add(
            contentStrip,
            lParams(matchConstraints, matchConstraints) {
                startOfParent()
                endOfParent()
                // Without both vertical anchors a matchConstraints height has nothing to span
                // between and collapses to zero -- an invisible strip.
                topOfParent()
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                // Symmetric vertical breathing room: the gap rides both margins while the bar
                // (the screen) grows by twice as much, so the strip keeps its own height and the
                // air opens up evenly above and below it.
                topMargin = ctx.dp(2 + stripGapDp.getValue())
                bottomMargin = ctx.dp(2 + stripGapDp.getValue())
                applyStripLength(this)
            },
        )
    }

    override val root = constraintLayout {
        add(
            menuButton,
            lParams(flankWidthPx, flankHeightPx) {
                startOfParent()
                centerVertically()
            },
        )
        add(
            switchToggle,
            lParams(flankWidthPx, flankHeightPx) {
                endOfParent()
                centerVertically()
            },
        )
        add(
            unrollButton,
            lParams(flankWidthPx, flankHeightPx) {
                endOfParent()
                centerVertically()
            },
        )
        add(
            stripTrack,
            lParams(matchConstraints, matchConstraints) {
                // Relative anchors on purpose: the start/end resolution that runs on every
                // setLayoutParams overwrites absolute left/right margins with zero.
                after(menuButton)
                before(switchToggle)
                topOfParent()
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            },
        )
    }.apply {
        background = screenBackground
        setPadding(sideMarginPx, 0, sideMarginPx, 0)
    }

    init {
        // The single switch (if the setting leaves one) has to be in place before the slot is
        // applied, since the slot decides its visibility.
        attachSingleSwitch()
        // The strip belongs to the candidates until the toggle is asked for the switches. The
        // first assignment is not a flip, so it does not fade -- at build time both layers would
        // be on screen for the length of an animation nobody asked for.
        setSwitchesShown(false, animate = false)
        applyEndSlot()
        // The strip can shrink under an open switches row (rotation, or a shorter length in the
        // settings). The row was opened when it still fit, so it has to give way again rather than
        // stay on screen with its buttons squeezed into each other.
        contentStrip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (switchesShown && switchesOverflow()) setSwitchesShown(false, animate = false)
        }
    }

    /**
     * The inset bar's own fill -- or none at all, when its switch says the candidates belong
     * straight on the screen, which is the bar's pre-strip look.
     */
    fun refreshStripBackground() {
        contentStrip.background = stripFill()
    }

    /** The fill both the initial assignment and [refreshStripBackground] draw from. */
    private fun stripFill(): Drawable? =
        if (stripBackgroundEnabled.getValue()) stripBackground else null

    /**
     * Puts the strip's length onto a set of layout params.
     *
     * Both edges stay pinned inside the middle slot and only the width changes, so the constraint
     * bias (0.5, the default) keeps the strip centred: a shorter bar shrinks symmetrically about
     * the slot's middle instead of leaning to one side. At 100% it fills everything between the
     * flanking buttons, so the flanks stay reachable whatever the length.
     */
    private fun applyStripLength(params: ConstraintLayout.LayoutParams) {
        params.matchConstraintPercentWidth = stripLengthPercent.getValue() / 100f
    }

    /** Re-applies the strip's length after its preference changed, without a rebuild. */
    fun refreshStripLength() {
        // A run in flight would keep writing its own width over the new length.
        stripWidthAnimator?.cancel()
        contentStrip.updateLayoutParams<ConstraintLayout.LayoutParams> { applyStripLength(this) }
    }

    /** Re-applies the air above and below the strip after the gap slider moved. */
    fun refreshStripGap() {
        contentStrip.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topMargin = ctx.dp(2 + stripGapDp.getValue())
            bottomMargin = ctx.dp(2 + stripGapDp.getValue())
        }
    }

    /** Restyles the panel after a scheme switch. */
    fun refreshColors() {
        root.background = screenBackground
        refreshStripBackground()
        unrollButton.refreshColors()
        privacyButton.refreshColors()
        switchToggle.refreshColors()
        (singleSwitch as? ToolButton)?.refreshColors()
    }

    private companion object {
        /** Distance between the screen's edge and the inset strip. */
        const val STRIP_INSET_DP = 6

        /** Length of the candidates-to-switches flip, in milliseconds. */
        const val SWITCH_FADE_MS = 120L

        /**
         * Length of the strip growing or shrinking as the corner toggles step aside.
         *
         * Kept short on purpose: the bar changes width on every composition start and end, so a
         * long run stops reading as "the row made room" and starts reading as an animation the
         * user has to wait through, several times a sentence.
         */
        const val STRIP_WIDTH_MS = 140L

        /** Decelerating curve, so the bar arrives rather than stopping dead. */
        val STRIP_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
