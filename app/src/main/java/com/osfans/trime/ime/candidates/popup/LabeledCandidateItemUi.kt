/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.util.sp
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.textView

class LabeledCandidateItemUi(
    override val ctx: Context,
    private val scope: ThemeScope,
) : Ui {
    private val theme: Theme
        get() = scope.theme

    private val labelSize = theme.window.foreground.labelFontSize
    private val textSize = theme.window.foreground.textFontSize
    private val commentSize = theme.window.foreground.commentFontSize
    private val labelFont = FontManager.getTypeface("label_font")
    private val textFont = FontManager.getTypeface("candidate_font")
    private val commentFont = FontManager.getTypeface("comment_font")

    // Read at use time so a scheme switch re-binds rows with the new colors.
    private val labelColor: Int get() = scope.colors.labelColor
    private val textColor: Int get() = scope.colors.candidateTextColor
    private val commentColor: Int get() = scope.colors.commentTextColor
    private val highlightLabelColor: Int get() = scope.colors.hilitedLabelColor
    private val highlightCommentTextColor: Int get() = scope.colors.hilitedCommentTextColor
    private val highlightCandidateTextColor: Int get() = scope.colors.hilitedCandidateTextColor
    private val highlightCandidateBackColor: Int get() = scope.colors.hilitedCandidateBackColor

    /**
     * Fill for the candidates that are not highlighted, when [itemBackground] asks for one.
     *
     * The separator colour is the right source here: every theme already defines it to sit between
     * the window background and the highlight, so an item filled with it reads as "a slot of the
     * row" while the highlighted item's own colour still clearly wins.
     */
    private val itemBackColor: Int get() = scope.colors.candidateSeparatorColor

    // Held as a delegate and read per bind rather than captured with `by`, which would freeze the
    // value at construction and leave the setting looking like it did nothing.
    private val itemBackground = AppPrefs.defaultInstance().candidates.itemBackground
    private val itemBackgroundAlpha = AppPrefs.defaultInstance().candidates.itemBackgroundAlpha


    override val root =
        textView {
            val v = dp(theme.window.itemPadding.vertical)
            val h = dp(theme.window.itemPadding.horizontal)
            setPadding(h, v, h, v)
            // An item shares its single line with its neighbours (see PagedCandidatesUi), so it can
            // never wrap: a two-line item would grow the whole window by a row. What *is* allowed
            // is to give up some size -- see [update], which shrinks the item before any text is
            // dropped. The ellipsis is the last-ditch guard behind that, for whatever the shrink
            // could not fit (a measurement that came back unusable, mostly).
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    private inline fun SpannableStringBuilder.inSpanWith(
        @ColorInt color: Int,
        textSize: Float,
        typeface: Typeface,
        builderAction: SpannableStringBuilder.() -> Unit,
    ) = inSpans(CandidateItemSpan(color, textSize, typeface), builderAction)

    /** The label, candidate and comment, in the theme's own sizes scaled by [scale]. */
    private fun textOf(
        candidate: CandidateProto,
        highlighted: Boolean,
        scale: Float,
    ): CharSequence {
        val labelFg = if (highlighted) highlightLabelColor else labelColor
        val textFg = if (highlighted) highlightCandidateTextColor else textColor
        val commentFg = if (highlighted) highlightCommentTextColor else commentColor
        return buildSpannedString {
            inSpanWith(labelFg, ctx.sp(labelSize * scale), labelFont) { append(candidate.label) }
            append(" ")
            inSpanWith(textFg, ctx.sp(textSize * scale), textFont) { append(candidate.text) }
            if (candidate.comment.isNotBlank()) {
                append(" ")
                inSpanWith(commentFg, ctx.sp(commentSize * scale), commentFont) { append(candidate.comment) }
            }
        }
    }

    /**
     * Binds one candidate, giving up as much of its size as [maxWidth] demands.
     *
     * A candidate that is longer than the space it has would otherwise be ellipsized, which drops
     * text the user is in the middle of reading. Shrinking costs size instead of text, and the
     * candidates drawn on the keyboard already behave that way, so both places read the same. Only
     * the squeezed items pay for it: the common case is measured, found to fit and left alone.
     */
    fun update(
        candidate: CandidateProto,
        highlighted: Boolean,
        maxWidth: Int,
    ) {
        root.text = textOf(candidate, highlighted, scale = 1f)
        val scale = candidateItemScale(measureUnconstrained(), maxWidth)
        // Rebuilding only when the item is actually being squeezed keeps the theme's own sizes --
        // and one less `setText` -- on the path every candidates window takes per keystroke.
        if (scale < 1f) root.text = textOf(candidate, highlighted, scale)
        root.background =
            GradientDrawable().apply {
                setColor(
                    candidateItemBackColor(
                        highlighted = highlighted,
                        fillUnhighlighted = itemBackground.getValue(),
                        highlightColor = highlightCandidateBackColor,
                        itemColor = itemBackColor,
                        // Only the unhighlighted fill is faded; the highlighted one keeps the
                        // theme's own strength, since that contrast is what marks the selection.
                        itemAlpha = itemBackgroundAlpha.getValue() * 255 / 100,
                    ),
                )
                cornerRadius = ctx.dp(theme.generalStyle.candidateCornerRadius)
            }
    }

    /**
     * Width this item takes on its own, with nothing constraining it, at the theme's own sizes.
     *
     * Measured on the real [root] instead of on a bare `TextPaint`: the number then comes from
     * the very layout pass the row itself will run, so the item's padding, fonts and spans are
     * all included. Measuring the spans by hand is not an option -- a `TextPaint` that never went
     * through the item cannot be trusted to report the width the `TextView` will actually take,
     * and an under-report silently squeezes every candidate into an ellipsis.
     */
    fun naturalWidth(candidate: CandidateProto): Int {
        root.text = textOf(candidate, highlighted = false, scale = 1f)
        return measureUnconstrained()
    }

    /**
     * The width [root] would take if nothing constrained it.
     *
     * [root] must already carry layout params: `TextView.setText` reads
     * `ViewGroup.LayoutParams.width` (via `checkForRelayout`) and throws a `NullPointerException`
     * when they are missing. The row assigns them in `onCreateViewHolder`; a standalone measuring
     * probe is never added to a parent, so it has to do the same before the first `setText`.
     */
    private fun measureUnconstrained(): Int {
        if (root.layoutParams == null) {
            root.layoutParams =
                FlexboxLayoutManager.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }
        root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return root.measuredWidth
    }
}

/**
 * The fill behind one candidate item.
 *
 * The highlighted candidate always wins: its colour is what tells the user which candidate the
 * next space bar will commit to, so [fillUnhighlighted] must never take it over and [itemAlpha]
 * never touches it. Filling the rest is opt-in and deliberately uses a *different* colour
 * ([itemColor], the theme's separator colour) rather than a dimmed version of the highlight -- a
 * dimmed highlight would compete with the real one, which is the whole thing this is meant to
 * avoid.
 */
internal fun candidateItemBackColor(
    highlighted: Boolean,
    fillUnhighlighted: Boolean,
    highlightColor: Int,
    itemColor: Int,
    itemAlpha: Int = 255,
): Int =
    when {
        highlighted -> highlightColor
        fillUnhighlighted -> itemColor.withAlpha(itemAlpha)
        else -> Color.TRANSPARENT
    }

/**
 * Ratio the text sizes of a shrunk item are multiplied by, alongside [candidateItemScale].
 *
 * A hair under the exact ratio: glyph advances do not scale perfectly linearly, so freeing exactly
 * as much width as the sum of the advances suggests can still round to a pixel over and bring back
 * the ellipsis this is meant to remove.
 */
internal const val CANDIDATE_SHRINK_MARGIN = 0.98f

/**
 * Floor for [candidateItemScale].
 *
 * Shrinking exists to save readable text, so it stops once the text stops being readable: past half
 * the theme's own size the ellipsis keeps more than a line of specks would.
 */
internal const val CANDIDATE_MIN_SCALE = 0.5f

/**
 * Share of the theme's own text sizes an item keeps when it is given [maxWidth].
 *
 * 1 means "leave it alone", which is both the answer for an item that already fits and the answer
 * before the row has been laid out at all ([maxWidth] of 0): a width that is not known is not a
 * reason to start shrinking text.
 */
internal fun candidateItemScale(
    naturalWidth: Int,
    maxWidth: Int,
): Float {
    if (naturalWidth <= 0 || maxWidth <= 0 || naturalWidth <= maxWidth) return 1f
    val exact = maxWidth.toFloat() / naturalWidth.toFloat()
    return (exact * CANDIDATE_SHRINK_MARGIN).coerceAtLeast(CANDIDATE_MIN_SCALE)
}

/**
 * [color] scaled by [alpha] (0..255), keeping the theme's own opacity as the ceiling.
 *
 * The fill comes from a theme colour, which carries whatever opacity the theme author chose -- it
 * may be deliberately translucent. The two have to *compose*: multiplying means 100% leaves the
 * theme's own translucency alone, while a lower setting dims it further. Replacing the alpha
 * outright would make 100% mean "fully opaque" and quietly override a theme that meant to be
 * see-through, and would make a lower setting *raise* the alpha of a faint theme colour.
 */
private fun Int.withAlpha(alpha: Int): Int {
    val own = (this ushr 24 and 0xFF)
    val scaled = own * alpha.coerceIn(0, 255) / 255
    return (this and 0x00FFFFFF) or (scaled shl 24)
}
