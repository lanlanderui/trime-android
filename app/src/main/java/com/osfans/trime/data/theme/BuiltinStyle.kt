/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.util.appContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The size overrides the built-in settings hold, keyed by the style key as the theme spells it.
 *
 * Stored as overrides rather than as a complete style: only the items the user actually touched
 * are in here, and everything else keeps following the theme. That is what makes a theme update
 * (or a theme switch) show through for every size the user did not deliberately pin.
 *
 * Held as strings-keyed floats so a file written by a newer build — with a size this build does
 * not know — still loads; see [unknownKeys].
 */
@Serializable
data class BuiltinStyle(
    val values: Map<String, Float> = emptyMap(),
) {
    /** Size names this build does not recognise, so a drop is reported instead of silent. */
    val unknownKeys: List<String>
        get() = values.keys.filter { StyleSemantic.from(it) == null }.sorted()

    fun isOverridden(semantic: StyleSemantic): Boolean = values.containsKey(semantic.key)

    /** The override for [semantic], or the value [theme] declares when there is none. */
    fun valueOf(
        semantic: StyleSemantic,
        theme: GeneralStyle,
    ): Float = values[semantic.key] ?: theme.valueOf(semantic)

    fun with(
        semantic: StyleSemantic,
        value: Float,
    ): BuiltinStyle = copy(values = values + (semantic.key to value))

    fun without(semantic: StyleSemantic): BuiltinStyle = copy(values = values - semantic.key)
}

/**
 * [theme] with [values] patched in, or [theme] itself when the built-in sizes are not in charge.
 *
 * The single place that decides which of the two size sources wins. Both call sites in
 * `ThemeManager` go through here — and so would any future caller — so the answer cannot drift
 * between them. Returning the very same instance (rather than a copy that happens to be equal)
 * is deliberate: it lets the caller's own equality check conclude "nothing changed" without a
 * flag of its own, and it makes the "the theme wins ⇒ stored sizes are skipped" rule something
 * a test can pin with `shouldBeSameInstanceAs`.
 */
internal fun patchedStyle(
    theme: Theme,
    values: Map<String, Float>,
    overridesInCharge: Boolean,
): Theme =
    if (overridesInCharge) {
        theme.copy(generalStyle = theme.generalStyle.withStyleValues(values))
    } else {
        theme
    }

/**
 * The source an install that predates [ThemePrefs.StyleSource] should start on.
 *
 * Pure on purpose: the migration is a one-time write that a user cannot easily undo by hand, so
 * the rule lives here where a test can pin it, out of the reading code that uses it. See
 * `ThemePrefs.migrateStyleSource` for when it runs.
 */
internal fun migratedStyleSource(hasStoredSizes: Boolean): ThemePrefs.StyleSource =
    if (hasStoredSizes) ThemePrefs.StyleSource.BUILTIN else ThemePrefs.StyleSource.THEME

/**
 * File-backed storage for the size overrides, written atomically like the colour palette.
 *
 * A file that cannot be read falls back to no overrides at all rather than failing: losing the
 * sizes must leave a keyboard that looks like the theme's own, not one that cannot start.
 */
object BuiltinStyleStore {
    private const val FILE_NAME = "builtin_style.json"

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    private val file: File get() = File(appContext.filesDir, FILE_NAME)

    @Volatile
    private var cached: BuiltinStyle? = null

    fun current(): BuiltinStyle = cached ?: load().also { cached = it }

    fun load(): BuiltinStyle {
        val stored =
            file
                .takeIf { it.exists() }
                ?.readText()
                ?.let { runCatching { json.decodeFromString<BuiltinStyle>(it) }.getOrNull() }
        return stored ?: BuiltinStyle()
    }

    fun save(style: BuiltinStyle) {
        val serialized = json.encodeToString(style)
        runCatching {
            val tmp = File(appContext.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(serialized)
            if (!tmp.renameTo(file)) {
                file.writeText(serialized)
                tmp.delete()
            }
        }
        cached = style
    }

    /**
     * [theme] with the stored size overrides patched in — but only while [overridesInCharge] says
     * the built-in settings are the ones in charge.
     *
     * Patching the decoded theme rather than each reader is what lets the twenty-odd places that
     * read `generalStyle` keep doing exactly what they do — they see a style that already has the
     * user's sizes in it. When the source is the theme's own, the patch is skipped whole: the
     * stored sizes stay on disk, frozen and switchable back, and the theme speaks alone. The
     * decision itself lives in [patchedStyle], which is pure and tested.
     */
    fun applyTo(
        theme: Theme,
        overridesInCharge: Boolean,
    ): Theme = patchedStyle(theme, current().values, overridesInCharge)
}
