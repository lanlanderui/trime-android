// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.provider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.FileNotFoundException
import io.kotest.assertions.throwables.shouldThrow
import kotlin.io.path.createTempDirectory

class DocumentsBaseDirTest :
    StringSpec({
        "null external files dir stays unresolved" {
            RimeDataProvider.resolveDocumentsRoot(null).shouldBeNull()
        }

        "writable external files dir yields a documents root" {
            val parent = createTempDirectory().toFile()
            try {
                val external = File(parent, "files").also { it.mkdirs() }
                val resolved = RimeDataProvider.resolveDocumentsRoot(external)
                resolved.shouldNotBeNull()
                resolved.first.canonicalFile shouldBe external.canonicalFile
                resolved.second shouldBe "${external.parent}${File.separator}"
            } finally {
                parent.deleteRecursively()
            }
        }

        "later real parent succeeds after a null attempt" {
            RimeDataProvider.resolveDocumentsRoot(null).shouldBeNull()
            val parent = createTempDirectory().toFile()
            try {
                val external = File(parent, "files").also { it.mkdirs() }
                RimeDataProvider.resolveDocumentsRoot(external).shouldNotBeNull()
            } finally {
                parent.deleteRecursively()
            }
        }

        "document IDs and child names cannot leave the published root" {
            val parent = createTempDirectory().toFile()
            try {
                val root = File(parent, "files").also { it.mkdirs() }
                RimeDataProvider.resolveDocument(root, "files") shouldBe root
                RimeDataProvider.resolveDocument(root, "files/theme.yaml") shouldBe File(root, "theme.yaml")
                shouldThrow<FileNotFoundException> { RimeDataProvider.resolveDocument(root, "files/../cache") }
                shouldThrow<FileNotFoundException> { RimeDataProvider.resolveDocument(root, "files-other/theme.yaml") }
                shouldThrow<FileNotFoundException> { RimeDataProvider.resolveDocument(root, "/files/theme.yaml") }
                shouldThrow<FileNotFoundException> { RimeDataProvider.resolveChild(root, "../cache", root) }
                shouldThrow<FileNotFoundException> { RimeDataProvider.resolveChild(root, "..", root) }
            } finally {
                parent.deleteRecursively()
            }
        }
    })
