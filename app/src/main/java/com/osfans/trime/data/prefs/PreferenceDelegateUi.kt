/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data.prefs

import android.content.Context
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.osfans.trime.ui.common.MaterialSwitchPreference
import com.osfans.trime.ui.main.settings.DialogSeekBarPreference
import com.osfans.trime.ui.main.settings.EditTextIntPreference

abstract class PreferenceDelegateUi<T : Preference>(
    val key: String,
    private val enableUiOn: (() -> Boolean)? = null,
) {
    abstract fun createUi(context: Context): T

    fun isEnabled() = enableUiOn?.invoke() ?: true

    class StringLike(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: String,
        @StringRes
        val summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null,
    ) : PreferenceDelegateUi<Preference>(key, enableUiOn) {
        override fun createUi(context: Context) = Preference(context).apply {
            key = this@StringLike.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(defaultValue)
            if (this@StringLike.summary != null) {
                setSummary(this@StringLike.summary)
            }
            setTitle(this@StringLike.title)
        }
    }

    class Switch(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Boolean,
        @StringRes
        val summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null,
    ) : PreferenceDelegateUi<MaterialSwitchPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = MaterialSwitchPreference(context).apply {
            key = this@Switch.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(defaultValue)
            if (this@Switch.summary != null) {
                setSummary(this@Switch.summary)
            }
            setTitle(this@Switch.title)
        }
    }

    class StringList<T : Any>(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: T,
        val serializer: PreferenceDelegate.Serializer<T>,
        val entryValues: List<T>,
        @StringRes
        val entryLabels: List<Int>,
        enableUiOn: (() -> Boolean)? = null,
    ) : PreferenceDelegateUi<ListPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = ListPreference(context).apply {
            key = this@StringList.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            entryValues = this@StringList.entryValues.map { serializer.serialize(it) }.toTypedArray()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setDefaultValue(serializer.serialize(defaultValue))
            setTitle(this@StringList.title)
            entries = this@StringList.entryLabels.map { context.getString(it) }.toTypedArray()
            setDialogTitle(this@StringList.title)
        }
    }

    class UniversalStringList<T : Any>(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: T,
        val entryValues: (() -> List<String>),
        val entryLabels: ((Context) -> List<CharSequence>),
        enableUiOn: (() -> Boolean)? = null,
    ) : PreferenceDelegateUi<ListPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = ListPreference(context).apply {
            key = this@UniversalStringList.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            entryValues = this@UniversalStringList.entryValues().toTypedArray()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setDefaultValue(defaultValue.toString())
            setTitle(this@UniversalStringList.title)
            entries = this@UniversalStringList.entryLabels(context).toTypedArray()
            setDialogTitle(this@UniversalStringList.title)
        }
    }

    class EditText(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: String,
        @StringRes
        val message: Int? = null,
        enableUiOn: (() -> Boolean)? = null,
    ) : PreferenceDelegateUi<EditTextPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = EditTextPreference(context).apply {
            key = this@EditText.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            setDefaultValue(this@EditText.defaultValue)
            setTitle(this@EditText.title)
            setDialogTitle(this@EditText.title)
            if (this@EditText.message != null) {
                setDialogMessage(this@EditText.message)
            }
        }
    }

    /**
     * A colour shown as a plain row.
     *
     * The picker itself belongs to the settings side, so this type only builds the row: the
     * hosting fragment looks it up by [key] and wires the tap and the summary. The value is an
     * ARGB int whose zero stands for "automatic", which is the one meaning every user of this
     * row has agreed on.
     */
    class ColorPick(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        enableUiOn: (() -> Boolean)? = null,
    ) : PreferenceDelegateUi<Preference>(key, enableUiOn) {
        override fun createUi(context: Context) = Preference(context).apply {
            key = this@ColorPick.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(defaultValue)
            setTitle(this@ColorPick.title)
        }
    }

    /**
     * A set of ids edited through a dialog the settings side owns.
     *
     * Same arrangement as [ColorPick]: this type only builds the row, the hosting fragment wires
     * the tap and the summary. The value is the ids joined by commas -- a plain string, so the
     * preference framework's own persistence carries it and a hand-edited file still parses.
     */
    class MultiSelect(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: String,
        @StringRes
        val summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null,
    ) : PreferenceDelegateUi<Preference>(key, enableUiOn) {
        override fun createUi(context: Context) = Preference(context).apply {
            key = this@MultiSelect.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(defaultValue)
            setTitle(this@MultiSelect.title)
            if (this@MultiSelect.summary != null) {
                setSummary(this@MultiSelect.summary)
            }
        }
    }

    class EditTextInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        enableUiOn: (() -> Boolean)? = null,
    ) : PreferenceDelegateUi<EditTextPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = EditTextIntPreference(context).apply {
            key = this@EditTextInt.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = EditTextIntPreference.SimpleSummaryProvider
            setDefaultValue(this@EditTextInt.defaultValue)
            setTitle(this@EditTextInt.title)
            setDialogTitle(this@EditTextInt.title)
            min = this@EditTextInt.min
            max = this@EditTextInt.max
            unit = this@EditTextInt.unit
        }
    }

    class SeekBarInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val step: Int = 1,
        @StringRes
        val defaultLabel: Int? = null,
        val useMinAsDefault: Boolean = false,
        enableUiOn: (() -> Boolean)? = null,
    ) : PreferenceDelegateUi<DialogSeekBarPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = DialogSeekBarPreference(context).apply {
            key = this@SeekBarInt.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
            this@SeekBarInt.defaultLabel?.let { systemDefaultText = context.getString(it) }
            setDefaultValue(this@SeekBarInt.defaultValue)
            setTitle(this@SeekBarInt.title)
            min = this@SeekBarInt.min
            max = this@SeekBarInt.max
            unit = this@SeekBarInt.unit
            step = this@SeekBarInt.step
            useMinAsDefault = this@SeekBarInt.useMinAsDefault
        }
    }
}
