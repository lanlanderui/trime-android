/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.userdict

import android.content.Context
import android.content.res.ColorStateList
import android.view.ViewGroup
import androidx.core.widget.TextViewCompat
import com.osfans.trime.R
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.styledColor
import splitties.resources.styledDrawable
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageButton
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

class UserDictListEntryUi(
    override val ctx: Context,
) : Ui {
    val nameText = textView {
        setPaddingDp(0, 16, 0, 16)
        TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        setTextColor(styledColor(com.google.android.material.R.attr.colorOnSurface))
    }

    val moreButton = imageButton {
        background = styledDrawable(android.R.attr.selectableItemBackground)
        imageDrawable = drawable(R.drawable.ic_baseline_more_horiz_24)
        imageTintList = ColorStateList.valueOf(styledColor(android.R.attr.colorControlNormal))
    }

    override val root = constraintLayout {
        layoutParams =
            ViewGroup.MarginLayoutParams(matchParent, wrapContent).apply {
                setMargins(0, dp(3), 0, dp(3))
            }
        background = drawable(R.drawable.bg_preference_item_material3)
        minHeight = dp(72)

        val paddingStart = dp(16)
        add(
            nameText,
            lParams {
                width = matchConstraints
                height = wrapContent
                centerVertically()
                startOfParent(paddingStart)
                before(moreButton)
            },
        )
        add(
            moreButton,
            lParams {
                width = dp(53)
                height = matchConstraints
                centerVertically()
                endOfParent()
            },
        )
    }
}
