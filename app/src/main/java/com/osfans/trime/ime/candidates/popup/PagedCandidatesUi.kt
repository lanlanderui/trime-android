/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.data.theme.ThemeScope
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.recyclerview.recyclerView

class PagedCandidatesUi(
    override val ctx: Context,
    val scope: ThemeScope,
    private val onCandidateClick: (Int) -> Unit,
    private val onCandidateAction: (Int, String, View) -> Unit,
    private val onPrevPage: () -> Unit,
    private val onNextPage: () -> Unit,
) : Ui {
    private var candidates = Candidates.Paged()

    private var isHorizontal = true

    /**
     * Fixed width given to every candidate, or 0 to let each one keep its natural width.
     *
     * The row is a single line, so its candidates cannot be allowed to size themselves freely: a
     * page that does not fit would wrap onto a second line. The owner computes one width from the
     * candidates' natural widths instead -- wide enough for the widest one it keeps, so nothing is
     * ellipsized. A layout that stacks the candidates ignores this: those span the window's width.
     */
    private var itemWidth = 0

    /** Read per bind rather than captured with `by`, which would freeze the value at construction
     * and leave the setting looking like it did nothing. */
    private val itemSpacingPref = AppPrefs.defaultInstance().candidates.itemSpacing

    /**
     * Width the row itself may occupy, as the window measured it.
     *
     * Only an item that spans the row needs it (a candidate layout that stacks them); an item of a
     * fixed width is handed its own number instead. Kept because the row cannot work it out from
     * its own bounds: those are only settled after it has been laid out, by which time the items
     * have already been bound and drawn.
     */
    private var rowWidth = 0

    /**
     * Total horizontal room the gaps between [count] candidates take, in pixels.
     *
     * The owner has to subtract this from the usable width before deciding how wide each candidate
     * may be. Every candidate carries the gap on **both** sides, and the outermost ones do too --
     * the row is a flex line, so the leading and trailing margins are part of its width just like
     * the ones in between. Overlooking that makes the row overflow and wrap onto a second line,
     * which is the one thing the single-row window must never do.
     */
    fun spacingFor(count: Int): Int = if (count <= 0) 0 else count * 2 * ctx.dp(itemSpacingPref.getValue())

    /**
     * Measures how wide each of [candidates] would be if nothing were compressed.
     *
     * The row has to be a single line, and the "max candidates per line" setting is a maximum
     * rather than a target, so the owner needs the natural widths to pick a width that shows
     * every candidate in full (see `balancedItemWidth`). It measures the *slice* the caller is
     * about to show, not the currently bound list, so it can be asked before the window is cut.
     *
     * A width is only ever an optimisation here, so a failed measurement degrades to 0 ("size
     * this candidate itself") instead of propagating. This runs on the per-keystroke path of an
     * input method, where an exception kills the whole IME process and leaves the user with no
     * keyboard at all -- never worth a nicer row.
     */
    private val measureItem = LabeledCandidateItemUi(ctx, scope)

    fun naturalWidths(candidates: List<CandidateProto>): List<Int> =
        candidates.map { candidate ->
            try {
                measureItem.naturalWidth(candidate)
            } catch (_: Exception) {
                0
            }
        }

    sealed class UiHolder(
        open val ui: Ui,
    ) : RecyclerView.ViewHolder(ui.root) {
        class Candidate(
            override val ui: LabeledCandidateItemUi,
        ) : UiHolder(ui)

        class Pagination(
            override val ui: PaginationUi,
        ) : UiHolder(ui)
    }

    private val candidatesAdapter =
        object : BaseQuickAdapter<CandidateProto, UiHolder>() {
            init {
                // We must do this to avoid ArrayIndexOutOfBoundsException
                // https://github.com/google/flexbox-layout/issues/363#issuecomment-382949953
                setHasStableIds(true)
            }

            override fun getItemId(position: Int): Long = items.getOrNull(position).hashCode().toLong()

            override fun getItemCount(items: List<CandidateProto>) = items.size + (if (candidates.hasPrevPage || candidates.hasNextPage) 1 else 0)

            override fun getItemViewType(
                position: Int,
                list: List<CandidateProto>,
            ) = if (position < list.size) 0 else 1

            override fun onCreateViewHolder(
                context: Context,
                parent: ViewGroup,
                viewType: Int,
            ): UiHolder = when (viewType) {
                0 -> UiHolder.Candidate(LabeledCandidateItemUi(ctx, scope))

                else -> UiHolder.Pagination(PaginationUi(ctx, scope)).apply {
                    ui.prevIcon.setOnClickListener {
                        onPrevPage.invoke()
                    }
                    ui.nextIcon.setOnClickListener {
                        onNextPage.invoke()
                    }
                }
            }.apply {
                // assign default LayoutParams, otherwise updateLayoutParams won't work
                ui.root.layoutParams = FlexboxLayoutManager.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }

            override fun onBindViewHolder(
                holder: UiHolder,
                position: Int,
                item: CandidateProto?,
            ) {
                when (holder) {
                    is UiHolder.Candidate -> {
                        val candidate = item ?: return
                        holder.ui.root.setOnClickListener {
                            onCandidateClick.invoke(position)
                        }
                        holder.ui.root.setOnLongClickListener { v ->
                            onCandidateAction.invoke(position, candidate.text, v)
                            true
                        }
                        holder.ui.update(candidate, position == candidates.highlighted, itemMaxWidth())
                        holder.ui.root.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                            width = when {
                                !isHorizontal -> MATCH_PARENT
                                itemWidth > 0 -> itemWidth
                                else -> WRAP_CONTENT
                            }
                            // Without a gap the fills of two neighbouring candidates touch and the
                            // row reads as a single block, so the items stop looking individually
                            // selectable. The gap follows the axis the candidates run along: a
                            // horizontal row separates them left/right, a vertical one top/bottom.
                            val gap = ctx.dp(itemSpacingPref.getValue())
                            val horizontalGap = if (isHorizontal) gap else 0
                            marginStart = horizontalGap
                            marginEnd = horizontalGap
                            val verticalGap = if (isHorizontal) 0 else gap
                            topMargin = verticalGap
                            bottomMargin = verticalGap
                        }
                    }

                    is UiHolder.Pagination -> {
                        holder.ui.update(candidates)
                        holder.ui.root.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                            flexGrow = 1f
                            width = if (isHorizontal) WRAP_CONTENT else MATCH_PARENT
                            alignSelf = if (isHorizontal) AlignItems.CENTER else AlignItems.STRETCH
                        }
                    }
                }
            }
        }

    private val candidatesLayoutManager =
        FlexboxLayoutManager(ctx).apply {
            flexWrap = FlexWrap.WRAP
        }

    override val root =
        recyclerView {
            itemAnimator = null
            isFocusable = false
            adapter = candidatesAdapter
            layoutManager = candidatesLayoutManager
            overScrollMode = View.OVER_SCROLL_NEVER
        }

    fun update(
        candidates: Candidates.Paged,
        layout: PopupCandidatesLayout,
        rowWidth: Int,
    ) {
        val wasHorizontal = isHorizontal
        this.candidates = candidates
        this.isHorizontal = resolvedHorizontalLayout(candidates, layout)
        this.rowWidth = rowWidth
        candidatesLayoutManager.apply {
            flexDirection = when (layout) {
                PopupCandidatesLayout.HORIZONTAL -> FlexDirection.ROW

                PopupCandidatesLayout.VERTICAL_REVERSE -> FlexDirection.COLUMN_REVERSE

                PopupCandidatesLayout.AUTOMATIC ->
                    if (isHorizontal) FlexDirection.ROW else FlexDirection.COLUMN

                else -> FlexDirection.COLUMN
            }
            alignItems = if (isHorizontal) AlignItems.BASELINE else AlignItems.STRETCH
        }
        candidatesAdapter.submitList(candidates.candidates.toList())
        // The gaps follow the axis, so a flip has to re-bind even when the page is unchanged.
        // submitList only dispatches when the contents differ, and Rime reports the same page
        // for both layouts, so without this the row would keep the previous orientation's margins.
        if (wasHorizontal != isHorizontal) {
            candidatesAdapter.notifyDataSetChanged()
        }
    }

    /** Re-binds visible rows so they re-render with the current scheme's colors. */
    fun refreshColors() {
        candidatesAdapter.notifyDataSetChanged()
    }

    /** Re-lays the row out with a new width per candidate; no-op when it did not change. */
    fun setItemWidth(width: Int) {
        if (itemWidth == width) return
        itemWidth = width
        candidatesAdapter.notifyDataSetChanged()
    }

    /**
     * Width an item may draw into: the one the row hands it (see [setItemWidth]), or the whole row
     * when the item spans it.
     *
     * 0 means the geometry is not known yet, which the item reads as "do not squeeze" -- the safe
     * behaviour, and the one every candidates window had before an item could shrink at all.
     */
    private fun itemMaxWidth(): Int =
        if (isHorizontal) itemWidth.takeIf { it > 0 } ?: rowWidth else rowWidth
}

/**
 * The row orientation the window resolves for [layout]: an explicit preference wins, while
 * [PopupCandidatesLayout.AUTOMATIC] defers to the layout Rime reports along with the page.
 *
 * The owner of the view needs the same answer to size its candidates against, so this is shared
 * rather than resolved twice.
 */
internal fun resolvedHorizontalLayout(
    candidates: Candidates.Paged,
    layout: PopupCandidatesLayout,
): Boolean = when (layout) {
    PopupCandidatesLayout.AUTOMATIC -> candidates.isHorizontalLayout
    else -> layout == PopupCandidatesLayout.HORIZONTAL
}
