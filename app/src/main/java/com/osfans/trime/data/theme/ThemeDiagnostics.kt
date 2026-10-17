/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.charleskorn.kaml.YamlMap
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.util.ColorUtils
import com.osfans.trime.util.mapping
import com.osfans.trime.util.pairs
import com.osfans.trime.util.sequence
import com.osfans.trime.util.string
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * Static checks over a loaded theme. Findings never affect how a theme is
 * loaded: they tell a theme author which settings the runtime ignores, which
 * values it cannot resolve, and which references point at nothing.
 *
 * The checks reuse the validators of the runtime itself — the color tables
 * ([ColorTable]) and the preset checks ([KeyActionManager.presetDiagnostics]) —
 * so the linter cannot disagree with what the keyboard actually does. A finding
 * can therefore depend on the platform: a preset send is checked against the key
 * names the runtime resolves, and part of them only exist on a device.
 */
object ThemeDiagnostics {
    /** Theme format version this build understands (theme `config_version`). */
    const val SUPPORTED_CONFIG_VERSION = "3.0"

    /** Where a finding comes from; also its stable identity in tests. */
    enum class Code {
        UNKNOWN_TOP_LEVEL_KEY,
        UNKNOWN_STYLE_KEY,
        CONFIG_VERSION_MISSING,
        CONFIG_VERSION_INVALID,
        CONFIG_VERSION_UNSUPPORTED,
        NO_COLOR_SCHEME,
        MISSING_DEFAULT_SCHEME,
        MISSING_SCHEME_LINK,
        INVALID_COLOR_VALUE,
        BROKEN_FALLBACK_TARGET,
        UNRESOLVABLE_PRESET_SEND,
        MISSING_KEYBOARD_REFERENCE,
        CYCLIC_KEYBOARD_REFERENCE,
        FONT_NOT_A_LIST,
        IGNORED_TOOL_BAR_KEY,
        IGNORED_LIQUID_KEYBOARD_KEY,

        /** A built-in settings override that keeps the theme's own value off the keyboard. */
        SHADOWED_BY_BUILTIN,
    }

    enum class Severity {
        /** The theme works, but the runtime ignores something. */
        INFO,

        /** Likely an authoring mistake. */
        WARNING,
    }

    /**
     * @param path location inside the theme, e.g. `style/candidate_text_size`
     *   or `preset_color_schemes/default/key_back_color`, when the finding
     *   points at one node.
     */
    data class Finding(
        val severity: Severity,
        val code: Code,
        val message: String,
        val path: String? = null,
    )

    /**
     * Lints a decoded [theme] together with the [node] it was decoded from,
     * which still holds keys the decoder ignores.
     *
     * @param parseColor parses a color string, null when it is not a color
     *   (dependency-injected for JVM testability).
     */
    fun lint(
        theme: Theme,
        node: YamlMap,
        parseColor: (String) -> Int? = ::parseColor,
    ): List<Finding> = buildList {
        lintTopLevelKeys(node)
        lintStyleKeys(node)
        lintConfigVersion(node)
        lintColorSchemes(theme, parseColor)
        lintPresetSends(theme)
        lintKeyboardReferences(theme)
        lintUnreadSettings(node)
    }

    /**
     * Reports settings that look right but do nothing: a value whose shape the decoder cannot
     * read, and keys this build has no reader for.
     *
     * The key-name checks above cannot see these. A theme that writes `key_font: key.ttf` has
     * spelled the key correctly, so nothing is unknown -- but the decoder reads a *list* of
     * files there and gets an empty one, and the setting is silently gone. The same holds for a
     * button declared by name under `tool_bar`, which only the list form reaches.
     */
    private fun MutableList<Finding>.lintUnreadSettings(node: YamlMap) {
        val style = node.pairs["style"]?.mapping
        FONT_KEYS.forEach { key ->
            val value = style?.pairs?.get(key) ?: return@forEach
            if (value.sequence == null) {
                add(
                    Finding(
                        Severity.WARNING,
                        Code.FONT_NOT_A_LIST,
                        "'$key' is written as one value; a font is read as a list of files, " +
                            "so this setting is ignored",
                        "style/$key",
                    ),
                )
            }
        }
        reportUnreadKeys(node.pairs["tool_bar"]?.mapping, IGNORED_TOOL_BAR_KEYS, "tool_bar", Code.IGNORED_TOOL_BAR_KEY)
        reportUnreadKeys(
            node.pairs["liquid_keyboard"]?.mapping,
            IGNORED_LIQUID_KEYBOARD_KEYS,
            "liquid_keyboard",
            Code.IGNORED_LIQUID_KEYBOARD_KEY,
        )
    }

    private fun MutableList<Finding>.reportUnreadKeys(
        node: YamlMap?,
        keys: List<String>,
        path: String,
        code: Code,
    ) {
        if (node == null) return
        keys.forEach { key ->
            if (key !in node.pairs) return@forEach
            add(
                Finding(
                    Severity.WARNING,
                    code,
                    "'$path/$key' has no reader in this build; the runtime ignores it",
                    "$path/$key",
                ),
            )
        }
    }

    /**
     * Logs [findings] of the theme [themeId], one line each, at their severity.
     * A theme is checked when it is read instead of on first use; the same
     * findings are what [format] renders for a report and what the theme
     * diagnostics screen lists.
     */
    fun log(
        themeId: String,
        findings: List<Finding>,
    ) {
        findings.forEach { finding ->
            val message = "Theme '$themeId': ${finding.message}"
            when (finding.severity) {
                Severity.INFO -> Timber.i(message)
                Severity.WARNING -> Timber.w(message)
            }
        }
    }

    /**
     * Renders [findings] as the text of a bug report: which theme was checked, what the checks
     * found, and — when [liveFindings] are given — what the running state is overriding right
     * now. [findings] is null when the static checks themselves failed to run, which the report
     * says instead of pretending all is well.
     */
    fun format(
        themeId: String,
        themeName: String,
        findings: List<Finding>?,
        liveFindings: List<Finding> = emptyList(),
    ): String = buildString {
        appendLine("Theme: $themeName ($themeId)")
        // Live findings first: they describe what the keyboard is doing *now*, which is usually
        // the question that sent the reader here; the static checks describe the file.
        if (liveFindings.isNotEmpty()) {
            appendLine("Live findings: ${liveFindings.size}")
            liveFindings.forEach { finding ->
                val path = finding.path?.let { "$it: " } ?: ""
                appendLine("[${finding.severity}] $path${finding.message} (${finding.code})")
            }
        }
        when {
            findings == null ->
                appendLine("Static checks could not run for this theme.")

            findings.isEmpty() -> appendLine("No findings.")

            else -> {
                val warnings = findings.count { it.severity == Severity.WARNING }
                appendLine("Findings: ${findings.size} ($warnings warnings, ${findings.size - warnings} info)")
                findings.forEach { finding ->
                    val path = finding.path?.let { "$it: " } ?: ""
                    appendLine("[${finding.severity}] $path${finding.message} (${finding.code})")
                }
            }
        }
    }

    /**
     * The built-in overrides that are in charge right now, as findings.
     *
     * The checks above read the theme's *file*. This one reads the state the keyboard is in:
     * while a domain's source is the built-in settings, every stored knob outranks the theme's
     * own — a theme edit to that key reaches nothing until the source changes back. The
     * diagnostics screen is where a theme author goes to ask "why did my edit do nothing", so
     * that answer belongs here, next to the file's own report.
     *
     * The sizes come first, then the layout, matching the order of the screens. One finding per
     * key, naming both values, so the message stands on its own in a bug report. A stored key
     * this build does not know is skipped: it changes nothing either way, and the editor already
     * reports it as unrecognised.
     *
     * Pure, like [lint]: both sides of each source gate are pinned by a test.
     */
    fun builtinOverridesInEffect(
        styleOverrides: Map<String, Float>,
        styleInCharge: Boolean,
        layoutOverrides: Map<String, Float>,
        layoutInCharge: Boolean,
        themeStyle: GeneralStyle,
    ): List<Finding> =
        buildList {
            if (styleInCharge) {
                addAll(
                    shadowed(
                        overrides = styleOverrides,
                        domain = "size",
                        resolve = { StyleSemantic.from(it) },
                        keyOf = { it.key },
                        themeValueOf = { themeStyle.valueOf(it) },
                    ),
                )
            }
            if (layoutInCharge) {
                addAll(
                    shadowed(
                        overrides = layoutOverrides,
                        domain = "layout",
                        resolve = { LayoutSemantic.from(it) },
                        keyOf = { it.key },
                        themeValueOf = { themeStyle.valueOf(it) },
                    ),
                )
            }
        }

    /**
     * One finding per stored key of one domain, at the theme key it overrides.
     *
     * [resolve] is what makes this work for two different vocabularies: it maps the stored string
     * to the build's own semantic type, which then supplies the canonical key and the theme value
     * — and a name neither vocabulary knows comes back null and is skipped.
     */
    private fun <T> shadowed(
        overrides: Map<String, Float>,
        domain: String,
        resolve: (String) -> T?,
        keyOf: (T) -> String,
        themeValueOf: (T) -> Float,
    ): List<Finding> =
        overrides.entries
            .mapNotNull { (key, value) -> resolve(key)?.let { Triple(keyOf(it), value, themeValueOf(it)) } }
            .sortedBy { it.first }
            .map { (canonical, value, themeValue) ->
                Finding(
                    Severity.INFO,
                    Code.SHADOWED_BY_BUILTIN,
                    "'$canonical' is overridden by the built-in $domain settings " +
                        "(theme ${formatKnobValue(themeValue)} → built-in ${formatKnobValue(value)}); " +
                        "theme edits have no effect while the $domain source is the built-in one",
                    "style/$canonical",
                )
            }

    /** `12` rather than `12.0`; a fractional override keeps its fraction. */
    private fun formatKnobValue(value: Float): String =
        if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else value.toString()

    private fun MutableList<Finding>.reportUnknownKeys(
        node: YamlMap?,
        known: Set<String>,
        path: String,
        code: Code,
        severity: Severity,
    ) {
        node?.pairs?.keys?.forEach { name ->
            if (name in known) return@forEach
            add(
                Finding(
                    severity,
                    code,
                    "unknown key '$name' in '$path'; the runtime ignores it",
                    "$path/$name",
                ),
            )
        }
    }

    private fun MutableList<Finding>.lintTopLevelKeys(node: YamlMap) {
        // `__include`/`__patch` are expansion directives: they never survive
        // expansion, but a theme may still carry them for the librime backend.
        reportUnknownKeys(
            node,
            Theme.TOP_LEVEL_KEYS + "__include" + "__patch",
            path = "",
            code = Code.UNKNOWN_TOP_LEVEL_KEY,
            severity = Severity.INFO,
        )
    }

    private fun MutableList<Finding>.lintStyleKeys(node: YamlMap) {
        reportUnknownKeys(
            node.pairs["style"]?.mapping,
            GeneralStyle.KNOWN_KEYS,
            path = "style",
            code = Code.UNKNOWN_STYLE_KEY,
            severity = Severity.WARNING,
        )
    }

    /**
     * Reads `config_version`, the theme format version. The librime backend
     * only uses it as a deploy cache key, so this is where it is checked
     * against the version this build can read.
     */
    private fun MutableList<Finding>.lintConfigVersion(node: YamlMap) {
        val raw = node.pairs["config_version"]?.string
        if (raw == null) {
            add(
                Finding(
                    Severity.INFO,
                    Code.CONFIG_VERSION_MISSING,
                    "no 'config_version'; the theme is read as version $SUPPORTED_CONFIG_VERSION",
                    "config_version",
                ),
            )
            return
        }
        val version = parseVersion(raw)
        if (version == null) {
            add(
                Finding(
                    Severity.WARNING,
                    Code.CONFIG_VERSION_INVALID,
                    "cannot read 'config_version' value '$raw'",
                    "config_version",
                ),
            )
            return
        }
        val supported = parseVersion(SUPPORTED_CONFIG_VERSION)!!
        val newer =
            version.first > supported.first ||
                (version.first == supported.first && version.second > supported.second)
        if (newer) {
            add(
                Finding(
                    Severity.WARNING,
                    Code.CONFIG_VERSION_UNSUPPORTED,
                    "'config_version' $raw is newer than the supported $SUPPORTED_CONFIG_VERSION; " +
                        "settings added by the newer format are ignored",
                    "config_version",
                ),
            )
        }
    }

    /** `major[.minor[.patch]]`, ignoring non-numeric parts. */
    private fun parseVersion(raw: String): Pair<Int, Int>? {
        val parts = raw.trim().split('.').map { it.takeWhile(Char::isDigit) }
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return major to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun MutableList<Finding>.lintColorSchemes(
        theme: Theme,
        parseColor: (String) -> Int?,
    ) {
        val schemes = theme.colorSchemes
        if (schemes.isEmpty()) {
            add(
                Finding(
                    Severity.WARNING,
                    Code.NO_COLOR_SCHEME,
                    "no 'preset_color_schemes'; the keyboard cannot resolve any color",
                ),
            )
            return
        }

        val ids = schemes.map(ColorScheme::id).toSet()
        if ("default" !in ids) {
            add(
                Finding(
                    Severity.WARNING,
                    Code.MISSING_DEFAULT_SCHEME,
                    "no 'default' color scheme; the first scheme is used when none is selected",
                    "preset_color_schemes",
                ),
            )
        }
        schemes.forEach { scheme ->
            listOf("light_scheme", "dark_scheme").forEach { link ->
                val target = scheme.colors[link]
                if (!target.isNullOrEmpty() && target !in ids) {
                    add(
                        Finding(
                            Severity.WARNING,
                            Code.MISSING_SCHEME_LINK,
                            "scheme '${scheme.id}' links to missing scheme '$target' via '$link'",
                            "preset_color_schemes/${scheme.id}/$link",
                        ),
                    )
                }
            }
        }

        // A key no scheme defines is not reported on its own: the built-in
        // fallback chains cover only part of the vocabulary, and the runtime
        // already reports missing keys on demand (see ThemeScope).
        //
        // A value that cannot be parsed, on the other hand, is a typo in the
        // file: it is reported at the key that carries it, which may be a theme
        // fallback entry rather than the key the runtime asks for. The keys
        // that inherit the value are left out, so it is reported once.
        schemes.forEach { scheme ->
            ColorTable.resolve(scheme, theme.fallbackColors, parseColor)
                .invalidValues
                .mapNotNull { key ->
                    ColorTable.resolveRawSource(key.key, scheme.colors, theme.fallbackColors)
                }
                .distinct()
                .forEach { (source, raw) ->
                    val definedByScheme = scheme.colors[source]?.isNotEmpty() == true
                    val where = if (definedByScheme) "scheme '${scheme.id}'" else "fallback_colors"
                    val path =
                        if (definedByScheme) {
                            "preset_color_schemes/${scheme.id}/$source"
                        } else {
                            "fallback_colors/$source"
                        }
                    add(
                        Finding(
                            Severity.WARNING,
                            Code.INVALID_COLOR_VALUE,
                            "$where: '$source' cannot be parsed as a color (value '$raw')",
                            path,
                        ),
                    )
                }
        }

        lintFallbackTargets(theme, schemes)
    }

    /**
     * A `fallback_colors` entry whose target no scheme defines is dead: the key
     * it feeds stays unresolved even though the theme asked for something.
     */
    private fun MutableList<Finding>.lintFallbackTargets(
        theme: Theme,
        schemes: List<ColorScheme>,
    ) {
        val fallbacks = theme.fallbackColors
        fallbacks.forEach { (from, target) ->
            if (target.isEmpty()) return@forEach
            // `target` is fine when walking it reaches a value the way the
            // runtime does: a key a scheme defines, another entry of the chain,
            // a built-in chain from there, or an image. Walking also means a
            // chain that ends nowhere, or in a cycle, is reported at every
            // entry pointing into it.
            val resolvable =
                ColorTable.isImageValue(target) ||
                    schemes.any { scheme ->
                        ColorTable.resolveRaw(target, scheme.colors, fallbacks) != null
                    }
            if (resolvable) return@forEach
            add(
                Finding(
                    Severity.WARNING,
                    Code.BROKEN_FALLBACK_TARGET,
                    "fallback_colors: '$from' points at '$target', which no scheme defines",
                    "fallback_colors/$from",
                ),
            )
        }
    }

    private fun MutableList<Finding>.lintPresetSends(theme: Theme) {
        KeyActionManager.presetDiagnostics(theme.presetKeys).forEach { message ->
            add(
                Finding(
                    Severity.WARNING,
                    Code.UNRESOLVABLE_PRESET_SEND,
                    message,
                    "preset_keys/${message.substringAfter('\'').substringBefore('\'')}",
                ),
            )
        }
    }

    private fun MutableList<Finding>.lintKeyboardReferences(theme: Theme) {
        val keyboards = theme.presetKeyboards
        keyboards.forEach { (id, keyboard) ->
            listOf(
                "import_preset" to keyboard.importPreset,
                "ascii_keyboard" to keyboard.asciiKeyboard,
                "landscape_keyboard" to keyboard.landscapeKeyboard,
            ).forEach { (key, target) ->
                if (target.isEmpty() || target in keyboards) return@forEach
                add(
                    Finding(
                        Severity.WARNING,
                        Code.MISSING_KEYBOARD_REFERENCE,
                        "keyboard '$id' references missing keyboard '$target' via '$key'",
                        "preset_keyboards/$id/$key",
                    ),
                )
            }
        }
        lintKeyboardCycles(theme)
    }

    /**
     * `import_preset` is followed until the chain ends, so a keyboard that
     * imports itself never ends. Reported once per cycle, at the entry that
     * closes it; the runtime has no guard either.
     */
    private fun MutableList<Finding>.lintKeyboardCycles(theme: Theme) {
        val keyboards = theme.presetKeyboards
        val reported = mutableSetOf<Set<String>>()
        keyboards.keys.forEach { start ->
            val chain = linkedSetOf<String>()
            var current: String? = start
            var last: String? = null
            while (current != null && current in keyboards && chain.add(current)) {
                last = current
                current = keyboards[current]?.importPreset?.takeIf { it.isNotEmpty() }
            }
            if (current == null || current !in chain) return@forEach
            val cycle = chain.dropWhile { it != current }
            if (!reported.add(cycle.toSet())) return@forEach
            add(
                Finding(
                    Severity.WARNING,
                    Code.CYCLIC_KEYBOARD_REFERENCE,
                    "preset keyboards import each other in a cycle " +
                        cycle.joinToString(" -> ", prefix = "(", postfix = ")") { "'$it'" } +
                        " via 'import_preset'; the reference never resolves",
                    "preset_keyboards/$last/import_preset",
                ),
            )
        }
    }

    private fun parseColor(value: String): Int? = runCatching { ColorUtils.parseColor(value) }.getOrNull()
}

/**
 * Font settings the decoder reads as a list of files. A theme that writes one of them as a bare
 * value has a key name the checks above accept, and a setting that is silently not applied.
 */
private val FONT_KEYS =
    listOf(
        "candidate_font",
        "comment_font",
        "hanb_font",
        "key_font",
        "label_font",
        "latin_font",
        "popup_font",
        "symbol_font",
        "text_font",
    )

/**
 * Buttons a theme may declare by name under `tool_bar`. Only the list form is read
 * (`buttons`, plus `primary_button`), so these do nothing where they stand.
 */
private val IGNORED_TOOL_BAR_KEYS =
    listOf(
        "ascii_mode_button",
        "clipboard_button",
        "edit_button",
        "emoji_button",
        "full_shape_button",
        "hide_button",
    )

/** Liquid keyboard settings this build has no reader for. */
private val IGNORED_LIQUID_KEYBOARD_KEYS =
    listOf(
        "author",
        "row",
        "row_land",
        "key_height_land",
        "vertical_gap",
    )
