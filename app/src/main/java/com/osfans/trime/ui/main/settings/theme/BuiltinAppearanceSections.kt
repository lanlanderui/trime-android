/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.osfans.trime.R
import com.osfans.trime.data.theme.BuiltinColorScheme
import com.osfans.trime.data.theme.BuiltinColorStore
import com.osfans.trime.data.theme.BuiltinLayout
import com.osfans.trime.data.theme.BuiltinLayoutStore
import com.osfans.trime.data.theme.BuiltinStyle
import com.osfans.trime.data.theme.BuiltinStyleStore
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ColorSemantic
import com.osfans.trime.data.theme.LayoutSemantic
import com.osfans.trime.data.theme.StyleSemantic
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePrefs
import com.osfans.trime.data.theme.valueOf
import com.osfans.trime.util.ColorUtils
import com.osfans.trime.util.isNightMode
import splitties.dimensions.dp
import kotlin.math.roundToInt

/**
 * The built-in appearance settings, as three lists that a screen can put in three tabs.
 *
 * This used to be a screen of its own, with a hand-drawn keyboard thumbnail on top. The thumbnail
 * was the weak part: it guessed at rows, scaled corners by an invented factor, and could not show
 * a key the user was actually looking at. The colouring screen next door already draws the real
 * keyboard — the IME's own [com.osfans.trime.ime.keyboard.Keyboard] — so the settings moved here
 * and the thumbnail went away.
 *
 * The split is deliberate: this class owns the *values* (the palette, the sizes, the layout) and
 * builds the rows that edit them. It owns neither the keyboard nor the tab strip — it only reports
 * that a colour moved (repaint) or that a size moved (rebuild, because the geometry changed).
 */
internal class BuiltinAppearanceSections(
    private val host: AppCompatActivity,
    private val mutedColor: Int,
    /** A colour changed: the keyboard repaints, but nothing about it moved. */
    private val onColorsChanged: () -> Unit,
    /** A size or layout value changed: the keyboard has to be built again. */
    private val onGeometryChanged: () -> Unit,
) {
    var scheme: BuiltinColorScheme = BuiltinColorStore.current()
        private set

    var style: BuiltinStyle = BuiltinStyleStore.current()
        private set

    var layout: BuiltinLayout = BuiltinLayoutStore.current()
        private set

    /** Which half of the palette is being edited. Follows the system until the user flips it. */
    var isNight: Boolean = host.resources.configuration.isNightMode()
        private set

    private var colorsContainer: LinearLayout? = null
    private var sizesContainer: LinearLayout? = null
    private var layoutContainer: LinearLayout? = null

    /** How many pages of the full override list have been asked for; see [overrideWindow]. */
    private var overridePagesShown = 0
    private var showAllForeignKeys = false

    private val refreshers = mutableListOf<() -> Unit>()

    /**
     * Import, export and the three resets, plus the palette library.
     *
     * Both were written against `AppCompatActivity` already, so they move across untouched — the
     * only thing that changes is who calls `rebuild()`.
     */
    val actions: BuiltinAppearanceActions =
        BuiltinAppearanceActions(
            host = host,
            currentScheme = { scheme },
            currentStyle = { style },
            currentLayout = { layout },
            applyScheme = { scheme = it },
            applyStyle = { style = it },
            applyLayout = { layout = it },
            rebuild = { rebuildAll() },
        )

    val palettes: BuiltinPaletteManager =
        BuiltinPaletteManager(
            host = host,
            mutedColor = mutedColor,
            onPaletteChanged = { scheme = it },
            rebuild = { rebuildAll() },
        )

    // ---------------------------------------------------------------- tabs

    fun fillColors(container: LinearLayout) {
        colorsContainer = container
        buildColors(container)
    }

    fun fillSizes(container: LinearLayout) {
        sizesContainer = container
        buildSizes(container)
    }

    fun fillLayouts(container: LinearLayout) {
        layoutContainer = container
        buildLayouts(container)
    }

    /** Redraws every tab that has been built. Used after an import or a reset. */
    fun rebuildAll() {
        colorsContainer?.let { buildColors(it) }
        sizesContainer?.let { buildSizes(it) }
        layoutContainer?.let { buildLayouts(it) }
    }

    private fun buildColors(container: LinearLayout) {
        refreshers.clear()
        container.removeAllViews()
        addModeRow(container)
        addStatusLine(container)
        addSourceHeader(
            container = container,
            titleRes = R.string.color_source,
            inCharge = colorInCharge(),
            builtinLabelRes = R.string.color_source_builtin,
            themeLabelRes = R.string.color_source_scheme,
        ) { builtin ->
            ThemeManager.prefs.colorSource.setValue(
                if (builtin) ThemePrefs.ColorSource.BUILTIN else ThemePrefs.ColorSource.SCHEME,
            )
        }
        if (!colorInCharge()) addNote(container, host.getString(R.string.builtin_colors_source_warning))
        palettes.addRow(container)
        semanticSections().forEach { section ->
            addSectionTitle(container, host.getString(section.groupLabelRes))
            section.items.forEach { addSemanticRow(container, it) }
        }
        addOverridesSection(container)
        addIneffectiveSection(container)
        refreshers.forEach { it() }
    }

    private fun buildSizes(container: LinearLayout) {
        refreshers.clear()
        container.removeAllViews()
        addSourceHeader(
            container = container,
            titleRes = R.string.style_source,
            inCharge = styleInCharge(),
            builtinLabelRes = R.string.style_source_builtin,
            themeLabelRes = R.string.style_source_theme,
        ) { builtin ->
            ThemeManager.prefs.styleSource.setValue(
                if (builtin) ThemePrefs.StyleSource.BUILTIN else ThemePrefs.StyleSource.THEME,
            )
        }
        addSourcePausedNote(container, styleInCharge(), style.values.size)
        styleSections().forEach { section ->
            addSectionTitle(container, host.getString(section.groupLabelRes))
            section.items.forEach { addStyleRow(container, it) }
        }
    }

    private fun buildLayouts(container: LinearLayout) {
        refreshers.clear()
        container.removeAllViews()
        addSourceHeader(
            container = container,
            titleRes = R.string.layout_source,
            inCharge = layoutInCharge(),
            builtinLabelRes = R.string.layout_source_builtin,
            themeLabelRes = R.string.style_source_theme,
        ) { builtin ->
            ThemeManager.prefs.layoutSource.setValue(
                if (builtin) ThemePrefs.LayoutSource.BUILTIN else ThemePrefs.LayoutSource.THEME,
            )
        }
        addSourcePausedNote(container, layoutInCharge(), layout.values.size)
        LayoutSemantic.entries.forEach { addLayoutRow(container, it) }
        // The toolbar height has no row of its own: it *is* the two rows above, summed. The line
        // says so rather than leaving the reader to wonder where that knob went.
        addNote(container, host.getString(R.string.layout_toolbar_note))
    }

    // ---------------------------------------------------------------- head of the colour tab

    private fun addModeRow(container: LinearLayout) {
        val row =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(host.dp(8), host.dp(8), host.dp(8), host.dp(2))
            }
        row.addView(
            TextView(host).apply {
                setText(if (isNight) R.string.builtin_palette_for_dark else R.string.builtin_palette_for_light)
                setTextColor(mutedColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            MaterialButton(host).apply {
                setText(R.string.builtin_palette_switch)
                setOnClickListener {
                    isNight = !isNight
                    // The two palettes are separate maps, so every swatch and every live colour
                    // read below has to be asked again — rebuilding the tab is the honest way.
                    colorsContainer?.let { buildColors(it) }
                    onColorsChanged()
                }
            },
        )
        container.addView(row, fillWidth())
    }

    /**
     * What the keyboard is actually getting from these settings, in one line.
     *
     * One clause per domain, because each has a source of its own and a sentence that spoke for
     * only some of them would quietly mis-state the rest. Inside a clause, the cases that need
     * words rather than a number are the ones a count hides: a source that is not the built-in
     * one, and a palette that is empty — which looks exactly like the theme's own scheme and is
     * therefore indistinguishable from "not working".
     */
    private fun statusLine(): String {
        val summary =
            activeSummary(
                scheme = scheme,
                style = style,
                layout = layout,
                isNight = isNight,
                colorsInCharge = colorInCharge(),
                sizesInCharge = styleInCharge(),
                layoutInCharge = layoutInCharge(),
            )
        val colours =
            when {
                !summary.colorsInCharge -> host.getString(R.string.builtin_active_colours_scheme)
                summary.colors == 0 -> host.getString(R.string.builtin_active_colours_none)
                else -> host.getString(R.string.builtin_active_colours_count, summary.colors)
            }
        val sizes =
            if (summary.sizesInCharge) {
                host.getString(R.string.builtin_active_sizes_count, summary.sizes)
            } else {
                host.getString(R.string.builtin_active_sizes_theme)
            }
        val layoutClause =
            if (summary.layoutInCharge) {
                host.getString(R.string.builtin_active_layout_count, summary.layout)
            } else {
                host.getString(R.string.builtin_active_layout_theme)
            }
        return host.getString(R.string.builtin_active_line, colours, sizes, layoutClause)
    }

    private fun addStatusLine(container: LinearLayout) {
        val line =
            TextView(host).apply {
                text = statusLine()
                textSize = 12f
                setTextColor(mutedColor)
                setPadding(host.dp(8), host.dp(2), host.dp(8), host.dp(6))
            }
        refreshers += { line.text = statusLine() }
        container.addView(line, fillWidth())
    }

    // ---------------------------------------------------------------- colour rows

    private fun addSemanticRow(
        container: LinearLayout,
        item: ColorSemantic,
    ) {
        addColorRow(
            container = container,
            label = host.getString(BuiltinLabels.colorItem(item)),
            state = {
                val value = scheme.values(isNight)[item]
                RowState(value?.let(::parseOrNull), value)
            },
            onClick = {
                showPicker(
                    title = host.getString(BuiltinLabels.colorItem(item)),
                    current = resolvePaletteColor(item),
                    onFollowScheme = {
                        scheme = scheme.withoutValues(isNight, listOf(item))
                        persistColors()
                    },
                ) { hex ->
                    scheme = scheme.withValues(isNight, mapOf(item to hex))
                    persistColors()
                }
            },
        )
    }

    /**
     * The per-key overrides: colour keys the theme names itself.
     *
     * Still worth a list even though the keyboard can now be tapped: tapping reaches the keys the
     * theme bound a colour to, and this reaches the colour *names* — the shared ones (`bgn` behind
     * every letter, `bbs` behind backspace) that no single key owns.
     */
    private fun addOverridesSection(container: LinearLayout) {
        addSectionTitle(container, host.getString(R.string.builtin_advanced_overrides))
        addNote(container, host.getString(R.string.builtin_advanced_overrides_summary))

        val all = runCatching { foreignKeys(ColorManager.activeColorScheme.colors.keys) }.getOrElse { emptyList() }
        if (all.isEmpty()) {
            addNote(container, host.getString(R.string.builtin_advanced_none))
            return
        }
        // The list is the theme's whole vocabulary — this theme runs to a couple of hundred
        // `*_custom_key_*` entries — so it opens on what the user actually overrode, and even then
        // builds only a page at a time.
        val overridden = all.filter { scheme.overrides.containsKey(it) }
        val shown =
            if (showAllForeignKeys) {
                all.take(overrideWindow(all, expanded = true, more = overridePagesShown))
            } else {
                overridden
            }
        if (shown.isEmpty()) {
            addNote(container, host.getString(R.string.builtin_advanced_no_overrides))
        } else {
            shown.forEach { addOverrideRow(container, it) }
        }
        addOverridePagingRow(container, all, shown.size)
    }

    private fun addOverridePagingRow(
        container: LinearLayout,
        all: List<String>,
        shownCount: Int,
    ) {
        val remaining = all.size - shownCount
        val label =
            when {
                !showAllForeignKeys -> host.getString(R.string.builtin_advanced_show_all, all.size)
                remaining > 0 -> host.getString(R.string.builtin_advanced_show_more, remaining)
                else -> host.getString(R.string.builtin_advanced_hide_all)
            }
        container.addView(
            MaterialButton(host).apply {
                setText(label)
                setOnClickListener {
                    when {
                        !showAllForeignKeys -> {
                            showAllForeignKeys = true
                            overridePagesShown = 0
                        }

                        remaining > 0 -> overridePagesShown++
                        else -> showAllForeignKeys = false
                    }
                    colorsContainer?.let { buildColors(it) }
                }
            },
            fillWidth(bottom = host.dp(8)),
        )
    }

    private fun addOverrideRow(
        container: LinearLayout,
        key: String,
    ) {
        addColorRow(
            container = container,
            label = key,
            state = { RowState(scheme.overrides[key]?.let(::parseOrNull), scheme.overrides[key]) },
            onClick = {
                showPicker(
                    title = key,
                    current =
                        scheme.overrides[key]?.let(::parseOrNull)
                            ?: runCatching { ColorManager.getColor(key) }.getOrElse { OPAQUE_BLACK },
                    onFollowScheme = {
                        scheme = scheme.copy(overrides = scheme.overrides - key)
                        persistColors()
                    },
                ) { hex ->
                    scheme = scheme.copy(overrides = scheme.overrides + (key to hex))
                    persistColors()
                }
            },
        )
    }

    private fun addIneffectiveSection(container: LinearLayout) {
        val keys = runCatching { ineffectiveKeys(ColorManager.activeColorScheme.colors.keys) }.getOrElse { emptyList() }
        if (keys.isEmpty()) return
        addSectionTitle(container, host.getString(R.string.builtin_ineffective))
        addNote(container, host.getString(R.string.builtin_ineffective_summary))
        keys.forEach { (key, reason) ->
            addColorRow(container = container, label = key, state = { RowState(null, reason) }, onClick = null)
        }
    }

    // ---------------------------------------------------------------- knobs

    /** The size rows: staged into [style], persisted to its store. */
    private fun addStyleRow(
        container: LinearLayout,
        semantic: StyleSemantic,
    ) = addKnobRow(
        container = container,
        label = host.getString(BuiltinLabels.styleItem(semantic)),
        min = semantic.min,
        max = semantic.max,
        zeroMeansInherit = semantic.zeroMeansInherit,
        unit = "",
        inCharge = styleInCharge(),
        current = { currentStyleValue(semantic) },
        overridden = { style.isOverridden(semantic) },
        stage = { style = style.with(semantic, it) },
        clear = { style = style.without(semantic) },
        persist = ::persistStyle,
    )

    /** The layout rows: the same row pointed at the layout store. */
    private fun addLayoutRow(
        container: LinearLayout,
        semantic: LayoutSemantic,
    ) = addKnobRow(
        container = container,
        label = host.getString(BuiltinLabels.layoutItem(semantic)),
        min = semantic.min,
        max = semantic.max,
        zeroMeansInherit = false,
        unit = semantic.unit,
        inCharge = layoutInCharge(),
        current = { currentLayoutValue(semantic) },
        overridden = { layout.isOverridden(semantic) },
        stage = { layout = layout.with(semantic, it) },
        clear = { layout = layout.without(semantic) },
        persist = ::persistLayout,
    )

    /**
     * One knob row: a label, its current value, a per-row "follow the theme" reset, and a slider.
     *
     * The keyboard follows the release, not the finger: a size is measured into every key, so
     * applying one means building the keyboard again, and doing that on every drag step would
     * stutter for a picture the user is going to judge on the stop anyway. The value is written to
     * disk when the gesture ends, and that write is what asks the screen to rebuild.
     *
     * Read-only while the domain's source is the theme: a slider that moves and changes nothing is
     * the silent no-op this whole feature set exists to refuse to be.
     */
    private fun addKnobRow(
        container: LinearLayout,
        label: CharSequence,
        min: Float,
        max: Float,
        zeroMeansInherit: Boolean,
        unit: String,
        inCharge: Boolean,
        current: () -> Float,
        overridden: () -> Boolean,
        stage: (Float) -> Unit,
        clear: () -> Unit,
        persist: () -> Unit,
    ) {
        val row =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(host.dp(8), host.dp(10), host.dp(8), host.dp(2))
            }
        val header =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        header.addView(
            TextView(host).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val valueView =
            TextView(host).apply {
                textSize = 13f
                setTextColor(mutedColor)
            }
        header.addView(valueView)
        val reset =
            MaterialButton(host).apply {
                setText(R.string.builtin_style_follow_theme)
                setOnClickListener {
                    clear()
                    persist()
                }
            }
        header.addView(reset)
        row.addView(header)

        val bar =
            SeekBar(host).apply {
                this.max = (max - min).toInt()
                progress = (current().coerceIn(min, max) - min).toInt().coerceIn(0, this.max)
                isEnabled = inCharge
            }
        row.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        fun showCurrent() {
            valueView.text = formatKnobValue(current(), zeroMeansInherit, unit)
            reset.isVisible = inCharge && overridden()
        }
        refreshers += { showCurrent() }
        showCurrent()

        bar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (!fromUser) return
                    stage(min + progress)
                    // The number follows the finger; the keyboard follows the release.
                    showCurrent()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    persist()
                }
            },
        )
        container.addView(row, fillWidth())
    }

    private fun currentStyleValue(semantic: StyleSemantic): Float {
        val theme = ThemeManager.baseGeneralStyle
        return if (styleInCharge()) style.valueOf(semantic, theme) else theme.valueOf(semantic)
    }

    private fun currentLayoutValue(semantic: LayoutSemantic): Float {
        val theme = ThemeManager.baseGeneralStyle
        return if (layoutInCharge()) layout.valueOf(semantic, theme) else theme.valueOf(semantic)
    }

    private fun formatKnobValue(
        value: Float,
        zeroMeansInherit: Boolean,
        unit: String,
    ): String =
        if (zeroMeansInherit && value <= 0f) {
            host.getString(R.string.builtin_style_inherits)
        } else {
            value.roundToInt().toString() + unit
        }

    // ---------------------------------------------------------------- persistence

    /** Writes the palette and repaints the keyboard, which is all a colour change needs. */
    private fun persistColors() {
        BuiltinColorStore.savePalette(scheme)
        ColorManager.refreshBuiltinColors()
        // Rebuilding the tab re-reads every swatch through the `refreshers` it registers, so the
        // rows that were on screen while the picker was open come back showing the new value.
        colorsContainer?.let { buildColors(it) }
        onColorsChanged()
    }

    /**
     * Writes the sizes and asks for the keyboard to be built again.
     *
     * Rebuilding rather than repainting is not a shortcut: a size is baked into each key's
     * measured width and height when the keyboard is constructed, so a repaint would show the old
     * geometry with the new colours.
     */
    private fun persistStyle() {
        BuiltinStyleStore.save(style)
        ThemeManager.refreshBuiltinOverrides()
        refreshers.forEach { it() }
        onGeometryChanged()
    }

    private fun persistLayout() {
        BuiltinLayoutStore.save(layout)
        ThemeManager.refreshBuiltinOverrides()
        refreshers.forEach { it() }
        onGeometryChanged()
    }

    // ---------------------------------------------------------------- shared row pieces

    private data class RowState(
        /** The colour to show, or null for "follows the scheme". */
        val color: Int?,
        /** The text beside it: a hex value, or the reason it is not editable. */
        val detail: String?,
    )

    private fun addColorRow(
        container: LinearLayout,
        label: String,
        state: () -> RowState,
        onClick: (() -> Unit)?,
    ) {
        val row =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(host.dp(8), host.dp(10), host.dp(8), host.dp(10))
                if (onClick != null) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                }
            }
        val swatch =
            View(host).apply {
                layoutParams = LinearLayout.LayoutParams(host.dp(28), host.dp(28)).apply { marginEnd = host.dp(12) }
            }
        row.addView(swatch)
        row.addView(
            TextView(host).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val detail =
            TextView(host).apply {
                textSize = 12f
                setTextColor(mutedColor)
            }
        row.addView(detail)

        refreshers += {
            val current = state()
            swatch.background =
                GradientDrawable().apply {
                    cornerRadius = host.dp(6).toFloat()
                    // A hollow swatch is the whole signal for "nothing is set here", which is the
                    // default state of every row: the palette only names what the user changed.
                    setColor(current.color ?: Color.TRANSPARENT)
                    setStroke(host.dp(1), if (current.color == null) 0x66000000 else 0x33000000)
                }
            detail.text = current.detail ?: host.getString(R.string.builtin_color_follow_scheme)
        }
        container.addView(row, fillWidth())
    }

    private fun addSourceHeader(
        container: LinearLayout,
        titleRes: Int,
        inCharge: Boolean,
        builtinLabelRes: Int,
        themeLabelRes: Int,
        flip: (Boolean) -> Unit,
    ) {
        val row =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(host.dp(8), host.dp(20), host.dp(8), host.dp(4))
            }
        row.addView(
            TextView(host).apply {
                setText(titleRes)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            MaterialSwitch(host).apply {
                // Text and state come before the listener: a programmatic `isChecked` is not a
                // flip, and must not be answered as one.
                text = host.getString(if (inCharge) builtinLabelRes else themeLabelRes)
                textSize = 13f
                isChecked = inCharge
                setOnCheckedChangeListener { _, checked ->
                    // Flipping the preference is the whole action: the change listener re-derives
                    // the patched theme and tells the keyboard. The rebuild here is for this
                    // screen only, so the rows come back under the new source in the same breath.
                    flip(checked)
                    rebuildAll()
                    onGeometryChanged()
                }
            },
        )
        container.addView(row, fillWidth())
    }

    private fun addSourcePausedNote(
        container: LinearLayout,
        inCharge: Boolean,
        count: Int,
    ) {
        if (inCharge || count == 0) return
        addNote(container, host.getString(R.string.builtin_source_paused_note, count))
    }

    private fun addSectionTitle(
        container: LinearLayout,
        text: String,
    ) {
        container.addView(
            TextView(host).apply {
                this.text = text
                textSize = 14f
                setPadding(host.dp(8), host.dp(20), host.dp(8), host.dp(4))
            },
            fillWidth(),
        )
    }

    private fun addNote(
        container: LinearLayout,
        text: String,
    ) {
        container.addView(
            TextView(host).apply {
                this.text = text
                textSize = 12f
                setTextColor(mutedColor)
                setPadding(host.dp(8), 0, host.dp(8), host.dp(4))
            },
            fillWidth(),
        )
    }

    private fun fillWidth(bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = bottom
        }

    private fun showPicker(
        title: String,
        current: Int,
        onFollowScheme: (() -> Unit)?,
        onPicked: (String) -> Unit,
    ) {
        BuiltinColorDialog
            .build(
                context = host,
                title = title,
                current = current,
                onFollowScheme = onFollowScheme,
                onPicked = onPicked,
            ).show()
    }

    /** The colour the palette shows for an item now, falling back to the keyboard's live colour. */
    private fun resolvePaletteColor(item: ColorSemantic): Int =
        scheme.values(isNight)[item]?.let(::parseOrNull)
            ?: runCatching { ColorManager.getColor(item.keys.first()) }.getOrElse { OPAQUE_BLACK }

    private fun parseOrNull(value: String): Int? = runCatching { ColorUtils.parseColor(value) }.getOrNull()

    // ---------------------------------------------------------------- source checks

    private fun colorInCharge(): Boolean =
        ThemeManager.prefs.colorSource.getValue() == ThemePrefs.ColorSource.BUILTIN

    private fun styleInCharge(): Boolean =
        ThemeManager.prefs.styleSource.getValue() == ThemePrefs.StyleSource.BUILTIN

    private fun layoutInCharge(): Boolean =
        ThemeManager.prefs.layoutSource.getValue() == ThemePrefs.LayoutSource.BUILTIN

    private companion object {
        val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}

/**
 * The manual push, for when something has gone wrong.
 *
 * Every edit already applies as it is made, so this is not what makes a change take effect — it
 * is the way back when one did not, and it answers the question rather than leaving a silent
 * no-op: whatever the screen can see about why a change would not show, it says.
 */
internal fun AppCompatActivity.applyBuiltinAppearance(
    scheme: BuiltinColorScheme,
    style: BuiltinStyle,
    layout: BuiltinLayout,
    onRebuilt: () -> Unit,
): String {
    ColorManager.ensureInitialized(resources.configuration)
    BuiltinColorStore.savePalette(scheme)
    BuiltinStyleStore.save(style)
    BuiltinLayoutStore.save(layout)
    ColorManager.refreshBuiltinColors()
    ThemeManager.refreshBuiltinOverrides()
    // The rebuild, not the calls above, is what makes this action mean "make it so". Those return
    // quietly when the composed result is unchanged, and the keyboard was told nothing; rebuilding
    // re-reads every colour and knob, so the outcome no longer depends on what the previous
    // attempt happened to do.
    val notified = ColorManager.requestRebuild()
    onRebuilt()
    return when {
        !isThisAppTheActiveIme() -> getString(R.string.builtin_apply_not_active_ime)

        ThemeManager.prefs.colorSource.getValue() != ThemePrefs.ColorSource.BUILTIN ->
            getString(R.string.builtin_apply_not_builtin)

        notified == 0 -> getString(R.string.builtin_apply_keyboard_asleep)

        else -> getString(R.string.builtin_apply_done)
    }
}

/** Whether the keyboard that will come up is this build's, rather than a sibling's. */
private fun AppCompatActivity.isThisAppTheActiveIme(): Boolean {
    val active = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return active?.startsWith(packageName) == true
}
