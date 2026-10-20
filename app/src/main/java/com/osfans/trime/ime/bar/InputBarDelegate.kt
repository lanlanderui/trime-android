/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.PrivacyMode
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.ui.AlwaysUi
import com.osfans.trime.ime.bar.ui.MonitorCandidateUi
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.switches.QuickSwitchWindow
import com.osfans.trime.ime.bar.ui.TabUi
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.unrolled.window.FlexboxUnrolledCandidateWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume

internal fun shouldShowInputBar(
    hideToolbar: Boolean,
    state: QuickBarStateMachine.State,
): Boolean = !hideToolbar || state != QuickBarStateMachine.State.Always

class InputBarDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {
    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme
    private val windowManager: BoardWindowManager by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()
    private val candidate: CompactCandidateDelegate by instance()
    private val rime: RimeSession by instance()

    private val prefs = AppPrefs.defaultInstance()

    /**
     * The bar's height, in dp.
     *
     * The strip's vertical spacing rides on top of the themed height -- twice its value, once for
     * the air above the strip and once for the air below -- so the spacing is *added* space: the
     * strip inside the bar keeps its own size and the panel opens up evenly around it.
     * A getter, not a value: the spacing slider changes it while the tree stays up, and a snapshot
     * would keep re-applying the height the bar was born with.
     */
    val themedHeight: Int
        get() = theme.generalStyle.run { candidateViewHeight + commentHeight } +
            2 * prefs.candidates.stripGap.getValue()

    private val hideQuickBar by prefs.keyboard.hideInputBar

    private val clipboardSuggestion by prefs.clipboard.clipboardSuggestion

    private val clipboardSuggestionTimeout by prefs.clipboard.clipboardSuggestionTimeout

    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false

    @Keep
    private val onClipboardUpdateListener = ClipboardHelper.OnClipboardUpdateListener {
        if (!clipboardSuggestion) return@OnClipboardUpdateListener
        service.lifecycleScope.launch {
            if (it.text.isNullOrEmpty()) {
                isClipboardFresh = false
            } else {
                alwaysUi.clipboardUi.text.text = it.text.take(42)
                isClipboardFresh = true
                launchClipboardTimeoutJob()
            }
            evalAlwaysUiState()
        }
    }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardSuggestionTimeout * 1000L
        if (timeout < 0L) return
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(timeout)
            isClipboardFresh = false
            clipboardTimeoutJob = null
            evalAlwaysUiState()
        }
    }

    private fun evalAlwaysUiState() {
        val newState = desiredAlwaysUiState()
        if (newState != alwaysUi.currentState) alwaysUi.updateState(newState)
        // The shell picks between the resident strip row and the takeover row.
        switchUiByState(barStateMachine.currentState)
    }

    private fun desiredAlwaysUiState(): AlwaysUi.State =
        when {
            isClipboardFresh -> AlwaysUi.State.Clipboard
            isInlineSuggestionPresent -> AlwaysUi.State.InlineSuggestion
            else -> AlwaysUi.State.Toolbar
        }

    private val swipeDownHideKeyboardCallback: ((KeyBehavior) -> Unit) = { d ->
        if (d == KeyBehavior.SWIPE_DOWN) {
            service.requestHideSelf(0)
        }
    }

    private val alwaysUi: AlwaysUi by lazy {
        AlwaysUi(context, scope) { action ->
            if (action.isNotEmpty()) {
                commonKeyboardActionListener.listener.onAction(KeyActionManager.getAction(action))
            } else {
                windowManager.attachWindow(SwitchOptionWindow(di))
            }
        }.apply {
            // The bottom strip's IME toggles live here now: they are quick switches, reached the
            // same way the clipboard and the input-mode key are, instead of a row of their own
            // along the bottom edge of the keyboard.
            val chosen = RightButton.parse(prefs.candidates.rightButtons.getValue())
            if (RightButton.HIDE_INPUT_BAR in chosen) {
                buttonsUi.addButton(
                    commandButton(R.drawable.ic_baseline_arrow_drop_down_24, "switch_hide_input_bar", R.string.toggle_input_bar),
                    null,
                )
            }
            if (RightButton.CANDIDATES_WINDOW in chosen) {
                buttonsUi.addButton(
                    commandButton(R.drawable.ic_baseline_list_alt_24, "switch_candidates_window", R.string.toggle_candidates_window),
                    null,
                )
            }
            hideKeyboardButton.apply {
                setOnClickListener { service.requestHideSelf(0) }
                onSwipe = swipeDownHideKeyboardCallback
            }
            floatingKeyboardButton.apply {
                val isFloating = prefs.keyboard.floatingKeyboard.getValue()
                setIcon(
                    if (isFloating) {
                        R.drawable.ic_baseline_keyboard_24
                    } else {
                        R.drawable.ic_floating_keyboard_24
                    },
                )
                contentDescription = context.getString(
                    if (isFloating) {
                        R.string.dock_keyboard
                    } else {
                        R.string.enable_floating_keyboard
                    },
                )
                setOnClickListener {
                    prefs.keyboard.floatingKeyboard.setValue(
                        !prefs.keyboard.floatingKeyboard.getValue(),
                    )
                }
                setOnLongClickListener {
                    AppUtils.launchMainToKeyboard(context)
                    true
                }
            }
            clipboardUi.suggestionView.apply {
                setOnClickListener {
                    val content = ClipboardHelper.lastBean?.text
                    content?.let { service.commitText(it) }
                    dismissClipboardSuggestion()
                }
                setOnLongClickListener {
                    ClipboardHelper.lastBean?.let {
                        AppUtils.launchClipEdit(context, it.id, ClipEditActivity.FROM_CLIPBOARD)
                    }
                    true
                }
            }
            clipboardUi.dismiss.setOnClickListener {
                dismissClipboardSuggestion()
            }
        }
    }

    /**
     * A quick switch that fires one of the keyboard's own commands.
     *
     * The commands are the ones the retired bottom toggles used (`switch_*`), so the behaviour is
     * the same wherever it is triggered from -- including whatever a theme has bound to them.
     */
    private fun commandButton(
        @DrawableRes icon: Int,
        command: String,
        @StringRes label: Int,
    ): ToolButton = ToolButton(context, icon, scope).apply {
        contentDescription = context.getString(label)
        setOnClickListener {
            commonKeyboardActionListener.listener.onAction(KeyActionManager.getCommandAction(command))
        }
    }

    private fun dismissClipboardSuggestion() {
        clipboardTimeoutJob?.cancel()
        clipboardTimeoutJob = null
        isClipboardFresh = false
        evalAlwaysUiState()
    }

    private val tabUi by lazy {
        TabUi(context, scope)
    }

    /**
     * The resident row: the menu button, the composing strip held between the flanks, and the
     * stow toggle. It stays up through both the idle and the composing states -- the strip simply
     * receives the candidates -- so the toolbar no longer trades places with the candidate bar.
     */
    private val monitorCandidateUi by lazy {
        val (flankWidth, flankHeight) = alwaysUi.buttonsUi.getButtonSize(theme.toolBar.primaryButton)
        // Exactly one switch left: it is shown in the trailing slot on its own, and the stow
        // toggle -- whose whole job is folding a row of them away -- has nothing left to fold.
        // The button keeps its view and listener; only its parent changes.
        val singleSwitch = alwaysUi.buttonsUi.root.let { bar ->
            if (bar.childCount == 1) {
                (bar.getChildAt(0) as? ToolButton)?.let { alwaysUi.detachButton(it) }
            } else {
                null
            }
        }
        MonitorCandidateUi(
            context,
            scope,
            candidate.view,
            alwaysUi.leftMostButton,
            alwaysUi.buttonsUi.root,
            flankWidth,
            flankHeight,
            swipeDownHideKeyboardCallback,
            onSwitchesRequested = {
                // The switches do not fit the strip: bring them up the way the menu brings up its
                // own list, rather than letting them squeeze each other into unreadable slivers.
                windowManager.attachWindow(QuickSwitchWindow(di) { alwaysUi.buttonsUi.root })
            },
            singleSwitch = singleSwitch,
        ).apply {
            unrollButton.apply {
                onSwipe = swipeDownHideKeyboardCallback
            }
        }
    }

    private val barStateMachine =
        QuickBarStateMachine.new {
            switchUiByState(it)
        }

    val unrollButtonStateMachine =
        UnrollButtonStateMachine.new {
            when (it) {
                UnrollButtonStateMachine.State.ClickToAttachWindow -> {
                    setUnrollButtonToAttach()
                    setUnrollButtonEnabled(true)
                }

                UnrollButtonStateMachine.State.ClickToDetachWindow -> {
                    setUnrollButtonToDetach()
                    setUnrollButtonEnabled(true)
                }

                UnrollButtonStateMachine.State.Hidden -> {
                    setUnrollButtonEnabled(false)
                }
            }
        }

    private fun setUnrollButtonToAttach() {
        monitorCandidateUi.unrollButton.setOnClickListener {
            windowManager.attachWindow(FlexboxUnrolledCandidateWindow(di))
        }
        monitorCandidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_more_24)
    }

    private fun setUnrollButtonToDetach() {
        monitorCandidateUi.unrollButton.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
        monitorCandidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_less_24)
    }

    private fun setUnrollButtonEnabled(enabled: Boolean) {
        monitorCandidateUi.setUnrollAvailable(enabled)
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        barStateMachine.push(
            QuickBarStateMachine.TransitionEvent.CandidatesUpdated,
            QuickBarStateMachine.BooleanKey.CandidateEmpty to data.candidates.isEmpty(),
        )
    }

    private fun switchUiByState(state: QuickBarStateMachine.State) {
        // The row's end slot follows the composition: expanding belongs to typing, stowing to the
        // idle row. Set before the early return below, which only guards the animator's child.
        monitorCandidateUi.setComposing(state == QuickBarStateMachine.State.Candidate)
        val index =
            when {
                // The clipboard and inline-suggestion rows take the bar over while they last.
                alwaysUi.currentState != AlwaysUi.State.Toolbar -> SUGGESTION_CHILD
                state == QuickBarStateMachine.State.Tab -> TAB_CHILD
                // Everything else -- idle and composing alike -- is the resident strip row.
                else -> ROW_CHILD
            }
        view.visibility =
            if (shouldShowInputBar(hideQuickBar, state)) View.VISIBLE else View.GONE
        // The strip row paints its own screen; only the takeover rows want the bar's decor.
        view.background = if (index == ROW_CHILD) null else barBackground()
        if (view.displayedChild == index) return
        val new = view.getChildAt(index)
        if (new != tabUi.root) {
            tabUi.setBackButtonOnClickListener { }
            tabUi.setTitle("")
            tabUi.removeExternal()
        }
        view.displayedChild = index
    }

    /** Apply toolbar preferences without replacing the input view hierarchy. */
    fun onFloatingKeyboardChanged(isFloating: Boolean) {
        alwaysUi.floatingKeyboardButton.apply {
            setIcon(
                if (isFloating) R.drawable.ic_baseline_keyboard_24 else R.drawable.ic_floating_keyboard_24,
            )
            contentDescription = context.getString(
                if (isFloating) R.string.dock_keyboard else R.string.enable_floating_keyboard,
            )
        }
        switchUiByState(barStateMachine.currentState)
    }

    fun onHideInputBarChanged() {
        switchUiByState(barStateMachine.currentState)
    }

    /** Re-applies the composing strip's inset-bar background after its switch changed. */
    fun refreshStripBackground() {
        monitorCandidateUi.refreshStripBackground()
    }

    /** Re-applies the composing strip's length after its preference changed. */
    fun refreshStripLength() {
        monitorCandidateUi.refreshStripLength()
    }

    /** Shows or hides the strip row's two corner toggles after their switch changed. */
    fun refreshCornerToggles() {
        monitorCandidateUi.refreshCornerToggles()
    }

    /** Re-applies whether the strip starts on the switches after that switch changed. */
    fun refreshSwitchesDefault() {
        monitorCandidateUi.refreshSwitchesDefault()
    }

    /** Re-sizes the bar after the strip-to-keyboard gap changed; the strip keeps its own height. */
    fun refreshStripGap() {
        view.updateLayoutParams { height = context.dp(themedHeight) }
        monitorCandidateUi.refreshStripGap()
        service.requestInputInsetsUpdate()
    }

    /** Re-binds the candidate rows after the size of the candidates around the highlighted changed. */
    fun refreshTwoRow() {
        candidate.notifyTwoRowChanged()
    }

    val view by lazy {
        ViewAnimator(context).apply {
            visibility =
                if (shouldShowInputBar(hideQuickBar, QuickBarStateMachine.State.Always)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            // Child order is the shell's private mapping, not the state ordinals: the resident
            // strip row leads, the takeover row follows, the tab UI closes.
            add(monitorCandidateUi.root, lParams(matchParent, matchParent))
            add(alwaysUi.root, lParams(matchParent, matchParent))
            add(tabUi.root, lParams(matchParent, matchParent))

            // Seeding must not go through evalAlwaysUiState: this lazy is mid-flight and the
            // shell method reads `view`, which would re-enter the initializer. The child order
            // is known, so a live takeover can be seeded by index instead.
            val initial = desiredAlwaysUiState()
            if (initial != AlwaysUi.State.Toolbar) {
                alwaysUi.updateState(initial)
                displayedChild = SUGGESTION_CHILD
                background = barBackground()
            }
            ClipboardHelper.addOnUpdateListener(onClipboardUpdateListener)
            // The helper keeps listeners in a static set, so it has to be
            // released when this view leaves the window -- otherwise it keeps
            // notifying a detached view after an input view rebuild.
            addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit

                    override fun onViewDetachedFromWindow(v: View) {
                        ClipboardHelper.removeOnUpdateListener(onClipboardUpdateListener)
                    }
                },
            )
            syncToolbarOptionStates()
            // The strip may be built while a private session is already up (an
            // input view rebuild on a theme switch), so seed the badge here too.
            syncPrivacyIndicator()
        }
    }

    /** Restyles the bar after a scheme switch without rebuilding its views. */
    fun refreshColors() {
        view.background = if (view.displayedChild == ROW_CHILD) null else barBackground()
        alwaysUi.refreshColors()
        monitorCandidateUi.refreshColors()
        // the compact candidate rows live inside the panel and re-bind via the delegate
        candidate.refreshColors()
        tabUi.refreshColors()
    }

    /**
     * The bar's own fill, for the rows that do not paint their own: the clipboard / inline
     * takeover and the tab UI. The resident strip row fills itself with the composing screen --
     * background, border and rounded corners -- and would otherwise composite twice.
     */
    private fun barBackground(): Drawable? =
        scope.decorDrawable(
            "candidate_background",
            "candidate_border_color",
            context.dp(theme.generalStyle.candidateBorder),
            context.dp(theme.generalStyle.candidateBorderRound),
        )

    override fun onStartInput(info: EditorInfo) {
        evalAlwaysUiState()
        syncPrivacyIndicator()
    }

    /**
     * Mirrors the private-session state onto the status badge.
     *
     * [PrivacyMode] is the authority here rather than [EditorInfo] itself: the
     * service decodes `imeOptions` in `onStartInput`, which always runs before
     * the input view is told to start input, so by the time this is called the
     * flag is already settled for the editor being typed into.
     */
    private fun syncPrivacyIndicator() {
        alwaysUi.setPrivacyMode(PrivacyMode.isActive)
        // The composing strip carries its own caption-row badge, so the state has to reach it too.
        monitorCandidateUi.privacyButton.visibility =
            if (PrivacyMode.isActive) View.VISIBLE else View.GONE
    }

    override fun onWindowAttached(window: BoardWindow) {
        if (window is BoardWindow.BarBoardWindow) {
            tabUi.setTitle(window.title)
            window.onCreateBarView()?.let { tabUi.addExternal(it, window.showTitle) }
            tabUi.setBackButtonOnClickListener {
                windowManager.attachWindow(KeyboardWindow)
            }
            barStateMachine.push(QuickBarStateMachine.TransitionEvent.BarBoardWindowAttached)
        }
    }

    override fun onWindowDetached(window: BoardWindow) {
        barStateMachine.push(QuickBarStateMachine.TransitionEvent.WindowDetached)
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(themedHeight))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            alwaysUi.inlineSuggestionsUi.setPinnedView(
                pinned?.let { inflateInlineContentView(it) },
            )
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            alwaysUi.inlineSuggestionsUi.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        evalAlwaysUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? = suspendCancellableCoroutine { c ->
        // callback view might be null
        suggestion.inflate(context, suggestionSize, directExecutor) { v ->
            c.resume(v)
        }
    }

    /**
     * Seed the toolbar toggle buttons with the current value of their rime
     * options. Rime access stays here in the delegate: the buttons themselves
     * are pure views and only react to [updateButtonsStyle].
     */
    private fun syncToolbarOptionStates() {
        val options = alwaysUi.toggleOptions()
        if (options.isEmpty()) return
        rime.launchOnReady { api ->
            val states = options.associateWith { api.getRuntimeOption(it) }
            ContextCompat.getMainExecutor(context).execute {
                states.forEach { (option, enabled) ->
                    alwaysUi.updateButtonsStyle(option, enabled)
                }
            }
        }
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        alwaysUi.updateButtonsStyle(value.option, value.value)
    }

    private companion object {
        /** Child slots of the bar's animator: the resident row, the takeover row, the tab UI. */
        const val ROW_CHILD = 0
        const val SUGGESTION_CHILD = 1
        const val TAB_CHILD = 2
    }
}
