/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.res.Configuration
import androidx.annotation.Keep
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object ThemeManager {
    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    fun getAllThemes(): List<ThemeItem> {
        val sharedThemes = ThemeFilesManager.listThemes(DataManager.sharedDataDir)
        val userThemes = ThemeFilesManager.listThemes(DataManager.userDataDir)
        return sharedThemes + userThemes
    }

    private lateinit var _activeTheme: Theme

    /**
     * The theme as decoded, with no size override patched in.
     *
     * Kept so the overrides can be re-derived without reading the file again, and so that clearing
     * one restores what the theme declares — patching an already-patched theme would leave the old
     * value behind.
     */
    private var _baseTheme: Theme? = null

    private var _activeFindings: List<ThemeDiagnostics.Finding>? = null

    /**
     * What static checks found in the active theme, or null when it was read
     * from its deployed artifact and never checked. Serves the diagnostics
     * screen: the findings belong to the load that produced the active theme.
     */
    val activeFindings: List<ThemeDiagnostics.Finding>?
        get() {
            ensureActiveTheme()
            return _activeFindings
        }

    private fun ensureActiveTheme() {
        if (!::_activeTheme.isInitialized) {
            _activeTheme = evaluateActiveTheme()
        }
    }

    var activeTheme: Theme
        get() {
            ensureActiveTheme()
            return _activeTheme
        }
        private set(value) {
            if (::_activeTheme.isInitialized && _activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onThemeChange(_activeTheme) }
    }

    val prefs = AppPrefs.defaultInstance().registerProvider(::ThemePrefs)

    /**
     * Whether the built-in size overrides are in charge — the size-side mirror of the colour
     * source in `ColorManager`. Read at patch time rather than cached, so a source flip and the
     * theme it produces can never disagree about which source is current.
     */
    private val styleOverridesInCharge: Boolean
        get() = prefs.styleSource.getValue() == ThemePrefs.StyleSource.BUILTIN

    /**
     * Whether the built-in layout overrides are in charge — the second gate next to the sizes',
     * read the same way and at the same moment.
     */
    private val layoutOverridesInCharge: Boolean
        get() = prefs.layoutSource.getValue() == ThemePrefs.LayoutSource.BUILTIN

    private var builtinSourceListenersAttached = false

    /**
     * The one reaction a built-in source flip needs in order to take effect live — one per
     * domain, both doing the same thing, because a flip only changes which override wins.
     *
     * Registered lazily from [applyTheme], the call every entry point already makes before an
     * override can be shown or read — the IME's own start-up, and the settings screens' theme
     * evaluation. That is early enough for any surface that can flip a source, and idempotent,
     * which matters because [applyTheme] runs on every theme switch.
     */
    @Keep
    private val styleSourceListener =
        PreferenceDelegate.OnChangeListener<ThemePrefs.StyleSource> { _, _ ->
            refreshBuiltinOverrides()
        }

    @Keep
    private val layoutSourceListener =
        PreferenceDelegate.OnChangeListener<ThemePrefs.LayoutSource> { _, _ ->
            refreshBuiltinOverrides()
        }

    private fun ensureBuiltinSourceListeners() {
        if (builtinSourceListenersAttached) return
        builtinSourceListenersAttached = true
        prefs.styleSource.registerOnChangeListener(styleSourceListener)
        prefs.layoutSource.registerOnChangeListener(layoutSourceListener)
    }

    private data class ResolvedTheme(
        val configId: String,
        val theme: Theme,
        val findings: List<ThemeDiagnostics.Finding>?,
    )

    private fun getThemeById(id: String): ResolvedTheme {
        when (val result = ThemeLoader.loadTheme(id)) {
            is ThemeLoader.ThemeLoadResult.Success -> return ResolvedTheme(id, result.theme, result.findings)
            is ThemeLoader.ThemeLoadResult.Failure -> Timber.w(result.error)
        }

        if (id != "trime") {
            when (val result = ThemeLoader.loadTheme("trime")) {
                is ThemeLoader.ThemeLoadResult.Success -> {
                    Timber.w("Theme '$id' is unavailable, fallback to default theme 'trime'")
                    return ResolvedTheme("trime", result.theme, result.findings)
                }

                is ThemeLoader.ThemeLoadResult.Failure -> Timber.w(result.error)
            }
        }

        var lastFailure: ThemeLoader.ThemeLoadError? = null
        for (fallbackId in getAllThemes().map { it.configId }.distinct()) {
            when (val result = ThemeLoader.loadTheme(fallbackId)) {
                is ThemeLoader.ThemeLoadResult.Success -> {
                    Timber.w("Theme '$id' is unavailable, fallback to available theme '$fallbackId'")
                    return ResolvedTheme(fallbackId, result.theme, result.findings)
                }

                is ThemeLoader.ThemeLoadResult.Failure -> lastFailure = result.error
            }
        }

        Timber.w(lastFailure, "No valid theme available")
        error("No valid theme available")
    }

    private fun evaluateActiveTheme(): Theme {
        val selectedThemeId = prefs.selectedTheme.getValue()
        val resolvedTheme = getThemeById(selectedThemeId)
        if (resolvedTheme.configId != selectedThemeId) {
            prefs.selectedTheme.setValue(resolvedTheme.configId)
        }
        // The patched theme is what `applyTheme` installs, and what it hands back. Returning the
        // raw decoded one instead would leave whoever caches this holding a theme the size
        // overrides never reached — which is exactly what `ensureActiveTheme` does with it.
        return applyTheme(resolvedTheme)
    }

    private fun applyTheme(resolvedTheme: ResolvedTheme): Theme {
        val theme = resolvedTheme.theme
        _baseTheme = theme
        // Which screen resolves a theme first is not the IME's business: opening the app's own
        // settings can do it before the keyboard has ever run. Bringing the colour pipeline up here
        // means every entry point gets a correct day/night state, rather than only the one that
        // happens to go through the service.
        ColorManager.ensureInitialized(appContext.resources.configuration)
        ensureBuiltinSourceListeners()
        val patched =
            BuiltinLayoutStore.applyTo(
                BuiltinStyleStore.applyTo(theme, styleOverridesInCharge),
                layoutOverridesInCharge,
            )
        // The findings describe the file this load read; they are not tied to the
        // views, so they are refreshed even when the theme itself is unchanged.
        _activeFindings = resolvedTheme.findings
        activatePatchedTheme(patched)
        return patched
    }

    /**
     * Swaps in a theme derived from [_baseTheme] plus the current size overrides.
     *
     * Every consumer of the theme has to be moved over together — the caches, the colour scope,
     * the liquid-keyboard data — because each of them holds its own copy. Updating only
     * [activeTheme] is what made an explicit "apply" look broken: the keyboard was rebuilt, but
     * from the *previous* scope, so a size override only appeared once something else forced a
     * full theme reload.
     */
    private fun activatePatchedTheme(patched: Theme) {
        // A structurally equal theme suppresses the change notification below, so the
        // UI tree keeps its views and their injected scope. Replace neither the
        // caches nor the scope in that case, or later scheme changes would update
        // the new global scope while existing views still read the old one.
        if (::_activeTheme.isInitialized && _activeTheme == patched) return
        KeyActionManager.resetCache()
        FontManager.resetCache(patched)
        ColorManager.attachTheme(patched)
        LiquidData.init(patched)
        activeTheme = patched
    }

    /**
     * Re-derives the active theme after a built-in override changed, whichever domain it was.
     *
     * Derived from the decoded theme held in memory rather than by loading the file again: the
     * settings screen calls this while a slider is being dragged, and decoding a theme is disk IO
     * that has no business on the main thread.
     */
    fun refreshBuiltinOverrides() {
        ensureActiveTheme()
        val base = _baseTheme ?: return
        // Through the same door as a theme load, not around it. Setting `activeTheme` on its own
        // left the colour scope holding the pre-patch theme, so a rebuilt keyboard read the old
        // sizes back out of it and the change only appeared after an unrelated theme switch.
        activatePatchedTheme(
            BuiltinLayoutStore.applyTo(
                BuiltinStyleStore.applyTo(base, styleOverridesInCharge),
                layoutOverridesInCharge,
            ),
        )
    }

    /**
     * The active theme's style as the *theme* declares it, with no size override patched in.
     *
     * The settings screen parks a slider at this value, so that clearing an override returns the
     * slider to what the theme wrote rather than to a default this build invented.
     */
    val baseGeneralStyle: com.osfans.trime.data.theme.model.GeneralStyle
        get() {
            ensureActiveTheme()
            return (_baseTheme ?: _activeTheme).generalStyle
        }

    fun init(configuration: Configuration) {
        ensureActiveTheme()
        ColorManager.init(configuration)
    }

    /**
     * Switches to theme [configId], falling back when it is unavailable.
     * Loading runs on [Dispatchers.IO]; state changes and listener callbacks run on the main thread.
     * @return the config id actually in effect; differs from [configId] when a fallback was used.
     */
    suspend fun selectTheme(configId: String): String {
        val resolvedTheme = withContext(Dispatchers.IO) { getThemeById(configId) }
        return withContext(Dispatchers.Main.immediate) {
            applyTheme(resolvedTheme)
            prefs.selectedTheme.setValue(resolvedTheme.configId)
            resolvedTheme.configId
        }
    }
}
