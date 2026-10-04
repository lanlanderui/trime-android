// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.graphics.Typeface
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
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
        val dialog =
            context.materialAlertDialogBuilder()
                .apply {
                    setTitle(R.string.normal_mode_color)
                    if (presetSchemes.isEmpty()) {
                        setMessage(R.string.no_color_to_select)
                    } else {
                        setSingleChoiceItems(
                            presetSchemes
                                .mapIndexed { index, scheme ->
                                    val name = scheme.displayName(context)
                                    if (index == currentIndex) activeRowLabel(name) else name
                                }.toTypedArray(),
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
        if (currentIndex >= 0) {
            // The list outgrows the dialog once a theme carries many presets, and the applied
            // scheme can sit well below the fold: land on it instead of making the user hunt.
            dialog.setOnShowListener { dialog.listView?.setSelection(currentIndex) }
        }
        return dialog
    }

    /**
     * The row of the scheme that is currently applied.
     *
     * A single-choice row only carries a radio button, which is easy to miss and which some
     * themes do not draw at all, so the row says it in its own text as well. The tick is a
     * prefix on purpose: scheme names are long enough to wrap, and a suffix would end up on the
     * second line.
     */
    private fun activeRowLabel(name: String): CharSequence =
        buildSpannedString {
            inSpans(StyleSpan(Typeface.BOLD)) { append(colorSchemeRowLabel(name, active = true)) }
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

/**
 * Text of one color picker row: the applied scheme is ticked so it can be told apart from every
 * other row of the list. Kept free of Android types so the marking itself stays testable.
 */
internal fun colorSchemeRowLabel(name: String, active: Boolean): String =
    if (active) "✓ $name" else name
