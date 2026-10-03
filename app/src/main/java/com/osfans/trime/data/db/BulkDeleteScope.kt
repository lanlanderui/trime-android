/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

/**
 * What a bulk delete is allowed to remove.
 *
 * This exists as a type rather than a `Boolean` because both helpers used to take a flag whose
 * *name* meant the opposite of its *behaviour* -- `ClipboardHelper.deleteAll(skipUnpinned = true)`
 * deleted the unpinned ones -- and the caller passed `haveUnpinned()` into it. With every entry
 * pinned that flag was false, so the table was wiped and the pinned entries, the ones the user had
 * deliberately kept, were destroyed along with it.
 *
 * A pin is a statement that an entry should survive, so [KEEP_PINNED] is the only sensible default
 * and reaching [ALL] takes naming that says so out loud.
 */
enum class BulkDeleteScope {
    /** Remove only unpinned entries; pinned ones are kept. This is the default. */
    KEEP_PINNED,

    /** Remove every entry, pinned or not. */
    ALL,
    ;

    /** Whether this scope wipes the pinned entries too. */
    val removesPinned: Boolean
        get() = this == ALL
}
