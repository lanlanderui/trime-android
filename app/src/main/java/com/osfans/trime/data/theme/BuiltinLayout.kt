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
 * The layout overrides the built-in settings hold, keyed by the style key as the theme spells it.
 *
 * Same shape and the same rules as [BuiltinStyle], for the domain next to it: only the items the
 * user actually moved are in here, everything else keeps following the theme, and while the
 * layout source is the theme's own the whole map is skipped — frozen, not deleted.
 *
 * A file of its own rather than a second section of the size file: the two answer different
 * questions ("how big is the text" vs "how big is the keyboard"), have their own sources, and
 * therefore their own stores — one gate and one migration story each, not a shared map with two
 * owners.
 */
@Serializable
data class BuiltinLayout(
    val values: Map<String, Float> = emptyMap(),
) {
    /** Layout names this build does not recognise, so a drop is reported instead of silent. */
    val unknownKeys: List<String>
        get() = values.keys.filter { LayoutSemantic.from(it) == null }.sorted()

    fun isOverridden(semantic: LayoutSemantic): Boolean = values.containsKey(semantic.key)

    /** The override for [semantic], or the value [theme] declares when there is none. */
    fun valueOf(
        semantic: LayoutSemantic,
        theme: GeneralStyle,
    ): Float = values[semantic.key] ?: theme.valueOf(semantic)

    fun with(
        semantic: LayoutSemantic,
        value: Float,
    ): BuiltinLayout = copy(values = values + (semantic.key to value))

    fun without(semantic: LayoutSemantic): BuiltinLayout = copy(values = values - semantic.key)
}

/**
 * [theme] with [values] patched in, or [theme] itself when the built-in layout is not in charge.
 *
 * The layout twin of [patchedStyle], with the same contract: returning the very same instance
 * lets the caller's equality check conclude "nothing changed" without a flag of its own.
 */
internal fun patchedLayout(
    theme: Theme,
    values: Map<String, Float>,
    overridesInCharge: Boolean,
): Theme =
    if (overridesInCharge) {
        theme.copy(generalStyle = theme.generalStyle.withLayoutValues(values))
    } else {
        theme
    }

/**
 * File-backed storage for the layout overrides, written atomically like the other two stores.
 *
 * A file that cannot be read falls back to no overrides at all rather than failing: losing the
 * layout must leave a keyboard that looks like the theme's own, not one that cannot start.
 */
object BuiltinLayoutStore {
    private const val FILE_NAME = "builtin_layout.json"

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    private val file: File get() = File(appContext.filesDir, FILE_NAME)

    @Volatile
    private var cached: BuiltinLayout? = null

    fun current(): BuiltinLayout = cached ?: load().also { cached = it }

    fun load(): BuiltinLayout {
        val stored =
            file
                .takeIf { it.exists() }
                ?.readText()
                ?.let { runCatching { json.decodeFromString<BuiltinLayout>(it) }.getOrNull() }
        return stored ?: BuiltinLayout()
    }

    fun save(layout: BuiltinLayout) {
        val serialized = json.encodeToString(layout)
        runCatching {
            val tmp = File(appContext.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(serialized)
            if (!tmp.renameTo(file)) {
                file.writeText(serialized)
                tmp.delete()
            }
        }
        cached = layout
    }

    /**
     * [theme] with the stored layout overrides patched in — but only while [overridesInCharge]
     * says the built-in settings are the ones in charge. See [patchedLayout] for the rule.
     */
    fun applyTo(
        theme: Theme,
        overridesInCharge: Boolean,
    ): Theme = patchedLayout(theme, current().values, overridesInCharge)
}
