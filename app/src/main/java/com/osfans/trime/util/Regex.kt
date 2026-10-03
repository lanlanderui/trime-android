// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

fun String.removeRegexSet(regexSet: Set<Regex>): String {
    regexSet.forEach { replace(it, String.EMPTY) }
    return this
}

fun String.matchesAny(regexSet: Set<Regex>): Boolean = regexSet.any { it.matches(this) }

/**
 * Compiles newline-separated [patterns] into a set of [Regex].
 *
 * Blank lines are dropped rather than compiled: an empty rules text splits into a single empty
 * string, and `Regex("")` matches every input, so keeping it would make the rule set filter
 * everything (or, for the dedup rules, reduce every clip to nothing). Trimming each line lets a
 * rules text survive the trailing newline an editor leaves behind.
 *
 * A line that is not a valid pattern is skipped rather than thrown, so a typo in the settings
 * cannot take the clipboard listener down with it.
 */
fun compileRegexSet(
    patterns: String,
    trim: Boolean = true,
): Set<Regex> =
    patterns
        .split('\n')
        .map { if (trim) it.trim() else it }
        .filter { it.isNotEmpty() }
        .mapNotNull { pattern ->
            try {
                Regex(pattern)
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toSet()
