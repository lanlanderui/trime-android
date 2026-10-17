/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data.prefs

import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import com.osfans.trime.util.WeakHashSet

abstract class PreferenceDelegateProvider {
    private val _preferenceDelegates: MutableMap<String, PreferenceDelegate<*>> = mutableMapOf()

    private val _preferenceDelegatesUi: MutableList<PreferenceDelegateUi<*>> = mutableListOf()

    val preferenceDelegates: Map<String, PreferenceDelegate<*>>
        get() = _preferenceDelegates

    val preferenceDelegatesUi: List<PreferenceDelegateUi<*>>
        get() = _preferenceDelegatesUi

    open fun createUi(screen: PreferenceScreen) {
        createUi(screen, emptyList())
    }

    /**
     * Renders this provider's rows under [sections], in the order the sections are given.
     *
     * A section with a title opens a [PreferenceCategory] — the settings list has always been
     * flat, and a page long enough to need sections was the one thing a user could not skim.
     * Keys no section names are appended at the end rather than dropped: a page that forgets a
     * row should look wrong in review, not lose the setting silently.
     */
    open fun createUi(
        screen: PreferenceScreen,
        sections: List<PrefSection>,
        includeUnlisted: Boolean = true,
    ) {
        val ctx = screen.context
        val pending = _preferenceDelegatesUi.toMutableList()
        sections.forEach { section ->
            val rows =
                if (section.keys.isEmpty()) {
                    pending.toList()
                } else {
                    pending.filter { it.key in section.keys }
                }
            pending.removeAll(rows)
            if (section.hidden) return@forEach
            val parent: PreferenceGroup =
                if (section.title != 0) {
                    PreferenceCategory(ctx).apply {
                        isIconSpaceReserved = false
                        setTitle(section.title)
                    }.also { screen.addPreference(it) }
                } else {
                    screen
                }
            rows.forEach {
                parent.addPreference(it.createUi(ctx).apply { isEnabled = it.isEnabled() })
            }
        }
        if (includeUnlisted) {
            pending.forEach {
                screen.addPreference(it.createUi(ctx).apply { isEnabled = it.isEnabled() })
            }
        }
    }

    fun interface OnChangeListener {
        fun onChange(key: String)
    }

    private val onChangeListeners = WeakHashSet<OnChangeListener>()

    fun registerOnChangeListener(listener: OnChangeListener) {
        onChangeListeners.add(listener)
    }

    fun unregisterOnChangeListener(listener: OnChangeListener) {
        onChangeListeners.remove(listener)
    }

    fun notifyChange(key: String) {
        val preference = _preferenceDelegates[key] ?: return
        onChangeListeners.forEach { it.onChange(key) }
        preference.notifyChange()
    }

    fun PreferenceDelegateUi<*>.registerUi() {
        _preferenceDelegatesUi.add(this)
    }

    fun PreferenceDelegate<*>.register() {
        _preferenceDelegates[key] = this
    }
}
