/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.unrolled.window

import android.view.Gravity
import android.view.ViewGroup
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.Transition
import androidx.transition.TransitionSet
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.osfans.trime.ime.candidates.CandidateViewHolder
import com.osfans.trime.ime.candidates.unrolled.PagingCandidateViewAdapter
import com.osfans.trime.ime.candidates.unrolled.UnrolledCandidateLayout
import com.osfans.trime.ime.candidates.unrolled.decoration.FlexboxHorizontalDecoration
import com.osfans.trime.ime.window.BoardWindow
import org.kodein.di.DI
import splitties.dimensions.dp
import splitties.views.dsl.core.wrapContent

class FlexboxUnrolledCandidateWindow(di: DI) : BaseUnrolledCandidateWindow(di) {
    /**
     * The window's own come-and-go: a fade with a short drop from the top edge, and the same lift
     * on the way out.
     *
     * It runs slower than the shared default so the rows are seen arriving rather than blinking,
     * and the fade keeps the panel from looking like a slab dropping in.
     */
    private fun unrolledTransition(): Transition = TransitionSet().apply {
        addTransition(Slide().apply { slideEdge = Gravity.TOP })
        addTransition(Fade())
        duration = UNROLLED_ANIMATION_MS
    }

    override fun enterAnimation(lastWindow: BoardWindow): Transition = unrolledTransition()

    override fun exitAnimation(nextWindow: BoardWindow): Transition = unrolledTransition()

    override val adapter by lazy {
        object : PagingCandidateViewAdapter(scope) {
            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): CandidateViewHolder = super.onCreateViewHolder(parent, viewType).apply {
                itemView.apply {
                    minimumWidth = dp(40)
                    val itemHeight = dp(theme.generalStyle.run { candidateViewHeight + commentHeight })
                    layoutParams =
                        FlexboxLayoutManager
                            .LayoutParams(wrapContent, itemHeight)
                            .apply { flexGrow = 1f }
                }
            }

            override fun onBindViewHolder(
                holder: CandidateViewHolder,
                position: Int,
            ) {
                super.onBindViewHolder(holder, position)
                bindCandidateUiViewHolder(holder)
            }
        }
    }

    override val layoutManager by lazy {
        FlexboxLayoutManager(context).apply {
            justifyContent = JustifyContent.SPACE_AROUND
            alignItems = AlignItems.FLEX_START
        }
    }

    override fun onCreateCandidateLayout(): UnrolledCandidateLayout = UnrolledCandidateLayout(context, scope).apply {
        recyclerView.apply {
            adapter = this@FlexboxUnrolledCandidateWindow.adapter
            layoutManager = this@FlexboxUnrolledCandidateWindow.layoutManager
            addItemDecoration(FlexboxHorizontalDecoration(separatorDrawable))
        }
    }

    private companion object {
        /** How long the panel takes to arrive or leave, in milliseconds. */
        const val UNROLLED_ANIMATION_MS = 180L
    }
}
