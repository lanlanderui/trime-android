/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.PerKeyColorStore
import com.osfans.trime.data.theme.PerKeyColors
import com.osfans.trime.data.theme.PerKeySlot
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.databinding.ActivityKeyColorEditorBinding
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.resolveKeyboardConfig
import com.osfans.trime.ui.common.materialAlertDialogBuilder
import com.osfans.trime.util.ColorUtils
import com.osfans.trime.util.isNightMode
import com.osfans.trime.util.toast
import splitties.dimensions.dp

/**
 * Everything about how the keyboard looks, on one screen with the keyboard on it.
 *
 * The screen is built around one rule: anything that *is* a key is edited by tapping that key, and
 * anything that is not a key is edited from the list below. That split is not a compromise, it is
 * the answer to the problem the old palette had — a theme names its colours by role
 * (`key_back_color`, `bgn`, and one `*_custom_key_*` per key), and nobody can tell which of two
 * hundred names sits under the letter Q. The keyboard can.
 *
 * The keyboard at the top is a real [Keyboard] — the IME's own class, from the same theme — drawn
 * by [KeyColorBoardView] with every colour read through [Key]'s getters. Tapping a key therefore
 * picks the thing the user is looking at, and a colour on it changes the same pixels on the
 * keyboard behind this screen.
 *
 * The three tabs below it are the settings that used to live on a screen of their own, next to a
 * hand-drawn thumbnail: the palette, the sizes, the layout. They kept their knobs and lost the
 * thumbnail — the keyboard above previews all three better, because it is the keyboard.
 *
 * Changes are written as they happen: there is no save button, and nothing to forget to press.
 */
class KeyColorEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityKeyColorEditorBinding

    /** The layout being edited, by its name in the theme's `preset_keyboards`. */
    private var keyboardName = ""

    private var keyboard: Keyboard? = null

    /** The layout width the current [keyboard] was built for. */
    private var builtWidth = 0

    /** Picked key indices. Ordered, so the first pick is the one the panel reads its state from. */
    private var selection: Set<Int> = emptySet()

    /** The palette, the sizes and the layout — the three tabs. */
    private lateinit var sections: BuiltinAppearanceSections

    private val theme get() = ThemeManager.activeTheme

    private val mutedColor: Int by lazy {
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    }

    /** Slot views, rebuilt with the panel and refreshed in place while colours change. */
    private val slotViews = mutableMapOf<PerKeySlot, Pair<View, TextView>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityKeyColorEditorBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = systemBars.left
                rightMargin = systemBars.right
                bottomMargin = systemBars.bottom
            }
            binding.editorToolbar.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
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
        // The colour pipeline is normally started by the IME service, which may never have run on
        // a device where this screen is opened from the launcher. Without it, every colour read
        // here would be a default and every edit would be stored and never painted.
        ColorManager.ensureInitialized(resources.configuration)

        // Built before the tabs because it registers the activity-result launchers that the menu's
        // import and export items use.
        sections =
            BuiltinAppearanceSections(
                host = this,
                mutedColor = mutedColor,
                // A colour moved: the board reads every colour at draw time, so a repaint is all
                // it takes — and it is what makes the palette tab live.
                onColorsChanged = { binding.editorBoard.invalidate() },
                // A size or a layout value moved: those are measured into the keys, so the
                // keyboard has to be built again rather than repainted.
                onGeometryChanged = { rebuildKeyboard(binding.editorBoard.width.takeIf { it > 0 } ?: builtWidth) },
            )

        setSupportActionBar(binding.editorToolbar.toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.key_colors_title)
        }

        keyboardName = initialKeyboardName()

        binding.editorBoard.onKeyTapped = ::onKeyTapped
        binding.editorBoard.onBackgroundTapped = { setSelection(emptySet()) }
        binding.editorSelectionDone.setOnClickListener { setSelection(emptySet()) }
        binding.editorSelectionReset.setOnClickListener { clearPickedColors() }

        buildKeyboardPages()
        buildTabs()

        // The keyboard is built from the width it is given, and that width only exists after the
        // first layout pass. One rebuild then, and never again unless the layout changes.
        binding.editorBoard.doOnLayout { view ->
            if (view.width > 0 && view.width != builtWidth) rebuildKeyboard(view.width)
        }

        refreshPanel()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------------------------------------------------------- tabs

    /**
     * Colour, sizes, layout — one tab each.
     *
     * The list is rebuilt on every switch rather than kept in three hidden containers: these rows
     * read their values from the stores at build time, and a stale row is exactly the "I changed
     * it and the number did not move" this screen exists to avoid. Three tabs of a few dozen rows
     * are cheap to build; keeping them correct by other means is not.
     */
    private fun buildTabs() {
        binding.editorTabs.removeAllTabs()
        arrayOf(R.string.key_colors_tab_colors, R.string.key_colors_tab_sizes, R.string.key_colors_tab_layout)
            .forEach { titleRes ->
                binding.editorTabs.addTab(binding.editorTabs.newTab().setText(titleRes))
            }
        binding.editorTabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    showTab(tab.position)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )
        showTab(0)
    }

    private fun showTab(position: Int) {
        binding.editorContent.removeAllViews()
        binding.editorScroll.scrollTo(0, 0)
        when (position) {
            1 -> sections.fillSizes(binding.editorContent)
            2 -> sections.fillLayouts(binding.editorContent)
            else -> {
                sections.fillColors(binding.editorContent)
                addPerKeyFooter(binding.editorContent)
            }
        }
    }

    /**
     * The one thing on this screen that belongs to no single key: dropping every key's own colours.
     *
     * It lives at the foot of the colour tab rather than in the menu because the menu's "reset"
     * means the palette, the sizes and the layout — the three tabs — and a user who tapped twelve
     * keys into a mess would not think to look there for them.
     */
    private fun addPerKeyFooter(container: LinearLayout) {
        val count = PerKeyColorStore.count()
        container.addView(
            TextView(this).apply {
                text = getString(R.string.key_colors_reset_title)
                textSize = 14f
                setPadding(dp(8), dp(20), dp(8), dp(4))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            TextView(this).apply {
                text =
                    if (count == 0) {
                        getString(R.string.key_colors_reset_none)
                    } else {
                        getString(R.string.key_colors_reset_summary)
                    }
                textSize = 12f
                setTextColor(mutedColor)
                setPadding(dp(8), 0, dp(8), dp(6))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (count == 0) return
        container.addView(
            MaterialButton(this).apply {
                setText(R.string.key_colors_reset_all)
                setOnClickListener { confirmResetAll() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) },
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.key_color_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_apply -> {
                val report =
                    applyBuiltinAppearance(sections.scheme, sections.style, sections.layout) {
                        sections.rebuildAll()
                        rebuildKeyboard(binding.editorBoard.width.takeIf { it > 0 } ?: builtWidth)
                    }
                toast(report, Toast.LENGTH_LONG)
                true
            }

            R.id.action_import -> {
                sections.actions.import()
                true
            }

            R.id.action_export -> {
                sections.actions.export()
                true
            }

            R.id.action_reset -> {
                sections.actions.reset()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }

    // ---------------------------------------------------------------- keyboard

    /**
     * The layout to open on: `default` when the theme has one, because that is what the IME shows.
     *
     * Falls back to the first layout in the theme rather than to an empty screen: a theme is free
     * to call its main layout anything, and "no keyboard" is not a useful place to land.
     */
    private fun initialKeyboardName(): String {
        val names = theme.presetKeyboards.keys
        return names.firstOrNull { it == "default" } ?: names.firstOrNull().orEmpty()
    }

    private fun rebuildKeyboard(width: Int) {
        if (width <= 0) return
        val config = resolveKeyboardConfig(theme.presetKeyboards, keyboardName)
        if (config == null) {
            keyboard = null
            binding.editorBoard.keyboard = null
            return
        }
        builtWidth = width
        val built = Keyboard(this, theme, width, config, keyboardName)
        keyboard = built
        binding.editorBoard.keyTextSize = theme.generalStyle.keyTextSize
        binding.editorBoard.symbolTextSize = theme.generalStyle.symbolTextSize
        binding.editorBoard.keyboard = built

        // Overrides filed under indices the layout no longer has can never be painted again; they
        // would only inflate the counts the panel and the reset button report.
        PerKeyColorStore.prune(keyboardName, built.keys.size)

        val kept = selection.filter { it in built.keys.indices }.toSet()
        setSelection(kept)
    }

    private fun buildKeyboardPages() {
        binding.editorPages.removeAllViews()
        theme.presetKeyboards.keys.forEach { name ->
            val chip =
                Chip(this).apply {
                    text = if (name == "default") getString(R.string.key_colors_page_default) else name
                    isCheckable = true
                    isChecked = name == keyboardName
                    setOnClickListener { selectKeyboard(name) }
                }
            binding.editorPages.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) },
            )
        }
        binding.editorPagesScroll.isVisible = theme.presetKeyboards.size > 1
    }

    private fun selectKeyboard(name: String) {
        if (name == keyboardName) return
        keyboardName = name
        // A different layout has different keys; keeping indices across the switch would paint
        // whichever key happens to sit at that position now.
        selection = emptySet()
        buildKeyboardPages()
        // Rebuild even when the width is unchanged: a different layout has different keys, and
        // keeping the old instance would leave the board showing the previous page.
        rebuildKeyboard(binding.editorBoard.width.takeIf { it > 0 } ?: builtWidth)
        refreshPanel()
        binding.editorBoard.invalidate()
    }

    // ---------------------------------------------------------------- selection

    private fun setSelection(indices: Set<Int>) {
        val snapshot = indices.toSet()
        if (selection == snapshot) return
        // Insertion order is kept, not sorted: the panel reads its state from the first pick, and
        // "the key you touched first" is the one the user is thinking about.
        selection = snapshot
        binding.editorBoard.setSelected(selection)
        refreshPanel()
    }

    private fun onKeyTapped(
        index: Int,
        longPress: Boolean,
    ) {
        if (longPress) {
            // "Only this one": the escape hatch from a selection that grew too large, without
            // having to find the clear button first.
            setSelection(setOf(index))
            return
        }
        val next = selection.toMutableSet()
        if (!next.add(index)) next.remove(index)
        setSelection(next)
    }

    /**
     * The panel above the tabs: who is picked, and the six colours a key can be told apart by.
     *
     * Rebuilt only when the selection changes; the swatches inside it are refreshed in place while
     * a colour is being dragged, so a frame does not rebuild six views.
     */
    private fun refreshPanel() {
        slotViews.clear()
        binding.editorSlots.removeAllViews()
        val picked = selection.size
        binding.editorSelection.isVisible = picked > 0
        if (picked == 0) return

        binding.editorSelectionTitle.text = getString(R.string.key_colors_selected, picked)
        PerKeySlot.entries.forEach { slot ->
            binding.editorSlots.addView(buildSlotView(slot))
        }
    }

    private fun buildSlotView(slot: PerKeySlot): View {
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(6), dp(2), dp(6), dp(2))
                isClickable = true
                isFocusable = true
                setOnClickListener { openSlotPicker(slot) }
            }
        val swatch = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)) }
        column.addView(swatch)
        column.addView(
            TextView(this).apply {
                text = getString(slotLabel(slot))
                textSize = 11f
                gravity = Gravity.CENTER
            },
        )
        val state =
            TextView(this).apply {
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(mutedColor)
            }
        column.addView(state)
        slotViews[slot] = swatch to state
        paintSlot(slot, swatch, state)
        return column
    }

    private fun paintSlot(
        slot: PerKeySlot,
        swatch: View,
        state: TextView,
    ) {
        val current = slotState(slot)
        val color = current.color
        swatch.background =
            GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color ?: Color.TRANSPARENT)
                // A hollow swatch is the whole signal for "the theme decides", matching the rows in
                // the list below. A mixed selection gets a heavier outline as well, because
                // "these keys disagree" is not the same statement as "nothing is set".
                setStroke(dp(1), if (current.mixed) 0x88000000.toInt() else 0x33000000)
            }
        state.text =
            when {
                current.mixed -> getString(R.string.key_colors_mixed)
                color == null -> getString(R.string.key_colors_follow_theme)
                else -> argbToHex(color)
            }
    }

    private fun refreshSlotStates() {
        slotViews.forEach { (slot, views) -> paintSlot(slot, views.first, views.second) }
    }

    private data class SlotState(
        val color: Int?,
        val mixed: Boolean = false,
    )

    /** What the picked keys say about one slot: one colour, nothing set, or a disagreement. */
    private fun slotState(slot: PerKeySlot): SlotState {
        val raw = selection.map { PerKeyColorStore.get(keyboardName, it)?.slot(slot) }
        val distinct = raw.distinct()
        return if (distinct.size <= 1) {
            SlotState(distinct.firstOrNull()?.let(::parseOrNull))
        } else {
            SlotState(null, mixed = true)
        }
    }

    /**
     * The colour to open the picker on.
     *
     * The stored value when there is one; otherwise what the key is wearing right now, so the
     * dialog starts from the colour the user is looking at rather than from black. That live value
     * is read through the key itself — its own theme binding, the scheme, and the fallback chain
     * have all been applied by then.
     */
    private fun currentSlotColor(slot: PerKeySlot): Int {
        val index = selection.firstOrNull() ?: return OPAQUE_BLACK
        PerKeyColorStore.get(keyboardName, index)?.slot(slot)?.let(::parseOrNull)?.let { return it }
        val key = keyboard?.keys?.getOrNull(index) ?: return OPAQUE_BLACK
        return liveColorOf(key, slot)
    }

    private fun liveColorOf(
        key: Key,
        slot: PerKeySlot,
    ): Int = when (slot) {
        PerKeySlot.BACK, PerKeySlot.PRESSED_BACK -> backgroundOf(key)
        PerKeySlot.TEXT -> runCatching { key.getTextColor() }.getOrDefault(OPAQUE_BLACK)
        PerKeySlot.SYMBOL -> runCatching { key.getSymbolColor() }.getOrDefault(OPAQUE_BLACK)
        PerKeySlot.BORDER -> runCatching { key.getBorderColor() }.getOrDefault(OPAQUE_BLACK)
        PerKeySlot.PRESSED_TEXT ->
            runCatching { ColorManager.getColor("hilited_key_text_color") }.getOrDefault(OPAQUE_BLACK)
    }

    /**
     * The colour a key's background drawable actually carries.
     *
     * A solid background resolves to a [GradientDrawable] whose colour is the honest answer — it is
     * what the theme bound to *this* key, which for a hand-written theme is often not
     * `key_back_color` but a name of its own. Anything else (an image, or a key with no background
     * at all) has no single colour to report, so the scheme's own key is the closest thing to one.
     */
    private fun backgroundOf(key: Key): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val drawable = runCatching { key.getBackgroundDrawable() }.getOrNull()
            (drawable as? GradientDrawable)?.color?.defaultColor?.let { return it }
        }
        return runCatching { ColorManager.getColor("key_back_color") }.getOrDefault(OPAQUE_BLACK)
    }

    /**
     * Opens the picker for one slot, live.
     *
     * Dragging repaints the board on every frame — that is the whole reason this screen exists —
     * but nothing reaches the disk until the user confirms. Cancelling restores the snapshot the
     * dialog opened on, so a preview never leaves a colour behind.
     */
    private fun openSlotPicker(slot: PerKeySlot) {
        val indices = selection.toList()
        if (indices.isEmpty()) return
        val before = snapshotOf(indices)
        var committed = false

        val dialog =
            BuiltinColorDialog.build(
                context = this,
                title = getString(slotLabel(slot)),
                current = currentSlotColor(slot),
                onPicked = { hex ->
                    committed = true
                    applySlot(slot, hex, persist = true)
                },
                onFollowScheme = {
                    committed = true
                    applySlot(slot, null, persist = true)
                },
                followLabelRes = R.string.key_colors_follow_theme,
                onPreview = { hex -> applySlot(slot, hex, persist = false) },
            )
        dialog.setOnDismissListener {
            if (!committed) rollback(indices, before)
        }
        dialog.show()
    }

    private fun applySlot(
        slot: PerKeySlot,
        hex: String?,
        persist: Boolean,
    ) {
        PerKeyColorStore.update(keyboardName, selection.toList(), persist) { it.with(slot, hex) }
        ColorManager.invalidateKeyColors()
        binding.editorBoard.invalidate()
        refreshSlotStates()
    }

    private fun snapshotOf(indices: List<Int>): Map<Int, PerKeyColors> =
        indices.mapNotNull { index -> PerKeyColorStore.get(keyboardName, index)?.let { index to it } }.toMap()

    private fun rollback(
        indices: List<Int>,
        snapshot: Map<Int, PerKeyColors>,
    ) {
        PerKeyColorStore.restore(keyboardName, indices, snapshot)
        ColorManager.invalidateKeyColors()
        binding.editorBoard.invalidate()
        refreshSlotStates()
    }

    /** Hands the picked keys back to the theme, all six slots at once. */
    private fun clearPickedColors() {
        if (selection.isEmpty()) return
        PerKeyColorStore.clear(keyboardName, selection.toList())
        ColorManager.invalidateKeyColors()
        binding.editorBoard.invalidate()
        refreshSlotStates()
        toast(R.string.key_colors_cleared)
    }

    /** Drops every key's own colours, on every layout, after asking. */
    private fun confirmResetAll() {
        val count = PerKeyColorStore.count()
        if (count == 0) {
            toast(R.string.key_colors_reset_none)
            return
        }
        materialAlertDialogBuilder()
            .setTitle(R.string.key_colors_reset_all)
            .setMessage(getString(R.string.key_colors_reset_all_confirm, count))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PerKeyColorStore.clear()
                ColorManager.invalidateKeyColors()
                binding.editorBoard.invalidate()
                refreshSlotStates()
                // The footer's own count just went to zero, and it is built from that count.
                showTab(0)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun slotLabel(slot: PerKeySlot): Int = when (slot) {
        PerKeySlot.BACK -> R.string.key_colors_slot_back
        PerKeySlot.TEXT -> R.string.key_colors_slot_text
        PerKeySlot.SYMBOL -> R.string.key_colors_slot_symbol
        PerKeySlot.BORDER -> R.string.key_colors_slot_border
        PerKeySlot.PRESSED_BACK -> R.string.key_colors_slot_pressed_back
        PerKeySlot.PRESSED_TEXT -> R.string.key_colors_slot_pressed_text
    }

    private fun parseOrNull(value: String): Int? = runCatching { ColorUtils.parseColor(value) }.getOrNull()

    private fun argbToHex(color: Int): String = "#%08X".format(color)

    private companion object {
        const val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}
