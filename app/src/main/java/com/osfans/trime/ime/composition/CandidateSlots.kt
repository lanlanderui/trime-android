/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates

/**
 * Geometry of the popup candidate window -- one row of candidates, or one column when the layout
 * stacks them -- and of the "window" it shows over a page.
 *
 * Rime pages the menu itself and the page size is a schema setting (`menu/page_size`), so a page
 * regularly holds more candidates than the window can show: the window then shows [perLine] of
 * them and its arrows step that window, only turning Rime's page once the window reaches the end
 * of the page.
 *
 * Cutting the page down instead would be simpler, but the extra candidates would become
 * unreachable -- the arrows turn Rime's page, not the window, so they would be skipped over.
 */

/** First offset of the window that holds [highlighted], rounded down to a whole window. */
internal fun slotOffsetFor(
    highlighted: Int,
    perLine: Int,
): Int {
    if (perLine <= 0) return 0
    return highlighted.coerceAtLeast(0) / perLine * perLine
}

/** Offset of the last window of a page of [pageCount] candidates. */
internal fun lastSlotOffset(
    pageCount: Int,
    perLine: Int,
): Int {
    if (perLine <= 0 || pageCount <= 0) return 0
    return (pageCount - 1) / perLine * perLine
}

/**
 * Offset of the window one step away, or `null` when the step has to leave the page.
 *
 * A `null` means the caller should ask Rime for the neighbouring page and start over at the
 * window holding its highlighted candidate.
 */
internal fun steppedSlotOffset(
    offset: Int,
    perLine: Int,
    pageCount: Int,
    forward: Boolean,
): Int? {
    if (perLine <= 0) return null
    val next = if (forward) offset + perLine else offset - perLine
    if (next < 0 || next >= pageCount) return null
    return next
}

/** Whether anything is left before a window at [offset], in this page or an earlier one. */
internal fun slotHasPrev(
    offset: Int,
    hasPrevPage: Boolean,
): Boolean = offset > 0 || hasPrevPage

/** Whether anything is left after a window at [offset], in this page or a later one. */
internal fun slotHasNext(
    offset: Int,
    perLine: Int,
    pageCount: Int,
    hasNextPage: Boolean,
): Boolean = offset + perLine < pageCount || hasNextPage

/**
 * How many candidates one window holds, given that at most [fit] of them share its width.
 *
 * This is the number the window is actually cut to, and therefore also the number the arrows have
 * to step by: stepping by [perLine] while the window holds fewer would jump over the candidates in
 * between, and they would be unreachable.
 */
internal fun windowCapacity(
    perLine: Int,
    fit: Int,
): Int = minOf(perLine, fit).coerceAtLeast(1)

/**
 * The window at [offset], with the paging flags remapped onto it.
 *
 * [fit] is how many candidates the row can actually show; it is at most [perLine], and is smaller
 * when the candidates' own widths mean fewer of them share one line. Passing the count the row
 * was measured for is what keeps the row from wrapping. A layout that does not fill a row by
 * width -- a vertical column -- passes nothing and is cut to [perLine] alone.
 *
 * A page that already fits is returned untouched, so a window size that is at least the page size
 * behaves exactly like the page itself. Note that "fits" is judged against [windowCapacity] and not
 * [perLine]: a page of exactly [perLine] candidates that measure too wide for the row still has to
 * be cut, or the row wraps.
 */
internal fun windowOf(
    page: Candidates.Paged,
    offset: Int,
    perLine: Int,
    fit: Int = perLine,
): Candidates.Paged {
    if (perLine <= 0) return page
    val count = page.candidates.size
    val capacity = windowCapacity(perLine, fit)
    if (count <= capacity) return page
    val from = offset.coerceIn(0, count - 1)
    val slice = page.candidates.drop(from).take(capacity)
    // -1 means "nothing on screen is highlighted", which is what browsing past the highlighted
    // candidate looks like; PagedCandidatesUi simply matches no position.
    val highlighted = (page.highlighted - from).takeIf { it in slice.indices } ?: -1
    return page.copy(
        candidates = slice.toTypedArray(),
        highlighted = highlighted,
        hasPrevPage = slotHasPrev(from, page.hasPrevPage),
        hasNextPage = slotHasNext(from, capacity, count, page.hasNextPage),
    )
}

/**
 * How many of [naturalWidths] fit on one line at one uniform width.
 *
 * This is the count that [balancedItemWidth] sizes for, and the window must be cut to the same
 * number: showing more candidates than the width was computed for would make the row wrap onto a
 * second line, which is the one thing the single-row window must never do.
 */
internal fun fittedCount(
    naturalWidths: List<Int>,
    availableWidth: Float,
    paginationWidth: Int,
): Int {
    if (naturalWidths.isEmpty()) return 1
    val usable = (availableWidth - paginationWidth).toInt()
    if (usable <= 0) return 1
    var keep = 1
    var widest = naturalWidths[0]
    while (keep < naturalWidths.size) {
        val candidate = widest.coerceAtLeast(naturalWidths[keep])
        if (candidate.toLong() * (keep + 1) > usable) break
        widest = candidate
        keep++
    }
    return keep
}

/**
 * Width every candidate of one row is given, so that no candidate is truncated.
 *
 * [perLine] is a *maximum*, not a target: forcing an exact split means a row of six candidates
 * gets a sixth of the width each, and any candidate with more than a character or two ends up
 * ellipsized ("我..." instead of "我们"). Instead as many candidates as will fit at their natural
 * width share one uniform width, and that width is the widest of them -- so the row stays on one
 * line *and* every candidate it shows is complete.
 *
 * The kept candidates are always a prefix in the caller's order (see [fittedCount]), so the row
 * reads in Rime's order; only the count is trimmed. Returns 0 when the geometry is not known yet
 * (before the first layout), which callers read as "let each candidate size itself".
 */
internal fun balancedItemWidth(
    naturalWidths: List<Int>,
    availableWidth: Float,
    paginationWidth: Int,
): Int {
    val usable = (availableWidth - paginationWidth).toInt()
    if (naturalWidths.isEmpty() || usable <= 0) return 0
    val keep = fittedCount(naturalWidths, availableWidth, paginationWidth)
    val widest = naturalWidths.take(keep).max()
    // A measurement that came back empty (an unlaid-out item, a zero-sized font) must not be
    // turned into a row of hairlines: every candidate would ellipsize to nothing at all. Handing
    // over 0 lets each candidate size itself, which is the old, safe behaviour.
    if (widest <= 0) return 0
    // If even one candidate overflows, hand over the whole row rather than a negative width.
    return if (widest.toLong() * keep <= usable) widest else usable
}

/** The candidates a window shows, and the width each of them is given. */
internal data class CandidateWindow(
    val page: Candidates.Paged,
    /** Shared width per candidate, or 0 to let each one size itself. */
    val itemWidth: Int,
    /**
     * How many candidates one window holds -- what the arrows have to step by.
     *
     * Read back by the owner rather than recomputed there, because it depends on the measurement
     * the owner does not keep.
     */
    val capacity: Int,
)

/**
 * The window to show for [page] at [offset], and how wide its candidates are.
 *
 * The two candidate layouts pose different problems, and sharing one code path between them would
 * make both wrong:
 *
 * - A row runs out of *width*. It has to be measured, and only as many candidates as fit at one
 *   uniform width may be shown, or the row wraps onto a second line -- which is the one thing a
 *   window anchored to the cursor must never do.
 * - A column runs out of *height*, not width: every candidate spans the window and they stack, so
 *   there is nothing to measure ([naturalWidths] is not even called) and nothing to balance. The
 *   count is still capped by [perLine], because an unbounded stack would cover the screen.
 */
internal fun candidateWindow(
    page: Candidates.Paged,
    offset: Int,
    perLine: Int,
    horizontal: Boolean,
    availableWidth: Float,
    paginationWidth: Int,
    spacingWidth: Int,
    naturalWidths: (List<CandidateProto>) -> List<Int>,
): CandidateWindow {
    if (!horizontal) {
        return CandidateWindow(
            page = windowOf(page, offset, perLine),
            itemWidth = 0,
            capacity = windowCapacity(perLine, perLine),
        )
    }
    val slot = page.candidates.drop(offset).take(perLine.coerceAtLeast(1))
    val usable = availableWidth - spacingWidth
    val natural = naturalWidths(slot)
    val fit = fittedCount(natural, usable, paginationWidth)
    return CandidateWindow(
        page = windowOf(page, offset, perLine, fit),
        itemWidth = balancedItemWidth(natural.take(fit.coerceAtLeast(0)), usable, paginationWidth),
        capacity = windowCapacity(perLine, fit),
    )
}
