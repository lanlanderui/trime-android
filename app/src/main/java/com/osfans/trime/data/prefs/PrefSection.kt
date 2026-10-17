/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.prefs

import androidx.annotation.StringRes

/**
 * One titled block of rows on a settings page.
 *
 * The page a row lives on used to be decided by which class declared it: a
 * [PreferenceDelegateOwner] was rendered top to bottom in field order, so
 * "where does this row go" and "where is this value stored" were the same
 * question. Sections break that link — a page names the keys it shows, in the
 * order it wants them, and the owner keeps owning them.
 *
 * [keys] are [PreferenceDelegateUi.key]s of the provider the section belongs to.
 * An empty list claims whatever the provider has left, which is what a
 * single-provider page uses when it wants one untitled block.
 *
 * [hidden] claims the keys without drawing anything: rows that are storage for
 * a switch drawn elsewhere. They must still be claimed, or the renderer treats
 * them as forgotten and appends them at the end of the page.
 */
data class PrefSection(
    @StringRes val title: Int = 0,
    val keys: List<String> = emptyList(),
    val hidden: Boolean = false,
)

/**
 * A provider and the sections of it this page shows.
 *
 * A page may stack several of these — that is how a row can move to a page
 * owned by another object without moving between classes, and therefore
 * without touching a single runtime read of the preference.
 */
data class ProviderSections(
    val provider: PreferenceDelegateProvider,
    val sections: List<PrefSection> = emptyList(),
    /**
     * Whether rows the sections do not name belong to this page.
     *
     * A page that owns a provider is rendered whole: a row nobody names is a row somebody forgot,
     * and it is appended where it can be seen in review. A page that *borrows* a slice of another
     * provider — the candidate page showing one keyboard row — must hand the rest back, or the
     * borrowed provider's entire page follows it.
     */
    val includeUnlisted: Boolean = true,
)
