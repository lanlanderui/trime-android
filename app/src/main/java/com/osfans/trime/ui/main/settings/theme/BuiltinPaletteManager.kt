/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.osfans.trime.R
import com.osfans.trime.data.theme.BuiltinColorScheme
import com.osfans.trime.data.theme.BuiltinColorStore
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ui.common.materialAlertDialogBuilder
import com.osfans.trime.ui.main.settings.colorSchemeRowLabel
import com.osfans.trime.util.toast
import splitties.dimensions.dp

/**
 * Choosing between the stored palettes, and the three things that can be done to one.
 *
 * Separated from the screen because none of it is rendering: each of these reads the library,
 * changes it, and hands the screen back the palette to edit. The row itself is built here too,
 * since it exists only to open [showChooser] and to name what is currently selected — both of which
 * are this class's state rather than the screen's.
 *
 * The screen owns the palette being edited; this works on the store and writes the result back
 * through [onPaletteChanged], so there is one copy of it and no second source of truth.
 */
internal class BuiltinPaletteManager(
    private val host: AppCompatActivity,
    private val mutedColor: Int,
    /** Called with the palette that is current after a change, so the screen can edit that one. */
    private val onPaletteChanged: (BuiltinColorScheme) -> Unit,
    /** Re-reads the stores and draws the screen again. */
    private val rebuild: () -> Unit,
) {
    /**
     * One row rather than a list, because switching is the occasional thing and editing is the
     * constant one: the palette a user has settled on is what opens, and the chooser is one tap away
     * for the times they want another.
     */
    fun addRow(container: LinearLayout) {
        val row =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
        row.addView(
            TextView(host).apply {
                setText(R.string.builtin_palette)
                setTextColor(mutedColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(host).apply {
                text = BuiltinColorStore.current().name
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                }
            },
        )
        row.addView(
            MaterialButton(host).apply {
                setText(R.string.builtin_palette_manage)
                setOnClickListener { showChooser() }
            },
        )
        container.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    /** The palettes, with the three things that can be done to one. */
    private fun showChooser() {
        val library = BuiltinColorStore.library()
        val labels =
            library.palettes
                .map { colorSchemeRowLabel(it.name, active = it.id == library.current().id) }
                .toTypedArray()
        val checked = library.palettes.indexOfFirst { it.id == library.selectedId }
        host.materialAlertDialogBuilder()
            .setTitle(R.string.builtin_palette)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                BuiltinColorStore.select(library.palettes[which].id)
                changed()
                dialog.dismiss()
            }
            .setPositiveButton(R.string.builtin_palette_new) { _, _ -> addPalette() }
            .setNeutralButton(R.string.builtin_palette_rename) { _, _ -> showRenameDialog() }
            .setNegativeButton(R.string.builtin_palette_delete) { _, _ -> confirmDeletePalette() }
            .show()
    }

    /**
     * Adds a copy of the palette in use, and switches to it.
     *
     * A copy rather than a blank palette: "another one of these, but quieter" is what a second
     * palette is usually for, and starting from the shipped default would throw away the one the
     * user was just looking at.
     */
    private fun addPalette() {
        val source = BuiltinColorStore.current()
        val taken = BuiltinColorStore.library().palettes.map { it.name }.toSet()
        var index = taken.size + 1
        while (host.getString(R.string.builtin_palette_new_name, index) in taken) index++
        BuiltinColorStore.savePalette(
            source.copy(
                // The id has to be unique and stable; the name is only ever shown.
                id = "palette-${System.currentTimeMillis()}",
                name = host.getString(R.string.builtin_palette_new_name, index),
            ),
        )
        changed()
        host.toast(host.getString(R.string.builtin_palette_created, source.name))
    }

    private fun showRenameDialog() {
        val current = BuiltinColorStore.current()
        val field =
            EditText(host).apply {
                setText(current.name)
                setSelection(current.name.length)
            }
        val container =
            FrameLayout(host).apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(field, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        host.materialAlertDialogBuilder()
            .setTitle(R.string.builtin_palette_rename)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = field.text.toString().trim()
                // A name is only ever shown, so an emptied one keeps what it was rather than
                // leaving a row in the chooser with nothing to read.
                BuiltinColorStore.savePalette(current.copy(name = name.ifEmpty { current.name }))
                changed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Deleting is the one action here that loses work, so it asks first.
     *
     * Deleting the last palette is allowed on purpose: the library then falls back to the shipped
     * one, which is a sensible place to land rather than an error to prevent.
     */
    private fun confirmDeletePalette() {
        val current = BuiltinColorStore.current()
        host.materialAlertDialogBuilder()
            .setTitle(R.string.builtin_palette_delete)
            .setMessage(host.getString(R.string.builtin_palette_delete_confirm, current.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                BuiltinColorStore.removePalette(current.id)
                changed()
                host.toast(R.string.builtin_palette_deleted)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The tail every one of these ends with.
     *
     * A rename does not reach the keyboard — the colours did not move — so this asks the colour
     * pipeline to re-resolve anyway rather than each caller remembering to: an unrefreshed scheme
     * here would leave the keyboard on the palette that was just deleted.
     */
    private fun changed() {
        onPaletteChanged(BuiltinColorStore.current())
        ColorManager.refreshBuiltinColors()
        rebuild()
    }
}