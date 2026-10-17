/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.theme.BuiltinAppearance
import com.osfans.trime.data.theme.BuiltinAppearanceIO
import com.osfans.trime.data.theme.BuiltinColorScheme
import com.osfans.trime.data.theme.BuiltinColorStore
import com.osfans.trime.data.theme.BuiltinLayout
import com.osfans.trime.data.theme.BuiltinLayoutStore
import com.osfans.trime.data.theme.BuiltinStyle
import com.osfans.trime.data.theme.BuiltinStyleStore
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePrefs
import com.osfans.trime.data.theme.summary
import com.osfans.trime.ui.common.materialAlertDialogBuilder
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the built-in appearance screen does with a file, or on a reset.
 *
 * Separated from the screen because none of it is rendering: each of these reads the stored
 * settings, changes them, and hands the screen back something to draw. Keeping them together also
 * keeps the honest reporting in one place — an export that did not happen, a file that could not be
 * read, a reset that left the sizes alone are all things the user has to be told, and each of them is
 * a few lines away from the store it belongs to.
 *
 * The screen owns the palette and the sizes being edited; this works on copies and writes them back
 * through the callbacks, so there is one copy of each and no second source of truth.
 */
internal class BuiltinAppearanceActions(
    private val host: AppCompatActivity,
    private val currentScheme: () -> BuiltinColorScheme,
    private val currentStyle: () -> BuiltinStyle,
    private val currentLayout: () -> BuiltinLayout,
    private val applyScheme: (BuiltinColorScheme) -> Unit,
    private val applyStyle: (BuiltinStyle) -> Unit,
    private val applyLayout: (BuiltinLayout) -> Unit,
    /** Re-reads the stores and draws the screen again. */
    private val rebuild: () -> Unit,
) {
    /**
     * Export goes through the document picker rather than a fixed path, so the file ends up wherever
     * the user keeps things and no storage permission is needed.
     */
    private val exportLauncher =
        host.registerForActivityResult(ActivityResultContracts.CreateDocument(BuiltinAppearanceIO.MIME)) { uri ->
            if (uri == null) return@registerForActivityResult
            host.lifecycleScope.launch {
                val written =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            host.contentResolver.openOutputStream(uri)?.use { stream ->
                                stream.write(
                                    BuiltinAppearanceIO.export(ThemeManager.activeTheme.name).toByteArray(),
                                )
                            } ?: error("the picked document could not be opened")
                        }
                    }
                host.toast(if (written.isSuccess) R.string.builtin_export_done else R.string.builtin_export_failed)
            }
        }

    /**
     * Import accepts any file type on purpose: a palette that arrived through a chat app is often
     * labelled `application/octet-stream`, and what matters is the content, which [parse] checks.
     */
    private val importLauncher =
        host.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            host.lifecycleScope.launch {
                val raw =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            host.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }
                    }.getOrNull()
                when (val parsed = raw?.let(BuiltinAppearanceIO::parse)) {
                    null -> host.toast(R.string.builtin_import_failed_malformed)

                    is BuiltinAppearanceIO.Parsed.Refused -> host.toast(parsed.reason.messageRes())

                    is BuiltinAppearanceIO.Parsed.Ok -> {
                        BuiltinAppearanceIO.apply(parsed.appearance)
                        // The screen is showing the values from before the import.
                        applyScheme(BuiltinColorStore.current())
                        applyStyle(BuiltinStyleStore.current())
                        rebuild()
                        toastImported(parsed.appearance)
                    }
                }
            }
        }

    fun export() = exportLauncher.launch(BuiltinAppearanceIO.SUGGESTED_FILE_NAME)

    fun import() = importLauncher.launch("*/*")

    /**
     * Asks where the reset should land, rather than guessing.
     *
     * "Reset" is three different wishes depending on what went wrong: back to the palette this build
     * ships, back to the theme's own scheme, or back to the theme entirely. Picking one for the user
     * would either throw away settings they meant to keep or leave half a mess behind.
     */
    fun reset() {
        val choices =
            arrayOf(
                host.getString(R.string.builtin_reset_default),
                host.getString(R.string.builtin_reset_follow_scheme),
                host.getString(R.string.builtin_reset_follow_theme),
            )
        host.materialAlertDialogBuilder()
            .setTitle(R.string.builtin_reset)
            .setItems(choices) { _, which ->
                val choice = ResetChoice.entries[which]
                val scheme = currentScheme().afterReset(choice)
                val style = currentStyle().afterReset(choice)
                // `replaceCurrent` refreshes the colours itself.
                BuiltinColorStore.replaceCurrent(scheme)
                applyScheme(scheme)
                // The sizes and the layout are only written when this reset touched them; the
                // other two answers leave them exactly as they were.
                if (choice.touchesBuiltinOverrides()) {
                    BuiltinStyleStore.save(style)
                    applyStyle(style)
                    val layout = currentLayout().afterReset(choice)
                    BuiltinLayoutStore.save(layout)
                    applyLayout(layout)
                    ThemeManager.refreshBuiltinOverrides()
                }
                rebuild()
                host.toast(R.string.builtin_reset_done)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Says what came in, and anything about it worth knowing before it is looked at.
     *
     * Two kinds of note, and neither is an error: names this build does not know (kept, but they
     * do nothing), and a file written against another theme — the colours are the user's, not the
     * theme's, so a palette made elsewhere usually looks fine, just tuned for different key sizes.
     */
    private fun toastImported(appearance: BuiltinAppearance) {
        val summary = appearance.summary()
        val message =
            buildString {
                append(
                    host.getString(
                        R.string.builtin_import_done,
                        summary.palettes,
                        summary.colors,
                        summary.sizes,
                        summary.layout,
                    ),
                )
                if (appearance.themeMismatch(ThemeManager.prefs.selectedTheme.getValue())) {
                    append('\n')
                    append(
                        host.getString(
                            R.string.builtin_import_theme_mismatch,
                            appearance.theme.ifBlank { appearance.themeId },
                            ThemeManager.activeTheme.name,
                        ),
                    )
                }
                if (summary.unknown > 0) {
                    append('\n')
                    append(host.getString(R.string.builtin_import_unknown, summary.unknown))
                }
                // The imported knobs land in their stores either way; whether they *show* depends
                // on the matching source, and a silently paused import is exactly the kind of
                // quiet no-op this feature set exists to answer.
                if (summary.sizes > 0 &&
                    ThemeManager.prefs.styleSource.getValue() == ThemePrefs.StyleSource.THEME
                ) {
                    append('\n')
                    append(host.getString(R.string.builtin_import_sizes_paused))
                }
                if (summary.layout > 0 &&
                    ThemeManager.prefs.layoutSource.getValue() == ThemePrefs.LayoutSource.THEME
                ) {
                    append('\n')
                    append(host.getString(R.string.builtin_import_layout_paused))
                }
            }
        host.toast(message, Toast.LENGTH_LONG)
    }
}

/** Why a file was refused, as something the user can act on. */
private fun BuiltinAppearanceIO.Parsed.Reason.messageRes(): Int =
    when (this) {
        BuiltinAppearanceIO.Parsed.Reason.MALFORMED -> R.string.builtin_import_failed_malformed
        BuiltinAppearanceIO.Parsed.Reason.NEWER_FORMAT -> R.string.builtin_import_failed_newer
        BuiltinAppearanceIO.Parsed.Reason.EMPTY -> R.string.builtin_import_failed_empty
    }