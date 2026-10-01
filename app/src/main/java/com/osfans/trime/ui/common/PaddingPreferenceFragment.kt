/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.updatePadding
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.osfans.trime.R
import com.osfans.trime.util.applyNavBarInsetsBottomPadding
import splitties.dimensions.dp

/**
 * A fragment template that apply navigation bar window insets bottom padding
 *
 * Taken from fcitx5-android project.
 * Source: https://github.com/fcitx5-android/fcitx5-android/blob/dedfc18/app/src/main/java/org/fcitx/fcitx5/android/ui/common/PaddingPreferenceFragment.kt
 */
abstract class PaddingPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreateAdapter(
        preferenceScreen: PreferenceScreen,
    ): RecyclerView.Adapter<*> {
        applyMaterial3Layouts(preferenceScreen)
        return super.onCreateAdapter(preferenceScreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = super.onCreateView(inflater, container, savedInstanceState).apply {
        listView.apply {
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurface,
                ),
            )
            clipToPadding = false
            updatePadding(
                left = context.dp(8),
                top = context.dp(8),
                right = context.dp(8),
                bottom = context.dp(24),
            )
            applyNavBarInsetsBottomPadding()
        }
    }

    private fun applyMaterial3Layouts(group: PreferenceGroup) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            preference.layoutResource =
                when {
                    preference is PreferenceCategory && preference.title.isNullOrBlank() ->
                        R.layout.preference_category_spacer_material3
                    preference is PreferenceCategory -> R.layout.preference_category_material3
                    else -> R.layout.preference_material3
                }
            if (preference is PreferenceGroup) {
                applyMaterial3Layouts(preference)
            }
        }
    }
}
