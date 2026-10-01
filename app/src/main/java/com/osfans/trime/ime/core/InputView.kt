/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
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
import splitties.views.dsl.constraintlayout.constraintLayout
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

    val isFloatingKeyboardEnabled = keyboardPrefs.floatingKeyboard.getValue()
    private val floatingKeyboardWidth = keyboardPrefs.floatingKeyboardWidth.getValue()
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

    private val floatingHandlePill =
        view(::View) {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3f)
                setColor(scope.colors.labelColor)
            }
        }

    private val floatingDragArea =
        constraintLayout {
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.floating_keyboard_drag_handle)
            add(
                floatingHandlePill,
                lParams(dp(48), dp(5)) {
                    centerInParent()
                },
            )
            setOnClickListener { }
            setOnTouchListener { _, event -> handleFloatingDrag(event) }
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
        val maxTranslationX = ((width - keyboardView.width) / 2f - horizontalMargin).coerceAtLeast(0f)
        val floatingTop =
            preedit.ui.root
                .takeIf { it.visibility == View.VISIBLE && it.height > 0 }
                ?.let { it.top + dp(floatingPreeditOffsetY + 1) }
                ?: keyboardView.top
        val minTranslationY = -(floatingTop - dp(16)).coerceAtLeast(0).toFloat()
        val resolvedX = requestedX.coerceIn(-maxTranslationX, maxTranslationX)
        val resolvedY = requestedY.coerceIn(minTranslationY, 0f)

        keyboardView.translationX = resolvedX
        keyboardView.translationY = resolvedY
        syncFloatingPreeditPosition()
        service.requestInputInsetsUpdate()

        if (persist) {
            keyboardPrefs.floatingKeyboardOffsetX.setValue(resolvedX.roundToInt())
            keyboardPrefs.floatingKeyboardOffsetY.setValue(resolvedY.roundToInt())
        }
    }

    private fun resetFloatingPosition() {
        lastHandleTapTime = 0L
        applyFloatingPosition(0f, 0f, persist = true)
    }

    private fun handleFloatingDrag(event: MotionEvent): Boolean {
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
                if (dragMoved) {
                    applyFloatingPosition(
                        keyboardView.translationX,
                        keyboardView.translationY,
                        persist = true,
                    )
                } else if (event.eventTime - lastHandleTapTime <= ViewConfiguration.getDoubleTapTimeout()) {
                    resetFloatingPosition()
                } else {
                    lastHandleTapTime = event.eventTime
                    floatingDragArea.performClick()
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
        outBounds.set(
            inputViewLocation[0],
            inputViewLocation[1],
            inputViewLocation[0] + keyboardView.width,
            inputViewLocation[1] + keyboardView.height,
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

    /** Restyles colors after a scheme switch without rebuilding the view tree. */
    fun refreshColors() {
        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")
        if (isFloatingKeyboardEnabled) {
            (keyboardView.background as? GradientDrawable)?.setColor(scope.colors.keyboardBackColor)
            (floatingHandlePill.background as? GradientDrawable)?.setColor(scope.colors.labelColor)
        }
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
            constraintLayout {
                isMotionEventSplittingEnabled = true
                if (isFloatingKeyboardEnabled) {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(24f)
                        setColor(scope.colors.keyboardBackColor)
                    }
                    clipToOutline = true
                    elevation = dp(FLOATING_KEYBOARD_ELEVATION_DP)
                }
                add(
                    keyboardBackground,
                    lParams {
                        centerInParent()
                    },
                )
                if (isFloatingKeyboardEnabled) {
                    add(
                        floatingDragArea,
                        lParams(matchParent, dp(28)) {
                            topOfParent()
                        },
                    )
                }
                add(
                    inputBar.view,
                    lParams(matchParent, dp(inputBar.themedHeight)) {
                        if (isFloatingKeyboardEnabled) {
                            below(floatingDragArea)
                        } else {
                            topOfParent()
                        }
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

        updateWindowViewHeightJob =
            service.lifecycleScope.launch {
                keyboardWindow.currentKeyboardHeight.collect {
                    windowManager.view.updateLayoutParams {
                        height = it
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

        if (isFloatingKeyboardEnabled) {
            keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                syncFloatingPreeditPosition()
            }
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
