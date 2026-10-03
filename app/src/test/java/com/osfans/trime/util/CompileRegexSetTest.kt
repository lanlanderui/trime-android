/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The clipboard rules are user-editable text, parsed on every change to them.
 *
 * The behaviour these pin down was previously untested because the parsing lived inline in a
 * `by lazy` block inside [com.osfans.trime.data.db.ClipboardHelper], which meant it could not be
 * reached without a Room database -- and, more importantly, meant nobody noticed the cache never
 * invalidated when the settings changed.
 */
class CompileRegexSetTest :
    BehaviorSpec({
        Given("an empty rules text") {
            Then("it compiles to no rules at all") {
                // "".split('\n') is [""], and Regex("") matches every input -- compiling it
                // would make the filter drop every clip.
                compileRegexSet("") shouldBe emptySet()
            }
        }

        Given("a rules text with blank lines") {
            Then("the blank lines are dropped rather than compiled") {
                val rules = compileRegexSet("\n\nfoo\n\n")
                rules.size shouldBe 1
            }

            Then("a trailing newline from the editor does not create a catch-all rule") {
                // The settings field is a text input, so a trailing newline is the normal case.
                compileRegexSet("foo\n").none { it.matches("anything") } shouldBe true
            }
        }

        Given("a single rule") {
            Then("it matches what the user wrote") {
                val rules = compileRegexSet("abc")
                // matches() anchors the whole string, so a rule is a filter rather than a
                // substring search: "xxabcxx" is a different clip and must not be caught.
                "abc".matchesAny(rules) shouldBe true
                "xxabcxx".matchesAny(rules) shouldBe false
                "xyz".matchesAny(rules) shouldBe false
            }
        }

        Given("several rules") {
            Then("each is compiled") {
                val rules = compileRegexSet("abc\ndef")
                rules.size shouldBe 2
                "abc".matchesAny(rules) shouldBe true
                "def".matchesAny(rules) shouldBe true
                "ghi".matchesAny(rules) shouldBe false
            }
        }

        Given("a rule written with surrounding whitespace") {
            Then("trimming is on by default so indentation does not change the pattern") {
                val trimmed = compileRegexSet("  abc  ", trim = true)
                "abc".matchesAny(trimmed) shouldBe true
            }

            Then("trimming can be turned off for rules where whitespace is significant") {
                val untrimmed = compileRegexSet("a b", trim = false)
                untrimmed.size shouldBe 1
                // The space is part of the pattern, so "ab" must not match.
                "ab".matchesAny(untrimmed) shouldBe false
            }
        }

        Given("a rule that is not a valid pattern") {
            Then("it is skipped instead of throwing") {
                // A typo in the settings must not take the clipboard listener down with it.
                val rules = compileRegexSet("foo\n[unclosed\nbar")
                // "foo" and "bar" survive; the broken line is dropped.
                rules.size shouldBe 2
                "foo".matchesAny(rules) shouldBe true
                "bar".matchesAny(rules) shouldBe true
            }
        }

        Given("duplicate rules") {
            Then("both are kept, because Regex does not implement equals") {
                // Worth pinning down: it reads like a set that should collapse, but Regex relies on
                // identity equality, so two identical patterns stay separate entries. Harmless --
                // matching either one has the same result -- but do not rely on it to dedupe.
                compileRegexSet("abc\nabc").size shouldBe 2
            }
        }
    })
