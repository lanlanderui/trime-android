// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.model.TextKeyboard
import timber.log.Timber

/** Follows import_preset without allowing an invalid theme to recurse forever. */
internal fun resolveKeyboardConfig(configs: Map<String, TextKeyboard>, name: String): TextKeyboard? {
    val visited = mutableSetOf<String>()
    var current = name
    while (visited.add(current)) {
        val config = configs[current] ?: configs["default"] ?: return null
        if (config.importPreset.isEmpty()) return config
        current = config.importPreset
    }
    Timber.w("Cyclic keyboard import_preset starting at '%s'", name)
    return configs["default"]?.takeIf { it.importPreset.isEmpty() }
}
