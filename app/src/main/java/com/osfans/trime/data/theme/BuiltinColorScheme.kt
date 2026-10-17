/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.util.appContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A user-editable built-in colour scheme.
 *
 * It is stored as a *semantic* palette — [ColorSemantic] name to colour — plus
 * [overrides], which is keyed by engine colour key instead. Both maps are held
 * as strings so that a file written by a future build (with a semantic this
 * build does not know) still loads. See [unknownSemantics].
 *
 * [light] and [dark] are separate palettes rather than one palette plus a
 * transform: the day and night states want opposite tones (a light surface with
 * dark text, and the reverse), and no arithmetic on one produces the other.
 *
 * [overrides] is deliberately *not* split by day/night — it is the escape hatch
 * for a handful of keys, and duplicating it would make the advanced screen a
 * two-mode place to get lost in.
 */
@Serializable
data class BuiltinColorScheme(
    val id: String = "",
    val name: String = "",
    val light: Map<String, String> = emptyMap(),
    val dark: Map<String, String> = emptyMap(),
    val overrides: Map<String, String> = emptyMap(),
) {
    /** The palette for the given day/night state, keyed by [ColorSemantic]. */
    fun values(isNight: Boolean): Map<ColorSemantic, String> =
        raw(isNight).mapNotNull { (key, value) ->
            ColorSemantic.from(key)?.let { it to value }
        }.toMap()

    /** The palette as stored, keyed by semantic name. */
    fun raw(isNight: Boolean): Map<String, String> = if (isNight) dark else light

    /**
     * Semantic names this file carries that this build does not recognise, so a
     * palette written by a newer build (or hand-edited with a typo) reports the
     * drop instead of losing the colour in silence.
     */
    val unknownSemantics: List<String>
        get() = (light.keys + dark.keys).filter { ColorSemantic.from(it) == null }.sorted()

    /** The semantic palette with [values] applied, leaving the other items alone. */
    fun withValues(
        isNight: Boolean,
        values: Map<ColorSemantic, String>,
    ): BuiltinColorScheme {
        val merged = raw(isNight).toMutableMap()
        values.forEach { (semantic, value) -> merged[semantic.key] = value }
        return if (isNight) copy(dark = merged) else copy(light = merged)
    }

    /** The semantic palette with the named items removed, so they fall through to the scheme. */
    fun withoutValues(
        isNight: Boolean,
        semantics: Collection<ColorSemantic>,
    ): BuiltinColorScheme {
        val merged = raw(isNight).toMutableMap()
        semantics.forEach { merged.remove(it.key) }
        return if (isNight) copy(dark = merged) else copy(light = merged)
    }

    /**
     * This palette with nothing set at all, so every colour follows the active scheme.
     *
     * The per-key overrides go too: they are colours the user chose, and "follow the theme" that
     * left half a palette pinned would not be what it says on the label. The day/night palettes
     * both go, because resetting one of the two is not a state anybody means to be in.
     */
    fun cleared(): BuiltinColorScheme = copy(light = emptyMap(), dark = emptyMap(), overrides = emptyMap())
}

/**
 * Layers the built-in palette over the scheme it sits on.
 *
 * Precedence, lowest first: the base scheme (which already carries the theme's
 * own `fallback_colors` aliases and, further down, librime's built-in chains),
 * then the semantic palette, then [overrides].
 *
 * The base is kept rather than replaced — that is the whole reason a built-in
 * scheme is usable on a theme it knows nothing about: a theme is free to name
 * colour keys of its own and bind them per key (`bgn`, `bbs`, `benter`, `bkg`
 * and the `*_custom_key_*` family in this theme), and those names only have a
 * value while the base still provides one. It is the same layering rule
 * [DynamicColorScheme.create] follows, for the same reason.
 *
 * A semantic item that the palette does not carry contributes nothing, so the
 * key falls through to the base exactly as it would with the built-in scheme
 * switched off.
 *
 * Pure, so the precedence is unit-testable without a theme or a device.
 */
internal fun composeWithPalette(
    scheme: ColorScheme,
    palette: BuiltinColorScheme,
    isNight: Boolean,
    colorsInCharge: Boolean,
): ColorScheme {
    if (!colorsInCharge) return scheme
    return ColorScheme(
        id = scheme.id,
        colors = composeBuiltinColors(scheme, palette.values(isNight), palette.overrides),
    )
}

internal fun composeBuiltinColors(
    base: ColorScheme?,
    values: Map<ColorSemantic, String>,
    overrides: Map<String, String>,
): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    base?.colors?.let(result::putAll)
    // Iterate the enum rather than the map: should two items ever claim the same
    // key, the outcome is then a property of the declaration order instead of
    // the order the palette happened to be decoded in.
    for (semantic in ColorSemantic.entries) {
        val value = values[semantic] ?: continue
        if (value.isEmpty()) continue
        semantic.keys.forEach { result[it] = value }
    }
    result.putAll(overrides.filterValues { it.isNotEmpty() })
    return result
}

/**
 * The palette a fresh install starts from.
 *
 * Picked to read as one piece in both day/night states rather than to copy any
 * particular theme: a near-white/never-black surface ladder, one accent that
 * carries every "chosen or active" role, and function keys on a tint of that
 * accent so they group apart from the letter keys.
 */
object BuiltinColorDefaults {
    const val DEFAULT_ID = "builtin-default"

    private val LIGHT =
        mapOf(
            ColorSemantic.KEYBOARD_SURFACE to "#EEF2F1",
            ColorSemantic.KEY_SURFACE to "#FBFDFC",
            ColorSemantic.CANDIDATE_SURFACE to "#EEF2F1",
            ColorSemantic.PREEDIT_SURFACE to "#E3E8E6",
            ColorSemantic.PREEDIT_SELECTION_SURFACE to "#0F6E56",
            ColorSemantic.POPUP_SURFACE to "#FFFFFF",
            ColorSemantic.KEY_LABEL to "#1B1C1C",
            ColorSemantic.KEY_SYMBOL to "#44474A",
            ColorSemantic.CANDIDATE_LABEL to "#1B1C1C",
            ColorSemantic.CANDIDATE_COMMENT to "#44474A",
            ColorSemantic.PREEDIT_TEXT to "#0F6E56",
            ColorSemantic.PREEDIT_SELECTION_TEXT to "#FFFFFF",
            ColorSemantic.POPUP_LABEL to "#1B1C1C",
            ColorSemantic.FUNCTION_SURFACE to "#C7E0D4",
            ColorSemantic.FUNCTION_LABEL to "#10352A",
            ColorSemantic.ACCENT to "#0F6E56",
            ColorSemantic.ON_ACCENT to "#FFFFFF",
            ColorSemantic.KEY_PRESSED_SURFACE to "#0F6E56",
            ColorSemantic.FUNCTION_PRESSED_SURFACE to "#A9CFBE",
            ColorSemantic.ON_PRESSED_SURFACE to "#0B5344",
            ColorSemantic.OUTLINE to "#9DB2AB",
            ColorSemantic.TEXT_SHADOW to "#1A000000",
        )

    private val DARK =
        mapOf(
            ColorSemantic.KEYBOARD_SURFACE to "#121414",
            ColorSemantic.KEY_SURFACE to "#232626",
            ColorSemantic.CANDIDATE_SURFACE to "#121414",
            ColorSemantic.PREEDIT_SURFACE to "#0C0E0E",
            ColorSemantic.PREEDIT_SELECTION_SURFACE to "#6FD8B4",
            ColorSemantic.POPUP_SURFACE to "#232626",
            ColorSemantic.KEY_LABEL to "#E3E4E4",
            ColorSemantic.KEY_SYMBOL to "#BFC4C2",
            ColorSemantic.CANDIDATE_LABEL to "#E3E4E4",
            ColorSemantic.CANDIDATE_COMMENT to "#BFC4C2",
            ColorSemantic.PREEDIT_TEXT to "#6FD8B4",
            ColorSemantic.PREEDIT_SELECTION_TEXT to "#003829",
            ColorSemantic.POPUP_LABEL to "#E3E4E4",
            ColorSemantic.FUNCTION_SURFACE to "#1F3A32",
            ColorSemantic.FUNCTION_LABEL to "#C8EADB",
            ColorSemantic.ACCENT to "#6FD8B4",
            ColorSemantic.ON_ACCENT to "#003829",
            ColorSemantic.KEY_PRESSED_SURFACE to "#6FD8B4",
            ColorSemantic.FUNCTION_PRESSED_SURFACE to "#2C4F45",
            ColorSemantic.ON_PRESSED_SURFACE to "#8CE6C6",
            ColorSemantic.OUTLINE to "#4A5551",
            ColorSemantic.TEXT_SHADOW to "#33000000",
        )

    val DEFAULT =
        BuiltinColorScheme(
            id = DEFAULT_ID,
            name = "Built-in default",
            light = LIGHT.mapKeys { it.key.key }.mapValues { it.value },
            dark = DARK.mapKeys { it.key.key }.mapValues { it.value },
        )
}

/**
 * File-backed storage for the user's palettes.
 *
 * Written atomically, like [com.osfans.trime.data.sync.SyncIndex], so an interrupted write cannot
 * leave a half-parsed library behind. A file that cannot be read or parsed falls back to the
 * shipped palette rather than failing: losing the palettes must not take the keyboard down with it.
 */
object BuiltinColorStore {
    /** The library this build writes. */
    private const val FILE_NAME = "builtin_palettes.json"

    /**
     * What the previous build wrote: one palette, and no library around it.
     *
     * A separate file rather than a shape to sniff for, so there is no guessing about which of the
     * two an older file is — it is read as exactly what it was, and adopted as the library's first
     * palette, which is what keeps a user who had already picked their colours from losing them.
     */
    private const val LEGACY_FILE_NAME = "builtin_colors.json"

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    private val file: File get() = File(appContext.filesDir, FILE_NAME)

    @Volatile
    private var cached: BuiltinColorLibrary? = null

    @Synchronized
    fun library(): BuiltinColorLibrary = cached ?: load().also { cached = it }

    /** The palette in use, which is what every reader of the colours wants. */
    @Synchronized
    fun current(): BuiltinColorScheme = library().current()

    @Synchronized
    fun load(): BuiltinColorLibrary {
        if (file.exists()) {
            return runCatching { json.decodeFromString<BuiltinColorLibrary>(file.readText()) }.getOrNull()
                ?: defaultColorLibrary()
        }
        val legacy = File(appContext.filesDir, LEGACY_FILE_NAME)
        if (legacy.exists()) {
            val palette = runCatching { json.decodeFromString<BuiltinColorScheme>(legacy.readText()) }.getOrNull()
            if (palette != null) return BuiltinColorLibrary(palettes = listOf(palette), selectedId = palette.id)
        }
        return defaultColorLibrary()
    }

    @Synchronized
    fun select(id: String) = save(library().select(id))

    /** Stores [palette] under the id it carries, adding it when the library has not seen it. */
    @Synchronized
    fun savePalette(palette: BuiltinColorScheme) = save(library().with(palette))

    @Synchronized
    fun removePalette(id: String) = save(library().without(id))

    /**
     * Replaces the palette in use wholesale and stores it.
     *
     * The two callers that replace a palette rather than edit one — an import, and a reset — go
     * through one place, so neither can leave a stored palette differing from the one on screen.
     */
    @Synchronized
    fun replaceCurrent(palette: BuiltinColorScheme) {
        save(library().with(palette))
        ColorManager.refreshBuiltinColors()
    }

    /**
     * Stores [library] as it stands, for an import that replaces everything.
     *
     * A file that carries no palette at all leaves the current ones alone rather than emptying the
     * library: the export always writes the palettes, so a file without them is one somebody made
     * by hand, and taking that as "you have no palettes" would delete work over a misunderstanding.
     */
    @Synchronized
    fun replaceLibrary(library: BuiltinColorLibrary) {
        save(library.takeIf { it.palettes.isNotEmpty() } ?: this.library())
        ColorManager.refreshBuiltinColors()
    }

    @Synchronized
    private fun save(library: BuiltinColorLibrary) {
        val serialized = json.encodeToString(library)
        runCatching {
            val tmp = File(appContext.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(serialized)
            if (!tmp.renameTo(file)) {
                // renameTo refuses to replace an existing file on some systems.
                file.writeText(serialized)
                tmp.delete()
            }
        }
        cached = library
    }
}
