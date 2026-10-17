/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The built-in settings as one file: the palettes, which one is in use, and the size and layout
 * overrides.
 *
 * One file rather than several because they are edited on one screen and travel together — a user
 * who exports their look and imports it on another device means all of it, and a half-imported look
 * is worse than none.
 *
 * [format] is the escape hatch for the day the shape changes: a file this build cannot be sure it
 * understands is refused with a reason rather than decoded hopefully. Format 1 carried a single
 * palette and is still read — see [library] — because a file exported last week has to keep
 * working; format 3 added [layout], and a format 2 file simply arrives with no layout in it.
 */
@Serializable
data class BuiltinAppearance(
    val format: Int = FORMAT,
    /** The app build that wrote the file, for a human reading it. */
    val app: String = "",
    /**
     * The theme the settings were taken from.
     *
     * Recorded rather than matched: a palette written against one theme can be perfectly usable
     * against another — the colours are the user's, not the theme's — so this is something to say
     * when it differs, not a reason to refuse. [themeId] is the stable half of it: theme names are
     * display strings and two themes can share one, while the config id cannot.
     */
    val theme: String = "",
    val themeId: String = "",
    /**
     * The palettes, from format 2 on.
     *
     * Empty in a format 1 file, which carries its one palette in [colors] instead — which is also
     * how the two are told apart, since a missing key decodes to empty rather than failing.
     */
    val palettes: List<BuiltinColorScheme> = emptyList(),
    val selectedId: String = "",
    /** The single palette of a format 1 file. Empty from format 2 on. */
    val colors: BuiltinColorScheme = BuiltinColorScheme(),
    val style: BuiltinStyle = BuiltinStyle(),
    /** The layout overrides, from format 3 on. Empty in every older file. */
    val layout: BuiltinLayout = BuiltinLayout(),
) {
    /** The library this file describes, whichever format it is written in. */
    fun library(): BuiltinColorLibrary =
        when {
            palettes.isNotEmpty() -> BuiltinColorLibrary(palettes, selectedId)
            colors.id.isNotEmpty() || colors.name.isNotEmpty() -> BuiltinColorLibrary(listOf(colors), colors.id)
            else -> BuiltinColorLibrary()
        }

    /**
     * Whether this file was written for a theme other than [currentThemeId].
     *
     * A note rather than a refusal: the colours are the user's, not the theme's, so a palette made
     * against another theme usually works — it is just tuned for different key sizes. A file that
     * names no theme at all cannot be blamed on one, because it was written by hand or by a build
     * that did not record it; that is unlabelled, not wrong.
     */
    fun themeMismatch(currentThemeId: String): Boolean = themeId.isNotBlank() && themeId != currentThemeId

    companion object {
        const val FORMAT = 3
    }
}

/** What an imported file actually carried, for a message the user can check against the file. */
internal data class AppearanceSummary(
    val palettes: Int,
    val colors: Int,
    val sizes: Int,
    val layout: Int,
    /**
     * Entries whose names this build does not recognise, so they will do nothing.
     *
     * Only the palette, the sizes and the layout are counted. The per-key overrides are
     * deliberately *not*: a theme binds colour keys of its own — `bgn`, `bbs`, and a
     * `*_custom_key_*` per physical key — and reaching those is the whole point of that map, so a
     * name outside the built-in list is the normal case rather than something to warn about.
     */
    val unknown: Int,
)

/** Counts of what a file carries, over every palette it holds. */
internal fun BuiltinAppearance.summary(): AppearanceSummary {
    val library = library()
    return AppearanceSummary(
        palettes = library.palettes.size,
        colors = library.palettes.sumOf { it.light.size + it.dark.size + it.overrides.size },
        sizes = style.values.size,
        layout = layout.values.size,
        unknown =
            library.palettes.sumOf { it.unknownSemantics.size } +
                style.unknownKeys.size +
                layout.unknownKeys.size,
    )
}

/**
 * Reading and writing the file, and putting it into effect.
 *
 * Separate from the stores because it spans them: each store owns one file of its own, while this
 * is about moving all of it through a user-chosen document.
 */
internal object BuiltinAppearanceIO {
    /** What to offer the document picker as a name; the user is free to rename it. */
    const val SUGGESTED_FILE_NAME = "trime-appearance.json"

    const val MIME = "application/json"

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    /** Every palette, the selected one, the sizes and the layout, as the text of a file. */
    fun export(themeName: String): String {
        val library = BuiltinColorStore.library()
        return json.encodeToString(
            BuiltinAppearance(
                app = BuildConfig.BUILD_VERSION_NAME,
                theme = themeName,
                themeId = ThemeManager.prefs.selectedTheme.getValue(),
                palettes = library.palettes,
                selectedId = library.selectedId,
                style = BuiltinStyleStore.current(),
                layout = BuiltinLayoutStore.current(),
            ),
        )
    }

    sealed interface Parsed {
        data class Ok(val appearance: BuiltinAppearance) : Parsed

        data class Refused(val reason: Reason) : Parsed

        enum class Reason {
            /** Not this format at all — a text file, a truncated download, a different app's export. */
            MALFORMED,

            /** Written by a newer build, whose format this one has no business guessing at. */
            NEWER_FORMAT,

            /** Parsed, but carries nothing: no palettes, no sizes and no layout. */
            EMPTY,
        }
    }

    /**
     * Reads [raw], or says why it will not.
     *
     * The checks are the ones the rest of the import cannot recover from. Names this build does not
     * know are *not* among them: the stores already keep them and the settings screen already
     * reports them, so refusing the whole file over one would lose the parts that do work.
     */
    fun parse(raw: String): Parsed {
        val appearance = runCatching { json.decodeFromString<BuiltinAppearance>(raw) }.getOrNull()
            ?: return Parsed.Refused(Parsed.Reason.MALFORMED)
        if (appearance.format > BuiltinAppearance.FORMAT) {
            return Parsed.Refused(Parsed.Reason.NEWER_FORMAT)
        }
        val nonFinite =
            appearance.style.values.values.any { !it.isFinite() } ||
                appearance.layout.values.values.any { !it.isFinite() }
        if (nonFinite) {
            // Belt and braces: JSON cannot carry a NaN past kotlinx.serialization's own check, so
            // this should be unreachable. It is kept because a NaN that did reach a style would be
            // measured into every key, and the habit of checking is cheaper than the deduction.
            return Parsed.Refused(Parsed.Reason.MALFORMED)
        }
        val summary = appearance.summary()
        if (summary.palettes == 0 && summary.sizes == 0 && summary.layout == 0) {
            return Parsed.Refused(Parsed.Reason.EMPTY)
        }
        return Parsed.Ok(appearance)
    }

    /**
     * Stores [appearance] and makes the keyboard pick it up.
     *
     * All layers are re-resolved here rather than by the caller: the palette is read on every
     * resolution but the sizes and the layout are only patched in when the theme is re-derived,
     * and forgetting either would leave an imported look half applied.
     *
     * The library is replaced wholesale. The file means "these are my looks", not "add this one" —
     * importing on top of what is already there would leave palettes behind that the user thought
     * they had exported away.
     */
    fun apply(appearance: BuiltinAppearance) {
        BuiltinColorStore.replaceLibrary(appearance.library())
        BuiltinStyleStore.save(appearance.style)
        BuiltinLayoutStore.save(appearance.layout)
        ColorManager.refreshBuiltinColors()
        ThemeManager.refreshBuiltinOverrides()
    }
}