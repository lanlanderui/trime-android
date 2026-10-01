// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data

import com.osfans.trime.util.appContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors

class SymbolHistory(
    val capacity: Int,
    private val file: File = appContext.filesDir.resolve(FILE_NAME),
) : LinkedHashMap<String, String>(0, .75f, true) {
    companion object {
        const val FILE_NAME = "symbol_history"

        /** Serializes writes: the history is saved on every symbol tap. */
        private val writer =
            Executors.newSingleThreadExecutor { Thread(it, "symbol-history") }
    }

    @Volatile
    private var loaded = false

    /**
     * Reads the persisted history into this map, at most once.
     *
     * Re-reading on every visit to the history tab also reset the
     * most-recently-used ordering that this map maintains ([accessOrder] is
     * true), so the list would come back in file order instead.
     */
    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        if (!file.exists()) {
            loaded = true
            return true
        }
        val lines = runCatching { file.readLines() }.getOrElse {
            Timber.w(it, "Failed to read $FILE_NAME")
            return false
        }
        lines.forEach {
            if (it.isNotBlank()) put(it, it)
        }
        loaded = true
        return true
    }

    /**
     * Persists the history off the main thread, replacing the file atomically so
     * an interrupted write cannot leave it truncated.
     */
    fun save() {
        if (!load()) return
        val snapshot = values.joinToString("\n")
        writer.execute {
            runCatching {
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(snapshot)
                if (!tmp.renameTo(file)) {
                    // Keep the old file intact if this filesystem cannot replace it.
                    error("Failed to replace $FILE_NAME atomically")
                }
            }.onFailure { Timber.w(it, "Failed to persist $FILE_NAME") }
        }
    }

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity

    fun insert(s: String) {
        if (load()) put(s, s)
    }

    fun toOrderedList() = values.toList().reversed()
}
