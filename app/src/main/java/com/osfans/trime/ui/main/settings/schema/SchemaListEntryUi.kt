/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.schema

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.TextViewCompat
import com.google.android.material.checkbox.MaterialCheckBox
import com.osfans.trime.R
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.styledColor
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

class SchemaListEntryUi(
    override val ctx: Context,
) : Ui {
    val checkBox = view(::MaterialCheckBox)
    val nameText =
        textView {
            setPaddingDp(0, 16, 0, 16)
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setTextColor(styledColor(com.google.android.material.R.attr.colorOnSurface))
        }

    override val root: View =
        constraintLayout {
            layoutParams =
                ViewGroup.MarginLayoutParams(matchParent, wrapContent).apply {
                    setMargins(0, dp(3), 0, dp(3))
                }
            background = drawable(R.drawable.bg_preference_item_material3)
            minHeight = dp(72)

            add(
                checkBox,
                lParams {
                    width = dp(48)
                    height = matchConstraints
                    centerVertically()
                    startOfParent(dp(4))
                },
            )

            add(
                nameText,
                lParams {
                    width = matchConstraints
                    height = wrapContent
                    centerVertically()
                    after(checkBox, dp(8))
                    endOfParent(dp(16))
                },
            )
        }
}
