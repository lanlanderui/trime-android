// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.base

sealed interface DataDiff {
    val path: String

    val ordinal: Int

    data class CreateFile(
        override val path: String,
    ) : DataDiff {
        override val ordinal: Int
            get() = 3
    }

    data class UpdateFile(
        override val path: String,
    ) : DataDiff {
        override val ordinal: Int
            get() = 2
    }

    data class DeleteDir(
        override val path: String,
    ) : DataDiff {
        override val ordinal: Int
            get() = 1
    }

    data class DeleteFile(
        override val path: String,
    ) : DataDiff {
        override val ordinal: Int
            get() = 0
    }

    companion object {
        fun diff(
            old: DataChecksums,
            new: DataChecksums,
        ): List<DataDiff> {
            if (old.sha256 == new.sha256) return emptyList()
            return new.files
                .mapNotNull { (path, sha256) ->
                    when {
                        path !in old.files && sha256.isNotBlank() -> CreateFile(path)

                        old.files[path] != sha256 ->
                            if (sha256.isNotBlank()) UpdateFile(path) else null

                        else -> null
                    }
                }.toMutableList()
                .apply {
                    addAll(
                        old.files
                            .filterKeys { it !in new.files }
                            .map { (path, sha256) ->
                                if (sha256.isNotBlank()) DeleteFile(path) else DeleteDir(path)
                            },
                    )
                }
        }
    }
}

/**
 * The bundled files that are not on disk, whatever the index says.
 *
 * [DataDiff.diff] answers "did the bundle change since the copy last ran", which is not the same
 * question as "is the copy still there": when the two indexes agree it returns nothing at all, so
 * a shared directory emptied from the outside — by a storage cleaner, by the user clearing app
 * data, by anything that touches `Android/data/<pkg>/files` — matches the index and is never
 * repaired. The symptom is quiet and confusing rather than loud: the bundled themes disappear from
 * the theme picker, because the picker lists `*trime.yaml` out of the shared directory.
 *
 * [exists] takes the entry's path as the index spells it (`shared/trime.yaml`) and is injected so
 * this stays a pure decision.
 */
internal fun missingDataFiles(
    files: Map<String, String>,
    exists: (String) -> Boolean,
): List<String> = files.filter { (path, sha256) -> sha256.isNotBlank() && !exists(path) }.keys.toList()
