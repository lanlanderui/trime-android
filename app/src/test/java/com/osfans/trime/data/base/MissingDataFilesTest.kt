/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.base

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * The repair pass that runs beside the bundle diff.
 *
 * Its whole reason to exist is a case the diff cannot see: two indexes that agree while the copy
 * they describe is gone. So the test that matters is the one where *nothing* would have been
 * copied — an unchanged index over an empty disk.
 */
class MissingDataFilesTest :
    BehaviorSpec({
        val index =
            mapOf(
                "shared" to "",
                "shared/default.yaml" to "aaa",
                "shared/trime.yaml" to "bbb",
                "shared/opencc" to "",
                "shared/opencc/HKVariants.txt" to "ccc",
            )

        Given("a shared directory that lost everything") {
            When("nothing on disk exists") {
                val missing = missingDataFiles(index) { false }

                Then("every file the index names is reported, and no directory") {
                    missing shouldContainExactlyInAnyOrder
                        listOf("shared/default.yaml", "shared/trime.yaml", "shared/opencc/HKVariants.txt")
                }
            }
        }

        Given("a shared directory that lost one file") {
            val onDisk = setOf("shared/default.yaml", "shared/opencc/HKVariants.txt")

            Then("only the absent one is reported") {
                missingDataFiles(index) { it in onDisk } shouldBe listOf("shared/trime.yaml")
            }
        }

        Given("a complete shared directory") {
            Then("nothing is reported, so a matching index costs nothing") {
                missingDataFiles(index) { true } shouldBe emptyList()
            }
        }

        Given("an empty bundle") {
            Then("there is nothing to restore") {
                missingDataFiles(emptyMap()) { false } shouldBe emptyList()
            }
        }
    })
