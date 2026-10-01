// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory

class SymbolHistoryTest :
    StringSpec({
        "inserting before opening history preserves persisted entries" {
            val directory = createTempDirectory().toFile()
            try {
                val file = File(directory, SymbolHistory.FILE_NAME)
                file.writeText("old")
                val history = SymbolHistory(180, file)

                history.insert("new")

                history.toOrderedList() shouldBe listOf("new", "old")
            } finally {
                directory.deleteRecursively()
            }
        }
    })
