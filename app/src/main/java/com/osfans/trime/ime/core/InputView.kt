/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.PreeditDelegate
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.allInstances
import org.kodein.di.bindInstance
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Height of the floating keyboard surface, whose drop shadow renders at this Z.
 */
internal const val FLOATING_KEYBOARD_ELEVATION_DP = 10f

/**
 * Z of the key popup layer (popup preview + popup keyboard).
 *
 * MUST stay strictly above [FLOATING_KEYBOARD_ELEVATION_DP]: the popup layer and the
 * keyboard are siblings, and Android paints siblings in ascending elevation order, so
 * an equal or lower value would let the floating keyboard cover the popups.
 */
internal const val POPUP_LAYER_ELEVATION_DP = 24f

internal fun resizedPercent(startPercent: Int, startPixels: Int, deltaPixels: Float, min: Int, max: Int): Int =
    (startPercent * (startPixels + deltaPixels) / startPixels).roundToInt().coerceIn(min, max)

/**
 * The preedit's clearance above the keyboard, in pixels.
 *
 * The preference is a dp value, so the pixel distance follows the display density: the gap keeps
 * its physical size on a phone, on a tablet, and after a rotation. Values outside the slider's
 * range -- a prefs file written by a build with a wider range, a hand edit -- are clamped rather
 * than trusted: this number ends up as a layout margin between two views, so a negative one
 * would pull the preedit into the keys and a runaway one would push it off the window.
 */
internal fun preeditGapPx(gapDp: Int, density: Float): Int =
    (gapDp.coerceIn(0, AppPrefs.Keyboard.PREEDIT_KEYBOARD_GAP_MAX_DP) * density).roundToInt()

/**
 * The overlap that hides the seam between the chip's square bottom edge and the floating card's
 * rounded top edge, in pixels.
 *
 * Only at a zero gap. While the two edges touch, a fractional layout coordinate can leave a
 * hairline of the window background showing between them, and one dp of overlap covers it. Once
 * the chip is lifted off the card there is no shared edge left to hide, and the clearance has to
 * be exactly the setting -- an unconditional overlap would shave a dp off every non-zero value.
 */
internal fun floatingPreeditOverlapPx(gapDp: Int, density: Float): Int =
    if (preeditGapPx(gapDp, density) == 0) density.roundToInt() else 0

/**
 * Narrowest the floating keyboard is allowed to get, in dp.
 *
 * The width preference is a share, and the share is taken against a reference that survives a
 * rotation (see [widthPercentForLayout]). On a device whose window is 1200px wide in portrait and
 * 2670px in landscape, a share of the *window* would make the same setting mean 1116px one way and
 * 2483px the other -- "too big in landscape, tiny in portrait". Pinning the floor to an absolute
 * size as well keeps the keyboard usable no matter how wide the window gets.
 */
internal const val FLOATING_KEYBOARD_MIN_WIDTH_DP = 160

/**
 * Floor for the floating keyboard's width share, below the absolute dp minimum above.
 *
 * The absolute minimum usually lands above this on a phone, so this only binds on very narrow
 * screens. It is a share rather than a size because the stored value is a share, and a drag that
 * reached a smaller one could not be written back without changing what the number means.
 */
internal const val MIN_FLOATING_KEYBOARD_WIDTH_PERCENT = 40

/**
 * Turns a width *share of [referenceWidthPx]* into the share of [windowWidthPx] the layout needs.
 *
 * This is what makes a chosen width survive a rotation: the reference is the screen's short side,
 * which is the same in both orientations, so the same setting produces the same physical width
 * instead of following the window around. Returns 100 when the geometry is not known yet (before
 * the first layout), which leaves the keyboard full width rather than guessing.
 */
internal fun widthPercentForLayout(
    widthPercent: Int,
    referenceWidthPx: Int,
    windowWidthPx: Int,
): Int {
    if (referenceWidthPx <= 0 || windowWidthPx <= 0) return 100
    val share = (widthPercent.toLong() * referenceWidthPx / windowWidthPx).toInt()
    return share.coerceIn(1, 100)
}

/**
 * Smallest share of [referenceWidthPx] that still leaves [minWidthDp] pixels of keyboard.
 *
 * Only bites on very wide windows (landscape, tablets); on a narrow one the ordinary lower bound
 * of the setting is already the larger of the two.
 */
internal fun minWidthPercentFor(
    minWidthDp: Int,
    referenceWidthPx: Int,
    density: Float,
): Int {
    if (referenceWidthPx <= 0 || density <= 0f) return 1
    val minPx = minWidthDp * density
    return (minPx * 100f / referenceWidthPx).roundToInt().coerceAtLeast(1)
}

/**
 * Horizontal scale that makes the composed width equal [widthPercent] percent of the width floor.
 */
internal fun floatingScaleX(widthPercent: Int, baseWidthPercent: Int): Float {
    if (baseWidthPercent <= 0) return 1f
    return widthPercent.toFloat() / baseWidthPercent
}

/**
 * Horizontal translation that keeps the floating keyboard within the window.
 *
 * The obvious `coerceIn(margin - left, window - margin - left - width)` collapses once the
 * keyboard is as wide as the window: the upper bound goes negative while the lower one stays
 * positive, and guarding it with `coerceAtLeast` pins the card to the left *margin* -- shoving it
 * 8dp to the right with its right edge hanging off the screen. That is not a corner case either:
 * 100% is the default width, and a card that cannot sit inside both margins has no such position,
 * so it is placed flush with the left edge instead. That is the only side the user can still
 * reach, and the right edge is where a full-width card has nothing left to give.
 */
internal fun floatingTranslationX(
    requestedX: Float,
    cardLeft: Int,
    visualWidth: Float,
    windowWidth: Int,
    margin: Float,
): Float {
    if (visualWidth >= windowWidth - 2 * margin) return 0f
    val min = margin - cardLeft
    val max = (windowWidth - margin - cardLeft - visualWidth).coerceAtLeast(min)
    return requestedX.coerceIn(min, max)
}

/**
 * Vertical scale for the floating keyboard.
 *
 * The toolbar keeps its themed height while the key area absorbs the whole delta. The delta is
 * expressed over [referenceHeight] (the IME window height, i.e. the space the keyboard can grow
 * into) and mapped onto [containerHeight] (the keyboard's own measured height), which is the
 * geometry the drag has always used.
 */
internal fun floatingScaleY(
    heightPercent: Int,
    baseHeightPercent: Int,
    containerHeight: Int,
    referenceHeight: Int,
): Float {
    if (baseHeightPercent <= 0 || containerHeight <= 0) return 1f
    return 1f +
        referenceHeight.toFloat() / containerHeight *
        (heightPercent - baseHeightPercent) / baseHeightPercent.toFloat()
}

/**
 * Horizontal anchor of the floating keyboard's grips, which pin themselves to the bottom edge of
 * the keyboard surface.
 */
internal enum class ActionHandleAnchor {
    /** Bottom-left corner of the surface. */
    SURFACE_START,

    /** Bottom-right corner of the surface. */
    SURFACE_END,
}

/** Horizontal margins of one bottom handle, in pixels. */
internal data class HandleMargins(
    val start: Int,
    val end: Int,
)

/**
 * Margins that keep a handle at the configured distance from the *sides* of the keyboard
 * surface.
 *
 * Only the horizontal axis is configurable. Vertically the row lives inside the bottom strip
 * (`bottomPaddingSpace`), whose own bottom margin carries the navigation bar inset -- so a
 * setting that moved the handles up would fight the strip instead of nudging them sideways,
 * which is the only thing this preference is for.
 *
 * Modern phones have rounded corners, and the corner-most handles would otherwise be clipped by
 * them. Only a handle pinned to a corner carries the horizontal margin; a chained handle follows
 * a neighbour that already carries it, so giving it one too would double the gap.
 */
internal fun handleMargins(anchor: ActionHandleAnchor, margin: Int): HandleMargins =
    HandleMargins(
        start = if (anchor == ActionHandleAnchor.SURFACE_START) margin else 0,
        end = if (anchor == ActionHandleAnchor.SURFACE_END) margin else 0,
    )

/** Edge length of the bottom handle icons, which the bottom strip has to be able to fit. */
internal const val ACTION_HANDLE_SIZE_DP = 36

/** Distance a bottom grip keeps from the surface edge, in dp. */
internal const val GRIP_MARGIN_DP = 12

/**
 * Height of the strip reserved below the keys, in dp.
 *
 * The strip exists for one reason: the IME toggles live in it, and a theme that reserves less
 * room than they need (`keyboard_padding_bottom: 0` is common) would have them sitting on the
 * keys. So the theme's own value is honoured as a floor, never as a ceiling — a theme can make
 * the strip taller, and it can never make it too short to hold the toggles.
 *
 * The strip is deliberately *not* painted: the keyboard's own background already covers the whole
 * surface (see [applyKeyboardBackdrop]), so filling it again with the same translucent colour
 * composited twice and left a visible band across the bottom of the keyboard.
 */
internal fun reservedBottomStripDp(
    themePaddingDp: Int,
    handleSizeDp: Int,
): Int = maxOf(themePaddingDp, handleSizeDp)

/** Lets a floating keyboard move with two fingers even when keys fill its surface. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class FloatingKeyboardContainer(
    context: Context,
    floating: Boolean,
    private val onDrag: (MotionEvent, Boolean) -> Boolean,
) : ConstraintLayout(context) {
    var floating: Boolean = floating
        private set

    fun setFloatingEnabled(enabled: Boolean) {
        floating = enabled
        cancelArming()
    }

    private var twoFingerDrag = false
    private var dragEnded = false

    // A second finger only *arms* the drag, it never starts it. Typing with two thumbs
    // produces exactly the same pointer pattern, and intercepting on ACTION_POINTER_DOWN
    // would cancel the key press already in flight and swallow the one that just began --
    // both keys go dead, and every following MOVE then re-runs the floating position sync,
    // which reads as "the keyboard froze and ignored my keys". The drag engages once the
    // fingers have actually travelled, which is what a deliberate drag always does.
    private var dragArmed = false
    private val armedIds = intArrayOf(MotionEvent.INVALID_POINTER_ID, MotionEvent.INVALID_POINTER_ID)
    private val armedX = FloatArray(2)
    private val armedY = FloatArray(2)

    // Twice the standard slop: a fast tap can slide a little, a real drag never stays under this.
    private val armingSlop = ViewConfiguration.get(context).scaledTouchSlop * 2

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!floating) return super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) armDrag(event)

            MotionEvent.ACTION_MOVE -> {
                if (dragArmed && hasArmedPointerMoved(event)) {
                    cancelArming()
                    twoFingerDrag = true
                    dragEnded = false
                    // Seed from the current position so the keyboard tracks the finger
                    // instead of jumping by the distance travelled while arming.
                    sendDragAction(event, MotionEvent.ACTION_DOWN)
                    return true
                }
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelArming()
        }
        return super.onInterceptTouchEvent(event)
    }

    private fun armDrag(event: MotionEvent) {
        dragArmed = true
        armedIds[0] = event.getPointerId(0)
        armedX[0] = event.getX(0)
        armedY[0] = event.getY(0)
        if (event.pointerCount > 1) {
            armedIds[1] = event.getPointerId(1)
            armedX[1] = event.getX(1)
            armedY[1] = event.getY(1)
        } else {
            armedIds[1] = MotionEvent.INVALID_POINTER_ID
        }
    }

    private fun hasArmedPointerMoved(event: MotionEvent): Boolean {
        for (i in armedIds.indices) {
            val id = armedIds[i]
            if (id == MotionEvent.INVALID_POINTER_ID) continue
            // Track by pointer id: indices shift as fingers lift, ids do not.
            val index = event.findPointerIndex(id)
            if (index < 0) continue
            val dx = event.getX(index) - armedX[i]
            val dy = event.getY(index) - armedY[i]
            if (dx * dx + dy * dy > armingSlop * armingSlop) return true
        }
        return false
    }

    private fun cancelArming() {
        dragArmed = false
        armedIds.fill(MotionEvent.INVALID_POINTER_ID)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!twoFingerDrag) return if (floating) onDrag(event, true) else super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (!dragEnded) onDrag(event, false)
            MotionEvent.ACTION_POINTER_UP -> {
                if (!dragEnded) sendDragAction(event, MotionEvent.ACTION_UP)
                dragEnded = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragEnded) sendDragAction(event, event.actionMasked)
                twoFingerDrag = false
                dragEnded = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun sendDragAction(event: MotionEvent, action: Int) {
        MotionEvent.obtain(event).also {
            it.action = action
            onDrag(it, false)
            it.recycle()
        }
    }
}

/** A small grip inside the card corner, so resizing adds no keyboard height. */
@SuppressLint("ViewConstructor")
private class FloatingResizeHandle(
    context: Context,
    private val onResize: (MotionEvent) -> Unit,
) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        strokeCap = Paint.Cap.ROUND
    }

    fun setGripColor(backgroundColor: Int) {
        stroke.color = if (ColorUtils.calculateLuminance(backgroundColor) > 0.5) Color.DKGRAY else Color.WHITE
        stroke.alpha = 180
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spacing = context.dp(5).toFloat()
        val inset = context.dp(9).toFloat()
        for (line in 0..2) {
            val endX = width - inset - line * spacing
            val endY = height - inset
            canvas.drawLine(endX - spacing * 2, endY, endX, endY - spacing * 2, stroke)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                onResize(event)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL -> onResize(event)
            MotionEvent.ACTION_UP -> {
                onResize(event)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** Compact movement grip in the existing bottom padding. */
@SuppressLint("ViewConstructor")
private class FloatingMoveHandle(
    context: Context,
    private val onDrag: (MotionEvent) -> Boolean,
) : View(context) {
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setGripColor(backgroundColor: Int) {
        dots.color = if (ColorUtils.calculateLuminance(backgroundColor) > 0.5) Color.DKGRAY else Color.WHITE
        dots.alpha = 180
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spacing = context.dp(6).toFloat()
        val radius = context.dp(1.5f)
        for (row in -1..1) {
            for (column in -1..1) {
                canvas.drawCircle(width / 2f + column * spacing, height / 2f + row * spacing, radius, dots)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true)
        onDrag(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

@SuppressLint("ViewConstructor")
private class FloatingActionHandle(context: Context) : ImageView(context) {
    init {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val padding = context.dp(8)
        setPadding(padding, padding, padding, padding)
        isClickable = true
        // IME controls must not take focus away from the active editor.
        isFocusable = false
        isFocusableInTouchMode = false
        val attributes = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackgroundBorderless),
        )
        background = attributes.getDrawable(0)
        attributes.recycle()
    }

    fun setAction(
        @DrawableRes icon: Int,
        description: CharSequence,
        tint: Int,
    ) {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(tint)
        contentDescription = description
    }
}

/**
 * Successor of the old InputRoot
 */
@SuppressLint("ViewConstructor")
class InputView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    scope: ThemeScope,
) : BaseInputView(service, rime, scope),
    DIAware {
    private val keyboardPrefs = AppPrefs.defaultInstance().keyboard

    var isFloatingKeyboardEnabled = keyboardPrefs.floatingKeyboard.getValue()
        private set
    /**
     * Percentage the surface would occupy with no extra scaling, i.e. the value the scale
     * factors are expressed against.
     *
     * Mutable on purpose: after a resize the drag *persists* new percentages, and the surface
     * is then rebuilt at those percentages with `scale = 1`. If these stayed pinned to the
     * values captured at construction, the next resize would apply the new ratio on top of an
     * already-scaled surface and the size would run away. See [handleFloatingResize].
     */
    private var floatingKeyboardBaseWidth = keyboardPrefs.floatingKeyboardWidth.getValue()
    private var floatingKeyboardBaseHeight = keyboardPrefs.floatingKeyboardHeight.getValue()
    private var floatingKeyboardWidth = floatingKeyboardBaseWidth
    private var floatingKeyboardHeight = floatingKeyboardBaseHeight
    private var preeditOffsetPercent = keyboardPrefs.preeditOffsetPercent.getValue().coerceIn(0, 100)

    /** The clearance the preedit keeps above the keyboard, in pixels. */
    private val preeditGap: Int
        get() = preeditGapPx(
            gapDp = keyboardPrefs.preeditKeyboardGap.getValue(),
            density = resources.displayMetrics.density,
        )

    /** The one-dp seam overlap the floating card needs while the chip sits flush on it. */
    private val preeditSeam: Int
        get() = floatingPreeditOverlapPx(
            gapDp = keyboardPrefs.preeditKeyboardGap.getValue(),
            density = resources.displayMetrics.density,
        )

    private val keyboardBackground =
        imageView {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    private val placeholderListener = OnClickListener { }

    private val leftPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val rightPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val bottomPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val updateWindowViewHeightJob: Job

    override val di = DI {
        bindInstance<InputView> { this@InputView }
        bindInstance<ContextThemeWrapper> { themedContext }
        bindInstance<ThemeScope> { scope }
        bindInstance<Theme> { scope.theme }
        bindInstance<TrimeInputMethodService> { service }
        bindInstance<RimeSession> { rime }
        bindSingleton { InputBroadcaster() }
        bindSingleton { PopupDelegate(di) }
        bindSingleton { EnterKeyDisplayDelegate(di) }
        bindSingleton { PreeditDelegate(di) }
        bindSingleton { CommonKeyboardActionListener(di) }
        bindSingleton { BoardWindowManager(di) }
        bindSingleton { InputBarDelegate(di) }
        bindSingleton { CompactCandidateDelegate(di) }
        bindSingleton { KeyboardWindow(di) }
        bindSingleton { LiquidWindow(di) }
    }

    private val broadcaster: InputBroadcaster by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()
    private val preedit: PreeditDelegate by instance()
    private val windowManager: BoardWindowManager by instance()
    private val inputBar: InputBarDelegate by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()
    private val keyboardWindow: KeyboardWindow by instance()
    private val liquidWindow: LiquidWindow by instance()

    private val candidatesMode by AppPrefs.defaultInstance().candidates.mode

    private val keyboardSidePadding = theme.generalStyle.keyboardPadding
    private val keyboardSidePaddingLandscape = theme.generalStyle.keyboardPaddingLand
    private val keyboardBottomPadding = theme.generalStyle.keyboardPaddingBottom
    private val keyboardBottomPaddingLandscape = theme.generalStyle.keyboardPaddingLandBottom

    private val keyboardSidePaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardSidePaddingLandscape else keyboardSidePadding
            return dp(value)
        }

    private var lastAppearanceState = Triple(false, false, false)

    private fun broadcastKeyAppearanceUpdate() {
        // Read through the cached accessors rather than rime.run { }: this runs on every rime
        // message, and a keystroke produces several of them, so three runBlocking round trips
        // per message added up to over a dozen stalls of the UI thread per keypress. The cached
        // properties read @Volatile snapshots and never touch the native layer.
        val current = Triple(rime.status.isComposing, rime.hasMenu, rime.paging)
        if (current != lastAppearanceState) {
            lastAppearanceState = current
            broadcaster.onKeyAppearanceUpdate(current.first, current.second, current.third)
        }
    }

    /**
     * Height of the strip below the keys: the theme's own reservation (`keyboard_padding_bottom`
     * or its landscape counterpart), never shorter than the toggle row it has to hold.
     */
    private val keyboardBottomPaddingPx: Int
        get() {
            val landscape = context.isLandscapeMode()
            val themePadding =
                if (landscape) keyboardBottomPaddingLandscape else keyboardBottomPadding
            return dp(reservedBottomStripDp(themePadding, ACTION_HANDLE_SIZE_DP))
        }

    /**
     * Distance kept between a grip and the surface it is pinned to.
     *
     * The floating card has rounded corners of its own, and a grip sitting flush in one of them
     * would be half outside the card's outline. Fixed rather than a setting: the only handles left
     * down here are the two grips, and there is one sensible distance for them.
     */
    private val bottomHandleMarginPx: Int
        get() = dp(GRIP_MARGIN_DP)

    val keyboardView: View

    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartTranslationX = 0f
    private var dragStartTranslationY = 0f
    private var dragMoved = false
    private var lastHandleTapTime = 0L
    private val dragTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var resizeDownRawX = 0f
    private var resizeDownRawY = 0f
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeStartScaleX = 1f
    private var resizeStartScaleY = 1f
    private var resizeStartWidthPercent = floatingKeyboardWidth
    private var resizeStartHeightPercent = floatingKeyboardHeight
    private var resizedWidth = floatingKeyboardWidth
    private var resizedHeight = floatingKeyboardHeight

    /**
     * Window-space bounds of the floating layer as last reported to the IME window.
     *
     * Guarded by [hasLastFloatingBounds] because the bounds are unknown (and the whole
     * floating layout is inactive) until the keyboard is actually laid out in floating mode.
     */
    private val lastFloatingBounds = Rect()
    private var hasLastFloatingBounds = false

    private val resizeHandle = FloatingResizeHandle(context, ::handleFloatingResize)
    private val moveHandle = FloatingMoveHandle(context) { handleFloatingDrag(it) }

    private fun dispatchKeyboardCommand(command: String) {
        commonKeyboardActionListener.listener.onAction(KeyActionManager.getCommandAction(command))
    }

    /**
     * The width the floating width percentage is measured against: the screen's **short** side.
     *
     * A rotation swaps the window's width and height, so a share of the window itself would mean
     * two different physical sizes for one setting — the keyboard ends up filling a landscape
     * screen and looking tiny in portrait. The short side is the one dimension a rotation leaves
     * alone, which is what makes the chosen width hold still across the turn.
     */
    private fun referenceWidthPx(): Int {
        val metrics = resources.displayMetrics
        return minOf(metrics.widthPixels, metrics.heightPixels)
    }

    /** The share [minWidthPercent] of the reference width, floored by an absolute size. */
    private fun minWidthPercent(): Int =
        minWidthPercentFor(
            minWidthDp = FLOATING_KEYBOARD_MIN_WIDTH_DP,
            referenceWidthPx = referenceWidthPx(),
            density = resources.displayMetrics.density,
        ).coerceAtLeast(MIN_FLOATING_KEYBOARD_WIDTH_PERCENT)

    /** Converts the stored width share into the share the layout has to use. */
    private fun layoutWidthPercent(widthPercent: Int): Int =
        widthPercentForLayout(
            widthPercent = widthPercent,
            referenceWidthPx = referenceWidthPx(),
            windowWidthPx = width,
        )

    private fun handleFloatingResize(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeDownRawX = event.rawX
                resizeDownRawY = event.rawY
                resizeStartWidth = keyboardView.width
                resizeStartHeight = windowManager.view.height
                resizeStartScaleX = keyboardView.scaleX
                resizeStartScaleY = keyboardView.scaleY
                resizeStartWidthPercent = floatingKeyboardWidth
                resizeStartHeightPercent = floatingKeyboardHeight
                resizedWidth = resizeStartWidthPercent
                resizedHeight = resizeStartHeightPercent
                keyboardView.pivotX = 0f
                keyboardView.pivotY = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizeStartWidth == 0 || resizeStartHeight == 0) return
                resizedWidth = resizedPercent(
                    resizeStartWidthPercent,
                    (resizeStartWidth * resizeStartScaleX).roundToInt(),
                    event.rawX - resizeDownRawX,
                    minWidthPercent(),
                    100,
                )
                resizedHeight = resizedPercent(
                    resizeStartHeightPercent,
                    (resizeStartHeight * resizeStartScaleY).roundToInt(),
                    event.rawY - resizeDownRawY,
                    70,
                    130,
                )
                keyboardView.scaleX = floatingScaleX(resizedWidth, floatingKeyboardBaseWidth)
                // The toolbar height is fixed, while the key area follows the height preference.
                keyboardView.scaleY =
                    floatingScaleY(
                        resizedHeight,
                        floatingKeyboardBaseHeight,
                        keyboardView.height,
                        resizeStartHeight,
                    )
                applyFloatingPosition(
                    keyboardView.translationX,
                    keyboardView.translationY,
                    persist = false,
                )
            }
            MotionEvent.ACTION_UP -> {
                floatingKeyboardWidth = resizedWidth
                floatingKeyboardHeight = resizedHeight
                // The surface is now persisted at these percentages, so they *are* the baseline:
                // the width reaches them through the layout (the preference drives the measured
                // width), and the height has no layout carrier at all, so it still has to be
                // expressed as a scale. Pinning the baseline here is what keeps a second resize
                // from compounding onto the previous one.
                floatingKeyboardBaseWidth = resizedWidth
                floatingKeyboardBaseHeight = resizedHeight
                keyboardView.updateLayoutParams<LayoutParams> {
                    matchConstraintPercentWidth = layoutWidthPercent(resizedWidth) / 100f
                }
                keyboardView.doOnLayout { applyFloatingScale() }
                service.updateFloatingKeyboardSize(resizedWidth, resizedHeight)
                service.requestInputInsetsUpdate()
            }
            MotionEvent.ACTION_CANCEL -> {
                keyboardView.scaleX = resizeStartScaleX
                keyboardView.scaleY = resizeStartScaleY
                applyFloatingPosition(
                    keyboardView.translationX,
                    keyboardView.translationY,
                    persist = false,
                )
            }
        }
    }

    /**
     * Pushes the persisted size preferences onto the surface as `scaleX` / `scaleY`.
     *
     * The width is also carried by the layout ([`matchConstraintPercentWidth`]), so its scale is
     * normally identity and only differs while the container still measures at the previous
     * percentage. The height has no layout carrier -- the key grid's height comes from the theme,
     * not from a preference -- so this scale is the *only* thing that keeps the height preference
     * alive across a rebuild. Without it a rebuild silently drops back to the baseline height
     * while the width is honoured, which reads as "the keyboard's proportions are wrong".
     */
    private fun applyFloatingScale() {
        if (!isFloatingKeyboardEnabled || keyboardView.width == 0 || keyboardView.height == 0) return
        keyboardView.pivotX = 0f
        keyboardView.pivotY = 0f
        keyboardView.scaleX = 1f
        keyboardView.scaleY =
            floatingScaleY(
                floatingKeyboardHeight,
                floatingKeyboardBaseHeight,
                keyboardView.height,
                windowManager.view.height,
            )
        applyFloatingPosition(
            keyboardView.translationX,
            keyboardView.translationY,
            persist = false,
        )
    }

    private fun syncFloatingPreeditPosition() {
        if (!isFloatingKeyboardEnabled || keyboardView.width == 0) return
        val preeditRoot = preedit.ui.root
        if (preeditRoot.layoutParams.width != keyboardView.width) {
            preeditRoot.updateLayoutParams {
                width = keyboardView.width
            }
        }
        // Horizontal position is window-wide and shared by both modes; only the vertical one
        // rides the card while floating. The distance itself is the gap preference's bottom
        // margin, already laid out above the card; what is left is the seam overlap -- and that
        // is zero as soon as the gap has lifted the chip off the card.
        applyPreeditHorizontalPosition()
        preeditRoot.pivotX = 0f
        preeditRoot.scaleX = keyboardView.scaleX
        preeditRoot.translationY = keyboardView.translationY + preeditSeam
        preedit.updateTouchReceiverPosition()
    }

    /**
     * Puts the preedit at its horizontal position along the keyboard's own span.
     *
     * The span is the keyboard area itself -- the full-width keyboard while docked, the floating
     * card while floating -- so the preedit is always placed against the keyboard and can never
     * leave it: 0% sits flush with the keyboard's left edge and 100% with its right, the value
     * being a percentage of the room the preedit may travel (the span minus its own width). The
     * preedit's width changes with every keystroke, so this runs from a layout listener rather
     * than once at build time.
     */
    private fun applyPreeditHorizontalPosition() {
        val root = preedit.ui.root
        val chip = preedit.ui.preedit
        val trackLeft = keyboardView.left + keyboardView.translationX
        val travel = (keyboardView.width * keyboardView.scaleX - chip.width).coerceAtLeast(0f)
        root.translationX =
            trackLeft + travel * (preeditOffsetPercent / 100f) - root.paddingStart - root.left
    }

    /** Re-places the preedit after its position preference changed, without a rebuild. */
    fun refreshPreeditPosition() {
        // The captured value would be stale; re-read through the delegate.
        preeditOffsetPercent = keyboardPrefs.preeditOffsetPercent.getValue().coerceIn(0, 100)
        applyPreeditHorizontalPosition()
    }

    /** Re-applies the preedit's clearance above the keyboard, without a rebuild. */
    fun refreshPreeditGap() {
        applyPreeditGap()
        // Floating, the chip is part of the surface's touchable rectangle, so the window manager
        // has to hear about its new bounds; docked, the edge the region ends at does not move.
        if (isFloatingKeyboardEnabled) service.requestInputInsetsUpdate()
    }

    /** Re-applies the chip's own look -- corners and fill -- after a settings change. */
    fun refreshPreeditAppearance() {
        preedit.refreshAppearance()
    }

    /**
     * Sets how far the preedit sits above the keyboard.
     *
     * The preedit is a sibling anchored above the keyboard, so the gap is that constraint's
     * bottom margin: the chip does not change size, the keyboard does not move, and the space in
     * between belongs to neither view. A margin also cannot overlap the keys -- the chip's bottom
     * edge is pinned to the keyboard's top edge minus the margin -- and being a dp value it means
     * the same physical distance at any density or window size. Nothing is rebuilt, which is what
     * makes the change land on the keyboard that is already on screen.
     *
     * The edge it measures against is the card's top edge while the keyboard floats, which is why
     * one preference covers both modes: the floating layer is translated as a whole, and the
     * preedit is translated with it, so a margin in the layout is what the chip keeps off the card.
     * At zero the floating layer adds [preeditSeam] back to stay flush; any larger value is the
     * clearance itself. The cost is that the gap falls inside the single touchable rectangle the
     * floating surface reports, so a drag there moves the card rather than reaching the app -- a
     * one-preference layout is worth that, and the band only exists while it is deliberately left
     * empty.
     */
    private fun applyPreeditGap() {
        preedit.ui.root.updateLayoutParams<LayoutParams> { bottomMargin = preeditGap }
        // Both followers read the preedit's laid-out position, so they have to wait for the new
        // margin: floating, the chip is placed by a translation computed from that position; docked,
        // only the touch receiver -- a popup anchored to the view -- has to follow it.
        preedit.ui.root.doOnLayout {
            if (isFloatingKeyboardEnabled) {
                syncFloatingPreeditPosition()
            } else {
                preedit.updateTouchReceiverPosition()
            }
        }
    }

    private fun applyFloatingPosition(
        requestedX: Float,
        requestedY: Float,
        persist: Boolean,
    ) {
        if (!isFloatingKeyboardEnabled || width == 0 || keyboardView.width == 0) return

        val horizontalMargin = dp(8).toFloat()
        val visualWidth = keyboardView.width * keyboardView.scaleX
        val resolvedX =
            floatingTranslationX(
                requestedX = requestedX,
                cardLeft = keyboardView.left,
                visualWidth = visualWidth,
                windowWidth = width,
                margin = horizontalMargin,
            )
        val floatingTop =
            preedit.ui.root
                .takeIf { it.visibility == View.VISIBLE && it.height > 0 }
                ?.let { it.top + preeditSeam }
                ?: keyboardView.top
        val minTranslationY = -(floatingTop - dp(16)).coerceAtLeast(0).toFloat()
        val maxTranslationY =
            (height - dp(8) - keyboardView.top - keyboardView.height * keyboardView.scaleY)
                .coerceAtLeast(minTranslationY)
        val resolvedY = requestedY.coerceIn(minTranslationY, maxTranslationY)

        keyboardView.translationX = resolvedX
        keyboardView.translationY = resolvedY
        syncFloatingPreeditPosition()
        requestInsetsUpdateIfFloatingBoundsChanged()

        if (persist) {
            keyboardPrefs.floatingKeyboardOffsetX.setValue(resolvedX.roundToInt())
            keyboardPrefs.floatingKeyboardOffsetY.setValue(resolvedY.roundToInt())
        }
    }

    /**
     * Re-specify the IME window's touchable region, but only when it actually moved.
     *
     * [TrimeInputMethodService.requestInputInsetsUpdate] lays the *entire* input view out
     * again and makes the framework call `onComputeInsets`, which pushes a new touchable
     * region to the window manager. Floating mode re-runs [applyFloatingPosition] for every
     * rime composition message -- that is, once per keystroke -- so an unconditional call
     * here means a full input-view measure/layout plus a window manager transaction per key.
     * The UI thread then trails the input queue and the keyboard appears to freeze.
     *
     * The region is a pure function of the floating layer's position and size, so an
     * unchanged region needs no update. Only [applyFloatingPosition] is throttled this way:
     * drags must keep the region in step with the finger, otherwise the framework would
     * send the rest of the gesture to the app behind the keyboard.
     */
    private fun requestInsetsUpdateIfFloatingBoundsChanged() {
        val bounds = Rect()
        if (getFloatingKeyboardBoundsInWindow(bounds)) {
            if (hasLastFloatingBounds && bounds == lastFloatingBounds) return
            lastFloatingBounds.set(bounds)
            hasLastFloatingBounds = true
        } else {
            // Not measurable yet; report unconditionally and stop comparing.
            hasLastFloatingBounds = false
        }
        service.requestInputInsetsUpdate()
    }

    private fun resetFloatingPosition() {
        lastHandleTapTime = 0L
        applyFloatingPosition(0f, 0f, persist = true)
    }

    private fun handleFloatingDrag(event: MotionEvent, allowTap: Boolean = true): Boolean {
        if (!isFloatingKeyboardEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragDownRawX = event.rawX
                dragDownRawY = event.rawY
                dragStartTranslationX = keyboardView.translationX
                dragStartTranslationY = keyboardView.translationY
                dragMoved = false
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - dragDownRawX
                val deltaY = event.rawY - dragDownRawY
                if (abs(deltaX) > dragTouchSlop || abs(deltaY) > dragTouchSlop) {
                    dragMoved = true
                }
                applyFloatingPosition(
                    dragStartTranslationX + deltaX,
                    dragStartTranslationY + deltaY,
                    persist = false,
                )
            }

            MotionEvent.ACTION_UP -> {
                if (dragMoved || !allowTap) {
                    applyFloatingPosition(
                        keyboardView.translationX,
                        keyboardView.translationY,
                        persist = true,
                    )
                } else if (event.eventTime - lastHandleTapTime <= ViewConfiguration.getDoubleTapTimeout()) {
                    resetFloatingPosition()
                } else {
                    lastHandleTapTime = event.eventTime
                    keyboardView.performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                applyFloatingPosition(
                    keyboardView.translationX,
                    keyboardView.translationY,
                    persist = true,
                )
            }
        }
        return true
    }

    fun getFloatingKeyboardBoundsInWindow(outBounds: Rect): Boolean {
        if (!isFloatingKeyboardEnabled || !keyboardView.isShown || keyboardView.width == 0) return false
        keyboardView.getLocationInWindow(inputViewLocation)
        val visualWidth = (keyboardView.width * keyboardView.scaleX).roundToInt()
        val visualHeight = (keyboardView.height * keyboardView.scaleY).roundToInt()
        outBounds.set(
            inputViewLocation[0],
            inputViewLocation[1],
            inputViewLocation[0] + visualWidth,
            inputViewLocation[1] + visualHeight,
        )
        val preeditRoot = preedit.ui.root
        if (preeditRoot.visibility == View.VISIBLE && preeditRoot.width > 0 && preeditRoot.height > 0) {
            preeditRoot.getLocationInWindow(preeditViewLocation)
            outBounds.union(
                preeditViewLocation[0],
                preeditViewLocation[1],
                preeditViewLocation[0] + preeditRoot.width,
                preeditViewLocation[1] + preeditRoot.height,
            )
        }
        return true
    }

    private val inputViewLocation = intArrayOf(0, 0)
    private val preeditViewLocation = intArrayOf(0, 0)

    /** Switch floating mode on the existing tree so a custom key does not rebuild the IME view. */
    fun setFloatingKeyboardEnabled(enabled: Boolean) {
        if (isFloatingKeyboardEnabled == enabled) return
        isFloatingKeyboardEnabled = enabled
        // The layout is about to be reflowed; the cached region no longer describes it.
        hasLastFloatingBounds = false
        (keyboardView as FloatingKeyboardContainer).setFloatingEnabled(enabled)
        configureFloatingSurface(enabled)
        inputBar.onFloatingKeyboardChanged(enabled)

        keyboardView.updateLayoutParams<LayoutParams> {
            width = if (enabled) 0 else matchParent
            matchConstraintPercentWidth = if (enabled) layoutWidthPercent(floatingKeyboardWidth) / 100f else 1f
            bottomMargin = if (enabled) dp(18) else 0
        }

        if (enabled) {
            keyboardView.translationX = 0f
            keyboardView.translationY = 0f
        } else {
            keyboardView.translationX = 0f
            keyboardView.translationY = 0f
            keyboardView.scaleX = 1f
            keyboardView.scaleY = 1f
            preedit.ui.root.apply {
                translationY = 0f
                scaleX = 1f
                updateLayoutParams { width = wrapContent }
            }
            // The preedit's own width is the travel span again, so re-place it.
            applyPreeditHorizontalPosition()
        }

        // Wait for the resized card to be measured, then rebuild only the key grid.
        // Keyboard height is baked into Key geometry, while the surrounding IME tree
        // and Rime session remain attached.
        windowManager.view.doOnLayout {
            keyboardWindow.refreshKeyboards()
            if (windowManager.isAttached(keyboardWindow)) {
                windowManager.view.updateLayoutParams<LayoutParams> {
                    height = KeyboardWindow.currentKeyboard.keyboardHeight
                }
            }
            if (enabled) {
                keyboardView.post {
                    // The card is measured now, so the persisted height percentage can be put
                    // back on the freshly measured surface.
                    applyFloatingScale()
                    applyFloatingPosition(
                        keyboardPrefs.floatingKeyboardOffsetX.getValue().toFloat(),
                        keyboardPrefs.floatingKeyboardOffsetY.getValue().toFloat(),
                        persist = false,
                    )
                }
            }
            service.requestInputInsetsUpdate()
        }
        service.requestInputInsetsUpdate()
    }

    fun refreshHideInputBar() {
        inputBar.onHideInputBarChanged()
    }

    /** Re-applies the composing strip's inset-bar background after its switch changed. */
    fun refreshStripBackground() {
        inputBar.refreshStripBackground()
    }

    /** Re-applies the composing strip's length after its preference changed. */
    fun refreshStripLength() {
        inputBar.refreshStripLength()
    }

    /** Shows or hides the strip row's two corner toggles after their switch changed. */
    fun refreshCornerToggles() {
        inputBar.refreshCornerToggles()
    }

    /** Re-sizes the input bar after the strip-to-keyboard gap changed. */
    fun refreshStripGap() {
        inputBar.refreshStripGap()
    }

    /** Re-sizes the input bar after the two-row strip's second line changed its text size. */
    fun refreshTwoRow() {
        inputBar.refreshTwoRow()
    }

    private fun configureFloatingSurface(enabled: Boolean) {
        val surface = keyboardView as FloatingKeyboardContainer
        if (enabled) {
            surface.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24f)
                setColor(scope.colors.keyboardBackColor)
            }
            surface.clipToOutline = true
            surface.elevation = dp(FLOATING_KEYBOARD_ELEVATION_DP)
            surface.isClickable = true
            surface.contentDescription = context.getString(R.string.floating_keyboard_drag_handle)
            surface.setOnClickListener { }
        } else {
            surface.background = null
            surface.clipToOutline = false
            surface.elevation = 0f
            surface.isClickable = false
            surface.contentDescription = null
            surface.setOnClickListener(null)
        }

        moveHandle.apply {
            contentDescription = context.getString(R.string.floating_keyboard_drag_handle)
            isClickable = enabled
            visibility = if (enabled) View.VISIBLE else View.GONE
            setGripColor(scope.colors.keyboardBackColor)
        }
        resizeHandle.apply {
            contentDescription = context.getString(R.string.floating_keyboard_resize_handle)
            isClickable = enabled
            visibility = if (enabled) View.VISIBLE else View.GONE
            setGripColor(scope.colors.keyboardBackColor)
        }
        if (moveHandle.parent == null) {
            surface.add(moveHandle, lParams(dp(ACTION_HANDLE_SIZE_DP), dp(ACTION_HANDLE_SIZE_DP)) { bottomOfParent() })
        }
        if (resizeHandle.parent == null) {
            surface.add(resizeHandle, lParams(dp(ACTION_HANDLE_SIZE_DP), dp(ACTION_HANDLE_SIZE_DP)) { bottomOfParent() })
        }
        // The grips' anchors depend on the mode, so the row is placed here rather than in the
        // layout params it was added with.
        placeBottomHandles()
    }

    /**
     * Places the floating keyboard's two grips.
     *
     * They are the only controls left in the bottom strip: the IME toggles that used to sit beside
     * them (input bar, floating, candidate window) are quick switches now, reached through the
     * strip's own toggle, which is where the user goes for that row of controls anyway.
     */
    private fun placeBottomHandles() {
        val margin = bottomHandleMarginPx
        moveHandle.updateLayoutParams<ConstraintLayout.LayoutParams> {
            anchorTo(ActionHandleAnchor.SURFACE_START, margin)
        }
        resizeHandle.updateLayoutParams<ConstraintLayout.LayoutParams> {
            anchorTo(ActionHandleAnchor.SURFACE_END, margin)
        }
    }

    /**
     * Pins the handle inside the bottom strip, at the requested horizontal anchor.
     *
     * The vertical constraint targets [bottomPaddingSpace] rather than the surface edge: that
     * strip is the row's container and is the one that knows how much room the bottom of the
     * screen takes (its bottom margin carries the navigation bar inset). The margins themselves
     * go through the *relative* [marginStart] / [marginEnd] properties on purpose: the splitties
     * helpers used here write absolute margins (and `endOfParent` writes `rightMargin` directly),
     * which the start/end resolution that runs on every `setLayoutParams` would then overwrite
     * with zero. Only a handle pinned to a corner keeps the edge margin -- a chained one follows
     * a neighbour that already carries it.
     */
    private fun ConstraintLayout.LayoutParams.anchorTo(anchor: ActionHandleAnchor, margin: Int) {
        // Clear first: an anchor left over from the previous placement must not survive.
        startToStart = ConstraintLayout.LayoutParams.UNSET
        startToEnd = ConstraintLayout.LayoutParams.UNSET
        endToStart = ConstraintLayout.LayoutParams.UNSET
        endToEnd = ConstraintLayout.LayoutParams.UNSET
        when (anchor) {
            ActionHandleAnchor.SURFACE_START -> startOfParent()
            ActionHandleAnchor.SURFACE_END -> endOfParent()
        }
        // Vertical placement inside the strip: centred, so a theme that reserves a tall one
        // does not leave the row stranded against its bottom edge. The strip is never shorter
        // than a handle (that is what the height floor guarantees), so centring cannot push one
        // off the edge either.
        topToTop = bottomPaddingSpace.id
        topToBottom = ConstraintLayout.LayoutParams.UNSET
        bottomToTop = ConstraintLayout.LayoutParams.UNSET
        bottomToBottom = bottomPaddingSpace.id
        bottomMargin = 0
        val margins = handleMargins(anchor, margin)
        marginStart = margins.start
        marginEnd = margins.end
    }

    /**
     * Re-reads the bottom bar preferences at runtime: its height, whether it is reserved at all,
     * and the toggle tint that follows from it. The keyboard changes height, so the IME window's
     * insets have to be recomputed as well.
     */
    fun refreshBottomBar() {
        applyBottomBar()
        service.requestInputInsetsUpdate()
    }

    /**
     * The keyboard's own surface: the theme's background image when it has a usable one, and the
     * keyboard's back colour underneath it either way.
     *
     * The colour is not optional. Without it, a theme that names a background image the device
     * cannot load — a file that was never pushed, a path that does not resolve — leaves this view
     * empty, and an empty view over an IME window shows the window's own background: `colorSurface`,
     * which is white under a light app theme. The keyboard then comes out white whatever the colour
     * scheme says, and no scheme can change it.
     *
     * Painted as the view's background rather than as a second layer, because that puts it *under*
     * the image: a theme with a translucent image keeps the colour showing through it, and a theme
     * with no image gets a keyboard in its own colour instead of a hole.
     *
     * That colour is also the whole of the frosted-glass effect: a translucent back colour
     * composited over the app behind is what stands in for a blur. A real cross-window blur was
     * built and then removed (2026-10-14) — the platform only blurs the whole IME window, which
     * would frost the app content above the keyboard along with the panel. Should that ever
     * change, this is the only place that needs to know.
     */
    private fun applyKeyboardBackdrop() {
        keyboardBackground.background =
            GradientDrawable().apply { setColor(scope.colors.keyboardBackColor) }
        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")
    }

    /** Restyles colors after a scheme switch without rebuilding the view tree. */
    fun refreshColors() {
        applyKeyboardBackdrop()
        if (isFloatingKeyboardEnabled) {
            (keyboardView.background as? GradientDrawable)?.setColor(scope.colors.keyboardBackColor)
        }
        applyBottomBar()
        resizeHandle.setGripColor(scope.colors.keyboardBackColor)
        moveHandle.setGripColor(scope.colors.keyboardBackColor)
        popup.refreshColors()
        keyboardWindow.refreshColors()
        inputBar.refreshColors()
        preedit.refreshColors()
        windowManager.refreshColors()
    }

    init {
        // MUST call before any operation
        val receivers: List<InputBroadcastReceiver> by allInstances()
        receivers.forEach { broadcaster.addReceiver(it) }

        windowManager.cacheResidentWindow(keyboardWindow, createView = true)
        windowManager.cacheResidentWindow(liquidWindow)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        applyKeyboardBackdrop()

        keyboardView =
            FloatingKeyboardContainer(context, isFloatingKeyboardEnabled, ::handleFloatingDrag).apply {
                isMotionEventSplittingEnabled = true
                add(
                    keyboardBackground,
                    lParams {
                        centerInParent()
                    },
                )
                add(
                    inputBar.view,
                    lParams(matchParent, dp(inputBar.themedHeight)) {
                        topOfParent()
                        centerHorizontally()
                    },
                )
                add(
                    leftPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        startOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    rightPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        endOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    windowManager.view,
                    lParams {
                        below(inputBar.view)
                        above(bottomPaddingSpace)
                    },
                )
                add(
                    bottomPaddingSpace,
                    lParams {
                        startToEndOf(leftPaddingSpace)
                        endToStartOf(rightPaddingSpace)
                        bottomOfParent()
                    },
                )
            }

        configureFloatingSurface(isFloatingKeyboardEnabled)

        updateWindowViewHeightJob =
            service.lifecycleScope.launch {
                keyboardWindow.currentKeyboardHeight.collect {
                    if (windowManager.isAttached(keyboardWindow)) {
                        windowManager.view.updateLayoutParams {
                            height = it
                        }
                    }
                }
            }

        updateKeyboardSize()

        // The preedit stays a sibling above the keyboard in both modes: the floating position math
        // moves it with the surface, and while docked it must not share the composing strip --
        // a long composition would take that strip over and squeeze the candidates into a sliver.
        add(
            preedit.ui.root,
            lParams(wrapContent, wrapContent) {
                above(keyboardView)
                startOfParent()
            },
        )
        // The gap is a property of that constraint, so it is applied right after it exists.
        applyPreeditGap()

        // The chip is what travels, and its width changes with every keystroke, so the position
        // follows the chip's re-measures rather than only the root's.
        preedit.ui.preedit.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPreeditHorizontalPosition()
        }

        add(
            keyboardView,
            lParams(if (isFloatingKeyboardEnabled) matchConstraints else matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
                if (isFloatingKeyboardEnabled) {
                    matchConstraintPercentWidth = layoutWidthPercent(floatingKeyboardWidth) / 100f
                    bottomMargin = dp(18)
                }
            },
        )

        keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncFloatingPreeditPosition()
        }
        if (isFloatingKeyboardEnabled) {
            keyboardView.post {
                // The build-time percent was computed before the window had a width and fell back
                // to a full-width share; now that the geometry is known, put the chosen width
                // back. Without this a view *built* while floating (an input-view rebuild, a
                // theme switch) would stay full width until the next toggle re-ran this maths.
                keyboardView.updateLayoutParams<LayoutParams> {
                    matchConstraintPercentWidth = layoutWidthPercent(floatingKeyboardWidth) / 100f
                }
                // Re-apply the persisted height percentage: the key grid's height comes from the
                // theme, so nothing else would carry it across this rebuild.
                applyFloatingScale()
                applyFloatingPosition(
                    keyboardPrefs.floatingKeyboardOffsetX.getValue().toFloat(),
                    keyboardPrefs.floatingKeyboardOffsetY.getValue().toFloat(),
                    persist = false,
                )
            }
        }

        add(
            popup.root,
            lParams(matchParent, matchParent) {
                centerInParent()
            },
        )
        // [popup.root] and [keyboardView] are siblings under this ConstraintLayout.
        // A ViewGroup that holds any child with a non-zero Z re-orders its children
        // by elevation, so once the floating keyboard raises itself with
        // [FLOATING_KEYBOARD_ELEVATION_DP] it would be painted *after* the popup
        // layer and hide the key popup preview / popup keyboard. Keeping the popup
        // layer above that keeps the popup on top in both floating and docked layouts.
        popup.root.elevation = dp(POPUP_LAYER_ELEVATION_DP)
    }

    private fun updateKeyboardSize() {
        applyBottomBar()
        val sidePadding = keyboardSidePaddingPx
        val unset = LayoutParams.UNSET
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = View.GONE
            rightPaddingSpace.visibility = View.GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = View.VISIBLE
            rightPaddingSpace.visibility = View.VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        inputBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    /**
     * Applies the strip the toggle row sits in: its height only.
     *
     * It gets no fill of its own. The keyboard's background already covers the whole surface, so
     * painting the strip with the same translucent colour composited it twice over the frosted
     * panel and left a visibly lighter band across the bottom of the keyboard — the strip read as
     * a separate slab rather than as the keyboard continuing into the space its toggles need.
     */
    private fun applyBottomBar() {
        bottomPaddingSpace.updateLayoutParams { height = keyboardBottomPaddingPx }
        bottomPaddingSpace.background = null
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    fun startInput(
        info: EditorInfo,
        restarting: Boolean = false,
    ) {
        updateEnterKeyLabel(info)
        broadcaster.onStartInput(info)
        if (!restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    fun updateEnterKeyLabel(info: EditorInfo) {
        enterKeyDisplay.updateLabelOnEditorInfo(info)
    }

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                broadcaster.onRimeSchemaUpdated(it.data)

                windowManager.attachWindow(KeyboardWindow)
            }

            is RimeMessage.OptionMessage -> {
                broadcaster.onRimeOptionUpdated(it.data)

                if (it.data.option == "_liquid_keyboard") {
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(LiquidWindow)
                        liquidWindow.setDataByIndex(0)
                    }
                }
            }

            is RimeMessage.CompositionMessage -> {
                val data = if (candidatesMode == PopupCandidatesMode.ALWAYS_SHOW) {
                    CompositionProto()
                } else {
                    it.data
                }
                broadcaster.onCompositionUpdate(data)
                if (isFloatingKeyboardEnabled) {
                    preedit.ui.root.post {
                        applyFloatingPosition(
                            keyboardView.translationX,
                            keyboardView.translationY,
                            persist = false,
                        )
                    }
                }
            }

            is RimeMessage.BulkCandidatesMessage -> {
                broadcaster.onCandidateListUpdate(it.data)
            }

            else -> {}
        }
        broadcastKeyAppearanceUpdate()
    }

    fun updateSelection(
        start: Int,
        end: Int,
    ) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean = inputBar.handleInlineSuggestions(response)

    override fun onDetachedFromWindow() {
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        // cancel the notification job and clear all broadcast receivers,
        // implies that InputView should not be attached again after detached.
        updateWindowViewHeightJob.cancel()
        popup.root.removeAllViews()
        broadcaster.clear()
        super.onDetachedFromWindow()
    }
}
