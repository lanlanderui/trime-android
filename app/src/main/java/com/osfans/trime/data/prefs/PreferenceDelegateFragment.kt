/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data.prefs

import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.annotation.Keep
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import kotlinx.coroutines.launch

/**
 * A settings page built out of one or more providers.
 *
 * A page used to be exactly one [PreferenceDelegateOwner], which is why moving a row to another
 * page meant moving a field between classes — and touching every runtime read of it. With
 * [ProviderSections] a page can show a slice of any provider, so the page structure is a UI
 * decision again: the value stays where it is, and only the row moves.
 *
 * Several blocks are stacked in order; each block renders its own sections.
 */
abstract class PreferenceDelegateFragment(
    private val blocks: List<ProviderSections>,
) : PaddingPreferenceFragment() {
    constructor(provider: PreferenceDelegateProvider) : this(listOf(ProviderSections(provider)))

    constructor(block: ProviderSections) : this(listOf(block))

    private val visibility = mutableMapOf<String, Boolean>()

    // it would be better to declare the dependency relationship, rather than reevaluating on each value changed
    @Keep
    private val onValueChangeListener = PreferenceDelegateProvider.OnChangeListener {
        evaluateVisibility()
    }

    init {
        blocks.forEach { it.provider.registerOnChangeListener(onValueChangeListener) }
    }

    open fun onPreferenceUiCreated(screen: PreferenceScreen) {}

    @CallSuper
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        evaluateVisibility()
        preferenceScreen =
            preferenceManager.createPreferenceScreen(preferenceManager.context).also { screen ->
                blocks.forEach { it.provider.createUi(screen, it.sections, it.includeUnlisted) }
                onPreferenceUiCreated(screen)
            }
    }

    fun evaluateVisibility() {
        val changed = mutableMapOf<String, Boolean>()
        blocks.forEach { block ->
            block.provider.preferenceDelegatesUi.forEach { ui ->
                val old = visibility[ui.key]
                val new = ui.isEnabled()
                if (old != null && old != new) {
                    changed[ui.key] = new
                }
                visibility[ui.key] = new
            }
        }
        if (changed.isNotEmpty()) {
            lifecycleScope.launch {
                changed.forEach { (key, enable) ->
                    findPreference<Preference>(key)?.isEnabled = enable
                }
            }
        }
    }

    override fun onDestroy() {
        blocks.forEach { it.provider.unregisterOnChangeListener(onValueChangeListener) }
        super.onDestroy()
    }
}
