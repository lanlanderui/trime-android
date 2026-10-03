/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.view.KeyEvent
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The dynamic colour scheme paints function keys from one palette, so "is this a function key?"
 * has to be answered without the theme: a key the user adds to a custom layout binds no colour
 * and often carries no `functional:` flag either.
 *
 * Only the key code and the text a key types are consulted, which is why both forms a keyboard can
 * use for the same key are covered here: `{click: a}` leaves the letter in the code, while
 * `{text: a}` carries it as text.
 */
class KeyPaletteTest :
    BehaviorSpec({
        Given("a key that types a letter") {
            When("the keyboard binds it as a plain token, as a layout normally does") {
                Then("the letter is in the key code and the key is a text key") {
                    keyPaletteOf(text = "", code = KeyEvent.KEYCODE_A) shouldBe KeyPalette.TEXT
                    keyPaletteOf(text = "", code = KeyEvent.KEYCODE_M) shouldBe KeyPalette.TEXT
                    keyPaletteOf(text = "", code = KeyEvent.KEYCODE_Z) shouldBe KeyPalette.TEXT
                }
            }
            When("the key carries the character as text instead") {
                Then("it is a text key all the same") {
                    keyPaletteOf(text = "q", code = 0) shouldBe KeyPalette.TEXT
                    keyPaletteOf(text = "Q", code = 0) shouldBe KeyPalette.TEXT
                }
            }
        }

        Given("the space bar") {
            When("it is bound as the plain token a layout uses") {
                Then("it counts as a text key") {
                    keyPaletteOf(text = "", code = KeyEvent.KEYCODE_SPACE) shouldBe KeyPalette.TEXT
                }
            }
            When("it carries the blank as text") {
                Then("it counts as a text key as well") {
                    keyPaletteOf(text = " ", code = 0) shouldBe KeyPalette.TEXT
                }
            }
        }

        Given("every other key") {
            Then("the modifiers are function keys") {
                keyPaletteOf(text = "", code = KeyEvent.KEYCODE_SHIFT_LEFT) shouldBe KeyPalette.FUNCTION
                keyPaletteOf(text = "", code = KeyEvent.KEYCODE_CTRL_LEFT) shouldBe KeyPalette.FUNCTION
                keyPaletteOf(text = "", code = KeyEvent.KEYCODE_ALT_LEFT) shouldBe KeyPalette.FUNCTION
            }
            Then("the editing keys are function keys") {
                keyPaletteOf(text = "", code = KeyEvent.KEYCODE_DEL) shouldBe KeyPalette.FUNCTION
                keyPaletteOf(text = "", code = KeyEvent.KEYCODE_ENTER) shouldBe KeyPalette.FUNCTION
            }
            Then("the punctuation and number rows are function keys") {
                keyPaletteOf(text = "", code = KeyEvent.KEYCODE_COMMA) shouldBe KeyPalette.FUNCTION
                keyPaletteOf(text = "", code = KeyEvent.KEYCODE_PERIOD) shouldBe KeyPalette.FUNCTION
                keyPaletteOf(text = "", code = KeyEvent.KEYCODE_1) shouldBe KeyPalette.FUNCTION
                keyPaletteOf(text = "，", code = 0) shouldBe KeyPalette.FUNCTION
                keyPaletteOf(text = "@", code = 0) shouldBe KeyPalette.FUNCTION
            }
            Then("a key that only carries a command is a function key") {
                keyPaletteOf(text = "", code = KeyEvent.KEYCODE_FUNCTION) shouldBe KeyPalette.FUNCTION
                keyPaletteOf(text = "", code = KeyEvent.KEYCODE_UNKNOWN) shouldBe KeyPalette.FUNCTION
            }
            Then("so is a key typing more than one character") {
                keyPaletteOf(text = "abc", code = 0) shouldBe KeyPalette.FUNCTION
                keyPaletteOf(text = "(){Left}", code = 0) shouldBe KeyPalette.FUNCTION
            }
        }
    })
