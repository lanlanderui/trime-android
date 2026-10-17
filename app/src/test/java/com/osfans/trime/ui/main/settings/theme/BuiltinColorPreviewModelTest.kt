/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * How the preview turns a theme's flat key list into something it can draw.
 *
 * The row breaks are not in the theme: `TextKeyboard.keys` is one list and the renderer derives
 * the rows by accumulating widths. A row that comes out one key too long shows a keyboard that
 * does not exist, which is worse than showing nothing — a colour preview is trusted precisely
 * because it looks like the keyboard.
 */
class BuiltinColorPreviewModelTest :
    BehaviorSpec({
        fun key(
            width: Float = 10f,
            name: String = "q",
            label: String = "",
            colorKey: String = "",
            action: KeyActionToken? = KeyActionToken.Plain(name),
        ): TextKeyboard.TextKey =
            TextKeyboard.TextKey(
                width = width,
                label = label,
                keyBackColor = colorKey,
                behaviors = action?.let { mapOf(com.osfans.trime.ime.keyboard.KeyBehavior.CLICK to it) } ?: emptyMap(),
            )

        fun keyboard(
            vararg keys: TextKeyboard.TextKey,
            width: Float = 10f,
            columns: Int = 30,
        ): TextKeyboard =
            TextKeyboard(
                width = width,
                columns = columns,
                keys = keys.toList(),
            )

        Given("a keyboard whose keys fill exactly one row") {
            When("ten keys of weight ten are declared") {
                val rows = previewRows(keyboard(*Array(10) { key() }.toList().toTypedArray()))

                Then("they make one row") {
                    rows.size shouldBe 1
                    rows[0].keys.size shouldBe 10
                }
            }

            When("an eleventh key is added") {
                val rows = previewRows(keyboard(*Array(11) { key() }.toList().toTypedArray()))

                Then("it wraps to a second row") {
                    rows.size shouldBe 2
                    rows[0].keys.size shouldBe 10
                    rows[1].keys.size shouldBe 1
                }
            }
        }

        Given("a keyboard that caps its columns") {
            When("columns is three and six keys are declared") {
                val rows = previewRows(keyboard(*Array(6) { key() }.toList().toTypedArray(), columns = 3))

                Then("every row is cut at three keys, well before the width runs out") {
                    rows.size shouldBe 2
                    rows.forEach { it.keys.size shouldBe 3 }
                }
            }
        }

        Given("a key with no width of its own") {
            When("it does something") {
                Then("it takes the keyboard's default width") {
                    val rows = previewRows(keyboard(key(width = 0f), width = 10f))
                    rows[0].keys[0].weight shouldBe 10f
                }
            }

            When("it does nothing at all") {
                Then("it stays zero wide and is not counted as a column") {
                    val rows = previewRows(keyboard(key(width = 0f, action = null), width = 10f, columns = 1))
                    rows[0].keys[0].weight shouldBe 0f
                    rows.size shouldBe 1
                }
            }
        }

        Given("the label of a key") {
            Then("the key's own label wins") {
                previewLabel(key(label = "中"), KeyActionToken.Plain("q")) shouldBe "中"
            }

            Then("a plain token is used when the key declares none") {
                previewLabel(key(), KeyActionToken.Plain("q")) shouldBe "q"
            }

            Then("an inline token prefers its label, then its text, then its commit") {
                previewLabel(key(), KeyActionToken.Inline(KeyActionToken.Inline.Token(commit = "c", text = "t", label = "L"))) shouldBe "L"
                previewLabel(key(), KeyActionToken.Inline(KeyActionToken.Inline.Token(commit = "c", text = "t", label = null))) shouldBe "t"
                previewLabel(key(), KeyActionToken.Inline(KeyActionToken.Inline.Token(commit = "c", text = null, label = null))) shouldBe "c"
            }

            Then("an empty action leaves it blank rather than crashing") {
                previewLabel(key(action = null), null) shouldBe ""
            }

            Then("the theme's own label for the preset is used when the key has none") {
                // This theme labels its command keys in `preset_keys`: `BackSpace: {label: ⊗}`.
                previewLabel(key(name = "BackSpace"), KeyActionToken.Plain("BackSpace"), mapOf("BackSpace" to "⊗")) shouldBe "⊗"
            }

            Then("the key's own label still wins over the preset's") {
                previewLabel(key(label = "删"), KeyActionToken.Plain("BackSpace"), mapOf("BackSpace" to "⊗")) shouldBe "删"
            }

            Then("the enter sentinel is skipped rather than printed") {
                // `label: enter_labels` means "whatever the enter key's mode says", which is not
                // something a preview can resolve, and printing it literally would be worse.
                previewLabel(key(name = "Return"), KeyActionToken.Plain("Return"), mapOf("Return" to "enter_labels")) shouldBe "Return"
            }

            Then("an inline action's own text is used without a preset lookup") {
                previewLabel(
                    key(),
                    KeyActionToken.Inline(KeyActionToken.Inline.Token(commit = null, text = "，", label = null)),
                    mapOf("，" to "should not be used"),
                ) shouldBe "，"
            }
        }

        Given("the letter-or-function decision") {
            Then("letters and the space bar are letter keys") {
                previewIsLetterKey(KeyActionToken.Plain("q")) shouldBe true
                previewIsLetterKey(KeyActionToken.Plain("Q")) shouldBe true
                previewIsLetterKey(KeyActionToken.Plain("space")) shouldBe true
            }

            Then("commands, digits and punctuation are function keys") {
                previewIsLetterKey(KeyActionToken.Plain("Shift")) shouldBe false
                previewIsLetterKey(KeyActionToken.Plain("BackSpace")) shouldBe false
                previewIsLetterKey(KeyActionToken.Plain("1")) shouldBe false
                previewIsLetterKey(KeyActionToken.Inline(KeyActionToken.Inline.Token("，", null, null))) shouldBe false
                previewIsLetterKey(null) shouldBe false
            }
        }

        Given("a key the theme painted itself") {
            Then("its colour key is carried into the preview") {
                val rows = previewRows(keyboard(key(colorKey = "bgn")))
                rows[0].keys[0].colorKey shouldBe "bgn"
            }

            Then("a key with no colour of its own carries none") {
                val rows = previewRows(keyboard(key()))
                rows[0].keys[0].colorKey shouldBe null
            }
        }

        Given("a keyboard with no keys at all") {
            Then("there are no rows to draw") {
                previewRows(TextKeyboard()) shouldBe emptyList()
            }
        }
    })
