/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R

/** Creates an MD3 dialog without assuming that the caller already has a Material-themed context. */
fun Context.materialAlertDialogBuilder(): MaterialAlertDialogBuilder {
    val value = TypedValue()
    val isMaterial3 =
        theme.resolveAttribute(
            com.google.android.material.R.attr.isMaterial3Theme,
            value,
            true,
        ) && value.data != 0
    val dialogContext =
        if (isMaterial3) {
            this
        } else {
            ContextThemeWrapper(this, R.style.Theme_TrimeAppTheme)
        }
    return MaterialAlertDialogBuilder(dialogContext)
}
