/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.popup

import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import java.util.LinkedList

class PopupDelegate(override val di: DI) : DIAware {
    private val context: ContextThemeWrapper by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme
    private val service: TrimeInputMethodService by instance()

    private val showingEntryUi = HashMap<Int, PopupEntryUi>()
    private val dismissJobs = HashMap<Int, Job>()
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    /**
     * Entries whose fade-out is running.
     *
     * They stay in [showingEntryUi] until the animation ends, so a key pressed again within the
     * fade reuses the very same view instead of stacking a second popup on top of a dying one.
     */
    private val fadingEntryUi = HashSet<PopupEntryUi>()

    private val showingContainerUi = HashMap<Int, PopupContainerUi>()

    private val popupBottomMargin by lazy {
        context.dp(theme.generalStyle.popupBottomMargin)
    }
    private val popupWidth by lazy {
        context.dp(theme.generalStyle.popupWidth)
    }
    private val popupHeight by lazy {
        context.dp(theme.generalStyle.popupHeight)
    }
    private val popupKeyHeight by lazy {
        context.dp(theme.generalStyle.popupKeyHeight)
    }
    private val popupRadius by lazy {
        context.dp(theme.generalStyle.roundCorner)
    }
    private val hideThreshold = 100L

    private val rootLocation = intArrayOf(0, 0)
    private val rootBounds: Rect = Rect()

    val root by lazy {
        context.frameLayout {
            // we want (0, 0) at top left
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = false
            isFocusable = false

            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val (x, y) = rootLocation.also { v.getLocationInWindow(it) }
                val width = right - left
                val height = bottom - top
                rootBounds.set(x, y, x + width, y + height)
            }
        }
    }

    private fun showPopup(viewId: Int, content: String, bounds: Rect) {
        showingEntryUi[viewId]?.apply {
            dismissJobs.remove(viewId)?.cancel()
            // A popup that was on its way out comes back instead of being replaced: the view is
            // still on screen for the length of the fade, and a second one would land on top of it.
            reveal()
            lastShowTime = System.currentTimeMillis()
            setText(content)
            return
        }
        val popup = (
            freeEntryUi.poll()
                ?: PopupEntryUi(context, scope, popupKeyHeight, popupRadius)
            ).apply {
            lastShowTime = System.currentTimeMillis()
            setText(content)
        }
        placePopup(popup, bounds)
        showingEntryUi[viewId] = popup
        // Fade in rather than appear: the popup sits above the finger, and a hard cut reads as a
        // flash. Short enough that it is still there by the time the key is released.
        popup.root.alpha = 0f
        popup.root.animate().alpha(1f).setDuration(POPUP_FADE_IN_MS).start()
    }

    /** Brings an entry fully back, cancelling a fade-out that may be running. */
    private fun PopupEntryUi.reveal() {
        root.animate().cancel()
        fadingEntryUi.remove(this)
        root.alpha = 1f
    }

    private fun placePopup(ui: PopupEntryUi, bounds: Rect) {
        val v = ui.root
        if (v.parent == root) return
        (v.parent as? ViewGroup)?.removeView(v)
        root.apply {
            add(
                v,
                lParams(popupWidth, popupHeight) {
                    // align popup bottom with key border bottom
                    topMargin = bounds.bottom - popupHeight - popupBottomMargin
                    leftMargin = (bounds.left + bounds.right - popupWidth) / 2
                },
            )
        }
    }

    private fun updatePopup(viewId: Int, content: String) {
        showingEntryUi[viewId]?.setText(content)
    }

    private fun showKeyboard(viewId: Int, keys: List<String>, bounds: Rect) {
        // clear popup preview text         OR create empty popup preview
        showingEntryUi[viewId]?.setText("") ?: showPopup(viewId, "", bounds)
        reallyShowKeyboard(viewId, keys, bounds)
    }

    private fun reallyShowKeyboard(viewId: Int, keys: List<String>, bounds: Rect) {
        val labels = keys
        val keyboardUi = PopupKeyboardUi(
            context,
            scope,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            popupRadius,
            popupWidth,
            popupKeyHeight,
            // position popup keyboard higher, because of [^1]
            popupHeight + popupBottomMargin,
            keys,
            labels,
        )
        showPopupContainer(viewId, keyboardUi)
    }

    private fun showPopupContainer(viewId: Int, ui: PopupContainerUi) {
        root.apply {
            add(
                ui.root,
                lParams {
                    leftMargin = ui.triggerBounds.left + ui.offsetX - rootBounds.left
                    topMargin = ui.triggerBounds.top + ui.offsetY - rootBounds.top
                },
            )
        }
        showingContainerUi[viewId] = ui
    }

    private fun changeFocus(viewId: Int, x: Float, y: Float): Boolean = showingContainerUi[viewId]?.changeFocus(x, y) ?: false

    private fun triggerFocused(viewId: Int): String? = showingContainerUi[viewId]?.onTrigger()

    private fun dismissPopup(viewId: Int) {
        dismissPopupContainer(viewId)
        showingEntryUi[viewId]?.also {
            val timeLeft = it.lastShowTime + hideThreshold - System.currentTimeMillis()
            if (timeLeft <= 0L) {
                dismissPopupEntry(viewId, it)
            } else {
                dismissJobs[viewId] = service.lifecycleScope.launch {
                    delay(timeLeft)
                    dismissPopupEntry(viewId, it)
                    dismissJobs.remove(viewId)
                }
            }
        }
    }

    private fun dismissPopupContainer(viewId: Int) {
        showingContainerUi[viewId]?.also {
            showingContainerUi.remove(viewId)
            root.removeView(it.root)
        }
    }

    private fun dismissPopupEntry(viewId: Int, popup: PopupEntryUi) {
        // The map keeps the entry until the fade ends; see [fadingEntryUi].
        if (!fadingEntryUi.add(popup)) return
        popup.root.animate().cancel()
        popup.root.animate()
            .alpha(0f)
            .setDuration(POPUP_FADE_OUT_MS)
            .withEndAction {
                fadingEntryUi.remove(popup)
                if (showingEntryUi[viewId] === popup) showingEntryUi.remove(viewId)
                popup.root.alpha = 1f
                root.removeView(popup.root)
                if (!freeEntryUi.contains(popup)) freeEntryUi.add(popup)
            }.start()
    }

    fun dismissAll() {
        // avoid modifying collection while iterating
        dismissJobs.forEach { (_, job) ->
            job.cancel()
        }
        dismissJobs.clear()
        // too
        showingContainerUi.forEach { (_, container) ->
            root.removeView(container.root)
        }
        showingContainerUi.clear()
        // too too
        showingEntryUi.forEach { (_, entry) ->
            entry.root.animate().cancel()
            fadingEntryUi.remove(entry)
            entry.root.alpha = 1f
            root.removeView(entry.root)
            if (!freeEntryUi.contains(entry)) freeEntryUi.add(entry)
        }
        showingEntryUi.clear()
        fadingEntryUi.clear()
    }

    /** Re-applies the scheme colors to all popups, pooled or showing. */
    fun refreshColors() {
        freeEntryUi.forEach { it.refreshColors() }
        showingEntryUi.values.forEach { it.refreshColors() }
        showingContainerUi.values.forEach { it.refreshColors() }
    }

    val listener = PopupActionListener { action ->
        with(action) {
            when (this) {
                is PopupAction.ChangeFocusAction -> outResult = changeFocus(viewId, x, y)
                is PopupAction.DismissAction -> dismissPopup(viewId)
                is PopupAction.PreviewAction -> showPopup(viewId, content, bounds)
                is PopupAction.PreviewUpdateAction -> updatePopup(viewId, content)
                is PopupAction.ShowKeyboardAction -> showKeyboard(viewId, keys, bounds)
                is PopupAction.TriggerAction -> outAction = triggerFocused(viewId)
            }
        }
    }

    private companion object {
        /** Length of the character popup's fade-in, in milliseconds. */
        const val POPUP_FADE_IN_MS = 90L

        /** Length of its fade-out, in milliseconds. */
        const val POPUP_FADE_OUT_MS = 110L
    }
}
