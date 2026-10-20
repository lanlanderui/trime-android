/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.Context
import com.osfans.trime.util.appContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Colours bound to one physical key, picked by tapping that key on the keyboard itself.
 *
 * This is a layer of its own, above the active colour scheme, rather than more entries in the
 * scheme: a scheme's colour keys are *shared* — a theme binds `key_back_color: bgn` on every
 * letter row, so overriding that name repaints the whole row. "Colour this key" has to mean this
 * key, and the only name unique enough is the key's own position.
 *
 * Every field is null by default and null means "whatever the scheme says", so an untouched key
 * keeps following the theme, and clearing one slot is enough to hand it back.
 */
@Serializable
data class PerKeyColors(
    val back: String? = null,
    val text: String? = null,
    val symbol: String? = null,
    val border: String? = null,
    val pressedBack: String? = null,
    val pressedText: String? = null,
) {
    val isEmpty: Boolean
        get() = back == null && text == null && symbol == null && border == null &&
            pressedBack == null && pressedText == null

    /** This entry with [slot] set to [value], or cleared when [value] is null. */
    fun with(
        slot: PerKeySlot,
        value: String?,
    ): PerKeyColors = when (slot) {
        PerKeySlot.BACK -> copy(back = value)
        PerKeySlot.TEXT -> copy(text = value)
        PerKeySlot.SYMBOL -> copy(symbol = value)
        PerKeySlot.BORDER -> copy(border = value)
        PerKeySlot.PRESSED_BACK -> copy(pressedBack = value)
        PerKeySlot.PRESSED_TEXT -> copy(pressedText = value)
    }

    fun slot(slot: PerKeySlot): String? = when (slot) {
        PerKeySlot.BACK -> back
        PerKeySlot.TEXT -> text
        PerKeySlot.SYMBOL -> symbol
        PerKeySlot.BORDER -> border
        PerKeySlot.PRESSED_BACK -> pressedBack
        PerKeySlot.PRESSED_TEXT -> pressedText
    }
}

/**
 * The six things a single key can be told apart by.
 *
 * Deliberately not the full set of colour keys a theme may bind — the remaining ones (popup,
 * hint, and the `off_key`/`on_key` families) either do not apply to the key under the finger or
 * are reachable through the "other colours" list. Six is what a user can hold in their head.
 */
enum class PerKeySlot {
    BACK,
    TEXT,
    SYMBOL,
    BORDER,
    PRESSED_BACK,
    PRESSED_TEXT,
}

/**
 * Where the per-key colours live: `"<keyboard>#<index>"` to [PerKeyColors].
 *
 * Keyed by index rather than by label because labels are not unique (a dozen keys carry no label
 * at all, and two keys may share one), while the index is the key's position in the layout the
 * user is looking at. That makes the palette follow the layout: editing a theme's key list moves
 * the indices, and the stored colours move with them. Which is the honest trade for a feature
 * that exists to colour *what is on screen* — a name-based key would survive a layout edit and
 * silently paint a different key.
 *
 * Storage is a SharedPreferences entry holding one JSON object. Preferences rather than a file
 * because this is written on every frame of a colour-picker drag: `apply()` updates the in-memory
 * map at once and lets the disk catch up, while a `writeText()` per frame would be a stall with
 * nothing to show for it.
 *
 * Nothing is cached across processes: the settings screen and the IME share one process (see
 * `ThemeManager`), so [cached] is always current. If that ever stops being true, the write has to
 * go through a broadcast — the read path is a plain map lookup and would not notice.
 */
object PerKeyColorStore {
    private const val PREFS_NAME = "per_key_colors"
    private const val KEY_COLORS = "colors_v1"

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val prefs by lazy { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    @Volatile
    private var cached: Map<String, PerKeyColors>? = null

    /** Every key that carries an override, keyed by [storageKey]. */
    fun colors(): Map<String, PerKeyColors> =
        cached ?: synchronized(this) {
            cached ?: load().also { cached = it }
        }

    /**
     * The override for one key, or null when it follows the scheme.
     *
     * A key with an entry whose slots are all null reads as absent: [remove] deletes such an entry,
     * but a hand-edited or half-written file can still carry one, and it means the same thing.
     */
    fun get(
        keyboard: String,
        index: Int,
    ): PerKeyColors? = colors()[storageKey(keyboard, index)]?.takeIf { !it.isEmpty }

    fun put(
        keyboard: String,
        index: Int,
        value: PerKeyColors,
    ) = update(keyboard, listOf(index)) { value }

    /**
     * Rewrites several keys in one pass: [transform] is applied to each key's current entry.
     *
     * One write for a whole multi-selection rather than one per key, because the storage is a
     * single JSON document — N separate writes would serialise the same map N times and flash the
     * preference N times for one gesture.
     *
     * [persist] false keeps the change in memory only. That is what a colour-picker drag uses: the
     * keyboard repaints per frame, but the disk is written once, when the user confirms.
     */
    fun update(
        keyboard: String,
        indices: Collection<Int>,
        persist: Boolean = true,
        transform: (PerKeyColors) -> PerKeyColors,
    ) {
        if (indices.isEmpty()) return
        val next = colors().toMutableMap()
        indices.forEach { index ->
            val key = storageKey(keyboard, index)
            val updated = transform(next[key] ?: PerKeyColors())
            if (updated.isEmpty) next.remove(key) else next[key] = updated
        }
        commit(next, persist)
    }

    /** Drops every override of the given keys. */
    fun clear(
        keyboard: String,
        indices: Collection<Int>,
    ) {
        if (indices.isEmpty()) return
        commit(colors() - indices.map { storageKey(keyboard, it) }.toSet())
    }

    /** Writes the in-memory map to disk, for a change that was made with `persist = false`. */
    fun flush() {
        prefs.edit().putString(KEY_COLORS, json.encodeToString(colors())).apply()
    }

    /**
     * Puts back what [snapshot] held for [indices], and clears the slots it does not carry.
     *
     * The rollback path for a live preview that was cancelled: only the keys the preview touched
     * are rewritten, so an edit made to some other key in the meantime survives.
     */
    fun restore(
        keyboard: String,
        indices: Collection<Int>,
        snapshot: Map<Int, PerKeyColors>,
    ) {
        val next = colors().toMutableMap()
        indices.forEach { index ->
            val key = storageKey(keyboard, index)
            val value = snapshot[index]
            if (value == null || value.isEmpty) next.remove(key) else next[key] = value
        }
        commit(next)
    }

    /** Forgets everything in memory and re-reads the file, so a preview can be rolled back. */
    fun reload() {
        cached = null
    }

    fun remove(
        keyboard: String,
        index: Int,
    ) {
        val key = storageKey(keyboard, index)
        if (!colors().containsKey(key)) return
        commit(colors() - key)
    }

    /** How many keys of [keyboard] carry an override, for the screen's own summary. */
    fun countOf(keyboard: String): Int =
        colors().keys.count { it.startsWith("$keyboard#") }

    fun count(): Int = colors().size

    fun clear() = commit(emptyMap())

    /**
     * Drops every override whose keyboard no longer exists, or whose index is past the end.
     *
     * Called by the editor when it opens on a keyboard: a theme switch changes both the names and
     * the key counts, and an override that can never be painted again is litter that keeps
     * [count] lying to the user. Returns how many were dropped, so the caller can say so.
     */
    fun prune(
        keyboard: String,
        keyCount: Int,
    ): Int {
        val drop = colors().keys.filter { key ->
            val (name, index) = splitKey(key) ?: return@filter true
            name == keyboard && index >= keyCount
        }
        if (drop.isEmpty()) return 0
        commit(colors() - drop.toSet())
        return drop.size
    }

    private fun storageKey(
        keyboard: String,
        index: Int,
    ): String = "$keyboard#$index"

    private fun splitKey(key: String): Pair<String, Int>? {
        val at = key.lastIndexOf('#')
        if (at <= 0) return null
        val index = key.substring(at + 1).toIntOrNull() ?: return null
        return key.substring(0, at) to index
    }

    private fun commit(
        next: Map<String, PerKeyColors>,
        persist: Boolean = true,
    ) {
        cached = next
        if (persist) prefs.edit().putString(KEY_COLORS, json.encodeToString(next)).apply()
    }

    private fun load(): Map<String, PerKeyColors> {
        val raw = prefs.getString(KEY_COLORS, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PerKeyColors>>(raw)
        }.getOrElse {
            // A palette this build cannot read is reported, not thrown away: the file stays put so
            // a downgrade-then-upgrade does not lose the user's work, and the keyboard simply
            // follows the scheme until a write replaces it.
            Timber.w(it, "per-key colours unreadable, ignoring")
            emptyMap()
        }
    }
}
