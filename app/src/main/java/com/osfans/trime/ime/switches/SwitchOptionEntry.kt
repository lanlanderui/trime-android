/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import androidx.annotation.DrawableRes
import com.osfans.trime.core.RimeSchema
import com.osfans.trime.daemon.RimeSession

sealed class SwitchOptionEntry(
    val label: String,
    @param:DrawableRes
    val icon: Int,
) {
    class Static(label: String, icon: Int, val type: Type) : SwitchOptionEntry(label, icon) {
        enum class Type {
            SchemaList,
            UpdateConfig,
            Keyboard,
            ThemeList,
        }
    }

    class Custom(
        val switch: RimeSchema.Switch,
        label: String,
        icon: Int,
        /**
         * Index into [RimeSchema.Switch.states] that is currently on, or -1 when no option is on.
         *
         * Only a switch that declares `options` in the schema yaml offers its states as a list, and
         * that list marks this index; a plain on/off switch ignores it.
         */
        val selectedIndex: Int = -1,
    ) : SwitchOptionEntry(label, icon)

    companion object {
        fun fromSwitch(
            rime: RimeSession,
            it: RimeSchema.Switch,
        ): Custom? = buildEntry(it) { name -> rime.run { getRuntimeOption(name) } }

        /**
         * The entry-building rules, expressed over a plain option lookup so they can be tested
         * without a Rime session.
         *
         * A switch that has a `name` is an on/off toggle and needs two states to read as one; a
         * switch without a `name` toggles a group of `options` instead, and then one state per
         * option. Anything else has nothing the menu could show, so it yields null and is skipped.
         */
        internal fun buildEntry(
            it: RimeSchema.Switch,
            isOptionOn: (String) -> Boolean,
        ): Custom? {
            val labels = it.states
            if (labels.size <= 1) return null
            return if (it.name.isNotEmpty()) {
                if (labels.size != 2) return null
                val (disabledText, enabledText) = labels
                val value = isOptionOn(it.name)
                val label = if (value) "$enabledText → $disabledText" else "$disabledText → $enabledText"
                Custom(it, label, 0, if (value) 1 else 0)
            } else {
                val options = it.options
                if (options.size != labels.size) return null
                val index = options.indexOfFirst(isOptionOn)
                val label = labels[if (index >= 0) index else 0]
                Custom(it, label, 0, index)
            }
        }
    }
}
