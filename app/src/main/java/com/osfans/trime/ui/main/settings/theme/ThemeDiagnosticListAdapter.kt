/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.data.theme.ThemeDiagnostics
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.styledColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

/** Lists the [ThemeDiagnostics.Finding]s of the theme in use, warnings showing. */
class ThemeDiagnosticListAdapter(
    private val findings: List<ThemeDiagnostics.Finding>,
) : RecyclerView.Adapter<ThemeDiagnosticListAdapter.ViewHolder>() {
    class ViewHolder(
        val ui: EntryUi,
    ) : RecyclerView.ViewHolder(ui.root)

    class EntryUi(
        override val ctx: Context,
    ) : Ui {
        private val warningColor = ctx.styledColor(androidx.appcompat.R.attr.colorError)
        private val mutedColor = ctx.styledColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        private val message =
            textView {
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(ctx.styledColor(com.google.android.material.R.attr.colorOnSurface))
            }

        private val messageColor = message.currentTextColor

        private val path =
            textView {
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                typeface = Typeface.MONOSPACE
                setTextColor(mutedColor)
            }

        override val root =
            verticalLayout {
                layoutParams =
                    ViewGroup.MarginLayoutParams(matchParent, wrapContent).apply {
                        setMargins(0, dp(3), 0, dp(3))
                    }
                background = drawable(R.drawable.bg_preference_item_material3)
                setPaddingDp(16, 14, 16, 14)
                add(message, lParams(matchParent, wrapContent))
                add(path, lParams(matchParent, wrapContent))
            }

        fun bind(finding: ThemeDiagnostics.Finding) {
            val warning = finding.severity == ThemeDiagnostics.Severity.WARNING
            message.setTextColor(if (warning) warningColor else messageColor)
            message.text = finding.message
            path.isVisible = finding.path != null
            path.text = finding.path.orEmpty()
        }
    }

    override fun getItemCount() = findings.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(EntryUi(parent.context))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        holder.ui.bind(findings[position])
    }
}
