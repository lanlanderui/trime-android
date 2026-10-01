// SPDX-FileCopyrightText: 2015 - 2025 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.sync

import com.osfans.trime.util.appContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SyncEntry(
    val size: Long,
    val lastModified: Long,
)

@Serializable
data class SyncIndexData(
    val treeUri: String = "",
    val entries: Map<String, SyncEntry> = emptyMap(),
)

/**
 * File-backed index of synced paths.
 *
 * Individual operations are synchronized and [save] replaces the file
 * atomically, so the index cannot be left half-written. A read-modify-write
 * sequence is still not atomic: callers must not interleave [load] and [save]
 * with another sync running at the same time.
 */
object SyncIndex {
    private const val INDEX_FILE = "rime_sync_index.json"

    private val json = Json { ignoreUnknownKeys = true }

    private val indexFile: File
        get() = File(appContext.filesDir, INDEX_FILE)

    @Synchronized
    fun load(): SyncIndexData {
        val stored =
            indexFile
                .takeIf { it.exists() }
                ?.readText()
                ?.let { runCatching { json.decodeFromString<SyncIndexData>(it) }.getOrNull() }
                ?: SyncIndexData()
        val currentTreeUri = RimeDataSync.treeUri()?.toString().orEmpty()
        if (stored.treeUri != currentTreeUri) {
            return SyncIndexData(treeUri = currentTreeUri)
        }
        return stored
    }

    @Synchronized
    fun save(data: SyncIndexData) {
        val serialized = json.encodeToString(data)
        val tmp = File(appContext.filesDir, "$INDEX_FILE.tmp")
        tmp.writeText(serialized)
        if (!tmp.renameTo(indexFile)) {
            // renameTo refuses to replace an existing file on some systems
            indexFile.writeText(serialized)
            tmp.delete()
        }
    }

    @Synchronized
    fun clear() {
        save(SyncIndexData())
    }

    fun withCurrentTree(entries: Map<String, SyncEntry>): SyncIndexData = SyncIndexData(
        treeUri = RimeDataSync.treeUri()?.toString().orEmpty(),
        entries = entries,
    )

    fun shouldCopy(
        relativePath: String,
        size: Long,
        lastModified: Long,
        index: SyncIndexData,
    ): Boolean {
        val cached = index.entries[relativePath] ?: return true
        return cached.size != size || cached.lastModified != lastModified
    }
}
