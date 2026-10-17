/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import com.chad.library.adapter4.BaseQuickAdapter
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.candidates.CandidateItemUi
import com.osfans.trime.ime.candidates.CandidateViewHolder
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

/**
 * Narrowest a row item may get, in dp.
 *
 * A candidate is a tap target, so it cannot shrink to its text -- but this floor also decides how
 * many fit on the row: at 40 dp, six slots on a 369 dp-wide phone came out narrower than the floor,
 * the last candidate wrapped onto a second line nobody can see, and the highlighted one ended up
 * half a slot off the middle. 32 dp keeps the target usable and lets the row hold what the setting
 * asks for; the delegate uses the same number when it counts how many fit.
 */
internal const val CANDIDATE_MIN_ITEM_WIDTH_DP = 32

open class CompactCandidateViewAdapter(
    val scope: ThemeScope,
) : BaseQuickAdapter<CandidateProto, CandidateViewHolder>() {
    private val restFontPercent = AppPrefs.defaultInstance().candidates.restFontPercent

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items.getOrNull(position).hashCode().toLong()

    var total: Int = -1
        private set

    var highlightedIdx: Int = -1
        private set

    /**
     * Positions of the submitted rows in Rime's own list, one entry per row.
     *
     * The row carries the highlighted candidate in its middle rather than in its original place,
     * so a row position is not Rime's index. Every position that reaches Rime again -- a tap or a
     * long press -- has to come back through [toRimeIndex].
     */
    private var shownIndices: List<Int> = emptyList()

    /** Where Rime's highlighted candidate sits in the row. */
    var highlightedAdapterPos: Int = -1
        private set

    /** The row position's index in Rime's list. */
    fun toRimeIndex(position: Int): Int = shownIndices.getOrNull(position) ?: position

    var layoutMinWidth: Int = 0
        private set

    var layoutFlexGrow: Float = 0f
        private set

    fun updateLayoutParams(minWidth: Int, flexGrow: Float) {
        layoutMinWidth = minWidth
        layoutFlexGrow = flexGrow
    }

    fun updateCandidates(
        data: Array<CandidateProto>,
        total: Int,
        highlightedIndex: Int,
        keptCount: Int,
    ) {
        val hasHighlighted = highlightedIndex in data.indices
        val rest = data.indices.filter { it != highlightedIndex }
        // Only what the strip can hold is laid out, and the candidate about to be committed is
        // put in the middle of that: a row that fits centres itself in the strip, and that is
        // what puts the candidate in the middle of the screen. The rest stay reachable through
        // the unroll window. [keptCount] is how many of them still fit -- it is measured against
        // the row's real width rather than counted, because a candidate can be a whole sentence
        // wide and one of those would push the row past the strip and cost it its centring.
        val kept = if (rest.size > keptCount) rest.take(keptCount) else rest
        val shown: List<Int> =
            if (hasHighlighted) {
                kept.toMutableList().apply { add(size / 2, highlightedIndex) }
            } else {
                kept
            }
        shownIndices = shown
        highlightedAdapterPos = shown.indexOf(highlightedIndex)

        super.submitList(shown.map { data[it] }, null)
        this.total = total
        this.highlightedIdx = highlightedIndex
    }

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): CandidateViewHolder {
        val ui = CandidateItemUi(context, scope)
        ui.root.apply {
            minimumWidth = dp(CANDIDATE_MIN_ITEM_WIDTH_DP)
            layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    override fun onBindViewHolder(
        holder: CandidateViewHolder,
        position: Int,
        item: CandidateProto?,
    ) {
        item ?: return
        // The candidate about to be committed keeps the theme's own size; the ones around it are
        // drawn smaller, by the size slider's amount, so the eye lands on it first.
        val scale = if (position == highlightedAdapterPos) 1f else restFontPercent.getValue() / 100f
        holder.ui.applyScale(scale)
        val isHighlighted = position == highlightedAdapterPos
        holder.ui.update(item, isHighlighted)
        holder.text = item.text
        holder.comment = item.comment
        holder.idx = position // unused
        holder.ui.root.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
            minWidth = this@CompactCandidateViewAdapter.layoutMinWidth
            flexGrow = this@CompactCandidateViewAdapter.layoutFlexGrow
        }
    }
}
