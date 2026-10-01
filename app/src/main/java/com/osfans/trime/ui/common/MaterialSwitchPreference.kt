/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.Context
import android.util.AttributeSet
import androidx.preference.PreferenceViewHolder
import androidx.preference.TwoStatePreference
import com.google.android.material.materialswitch.MaterialSwitch
import com.osfans.trime.R

class MaterialSwitchPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.switchPreferenceCompatStyle,
) : TwoStatePreference(context, attrs, defStyleAttr) {
    init {
        widgetLayoutResource = R.layout.preference_widget_material_switch
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.checkbox) as? MaterialSwitch)?.apply {
            isChecked = this@MaterialSwitchPreference.isChecked
            isEnabled = this@MaterialSwitchPreference.isEnabled
        }
    }
}
