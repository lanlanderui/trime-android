/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * When a theme's `shadow_color` and `style.shadow_radius` add up to a shadow.
 *
 * The rule matters more than it looks: `shadow_color` is set by themes that never had a reader for
 * it, so a theme is as likely to carry a placeholder as an intention, and a shadow drawn from a
 * placeholder is a smudge over every key.
 */
class KeyTextShadowTest :
    StringSpec({
        val opaqueBlack = 0xFF000000.toInt()

        "a radius and a visible colour make a halo" {
            KeyTextShadow.of(radiusPx = 10f, color = opaqueBlack) shouldBe
                KeyTextShadow(radiusPx = 10f, dxPx = 0f, dyPx = 0f, color = opaqueBlack)
        }

        "no radius draws nothing" {
            KeyTextShadow.of(radiusPx = 0f, color = opaqueBlack).shouldBeNull()
            KeyTextShadow.of(radiusPx = -3f, color = opaqueBlack).shouldBeNull()
        }

        "a fully transparent colour draws nothing" {
            KeyTextShadow.of(radiusPx = 10f, color = 0x00000000).shouldBeNull()
            KeyTextShadow.of(radiusPx = 10f, color = 0x00FFFFFF).shouldBeNull()
        }

        "the alpha is the colour's top byte and nothing else" {
            // One step above transparent is a shadow, however faint — the theme asked for one.
            KeyTextShadow.of(radiusPx = 10f, color = 0x01FFFFFF).shouldNotBeNull()
            // A colour with no alpha byte at all is a fully transparent shadow, not an opaque black
            // one: `#RRGGBB` left in a slot that wants ARGB must not paint a black halo.
            KeyTextShadow.of(radiusPx = 10f, color = 0x000000FF).shouldBeNull()
        }

        "the theme's own settings, as this theme writes them, are a shadow" {
            // `shadow_color: 0x88000000` and `shadow_radius: 10`.
            KeyTextShadow.of(radiusPx = 10f, color = 0x88000000.toInt())?.color shouldBe 0x88000000.toInt()
        }
    })
