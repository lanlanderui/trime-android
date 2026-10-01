// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.DynamicColorScheme
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.ui.common.materialAlertDialogBuilder
import kotlinx.coroutines.launch

object ColorPickerDialog {
    fun build(
        scope: LifecycleCoroutineScope,
        context: Context,
        afterConfirm: (suspend () -> Unit)? = null,
    ): AlertDialog {
        val presetSchemes = ColorManager.availableColorSchemes
        val currentScheme = ColorManager.activeColorScheme
        val currentIndex = presetSchemes.indexOfFirst { it.id == currentScheme.id }
        return context.materialAlertDialogBuilder()
            .apply {
                setTitle(R.string.normal_mode_color)
                if (presetSchemes.isEmpty()) {
                    setMessage(R.string.no_color_to_select)
                } else {
                    setSingleChoiceItems(
                        presetSchemes.map { it.displayName(context) }.toTypedArray(),
                        currentIndex,
                    ) { dialog, which ->
                        scope.launch {
                            afterConfirm?.invoke()
                            if (which != currentIndex) {
                                val newScheme = presetSchemes[which]
                                ColorManager.setColorScheme(newScheme)
                            }
                            dialog.dismiss()
                        }
                    }
                }
                setNegativeButton(android.R.string.cancel, null)
            }.create()
    }

    /**
     * The label to show for a scheme.
     *
     * The dynamic scheme is not a theme preset and has no `name` of its own, so it gets a
     * translated one. Presets fall back to their id when a theme leaves `name` out, instead of
     * rendering an empty row.
     */
    private fun ColorScheme.displayName(context: Context): String =
        if (id == DynamicColorScheme.ID) {
            context.getString(R.string.dynamic_color_scheme)
        } else {
            colors["name"] ?: id
        }
}
