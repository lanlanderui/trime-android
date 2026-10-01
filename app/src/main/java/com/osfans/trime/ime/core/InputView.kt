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
    private val floatingKeyboardBaseWidth = keyboardPrefs.floatingKeyboardWidth.getValue()
    private val floatingKeyboardBaseHeight = keyboardPrefs.floatingKeyboardHeight.getValue()
    private var floatingKeyboardWidth = floatingKeyboardBaseWidth
    private var floatingKeyboardHeight = floatingKeyboardBaseHeight
    private val floatingPreeditOffsetX = keyboardPrefs.floatingPreeditOffsetX.getValue()
    private val floatingPreeditOffsetY = keyboardPrefs.floatingPreeditOffsetY.getValue()

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
        val composing = rime.run { statusCached.isComposing }
        val hasMenu = rime.run { hasMenu }
        val paging = rime.run { paging }
        val current = Triple(composing, hasMenu, paging)
        if (current != lastAppearanceState) {
            lastAppearanceState = current
            broadcaster.onKeyAppearanceUpdate(current.first, current.second, current.third)
        }
    }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardBottomPaddingLandscape else keyboardBottomPadding
            return dp(value)
        }

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
    private val inputBarToggleHandle = FloatingActionHandle(context).apply {
        setOnClickListener {
            dispatchKeyboardCommand("switch_hide_input_bar")
        }
    }
    private val floatingModeToggleHandle = FloatingActionHandle(context).apply {
        setOnClickListener {
            dispatchKeyboardCommand("switch_floating_keyboard")
        }
    }

    private fun dispatchKeyboardCommand(command: String) {
        commonKeyboardActionListener.listener.onAction(KeyActionManager.getCommandAction(command))
    }

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
                    60,
                    100,
                )
                resizedHeight = resizedPercent(
                    resizeStartHeightPercent,
                    (resizeStartHeight * resizeStartScaleY).roundToInt(),
                    event.rawY - resizeDownRawY,
                    70,
                    130,
                )
                keyboardView.scaleX = resizedWidth.toFloat() / floatingKeyboardBaseWidth
                // The toolbar height is fixed, while the key area follows the height preference.
                keyboardView.scaleY =
                    1f + resizeStartHeight.toFloat() / keyboardView.height *
                    (resizedHeight - floatingKeyboardBaseHeight) / floatingKeyboardBaseHeight.toFloat()
                applyFloatingPosition(
                    keyboardView.translationX,
                    keyboardView.translationY,
                    persist = false,
                )
            }
            MotionEvent.ACTION_UP -> {
                floatingKeyboardWidth = resizedWidth
                floatingKeyboardHeight = resizedHeight
                service.updateFloatingKeyboardSize(resizedWidth, resizedHeight)
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

    private fun syncFloatingPreeditPosition() {
        if (!isFloatingKeyboardEnabled || keyboardView.width == 0) return
        val preeditRoot = preedit.ui.root
        if (preeditRoot.layoutParams.width != keyboardView.width) {
            preeditRoot.updateLayoutParams {
                width = keyboardView.width
            }
        }
        preeditRoot.translationX =
            keyboardView.left + keyboardView.translationX - preeditRoot.left + dp(floatingPreeditOffsetX)
        preeditRoot.pivotX = 0f
        preeditRoot.scaleX = keyboardView.scaleX
        // The extra 1 dp overlap hides fractional-pixel seams between the square
        // preedit bottom edge and the floating keyboard's rounded top edge.
        preeditRoot.translationY = keyboardView.translationY + dp(floatingPreeditOffsetY + 1)
        preedit.updateTouchReceiverPosition()
    }

    private fun applyFloatingPosition(
        requestedX: Float,
        requestedY: Float,
        persist: Boolean,
    ) {
        if (!isFloatingKeyboardEnabled || width == 0 || keyboardView.width == 0) return

        val horizontalMargin = dp(8).toFloat()
        val visualWidth = keyboardView.width * keyboardView.scaleX
        val minTranslationX = horizontalMargin - keyboardView.left
        val maxTranslationX = width - horizontalMargin - keyboardView.left - visualWidth
        val floatingTop =
            preedit.ui.root
                .takeIf { it.visibility == View.VISIBLE && it.height > 0 }
                ?.let { it.top + dp(floatingPreeditOffsetY + 1) }
                ?: keyboardView.top
        val minTranslationY = -(floatingTop - dp(16)).coerceAtLeast(0).toFloat()
        val resolvedX = requestedX.coerceIn(minTranslationX, maxTranslationX.coerceAtLeast(minTranslationX))
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
            matchConstraintPercentWidth = if (enabled) floatingKeyboardWidth / 100f else 1f
            bottomMargin = if (enabled) dp(18) else 0
        }

        if (enabled) {
            keyboardView.scaleX = 1f
            keyboardView.scaleY = 1f
        } else {
            keyboardView.translationX = 0f
            keyboardView.translationY = 0f
            keyboardView.scaleX = 1f
            keyboardView.scaleY = 1f
            preedit.ui.root.apply {
                translationX = 0f
                translationY = 0f
                scaleX = 1f
                updateLayoutParams { width = wrapContent }
            }
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
        updateFloatingActionHandles()
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
            surface.add(moveHandle, lParams(dp(36), dp(36)) {
                startOfParent()
                bottomOfParent()
            })
        }
        if (inputBarToggleHandle.parent == null) {
            surface.add(inputBarToggleHandle, lParams(dp(36), dp(36)) {
                startToEndOf(moveHandle)
                bottomOfParent()
            })
        }
        if (floatingModeToggleHandle.parent == null) {
            surface.add(floatingModeToggleHandle, lParams(dp(36), dp(36)) {
                startToEndOf(inputBarToggleHandle)
                bottomOfParent()
            })
        }
        if (resizeHandle.parent == null) {
            surface.add(resizeHandle, lParams(dp(36), dp(36)) {
                endOfParent()
                bottomOfParent()
            })
        }
        updateFloatingActionHandles()
    }

    private fun updateFloatingActionHandles() {
        val toolbarHidden = keyboardPrefs.hideInputBar.getValue()
        val handleTint =
            if (ColorUtils.calculateLuminance(scope.colors.keyboardBackColor) > 0.5) {
                Color.DKGRAY
            } else {
                Color.WHITE
            }
        inputBarToggleHandle.apply {
            setAction(
                if (toolbarHidden) R.drawable.ic_baseline_more_horiz_24 else R.drawable.ic_baseline_arrow_drop_down_24,
                context.getString(if (toolbarHidden) R.string.show_input_bar else R.string.hide_input_bar),
                handleTint,
            )
            visibility = View.VISIBLE
        }
        floatingModeToggleHandle.apply {
            setAction(
                if (isFloatingKeyboardEnabled) {
                    R.drawable.ic_baseline_keyboard_24
                } else {
                    R.drawable.ic_floating_keyboard_24
                },
                context.getString(
                    if (isFloatingKeyboardEnabled) R.string.dock_keyboard else R.string.enable_floating_keyboard,
                ),
                handleTint,
            )
            visibility =
                if (!isFloatingKeyboardEnabled || toolbarHidden) View.VISIBLE else View.GONE
        }
    }

    /** Restyles colors after a scheme switch without rebuilding the view tree. */
    fun refreshColors() {
        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")
        if (isFloatingKeyboardEnabled) {
            (keyboardView.background as? GradientDrawable)?.setColor(scope.colors.keyboardBackColor)
        }
        resizeHandle.setGripColor(scope.colors.keyboardBackColor)
        moveHandle.setGripColor(scope.colors.keyboardBackColor)
        updateFloatingActionHandles()
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

        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")

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

        add(
            preedit.ui.root,
            lParams(wrapContent, wrapContent) {
                above(keyboardView)
                startOfParent()
            },
        )

        add(
            keyboardView,
            lParams(if (isFloatingKeyboardEnabled) matchConstraints else matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
                if (isFloatingKeyboardEnabled) {
                    matchConstraintPercentWidth = floatingKeyboardWidth / 100f
                    bottomMargin = dp(18)
                }
            },
        )

        keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncFloatingPreeditPosition()
        }
        if (isFloatingKeyboardEnabled) {
            keyboardView.post {
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
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
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
