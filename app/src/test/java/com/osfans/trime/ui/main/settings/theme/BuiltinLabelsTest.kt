/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import com.osfans.trime.data.theme.ColorSemantic
import com.osfans.trime.data.theme.StyleSemantic
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The names on the built-in settings screen, and the order they appear in.
 *
 * Both used to be written out twice — once for colours, once for sizes, each as an exhaustive
 * `when`. The cost of that is not the duplication but where the failure lands: a name that is
 * missing is not a compile error once the `when` becomes a map lookup, it is a raw resource id
 * rendered as a heading on a device. This is the test that turns that back into a build failure.
 */
class BuiltinLabelsTest :
    BehaviorSpec({
        Given("every semantic item and group in the settings") {
            Then("each one has a label") {
                // Names the ones with no entry, which is the only list this assertion can ever
                // produce — so an empty list is the pass and anything else names what was added.
                BuiltinLabels.missingLabels() shouldBe emptyList()
            }

            Then("no two items share a label") {
                // A duplicate is not a crash, it is two rows with the same heading, which is how a
                // user ends up editing the wrong one.
                val colorLabels = ColorSemantic.entries.map { BuiltinLabels.colorItem(it) }
                colorLabels.groupingBy { it }.eachCount().filterValues { it > 1 } shouldBe emptyMap()

                val styleLabels = StyleSemantic.entries.map { BuiltinLabels.styleItem(it) }
                styleLabels.groupingBy { it }.eachCount().filterValues { it > 1 } shouldBe emptyMap()
            }
        }

        Given("the grouped lists the screen iterates") {
            Then("every item appears exactly once, in its own group") {
                // The screen draws what these return and nothing else, so an item missing from the
                // grouping is an item that cannot be reached at all.
                colorSections().flatMap { it.items } shouldBe ColorSemantic.entries
                sizeSections().flatMap { it.items } shouldBe StyleSemantic.entries
            }

            Then("no group is rendered empty") {
                // An empty heading is a section title with nothing under it, which reads as a
                // rendering fault rather than as "nothing to set here".
                colorSections().filter { it.items.isEmpty() } shouldBe emptyList()
                sizeSections().filter { it.items.isEmpty() } shouldBe emptyList()
            }

            Then("the group order follows the enum, not the map") {
                // The screen's order is the semantic layer's declaration order, so that moving an
                // item in ColorSemantic moves it on screen without touching anything else.
                colorSections().map { it.group } shouldBe ColorSemantic.Group.entries
                sizeSections().map { it.group } shouldBe StyleSemantic.Group.entries
            }
        }
    })