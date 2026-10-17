/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.osfans.trime.R
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.unrolled.decoration.FlexboxVerticalDecoration
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.recyclerview.recyclerView
import kotlin.math.max

class CompactCandidateDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {
    private val context: ContextThemeWrapper by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val inputView: InputView by instance()
    private val bar: InputBarDelegate by instance()

    private val theme: Theme
        get() = scope.theme

    private val fillStyle by AppPrefs.defaultInstance().candidates.horizontalCandidateMode

    private val maxSpanCountPref by lazy {
        AppPrefs.defaultInstance().candidates.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                maxSpanCount
            } else {
                maxSpanCountLandscape
            }
        }
    }

    private var layoutMinWidth = 0
    private var layoutFlexGrow = 0f

    /** Text of the candidate about to be committed, remembered for the slot arithmetic. */
    private var highlightedText = ""
    private var highlightedComment = ""

    /** Companions the row laid out, and the number the last list asked for. */
    private var lastKept = 0

    /** Bumped per candidate list, so a correction computed during layout cannot outlive it. */
    private var rowVersion = 0

    /** Whether the row is on screen; false while it fades out or before the first composition. */
    private var rowVisible = false

    /** Where the post-layout correction is queued: it cannot run inside the layout pass itself. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The last list Rime sent, kept so the row can be re-submitted with one candidate fewer. */
    private var lastCandidates: Array<CandidateProto> = emptyArray()
    private var lastHighlighted = -1
    private var lastTotal = -1

    /** Padding the row keeps on both sides for the end badge; the shift below moves it around. */
    private val rowPadPx by lazy { context.dp(END_BUTTONS_WIDTH_DP) }

    /** The floor every item is raised to; the adapter applies the same one when it builds them. */
    private val minItemWidthPx by lazy { context.dp(CANDIDATE_MIN_ITEM_WIDTH_DP) }

    /**
     * (for [CompactCandidateMode.AUTO_FILL] only)
     * Second layout pass is needed when:
     * [^1] total candidates count < maxSpanCount && [^2] RecyclerView cannot display all of them
     * In that case, displayed candidates should be stretched evenly (by setting flexGrow to 1.0f).
     */
    private var secondLayoutPassNeeded = false
    private var secondLayoutPassDone = false

    private val _unrolledCandidateOffset =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val unrolledCandidateOffset = _unrolledCandidateOffset.asSharedFlow()

    fun refreshUnrolled(childCount: Int) {
        _unrolledCandidateOffset.tryEmit(childCount)
        bar.unrollButtonStateMachine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to
                (adapter.total == childCount),
        )
    }

    /** Restyles the compact list after a scheme switch; visible rows re-bind. */
    fun refreshColors() {
        separatorDrawable.paint.color = scope.colors.candidateSeparatorColor
        adapter.notifyDataSetChanged()
    }

    val adapter by lazy {
        CompactCandidateViewAdapter(scope).apply {
            setOnItemClickListener { _, view, position ->
                // The candidates are not keys, but picking one is the same kind of action -- and the
                // row sits right above the keys, where the absence of the tap's feedback is the one
                // thing that reads as broken. The same call the keyboard makes, so one preference
                // governs both.
                InputFeedbackManager.keyPressVibrate(view)
                // The two-row strip lifts the highlighted candidate out of the row, so a row
                // position is not Rime's index and has to be mapped back.
                val index = toRimeIndex(position)
                rime.launchOnReady { it.selectCandidate(index, global = true) }
            }
            setOnItemLongClickListener { _, view, position ->
                inputView.showCandidateActionMenu(toRimeIndex(position), items[position].text, view, global = true)
                true
            }
        }
    }

    /** Re-binds the rows, after a change to how they are drawn (the two-row text size). */
    fun notifyTwoRowChanged() {
        adapter.notifyDataSetChanged()
    }

    fun updateLayoutParams(minWidth: Int, flexGrow: Float) {
        layoutMinWidth = minWidth
        layoutFlexGrow = flexGrow
    }

    val layoutManager by lazy {
        object : FlexboxLayoutManager(context) {
            init {
                // A row that fits centres in the strip, which is what pins the candidate in the
                // middle of the screen; a row that overflows simply flows and scrolls.
                justifyContent = JustifyContent.CENTER
            }

            override fun canScrollVertically(): Boolean = false

            override fun onLayoutCompleted(state: RecyclerView.State?) {
                super.onLayoutCompleted(state)
                val cnt = this.childCount
                // The row held one candidate more than its width could take. The flexbox wraps the
                // excess onto a second line, where nothing shows it -- but it still drags the
                // highlighted candidate off the middle, because the row is now lopsided around it.
                // The width estimate below the line is only an estimate, so this is where the
                // mistake is caught: give one back and lay the row out again.
                if (cnt in 1 until adapter.itemCount && lastKept > 0) {
                    val target = (lastKept - (adapter.itemCount - cnt)).coerceAtLeast(0)
                    val version = rowVersion
                    // Re-submitting is a data-set change, which RecyclerView refuses to take while
                    // it is laying out -- so it waits for the next frame, and only if no newer list
                    // arrived in the meantime.
                    this@CompactCandidateDelegate.mainHandler.post {
                        if (version == rowVersion) applyCandidates(target)
                    }
                    return
                }
                // The real widths are known now: move the row by exactly half the difference
                // between its two flanks, which is what puts the highlighted candidate on the
                // strip's centre line. A translation does not re-lay anything out, so it is safe
                // here -- and it is measured rather than estimated.
                val leadPos = adapter.highlightedAdapterPos
                if (leadPos in 0 until cnt) {
                    var left = 0
                    var right = 0
                    for (i in 0 until cnt) {
                        val width = (getChildAt(i)?.width ?: 0) + separatorDrawable.intrinsicWidth
                        if (i < leadPos) left += width else if (i > leadPos) right += width
                    }
                    applyRowShift((right - left) / 2)
                }
                if (secondLayoutPassNeeded) {
                    if (cnt < adapter.itemCount) {
                        // [^2] RecyclerView can't display all candidates
                        // update LayoutParams in onLayoutCompleted would trigger another
                        // onLayoutCompleted, skip the second one to avoid infinite loop
                        if (secondLayoutPassDone) return
                        secondLayoutPassDone = true
                        for (i in 0 until cnt) {
                            getChildAt(i)!!.updateLayoutParams<LayoutParams> {
                                flexGrow = 1f
                            }
                        }
                    } else {
                        secondLayoutPassNeeded = false
                    }
                }
                // "How many candidates the row shows" is not its child count: the row may hold
                // fewer than Rime's total (see [fittingRowCandidates]), and what it does hold is
                // whatever the last laid-out one is. That is all the unrolled window needs.
                refreshUnrolled(this.findLastVisibleItemPosition() + 1)
            }
        }
    }

    private val separatorDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val spacing = theme.generalStyle.candidateSpacing
            val intrinsicSize = max(spacing, context.dp(spacing)).toInt()
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = scope.colors.candidateSeparatorColor
        }
    }

    val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (fillStyle == CompactCandidateMode.AUTO_FILL) {
                    layoutMinWidth = slotMinWidth(maxSpanCountPref.getValue(), highlightedText)
                    this@CompactCandidateDelegate.adapter.updateLayoutParams(layoutMinWidth, layoutFlexGrow)
                }
            }
        }
        context.recyclerView(R.id.candidate_view) {
            itemAnimator = null
            isFocusable = false
            isFocusableInTouchMode = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                defaultFocusHighlightEnabled = false
            }
            adapter = this@CompactCandidateDelegate.adapter
            layoutManager = this@CompactCandidateDelegate.layoutManager
            // The strip ends in the privacy badge; padding the row by its width on BOTH sides
            // keeps its centre on the strip's centre, and the row is then slid by whatever its two
            // flanks differ by (see [applyRowShift]) so the highlighted candidate is on that same
            // centre line. (The unroll button used to live in here too, and its width came out of
            // every row on both sides -- on a phone that was a third of the strip.)
            setPadding(rowPadPx, 0, rowPadPx, 0)
            addItemDecoration(FlexboxVerticalDecoration(separatorDrawable))
        }
    }

    /**
     * Width the row really has: the view's width less the padding it keeps on both sides.
     *
     * The padding stays symmetric -- the row is centred inside it and then moved as a whole (see
     * [applyRowShift]) -- so this is simply the width the candidates may occupy.
     */
    private val rowUsableWidth: Int
        get() = (view.width - 2 * rowPadPx).coerceAtLeast(0)

    /**
     * Minimum width of one row item in the fill modes, in px.
     *
     * `maxSpanCount` candidates share the row's usable width -- inside its padding, not the view's
     * full width -- which is the whole point: the slot this returns is what every item, the
     * highlighted one included, is raised to, so it has to be small enough for that many of them to
     * sit side by side. Cutting it from the full width instead is what made a phone stop at three
     * candidates however high the setting was: no number of slots derived that way could ever fit
     * the row once the end padding was taken out.
     */
    private fun slotMinWidth(
        maxSpanCount: Int,
        highlightedText: String,
    ): Int = candidateSlotWidth(
        usable = rowUsableWidth,
        leadText = estimatedTextWidth(highlightedText, highlightedComment, 1f),
        slots = maxSpanCount,
        separator = separatorDrawable.intrinsicWidth,
    )

    /** What one row item costs the line: its own width, floored by the slot and the tap target. */
    private fun rowItemCost(
        text: String,
        comment: String,
        scale: Float,
        floor: Int,
    ): Int = maxOf(estimatedTextWidth(text, comment, scale), floor, minItemWidthPx) + separatorDrawable.intrinsicWidth

    /**
     * Moves the row so the highlighted candidate lands in the middle of the strip.
     *
     * The row is laid out centred inside the view's padding, which puts the candidate in the middle
     * of the *row* in the middle of the strip -- but the row is lopsided whenever its two flanks
     * weigh differently, and it always does once one of them has a candidate more. Sliding the row
     * by half that difference puts the highlighted candidate back where it belongs.
     *
     * Moved, not re-padded: the padding's job is to reserve the end badge, and a slide bigger than
     * the padding would need a negative margin, which would change the width the row has to work
     * with. A translation moves the row inside the space it already measured, and the count was
     * chosen so that it stays inside that space.
     */
    private fun applyRowShift(shift: Int, target: View = view) {
        target.translationX = shift.toFloat()
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        val (total, highlighted, candidates) = data

        val maxSpanCount = maxSpanCountPref.getValue()
        val highlightedCandidate = candidates.getOrNull(highlighted)
        highlightedText = highlightedCandidate?.text.orEmpty()
        highlightedComment = highlightedCandidate?.comment.orEmpty()

        when (fillStyle) {
            CompactCandidateMode.NEVER_FILL -> {
                layoutMinWidth = 0
                layoutFlexGrow = 0f
                secondLayoutPassNeeded = false
            }

            CompactCandidateMode.AUTO_FILL -> {
                layoutMinWidth = slotMinWidth(maxSpanCount, highlightedText)
                layoutFlexGrow = if (candidates.size < maxSpanCount) 0f else 1f
                // [^1] total candidates count < maxSpanCount
                secondLayoutPassNeeded = candidates.size < maxSpanCount
                secondLayoutPassDone = false
            }

            CompactCandidateMode.ALWAYS_FILL -> {
                layoutMinWidth = 0
                layoutFlexGrow = 1f
                secondLayoutPassNeeded = false
            }
        }

        adapter.updateLayoutParams(layoutMinWidth, layoutFlexGrow)

        lastCandidates = candidates
        lastHighlighted = highlighted
        lastTotal = total
        rowVersion++
        // A composition that just ended takes the row with it -- but not in one frame. The words
        // stay on screen while the row fades, then the list is emptied; a new list arriving during
        // the fade bumps the version and the emptying is dropped with it.
        if (candidates.isEmpty() && rowVisible) {
            val version = rowVersion
            rowVisible = false
            view.animate().cancel()
            view.animate().alpha(0f).setDuration(CANDIDATE_FADE_MS).withEndAction {
                if (version == rowVersion) applyCandidates(0)
            }.start()
            return
        }
        // Open on the candidate about to be committed: it is laid out in the middle of the row,
        // and the shift brings it to the middle of the strip. `submitList` does not place anything
        // on its own.
        applyCandidates(fittingRowCandidates(candidates, highlighted, maxSpanCount).kept)
        if (candidates.isNotEmpty()) fadeRowIn()
    }

    /**
     * Brings the row in with the composition: a short fade plus a few pixels of rise, so the first
     * keystroke reads as the candidates arriving rather than as the row being switched on.
     *
     * Only the arrival is animated. While a composition is running the list changes on every key,
     * and fading each of those would put the row a frame behind the typing for no gain.
     */
    private fun fadeRowIn() {
        if (rowVisible) return
        rowVisible = true
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = context.dp(CANDIDATE_RISE_DP).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(CANDIDATE_FADE_MS)
            .start()
    }

    /**
     * Submits the row with [kept] companions beside the highlighted candidate and re-centres it.
     *
     * Split out because the row has two chances to get this right: the width estimate above, and
     * the layout itself, which reports a candidate that did not fit and is asked for one fewer.
     */
    private fun applyCandidates(kept: Int) {
        lastKept = kept
        adapter.updateCandidates(lastCandidates, lastTotal, lastHighlighted, kept)
        applyRowShift(estimatedRowShift(kept))
        // The row is short enough to fit, so it starts at its own beginning.
        view.scrollToPosition(0)

        // not sure why empty candidates won't trigger `FlexboxLayoutManager#onLayoutCompleted()`
        if (lastCandidates.isEmpty()) {
            refreshUnrolled(0)
        }
    }

    /**
     * How many companions fit beside the highlighted candidate, and how far the row has to move to
     * put it back in the middle.
     *
     * The rule the row follows: the highlighted candidate's centre *is* the strip's centre, and the
     * others are laid out around it as evenly as their widths allow. A companion may therefore use
     * at most the half of the row left over once the highlighted one has taken its own width -- the
     * place a candidate on one side steals is taken from the *other* side, which is exactly how the
     * highlighted one ends up off-centre. When the next candidate does not fit that budget it is
     * simply not shown; the rest are reachable through the expand window.
     */
    private fun fittingRowCandidates(
        candidates: Array<CandidateProto>,
        highlighted: Int,
        maxSpanCount: Int,
    ): RowLayout {
        val room = (maxSpanCount - 1).coerceAtLeast(0)
        if (room == 0) return RowLayout(0, 0)
        // The same numbers slotMinWidth works from; a wider-than-its-slot candidate still costs
        // what it really costs.
        val restScale = AppPrefs.defaultInstance().candidates.restFontPercent.getValue() / 100f
        val widths =
            candidates.indices
                .filter { it != highlighted }
                .map { rowItemCost(candidates[it].text, candidates[it].comment, restScale, floor = layoutMinWidth) }
        val lead = candidates.getOrNull(highlighted)
        return fitRow(
            leadWidth = rowItemCost(lead?.text.orEmpty(), lead?.comment.orEmpty(), 1f, floor = layoutMinWidth),
            otherWidths = widths,
            usable = rowUsableWidth,
            room = room,
        )
    }

    /**
     * The shift for a row of [kept] companions, from the same widths the count was chosen with.
     *
     * Used for the first frame, before anything is laid out; [onLayoutCompleted] then measures the
     * children and corrects whatever the estimate got wrong.
     */
    private fun estimatedRowShift(kept: Int): Int {
        if (kept <= 0) return 0
        val restScale = AppPrefs.defaultInstance().candidates.restFontPercent.getValue() / 100f
        val widths =
            lastCandidates.indices
                .filter { it != lastHighlighted }
                .take(kept)
                .map { rowItemCost(lastCandidates[it].text, lastCandidates[it].comment, restScale, floor = layoutMinWidth) }
        val left = widths.take(kept / 2).sum()
        val right = widths.drop(kept / 2).sum()
        return (right - left) / 2
    }

    /**
     * Width of a candidate's text plus its comment plus its own padding, in px.
     *
     * A CJK glyph is about one em wide and a latin one about half; the comment is set in the theme's
     * smaller comment size and rides beside the text, and the item's padding is the theme's, doubled
     * for both sides. Leaving the comment out is not cosmetic: a candidate with one is wider than its
     * text suggests, and a row that then wraps puts the excess on a line nobody can see -- with the
     * highlighted candidate left half a slot off the middle of the strip.
     *
     * This is the width an item asks for on its own; the slot floor the fill modes impose is added by
     * the caller.
     */
    private fun estimatedTextWidth(
        text: String,
        comment: String,
        scale: Float,
    ): Int {
        val density = context.resources.displayMetrics.scaledDensity
        val textWidth = unitsOf(text) * theme.generalStyle.candidateTextSize * scale * density
        val commentWidth =
            if (comment.isEmpty()) {
                0f
            } else {
                unitsOf(comment) * theme.generalStyle.commentTextSize * scale * density + context.dp(1)
            }
        return (textWidth + commentWidth).toInt() + context.dp(theme.generalStyle.candidatePadding * 2)
    }

    /** Rough advance width of [text] in ems: a CJK glyph takes one, a latin one about half. */
    private fun unitsOf(text: String): Float {
        var units = 0f
        for (ch in text) units += if (ch.code > 0x2E80) 1f else 0.55f
        return units
    }

    /** Width of the strip's end badge, matching the cluster's layout. */
    private companion object {
        const val END_BUTTONS_WIDTH_DP = 22

        /** Length of the row's fade when a composition starts or ends, in milliseconds. */
        const val CANDIDATE_FADE_MS = 120L

        /** How far the row travels upwards while it fades in, in dp. */
        const val CANDIDATE_RISE_DP = 4
    }
}

/**
 * What the row can show: how many companions fit beside the highlighted candidate, and how far the
 * row has to move for it to sit in the middle.
 */
internal data class RowLayout(
    val kept: Int,
    val shift: Int,
)

/**
 * Picks the companions that fit around a centred candidate.
 *
 * The rule is about the highlighted candidate, not about the row: it sits in the middle of the
 * strip, so each flank may use at most half of what is left over once the highlighted candidate has
 * taken its own width. Anything beyond that has to be paid for by the other flank -- which is
 * exactly how a row grows lopsided and the highlighted candidate drifts off the middle. A candidate
 * that does not fit its flank is therefore not shown at all; it stays reachable through the expand
 * window, which is where the rest of Rime's list lives anyway.
 *
 * [otherWidths] are in Rime's order and are split the way the adapter splits them -- the first half
 * to the left of the highlighted candidate, the rest to the right -- so the returned count is the
 * same one the adapter is handed.
 */
internal fun fitRow(
    leadWidth: Int,
    otherWidths: List<Int>,
    usable: Int,
    room: Int,
): RowLayout {
    if (room <= 0 || otherWidths.isEmpty()) return RowLayout(0, 0)
    // Before the first layout there is no width to judge against; show what the setting asks for
    // rather than blinking the row empty.
    if (usable <= 0) return RowLayout(minOf(room, otherWidths.size), 0)
    val sideBudget = (usable - leadWidth) / 2
    if (sideBudget <= 0) return RowLayout(0, 0)
    var kept = 0
    var left = 0
    var right = 0
    for (k in 1..minOf(room, otherWidths.size)) {
        val leftCount = k / 2
        val nextLeft = otherWidths.take(leftCount).sum()
        val nextRight = otherWidths.drop(leftCount).take(k - leftCount).sum()
        if (nextLeft > sideBudget || nextRight > sideBudget) break
        kept = k
        left = nextLeft
        right = nextRight
    }
    return RowLayout(kept, (right - left) / 2)
}

/**
 * Width of one slot in the fill modes, in px.
 *
 * Every item is given this as a minimum -- the highlighted candidate too -- so the arithmetic has to
 * budget for [slots] of them, not [slots] minus one: giving the lead a share and then handing it a
 * slot as well was what pushed the last candidate of the row onto a second line, where it simply is
 * not visible. The exception is the lead's own text being wider than a slot, in which case it keeps
 * its width and the rest share what is left.
 *
 * Separated from the delegate so the arithmetic behind "how many candidates fit" can be pinned on
 * the JVM: it is the number users compare against the settings slider, and it can be wrong for a
 * whole release without anything failing.
 */
internal fun candidateSlotWidth(
    usable: Int,
    leadText: Int,
    slots: Int,
    separator: Int,
): Int {
    if (usable <= 0 || slots <= 0) return 0
    // The decoration puts a separator after *every* item, the last one included.
    if (slots == 1) return (usable - separator).coerceAtLeast(0)
    val even = (usable - slots * separator) / slots
    if (leadText <= even) return even.coerceAtLeast(0)
    return ((usable - slots * separator - leadText) / (slots - 1)).coerceAtLeast(0)
}
