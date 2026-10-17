/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import com.osfans.trime.data.theme.BuiltinColorDefaults
import com.osfans.trime.data.theme.BuiltinColorScheme
import com.osfans.trime.data.theme.BuiltinLayout
import com.osfans.trime.data.theme.BuiltinStyle

/**
 * What "reset" can mean, which is three wishes rather than one.
 *
 * Picking one for the user would either throw away settings they meant to keep — the sizes, when
 * only the colours are a mess — or leave half a mess behind. The labels on the buttons say which is
 * which; this is the rule behind them.
 */
internal enum class ResetChoice {
    /** Back to the palette this build ships, for both day and night. */
    DEFAULT_PALETTE,

    /** Colours follow the theme's own scheme. Sizes are not mentioned, so they are left alone. */
    FOLLOW_SCHEME,

    /** Colours, sizes and layout all follow the theme. */
    FOLLOW_THEME,
}

/** The palette [choice] leaves behind. */
internal fun BuiltinColorScheme.afterReset(choice: ResetChoice): BuiltinColorScheme =
    when (choice) {
        ResetChoice.DEFAULT_PALETTE -> BuiltinColorDefaults.DEFAULT
        ResetChoice.FOLLOW_SCHEME, ResetChoice.FOLLOW_THEME -> cleared()
    }

/**
 * The sizes [choice] leaves behind.
 *
 * Only [ResetChoice.FOLLOW_THEME] touches them. The other two are answers to "my colours are a
 * mess", and taking the sizes along would discard work the user never asked about.
 */
internal fun BuiltinStyle.afterReset(choice: ResetChoice): BuiltinStyle =
    if (choice.touchesBuiltinOverrides()) BuiltinStyle() else this

/**
 * The layout [choice] leaves behind — the same rule as the sizes: "follow the theme" means
 * everything the built-in settings were overriding, and nothing else touches the layout.
 */
internal fun BuiltinLayout.afterReset(choice: ResetChoice): BuiltinLayout =
    if (choice.touchesBuiltinOverrides()) BuiltinLayout() else this

/**
 * Whether [choice] changes the built-in size and layout overrides at all.
 *
 * Also whether they need saving: writing a file that did not change is how a screen starts doing
 * disk IO for nothing.
 */
internal fun ResetChoice.touchesBuiltinOverrides(): Boolean = this == ResetChoice.FOLLOW_THEME