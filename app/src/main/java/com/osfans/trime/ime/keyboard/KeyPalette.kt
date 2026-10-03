/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.view.KeyEvent

/**
 * The two palettes the built-in dynamic colour scheme paints keys from.
 *
 * A theme scheme needs no such split: it decides a key's colour through the colour keys the key
 * binds, so the theme author stays in control of every key they wrote. The dynamic scheme is the
 * app's palette instead, and it cannot know a name a theme invented — nor a key the user added to
 * a custom layout, which binds no colour at all. It therefore sorts keys by what they type, which
 * survives any layout change.
 */
internal enum class KeyPalette {
    /** A letter key or the space bar. */
    TEXT,

    /** Everything else: shift, backspace, return, the symbol and number rows, command-only keys. */
    FUNCTION,
}

/**
 * Classifies a key by the character it types.
 *
 * [text] is what a key commits when its key code is not one Android knows (see
 * `KeyAction`); `{ text: "a" }` lands there, while the `{ click: a }` a keyboard normally uses
 * carries the letter in the key code instead and leaves [text] empty. Both forms have to classify
 * as a letter key, so both are checked.
 *
 * The space bar is a letter key by this rule: it types `" "`, and a keyboard reads it as one of
 * the typing keys rather than as a modifier. Digits, punctuation and the like are not — they sit
 * on the planes a user opens deliberately, next to keys that are unambiguously function keys.
 */
internal fun keyPaletteOf(
    text: String,
    code: Int,
): KeyPalette = when {
    text.length == 1 && text[0].isAsciiLetter() -> KeyPalette.TEXT
    text == " " -> KeyPalette.TEXT
    code == KeyEvent.KEYCODE_SPACE -> KeyPalette.TEXT
    // KEYCODE_A..KEYCODE_Z is contiguous, and holds nothing but the 26 letters.
    code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> KeyPalette.TEXT
    else -> KeyPalette.FUNCTION
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
