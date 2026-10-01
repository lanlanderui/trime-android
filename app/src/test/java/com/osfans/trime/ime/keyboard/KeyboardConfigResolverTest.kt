// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.model.TextKeyboard
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KeyboardConfigResolverTest :
    StringSpec({
        "valid imports resolve while cycles fall back to the default layout" {
            val fallback = TextKeyboard(name = "default")
            val layouts = mapOf(
                "default" to fallback,
                "letters" to TextKeyboard(importPreset = "symbols"),
                "symbols" to TextKeyboard(importPreset = "letters"),
                "numbers" to TextKeyboard(importPreset = "default"),
            )
            resolveKeyboardConfig(layouts, "numbers") shouldBe fallback
            resolveKeyboardConfig(layouts, "letters") shouldBe fallback
        }
    })
