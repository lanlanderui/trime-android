// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.NinePatch
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.NinePatchDrawable
import androidx.annotation.ColorInt
import androidx.annotation.Keep
import androidx.collection.LruCache
import androidx.core.graphics.drawable.toDrawable
import androidx.core.math.MathUtils
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.util.ColorUtils
import com.osfans.trime.util.NinePatchBitmapFactory
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.appContext
import com.osfans.trime.util.isNightMode
import java.io.File
import timber.log.Timber

/**
 * Global entry point for the colors and drawables of the active theme.
 *
 * The activation state itself lives in the current [ThemeScope], which
 * [attachTheme] replaces on every theme switch; scheme switches update the
 * same scope in place. String-keyed lookups below ([getColor], [getDrawable])
 * resolve through that scope so that keys only a theme can define (per-key
 * keyboard colors, image-valued entries) keep working, while [activeColorScheme]
 * and the scope's [ThemeScope.colors] expose the typed view for UI code.
 */
object ColorManager {
    private var scope: ThemeScope? = null
    private val prefs = ThemeManager.prefs

    private var isNightMode = false

    val activeColorScheme: ColorScheme
        get() = requireNotNull(requireScope().activeColorScheme) { "ColorManager is not initialized" }

    /**
     * Whether the built-in dynamic scheme is the active one.
     *
     * The one scheme that is not the theme's: it paints keys from its own palette by key *kind*
     * (see `KeyPalette`), so a key asks this to know that the colour keys the theme bound to it
     * no longer apply.
     */
    val isDynamicSchemeActive: Boolean
        get() = scope?.activeColorScheme?.id == DynamicColorScheme.ID

    /**
     * Everything the user can pick between: the theme's presets plus the built-in system dynamic
     * scheme.
     *
     * The dynamic scheme is not a preset, so it has to be appended here — that is what keeps it
     * in the list no matter what a theme's `preset_color_schemes` declares. It deliberately sorts
     * last, and a preset that happens to use its reserved id is dropped, so a theme can neither
     * hide it nor shadow it.
     */
    val availableColorSchemes: List<ColorScheme>
        get() = selectableSchemes(requireScope().theme)

    private var bitmapCache: LruCache<String, Bitmap>? = null

    private var generation = 0L

    /**
     * Bumped whenever the active scheme changes. Scheme-dependent caches
     * (e.g. per-key colors in Key) re-resolve when it moves.
     */
    val colorGeneration: Long
        get() = generation

    fun interface OnColorChangeListener {
        fun onColorChange(theme: Theme)
    }

    /**
     * Asks the IME to rebuild its input view from scratch.
     *
     * Repainting in place is the right path for a colour *edit*: it keeps the views, and with them
     * the scroll position and the floating keyboard's placement. But it is silent when there is
     * nothing to repaint — the composed scheme came out identical, so the change notification is
     * never fired — and it does nothing at all while the keyboard is not on screen. For an explicit
     * "make it so" from the settings screen, rebuilding is the honest answer: every colour and every
     * size is read again, whatever the previous attempt did or did not manage.
     */
    fun interface OnRebuildListener {
        fun onRebuildRequested()
    }

    private val onChangeListeners = WeakHashSet<OnColorChangeListener>()

    private val rebuildListeners = WeakHashSet<OnRebuildListener>()

    fun addOnRebuildRequestedListener(listener: OnRebuildListener) {
        rebuildListeners.add(listener)
    }

    fun removeOnRebuildRequestedListener(listener: OnRebuildListener) {
        rebuildListeners.remove(listener)
    }

    /**
     * Rebuilds the keyboard, if one is listening, and reports whether anything was.
     *
     * The count is what a caller needs in order to be honest: the listeners belong to the IME
     * service, which registers them once rime is ready, so "nothing was listening" is a normal
     * state rather than a failure — the next time the keyboard comes up it reads everything afresh.
     * A caller that says "rebuilt" without checking would be claiming something it cannot know.
     *
     * @return how many listeners were notified.
     */
    fun requestRebuild(): Int {
        var notified = 0
        rebuildListeners.forEach {
            it.onRebuildRequested()
            notified++
        }
        return notified
    }

    fun addOnChangedListener(listener: OnColorChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnColorChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onColorChange(requireScope().theme) }
    }

    /**
     * Re-resolves the active scheme when the colour source flips, so switching
     * between the theme's own schemes and the built-in palette lands on a
     * keyboard that is already up.
     */
    @Keep
    private val colorSourceListener =
        PreferenceDelegate.OnChangeListener<ThemePrefs.ColorSource> { _, _ ->
            activateScheme(notify = true)
        }

    fun init(configuration: Configuration) {
        isNightMode = configuration.isNightMode()
        activateScheme(notify = false)
        prefs.colorSource.registerOnChangeListener(colorSourceListener)
        initialized = true

        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        val cacheSize = maxMemory / 8
        bitmapCache =
            object : LruCache<String, Bitmap>(cacheSize.toInt()) {
                override fun sizeOf(
                    key: String,
                    value: Bitmap,
                ): Int = value.byteCount / 1024
            }
    }

    fun onSystemNightModeChange(isNight: Boolean) {
        isNightMode = isNight
        activateScheme(notify = true)
    }

    /** The current theme scope, or null before the first theme is attached. */
    fun currentScope(): ThemeScope? = scope

    @Volatile
    private var initialized = false

    /**
     * Makes sure the colour pipeline is up, for anything that can run before the IME service does.
     *
     * [init] has exactly one caller — the IME service, once rime is ready — so an app launched
     * straight into its own settings, with the keyboard not running, had no night mode and no
     * scheme of its own to activate. The damage was quiet and confusing rather than loud: a palette
     * edit was stored and never painted, and on a dark device the *light* palette was the one
     * applied, because [isNightMode] still held its initial `false`.
     *
     * The scope [init] activates into comes from the theme, so when this runs before any theme has
     * been resolved that one step does nothing and the activation happens later, in
     * [attachTheme] — which is why this must *not* go looking for the theme itself. Doing so would
     * recurse: resolving a theme is what brings the pipeline up.
     */
    fun ensureInitialized(configuration: Configuration) {
        if (initialized) return
        init(configuration)
    }

    /**
     * Attaches a theme, replacing the current scope. Theme switches notify
     * through ThemeManager, so no color listener fires here.
     */
    fun attachTheme(theme: Theme) {
        bitmapCache?.evictAll()
        scope = ThemeScope(theme)
        // Bumped before activating rather than only inside [activateScheme]: the scheme-derived
        // caches are keyed on this, and not every theme change alters the scheme. A size
        // override moves the theme while leaving every colour identical, so [activateScheme] would
        // find its target unchanged and return without a bump — leaving every key holding the
        // drawable and the style value the *previous* theme produced. That is not hypothetical:
        // `shadow_radius` is read through the same cache.
        generation++
        activateScheme(notify = false)
    }

    fun setColorScheme(scheme: ColorScheme) {
        activateScheme(scheme, notify = true)
        prefs.normalModeColor.setValue(scheme.id)
    }

    /**
     * Re-resolves the active scheme after the built-in palette was edited.
     *
     * The palette is read on every resolution (see [withBuiltinColors]), so this only has to make
     * the resolution happen again; the comparison inside [activateScheme] keeps it from repainting
     * when an edit left the composed colours unchanged.
     */
    fun refreshBuiltinColors() {
        activateScheme(notify = true)
    }

    private fun requireScope(): ThemeScope = requireNotNull(scope) { "ColorManager is not initialized" }

    /**
     * The theme's presets with the dynamic scheme appended, in selection order. The dynamic
     * scheme has to be part of this list and not just of the picker: an id is only resolvable
     * against the schemes handed to [ColorSchemeResolver], so leaving it out would silently
     * resolve a persisted [DynamicColorScheme.ID] back to the theme default.
     */
    private fun selectableSchemes(theme: Theme): List<ColorScheme> {
        val presets = theme.colorSchemes
        return ColorSchemeResolver.selectable(
            presets = presets,
            dynamic =
                if (DynamicColorScheme.isSupported) {
                    DynamicColorScheme.create(
                        isNight = isNightMode,
                        // Layered on whatever the theme itself would show for this day/night state,
                        // so colour keys only the theme defines keep a value. A theme declaring no
                        // preset is refused by ThemeLoader, but the picker still asks before one is
                        // attached, and `themeBaseline` insists on a non-empty list.
                        base = presets.takeIf { it.isNotEmpty() }?.let { ColorSchemeResolver.themeBaseline(it, isNightMode) },
                    )
                } else {
                    null
                },
        )
    }

    private fun resolveActiveScheme(theme: Theme): ColorScheme = ColorSchemeResolver.resolve(
        schemes = selectableSchemes(theme),
        selectedSchemeId = prefs.normalModeColor.getValue(),
        followSystemDayNight = prefs.followSystemDayNight.getValue(),
        isNightMode = isNightMode,
    )

    /**
     * Re-resolves the active scheme from the prefs and current theme, or
     * activates the given one. Listener notification fires only when the
     * scheme actually changed.
     */
    private fun activateScheme(
        scheme: ColorScheme? = null,
        notify: Boolean,
    ) {
        val activeScope = scope ?: return
        val target = withBuiltinColors(scheme ?: resolveActiveScheme(activeScope.theme))
        if (activeScope.activeColorScheme == target) {
            // Worth saying out loud. This is the path a settings edit takes when it changes nothing
            // — and a caller that cannot tell "nothing to do" from "done" has no way to explain
            // itself to the user, which is how an empty palette came to look like a broken one.
            Timber.d("Colour scheme unchanged, nothing to notify")
            return
        }
        activeScope.updateScheme(target)
        generation++
        if (notify) fireChange()
    }

    /**
     * Layers the built-in palette over [scheme] when the user picked it as the colour source.
     *
     * The scheme stays the base rather than being replaced — see [composeBuiltinColors] for why
     * that is what keeps a theme's own colour keys (this theme's `bgn`/`bbs`/`benter`/`bkg` and
     * its `*_custom_key_*` family) alive. The id is kept too, so the scheme picker still shows
     * which preset the built-in palette is sitting on.
     *
     * The day/night state comes from the system rather than from [ThemePrefs.followSystemDayNight]:
     * a palette that ships a light and a dark version should track the system even for a user who
     * has not opted into day/night *scheme* linking.
     *
     * A thin adapter over [composeWithPalette], fed from the manager's own state; that function is
     * where the actual rule lives, and it is pure so it can be tested without a scope.
     */
    private fun withBuiltinColors(scheme: ColorScheme): ColorScheme =
        composeWithPalette(
            scheme = scheme,
            palette = BuiltinColorStore.current(),
            isNight = isNightMode,
            colorsInCharge = prefs.colorSource.getValue() == ThemePrefs.ColorSource.BUILTIN,
        )

    private fun backgroundFolder(scope: ThemeScope) = scope.theme.generalStyle.backgroundFolder

    /**
     * Resolves a color key against the given scope. Exposed so UI code can
     * look up keys only a theme can define through an injected scope.
     */
    @ColorInt
    internal fun resolveColor(
        scope: ThemeScope,
        key: String,
    ): Int {
        val tableEntry = ColorKey.from(key)?.let { scope.colorTable?.get(it) }
        if (tableEntry is ColorTable.Value.Color) return tableEntry.argb
        // Keys defined only by a theme resolve through the same chain rules.
        val scheme = requireNotNull(scope.activeColorScheme)
        val raw = ColorTable.resolveRaw(key, scheme.colors, scope.theme.fallbackColors)
        return try {
            if (raw == null) throw IllegalArgumentException("$key not found")
            ColorUtils.parseColor(raw)
        } catch (_: IllegalArgumentException) {
            ColorUtils.parseColor(key)
        }
    }

    /** Resolves a drawable key (color or image asset) against the given scope. */
    internal fun resolveDrawable(
        scope: ThemeScope,
        key: String,
    ): Drawable? {
        val tableEntry = ColorKey.from(key)?.let { scope.colorTable?.get(it) }
        if (tableEntry != null) {
            return when (tableEntry) {
                is ColorTable.Value.Color -> GradientDrawable().apply { setColor(tableEntry.argb) }
                is ColorTable.Value.Image -> imageDrawable(scope, tableEntry.path)
                // The chain found nothing usable for a built-in key, so report "no drawable" and let
                // the caller fall back to a key of its own. Parsing the key *name* as a colour, as
                // this used to, can only fail — it painted a transparent drawable, which is how a key
                // ends up silently invisible instead of picking up a default.
                //
                // Logged here rather than once for the whole lookup: this branch is the one that ends
                // the search without a word, so it is the one that makes a mis-resolved key look
                // identical to a key nobody set. The other two are the ordinary outcomes and are not
                // worth a line each — every theme switch re-resolves every key.
                ColorTable.Value.None -> {
                    Timber.d("resolveDrawable %s -> nothing resolved for a built-in key", key)
                    null
                }
            }
        }
        // Keys defined only by a theme resolve through the same chain rules.
        val scheme = requireNotNull(scope.activeColorScheme)
        val raw = ColorTable.resolveRaw(key, scheme.colors, scope.theme.fallbackColors)
        return parseDrawable(scope, raw ?: key)
    }

    private fun parseDrawable(
        scope: ThemeScope,
        value: String,
    ): Drawable? {
        if (value.isEmpty()) return null
        if (ColorTable.isImageValue(value)) return imageDrawable(scope, value)
        val color = runCatching { ColorUtils.parseColor(value) }.getOrDefault(Color.TRANSPARENT)
        return GradientDrawable().apply { setColor(color) }
    }

    private fun imageDrawable(
        scope: ThemeScope,
        value: String,
    ): Drawable? {
        val path = resolveImageFilePath(scope, value)
        val bitmap =
            bitmapCache?.get(path)
                ?: BitmapFactory.decodeFile(path)?.also {
                    bitmapCache?.put(path, it)
                    // Logged on a real decode only. A cache hit is the ordinary case on every
                    // re-resolve, and saying "loaded" for a bitmap that was already in memory would
                    // bury the one line that means the file was actually read off disk.
                    Timber.d("image decoded: %s -> %s (%dx%d)", value, path, it.width, it.height)
                }
        if (bitmap == null) {
            // A missing or undecodable image is the single most likely reason a themed background
            // "does nothing", and it fails completely silently -- the caller just gets null and
            // draws nothing. Log the path that was actually tried, so the two common causes (file
            // never pushed, file pushed to the wrong place) are distinguishable from a decode
            // failure on an existing file.
            Timber.w("image not loadable: %s -> %s (exists=%s)", value, path, File(path).exists())
            return null
        }
        if (path.endsWith(".9.png")) {
            val chunk = bitmap.ninePatchChunk
            return if (NinePatch.isNinePatchChunk(chunk)) {
                // for compiled nine patch image
                NinePatchDrawable(Resources.getSystem(), bitmap, chunk, Rect(), null)
            } else {
                // for source nine patch image
                NinePatchBitmapFactory.createNinePatchDrawable(Resources.getSystem(), bitmap)
            }
        }
        return bitmap.toDrawable(Resources.getSystem())
    }

    /**
     * Where a themed background image lives.
     *
     * `background_folder` names a folder *inside* `backgrounds/`, so a per-scheme theme puts its
     * images in `backgrounds/<folder>/`. But its default value is itself `"backgrounds"`, which is
     * the classic upstream default — so the literal concatenation produces
     * `backgrounds/backgrounds/<file>`, a path that never exists for anyone who did not go looking
     * for it and then create it. The flat `backgrounds/<file>` is tried first because that is where
     * the file actually is in practice, and a theme only has to create the nested folder to get the
     * per-scheme behaviour it was asking for.
     *
     * Returning a path that does not exist is fine: the caller reports it and draws nothing, which
     * is the honest outcome for a background that was never deployed.
     */
    private fun resolveImageFilePath(
        scope: ThemeScope,
        value: String,
    ): String {
        val root = DataManager.userDataDir
        val folder = backgroundFolder(scope)
        val flat = root.resolve("backgrounds/$value")
        if (flat.exists()) return flat.absolutePath
        // Skip the redundant segment when the folder *is* "backgrounds", so the log names the path
        // a reader would actually try rather than the doubled-up one.
        if (folder != "backgrounds") {
            val nested = root.resolve("backgrounds/$folder/$value")
            if (nested.exists()) return nested.absolutePath
        }
        return flat.absolutePath
    }

    @ColorInt
    fun getColor(key: String): Int = resolveColor(requireScope(), key)

    fun getDrawable(key: String): Drawable? = resolveDrawable(requireScope(), key)

    internal fun resolveDecorDrawable(
        scope: ThemeScope,
        colorKey: String,
        borderColorKey: String?,
        borderPx: Int,
        cornerRadius: Float,
        alpha: Int,
    ): Drawable? = when (val drawable = resolveDrawable(scope, colorKey)) {
        is GradientDrawable ->
            drawable.also {
                it.cornerRadius = cornerRadius
                it.alpha = MathUtils.clamp(alpha, 0, 255)
                if (!borderColorKey.isNullOrEmpty()) {
                    try {
                        val borderColor = resolveColor(scope, borderColorKey)
                        it.setStroke(borderPx, borderColor)
                    } catch (_: Exception) {
                    }
                }
            }

        else -> drawable?.also { it.alpha = MathUtils.clamp(alpha, 0, 255) }
    }

    fun getDecorDrawable(
        colorKey: String,
        borderColorKey: String? = null,
        borderPx: Int = 0,
        cornerRadius: Float = 0f,
        alpha: Int = 255,
    ): Drawable? = resolveDecorDrawable(requireScope(), colorKey, borderColorKey, borderPx, cornerRadius, alpha)
}
