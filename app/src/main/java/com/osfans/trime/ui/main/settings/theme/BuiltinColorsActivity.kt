/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.osfans.trime.R
import com.osfans.trime.data.theme.BuiltinColorDefaults
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
import com.osfans.trime.data.theme.summary
import com.osfans.trime.data.theme.valueOf
import com.osfans.trime.data.theme.withStyleValues
import com.osfans.trime.databinding.ActivityBuiltinColorsBinding
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.util.ColorUtils
import com.osfans.trime.util.isNightMode
import com.osfans.trime.util.toast
import kotlin.math.roundToInt
import splitties.dimensions.dp

/**
 * Edits the built-in appearance: the colour palette first, then the sizes and the layout — the
 * same question three times over ("what does this knob say, and who is in charge of it"), with
 * the colour side reaching keys a theme keeps to itself through the per-key overrides.
 *
 * Every change is written straight to its store and pushed to the keyboard, so the keyboard
 * behind this screen restyles as knobs are moved — there is no "save" to forget.
 */
class BuiltinColorsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBuiltinColorsBinding
    private var scheme: BuiltinColorScheme = BuiltinColorDefaults.DEFAULT
    private var style = BuiltinStyle()
    private var layout = BuiltinLayout()
    private var isNight = false
    private var showAllForeignKeys = false

    /** How many pages of the full override list have been asked for; see [overrideWindow]. */
    private var overridePagesShown = 0
    private val refreshers = mutableListOf<() -> Unit>()

    /** Muted text that follows the app's own light/dark theme, not a hardcoded grey. */
    private val mutedColor: Int by lazy {
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.BLACK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBuiltinColorsBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = systemBars.left
                rightMargin = systemBars.right
                bottomMargin = systemBars.bottom
            }
            binding.colorsToolbar.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top
            }
            windowInsets
        }
        val darkIcons = !resources.configuration.isNightMode()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }

        setContentView(binding.root)
        // The colour pipeline is normally brought up by the IME service, which may not be running
        // when this screen is opened from the launcher; without it a palette edit is stored and
        // never painted.
        ColorManager.ensureInitialized(resources.configuration)
        scheme = BuiltinColorStore.current()
        style = BuiltinStyleStore.current()
        layout = BuiltinLayoutStore.current()
        // The palette ships a light and a dark version and the keyboard picks between them by the
        // system state, so the screen opens on whichever one is live right now.
        isNight = resources.configuration.isNightMode()
        setSupportActionBar(binding.colorsToolbar.toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.builtin_colors)
        }
        actions =
            BuiltinAppearanceActions(
                host = this,
                currentScheme = { scheme },
                currentStyle = { style },
                currentLayout = { layout },
                applyScheme = { scheme = it },
                applyStyle = { style = it },
                applyLayout = { layout = it },
                rebuild = { buildScreen() },
            )
        palettes =
            BuiltinPaletteManager(
                host = this,
                mutedColor = mutedColor,
                onPaletteChanged = { scheme = it },
                rebuild = { buildScreen() },
            )
        buildScreen()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * The file and reset actions, which own their own launchers and dialogs.
     *
     * Created in [onCreate] rather than in a property initialiser because registering an activity
     * result launcher reaches into the activity's lifecycle, which is not ready before then.
     */
    private lateinit var actions: BuiltinAppearanceActions

    /**
     * Choosing and managing the stored palettes.
     *
     * Created here rather than in a property initialiser for the same reason as [actions] — and
     * because it reads [mutedColor], which needs a live context.
     */
    private lateinit var palettes: BuiltinPaletteManager

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.builtin_colors, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_export -> {
                actions.export()
                true
            }

            R.id.action_import -> {
                actions.import()
                true
            }

            R.id.action_reset -> {
                actions.reset()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }


    private fun buildScreen() {
        refreshers.clear()
        binding.colorsContent.removeAllViews()
        updateHeader()
        addSourceHeader(
            titleRes = R.string.color_source,
            inCharge = colorInCharge(),
            builtinLabelRes = R.string.color_source_builtin,
            themeLabelRes = R.string.color_source_scheme,
        ) { builtin ->
            ThemeManager.prefs.colorSource.setValue(
                if (builtin) ThemePrefs.ColorSource.BUILTIN else ThemePrefs.ColorSource.SCHEME,
            )
        }
        addColorSourceNote()
        palettes.addRow(binding.colorsContent)
        addApplyRow()
        semanticSections().forEach { section ->
            addSectionTitle(getString(section.groupLabelRes))
            section.items.forEach { addSemanticRow(it) }
        }
        addOverridesSection()
        addIneffectiveSection()
        addSourceHeader(
            titleRes = R.string.style_source,
            inCharge = styleInCharge(),
            builtinLabelRes = R.string.style_source_builtin,
            themeLabelRes = R.string.style_source_theme,
        ) { builtin ->
            ThemeManager.prefs.styleSource.setValue(
                if (builtin) ThemePrefs.StyleSource.BUILTIN else ThemePrefs.StyleSource.THEME,
            )
        }
        addSourcePausedNote(styleInCharge(), style.values.size)
        styleSections().forEach { section ->
            addSectionTitle(getString(section.groupLabelRes))
            section.items.forEach { addStyleRow(it) }
        }
        addSourceHeader(
            titleRes = R.string.layout_source,
            inCharge = layoutInCharge(),
            builtinLabelRes = R.string.layout_source_builtin,
            themeLabelRes = R.string.style_source_theme,
        ) { builtin ->
            ThemeManager.prefs.layoutSource.setValue(
                if (builtin) ThemePrefs.LayoutSource.BUILTIN else ThemePrefs.LayoutSource.THEME,
            )
        }
        addSourcePausedNote(layoutInCharge(), layout.values.size)
        LayoutSemantic.entries.forEach { addLayoutRow(it) }
        // The toolbar height has no row of its own: it *is* the two rows above, summed. The line
        // says so rather than leaving the reader to wonder where that knob went.
        addNote(getString(R.string.layout_toolbar_note))
        refreshRows()
    }

    /**
     * The mode row and the preview, which live outside the scroll view.
     *
     * They are rebuilt on every screen build because the palette they describe is what just
     * changed; the preview reads its colours at draw time, so the [View.invalidate] at the end of
     * a colour edit is all it needs to stay live.
     */
    private fun updateHeader() {
        val theme = ThemeManager.activeTheme
        binding.colorsModeRow.removeAllViews()
        binding.colorsModeRow.addView(
            TextView(this).apply {
                setText(if (isNight) R.string.builtin_palette_for_dark else R.string.builtin_palette_for_light)
                setTextColor(mutedColor)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        binding.colorsModeRow.addView(
            MaterialButton(this).apply {
                setText(R.string.builtin_palette_switch)
                setOnClickListener {
                    isNight = !isNight
                    buildScreen()
                }
            },
        )
        // The preview draws the keyboard that is actually on screen: that is the only way it can
        // promise to look like the one behind it. The theme's `default` is merely where the IME
        // starts -- a schema, a rotation or an explicit switch may have replaced it since.
        val live = KeyboardWindow.currentKeyboardOrNull
        if (live != null) {
            val rows = previewRows(live, theme.presetKeys.mapValues { it.value.label })
            val rowHeight = if (rows.isEmpty()) 0 else live.height / rows.size
            val aspect = if (live.minWidth > 0) live.height.toFloat() / live.minWidth else 0f
            binding.colorsPreview.setKeyboard(rows, live.roundCorner, live.keyBorder, rowHeight, aspect)
        } else {
            // No keyboard has been built yet (the settings opened before an editor ever asked for
            // input), so fall back to the theme's own default -- what the keyboard would start on.
            // It takes the theme's own key labels: without them a command key would show the name
            // it goes by in the file (`BackSpace`) instead of the glyph on screen (`⊗`).
            val keyboard = theme.presetKeyboards["default"] ?: theme.presetKeyboards.values.firstOrNull()
            binding.colorsPreview.setKeyboard(keyboard, theme.presetKeys.mapValues { it.value.label })
        }
        binding.colorsPreview.setStyle(previewStyle())
        refreshers += { binding.colorsStatus.text = activeStatus() }
        refreshStatus()
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
    private fun activeStatus(): String {
        val summary =
            activeSummary(
                scheme = scheme,
                style = style,
                layout = layout,
                isNight = isNight,
                colorsInCharge =
                    ThemeManager.prefs.colorSource.getValue() == ThemePrefs.ColorSource.BUILTIN,
                sizesInCharge = styleInCharge(),
                layoutInCharge = layoutInCharge(),
            )
        val colours =
            when {
                !summary.colorsInCharge -> getString(R.string.builtin_active_colours_scheme)
                summary.colors == 0 -> getString(R.string.builtin_active_colours_none)
                else -> getString(R.string.builtin_active_colours_count, summary.colors)
            }
        val sizes =
            if (summary.sizesInCharge) {
                getString(R.string.builtin_active_sizes_count, summary.sizes)
            } else {
                getString(R.string.builtin_active_sizes_theme)
            }
        val layoutClause =
            if (summary.layoutInCharge) {
                getString(R.string.builtin_active_layout_count, summary.layout)
            } else {
                getString(R.string.builtin_active_layout_theme)
            }
        return getString(R.string.builtin_active_line, colours, sizes, layoutClause)
    }

    private fun refreshStatus() {
        binding.colorsStatus.text = activeStatus()
    }

    /**
     * The style the preview should draw with: what the theme declares, with the staged size
     * overrides on top.
     *
     * Built from the *unpatched* style rather than from `activeTheme.generalStyle` so that the
     * preview follows a slider while it is being dragged — the active theme only catches up when
     * the gesture ends. When the size source is the theme's own, the staged overrides are skipped
     * the same way `ThemeManager` skips them: the preview must not promise a size the keyboard is
     * not using.
     */
    private fun previewStyle() =
        ThemeManager.baseGeneralStyle.let { base ->
            if (styleInCharge()) base.withStyleValues(style.values) else base
        }

    /** Whether the built-in sizes are the source in charge, read the same way the colour one is. */
    private fun styleInCharge(): Boolean =
        ThemeManager.prefs.styleSource.getValue() == ThemePrefs.StyleSource.BUILTIN

    /** And whether the built-in layout is the source in charge. */
    private fun layoutInCharge(): Boolean =
        ThemeManager.prefs.layoutSource.getValue() == ThemePrefs.LayoutSource.BUILTIN

    /** Whether the built-in palette is the source in charge, read the same way as the others. */
    private fun colorInCharge(): Boolean =
        ThemeManager.prefs.colorSource.getValue() == ThemePrefs.ColorSource.BUILTIN

    /**
     * A domain's header: its name, and the switch that decides who is in charge of it.
     *
     * The switch lives with the domain it governs rather than on the settings page, because the
     * two halves are one decision: what the knobs below are set to means a different number
     * depending on which side owns them, and a source flipped from another screen turns into a
     * puzzle ("why did the number change?") instead of a control. The label states the side that
     * is in charge, so the switch reads correctly without knowing which way is "on".
     *
     * The three domains share this; only the strings and the preference being flipped differ.
     */
    private fun addSourceHeader(
        titleRes: Int,
        inCharge: Boolean,
        builtinLabelRes: Int,
        themeLabelRes: Int,
        flip: (Boolean) -> Unit,
    ) {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(20), dp(8), dp(4))
            }
        row.addView(
            TextView(this).apply {
                setText(titleRes)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            MaterialSwitch(this).apply {
                // Text and state come before the listener: a programmatic `isChecked` is not a
                // flip, and must not be answered as one.
                text = getString(if (inCharge) builtinLabelRes else themeLabelRes)
                textSize = 13f
                isChecked = inCharge
                setOnCheckedChangeListener { _, checked ->
                    // Flipping the preference is the whole action: the change listener re-derives
                    // the patched theme and tells the keyboard. The rebuild here is for this
                    // screen only, so the knobs come back under the new source in the same breath.
                    flip(checked)
                    buildScreen()
                }
            },
        )
        binding.colorsContent.addView(row, fillWidth())
    }

    /**
     * The colour domain's note: a bare reminder that edits are not on the keyboard yet.
     *
     * The switch above already states who is in charge, so the note only adds what the switch
     * cannot — and only while the answer is the unhelpful one.
     */
    private fun addColorSourceNote() {
        if (colorInCharge()) return
        addNote(getString(R.string.builtin_colors_source_warning))
    }

    /**
     * How much stored work is sitting frozen while the theme owns a domain.
     *
     * Only the fact the switch cannot state by itself: which side is in charge is the switch's
     * own label, and repeating it here would be the two-places noise this arrangement just ended.
     */
    private fun addSourcePausedNote(
        inCharge: Boolean,
        count: Int,
    ) {
        if (inCharge || count == 0) return
        addNote(getString(R.string.builtin_source_paused_note, count))
    }

    private fun addSemanticRow(item: ColorSemantic) {
        addColorRow(
            label = getString(BuiltinLabels.colorItem(item)),
            state = {
                val value = scheme.values(isNight)[item]
                RowState(value?.let(::parseOrNull), value)
            },
            onClick = {
                showPicker(
                    title = getString(BuiltinLabels.colorItem(item)),
                    current = resolvePaletteColor(item),
                    onFollowScheme = {
                        scheme = scheme.withoutValues(isNight, listOf(item))
                        persist()
                    },
                ) { hex ->
                    scheme = scheme.withValues(isNight, mapOf(item to hex))
                    persist()
                }
            },
        )
    }

    /**
     * The per-key overrides.
     *
     * The list is the colour keys the active scheme declares that no semantic item owns. For a
     * theme that binds its own names per key — this one binds `bgn`, `bbs`, `benter`, `bkg` and a
     * `*_custom_key_*` per physical key — that is the only way in: the palette cannot name a key it
     * has never heard of, and an explicit scheme value beats the `fallback_colors` alias that would
     * otherwise point it at a key the palette does own.
     */
    private fun addOverridesSection() {
        addSectionTitle(getString(R.string.builtin_advanced_overrides))
        addNote(getString(R.string.builtin_advanced_overrides_summary))

        val all = runCatching { foreignKeys(ColorManager.activeColorScheme.colors.keys) }.getOrElse { emptyList() }
        if (all.isEmpty()) {
            addNote(getString(R.string.builtin_advanced_none))
            return
        }
        // The list is the theme's whole vocabulary — this theme runs to a couple of hundred
        // `*_custom_key_*` entries — so it opens on what the user actually overrode, and even then
        // builds only a page at a time. An escape hatch that dumps every key in the theme on first
        // sight is the "too many parameters" problem this screen exists to fix; one that builds
        // 800 views to avoid a scroll is a stall instead.
        val overridden = all.filter { scheme.overrides.containsKey(it) }
        val shown =
            if (showAllForeignKeys) {
                all.take(overrideWindow(all, expanded = true, more = overridePagesShown))
            } else {
                overridden
            }
        if (shown.isEmpty()) {
            addNote(getString(R.string.builtin_advanced_no_overrides))
        } else {
            shown.forEach { addOverrideRow(it) }
        }
        addOverridePagingRow(all, shown.size)
    }

    /**
     * The button under the override list: either open the full list, page through it, or close it.
     *
     * Which one is a pure function of how much is left, so the label cannot claim there is more
     * when there is not — a button reading "show all (200)" over a list of 200 rows would be a lie
     * the user finds out about by pressing it and having nothing happen.
     */
    private fun addOverridePagingRow(
        all: List<String>,
        shownCount: Int,
    ) {
        val remaining = all.size - shownCount
        val label =
            when {
                !showAllForeignKeys -> getString(R.string.builtin_advanced_show_all, all.size)
                remaining > 0 -> getString(R.string.builtin_advanced_show_more, remaining)
                else -> getString(R.string.builtin_advanced_hide_all)
            }
        binding.colorsContent.addView(
            MaterialButton(this).apply {
                setText(label)
                setOnClickListener {
                    when {
                        // Closed: open the first page.
                        !showAllForeignKeys -> {
                            showAllForeignKeys = true
                            overridePagesShown = 0
                        }
                        // Open with more behind it: one more page.
                        remaining > 0 -> overridePagesShown++
                        // Everything is already on screen: close it again.
                        else -> showAllForeignKeys = false
                    }
                    buildScreen()
                }
            },
            fillWidth(bottom = dp(8)),
        )
    }

    private fun addOverrideRow(key: String) {
        addColorRow(
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
                        persist()
                    },
                ) { hex ->
                    scheme = scheme.copy(overrides = scheme.overrides + (key to hex))
                    persist()
                }
            },
        )
    }

    /** Keys the theme sets that this build reads nowhere, so nobody keeps changing them for nothing. */
    private fun addIneffectiveSection() {
        val keys = runCatching { ineffectiveKeys(ColorManager.activeColorScheme.colors.keys) }.getOrElse { emptyList() }
        if (keys.isEmpty()) return
        addSectionTitle(getString(R.string.builtin_ineffective))
        addNote(getString(R.string.builtin_ineffective_summary))
        keys.forEach { (key, reason) ->
            addColorRow(label = key, state = { RowState(null, reason) }, onClick = null)
        }
    }

    /**
     * One knob row: a label, its current value, a per-row "follow the theme" reset, and a slider.
     *
     * One implementation for both editable domains — the sizes and the layout — because the row
     * asks the same question either way ("what is this set to, and who is in charge"); [addStyleRow]
     * and [addLayoutRow] below are the two wirings that answer it.
     *
     * The preview follows the finger and the keyboard follows the release: a knob is measured into
     * the views, so applying one means rebuilding the keyboard, and doing that on every drag step
     * would stutter. The value is written to disk when the gesture ends. Read-only while the
     * domain's source is the theme: a slider that moves and changes nothing is the silent no-op
     * this whole screen exists to refuse to be.
     */
    private fun addKnobRow(
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
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(2))
            }
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        header.addView(
            TextView(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val valueView =
            TextView(this).apply {
                textSize = 13f
                setTextColor(mutedColor)
            }
        header.addView(valueView)
        val reset =
            MaterialButton(this).apply {
                setText(R.string.builtin_style_follow_theme)
                setOnClickListener {
                    clear()
                    persist()
                }
            }
        header.addView(reset)
        row.addView(header)

        val bar =
            SeekBar(this).apply {
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
                    // The preview only; `refreshBuiltinOverrides` would rebuild the keyboard.
                    binding.colorsPreview.setStyle(previewStyle())
                    showCurrent()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    persist()
                }
            },
        )
        binding.colorsContent.addView(row, fillWidth())
    }

    /** The size rows: staged into [style], persisted to its store. */
    private fun addStyleRow(semantic: StyleSemantic) =
        addKnobRow(
            label = getString(BuiltinLabels.styleItem(semantic)),
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
    private fun addLayoutRow(semantic: LayoutSemantic) =
        addKnobRow(
            label = getString(BuiltinLabels.layoutItem(semantic)),
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
     * The value this size is actually getting, which is also what the slider should show.
     *
     * With the built-in sizes in charge that is the stored override if one is set, else the
     * theme's value. With the source on the theme it is the theme's value alone — the stored
     * override is frozen at that point, and showing it would claim the keyboard uses something
     * it does not.
     */
    private fun currentStyleValue(semantic: StyleSemantic): Float {
        val theme = ThemeManager.baseGeneralStyle
        return if (styleInCharge()) style.valueOf(semantic, theme) else theme.valueOf(semantic)
    }

    /** The same question for a layout knob; the frozen store is not shown when the theme wins. */
    private fun currentLayoutValue(semantic: LayoutSemantic): Float {
        val theme = ThemeManager.baseGeneralStyle
        return if (layoutInCharge()) layout.valueOf(semantic, theme) else theme.valueOf(semantic)
    }

    /**
     * A value as a row shows it: a bare number with its unit — or "follow the key size" for the
     * knobs where zero means exactly that, since the symbol, label and popup sizes all fall back
     * to `key_text_size` at zero and a bare `0` would read as "invisible".
     */
    private fun formatKnobValue(
        value: Float,
        zeroMeansInherit: Boolean,
        unit: String,
    ): String =
        if (zeroMeansInherit && value <= 0f) {
            getString(R.string.builtin_style_inherits)
        } else {
            value.roundToInt().toString() + unit
        }

    /** Writes the sizes and rebuilds the keyboard, which is what a size change needs. */
    private fun persistStyle() {
        BuiltinStyleStore.save(style)
        ThemeManager.refreshBuiltinOverrides()
        refreshRows()
        binding.colorsPreview.setStyle(previewStyle())
    }

    /** Writes the layout and rebuilds the keyboard, which is what a layout change needs. */
    private fun persistLayout() {
        BuiltinLayoutStore.save(layout)
        ThemeManager.refreshBuiltinOverrides()
        refreshRows()
        binding.colorsPreview.setStyle(previewStyle())
    }

    /**
     * The manual push, for when something has gone wrong.
     *
     * Every edit already applies as it is made, so this is not what makes a change take effect —
     * it is the way back when one did not, and it answers the question rather than leaving a silent
     * no-op: whatever this screen can see about why a change would not show, it says.
     */
    private fun addApplyRow() {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(dp(8), 0, dp(8), dp(8))
            }
        row.addView(
            MaterialButton(this).apply {
                setText(R.string.builtin_apply)
                setOnClickListener { applyNow() }
            },
        )
        binding.colorsContent.addView(row, fillWidth())
    }

    private fun applyNow(): Int {
        ColorManager.ensureInitialized(resources.configuration)
        BuiltinColorStore.savePalette(scheme)
        BuiltinStyleStore.save(style)
        BuiltinLayoutStore.save(layout)
        ColorManager.refreshBuiltinColors()
        ThemeManager.refreshBuiltinOverrides()
        // The rebuild, not the calls above, is what makes this button mean "make it so". Those
        // return quietly when the composed result is unchanged, and the keyboard was told
        // nothing; rebuilding re-reads every colour and knob, so the outcome no longer depends
        // on what the previous attempt happened to do.
        val notified = ColorManager.requestRebuild()
        buildScreen()
        toast(applyReport(notified), Toast.LENGTH_LONG)
        return notified
    }

    /**
     * What this screen can tell about how the apply went.
     *
     * Asked in the order that makes a wrong answer impossible: an edit to this app's palette cannot
     * show on a keyboard that is not this app, whatever else is true. And the rebuild is only
     * reported as done when something was actually listening — the IME service registers its
     * listeners once rime is ready, so a rebuild that reached nobody is not a rebuild.
     */
    private fun applyReport(rebuildNotified: Int): String =
        when {
            !isThisAppTheActiveIme() -> getString(R.string.builtin_apply_not_active_ime)

            ThemeManager.prefs.colorSource.getValue() != ThemePrefs.ColorSource.BUILTIN ->
                getString(R.string.builtin_apply_not_builtin)

            rebuildNotified == 0 -> getString(R.string.builtin_apply_keyboard_asleep)

            else -> getString(R.string.builtin_apply_done)
        }

    /** Whether the keyboard that will come up is this build's, rather than a sibling's. */
    private fun isThisAppTheActiveIme(): Boolean {
        val active =
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return active?.startsWith(packageName) == true
    }

    private data class RowState(
        /** The colour to show, or null for "follows the scheme". */
        val color: Int?,
        /** The text beside it: a hex value, or the reason it is not editable. */
        val detail: String?,
    )

    private fun addColorRow(
        label: String,
        state: () -> RowState,
        onClick: (() -> Unit)?,
    ) {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                if (onClick != null) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                }
            }
        val swatch =
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) }
            }
        row.addView(swatch)
        row.addView(
            TextView(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val detail =
            TextView(this).apply {
                textSize = 12f
                setTextColor(mutedColor)
            }
        row.addView(detail)

        refreshers += {
            val current = state()
            swatch.background =
                GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    // A hollow swatch is the whole signal for "nothing is set here", which is the
                    // default state of every row: the palette only names what the user changed.
                    setColor(current.color ?: Color.TRANSPARENT)
                    setStroke(dp(1), if (current.color == null) 0x66000000 else 0x33000000)
                }
            detail.text = current.detail ?: getString(R.string.builtin_color_follow_scheme)
        }
        binding.colorsContent.addView(row, fillWidth())
    }

    private fun addSectionTitle(text: String) {
        binding.colorsContent.addView(
            TextView(this).apply {
                this.text = text
                textSize = 14f
                setPadding(dp(8), dp(20), dp(8), dp(4))
            },
            fillWidth(),
        )
    }

    private fun addNote(text: String) {
        binding.colorsContent.addView(
            TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(mutedColor)
                setPadding(dp(8), 0, dp(8), dp(4))
            },
            fillWidth(),
        )
    }

    private fun fillWidth(bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = bottom
        }

    private fun refreshRows() {
        refreshers.forEach { it() }
    }

    /** Writes the palette and makes the keyboard pick it up, without leaving this screen. */
    private fun persist() {
        BuiltinColorStore.savePalette(scheme)
        ColorManager.refreshBuiltinColors()
        refreshRows()
        // The preview reads its colours at draw time, so this is the whole of "repaint live".
        binding.colorsPreview.invalidate()
    }

    private fun showPicker(
        title: String,
        current: Int,
        onFollowScheme: (() -> Unit)?,
        onPicked: (String) -> Unit,
    ) {
        BuiltinColorDialog
            .build(
                context = this,
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

    private companion object {
        val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}
