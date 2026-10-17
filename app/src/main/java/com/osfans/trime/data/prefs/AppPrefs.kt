/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.Keep
import com.osfans.trime.R
import com.osfans.trime.data.sync.DataStorageMode
import com.osfans.trime.ime.candidates.compact.CompactCandidateMode
import com.osfans.trime.ime.candidates.popup.PopupCandidatesLayout
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.PopupPosition
import com.osfans.trime.ime.core.InlinePreeditMode
import com.osfans.trime.ime.keyboard.TtsAvailability
import com.osfans.trime.util.InputMethodUtils
import com.osfans.trime.util.appContext
import java.lang.ref.WeakReference

/**
 * Helper class for an organized access to the shared preferences.
 */
class AppPrefs(
    private val shared: SharedPreferences,
) {
    private val applicationContext: WeakReference<Context> = WeakReference(appContext)

    private val providers = mutableListOf<PreferenceDelegateProvider>()

    fun <T : PreferenceDelegateProvider> registerProvider(providerF: (SharedPreferences) -> T): T {
        val provider = providerF(shared)
        providers.add(provider)
        return provider
    }

    private fun <T : PreferenceDelegateProvider> T.register() = this.apply {
        registerProvider { this }
    }

    val internal = Internal(shared)
    val general = General(shared).register()
    val profile = Profile(shared).register()
    val keyboard = Keyboard(shared).register()
    val preeditAppearance = PreeditAppearance(shared).register()
    val feedback = Feedback(shared).register()
    val shortcut = Shortcut(shared).register()
    val candidates = Candidates(shared).register()
    val clipboard = Clipboard(shared).register()
    val advanced = Advanced(shared).register()

    @Keep
    private val onSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null) return@OnSharedPreferenceChangeListener
            providers.forEach {
                it.notifyChange(key)
            }
        }

    companion object {
        private var defaultInstance: AppPrefs? = null

        fun initDefault(sharedPreferences: SharedPreferences): AppPrefs {
            val instance = AppPrefs(sharedPreferences)
            defaultInstance = instance
            sharedPreferences.registerOnSharedPreferenceChangeListener(
                defaultInstance().onSharedPreferenceChangeListener,
            )
            return instance
        }

        fun defaultInstance(): AppPrefs = defaultInstance
            ?: throw UninitializedPropertyAccessException(
                """
                    Default preferences not initialized! Make sure to call initDefault()
                    before accessing the default preferences.
                """.trimIndent(),
            )
    }

    class Internal(
        shared: SharedPreferences,
    ) : PreferenceDelegateOwner(shared) {
        companion object {
            const val PID = "general__pid"
        }

        val pid = int(PID, 0)
    }

    class General(
        shared: SharedPreferences,
    ) : PreferenceDelegateOwner(shared, R.string.general) {
        companion object {
            const val INLINE_PREEDIT_MODE = "inline_preedit_mode"
            const val ASCII_SWITCH_TIPS = "ascii_switch_tips"
            const val INLINE_SUGGESTIONS = "inline_suggestions"
            const val PREFERRED_VOICE_INPUT = "preferred_voice_input"
        }

        val inlinePreeditMode = enum(R.string.inline_preedit_mode, INLINE_PREEDIT_MODE, InlinePreeditMode.DISABLE)
        val asciiSwitchTips = switch(R.string.ascii_switch_tips, ASCII_SWITCH_TIPS, true)
        val inlineSuggestions = switch(R.string.inline_suggestions, INLINE_SUGGESTIONS, true)

        val preferredVoiceInput = list(
            R.string.preferred_voice_input,
            PREFERRED_VOICE_INPUT,
            "",
            { InputMethodUtils.voiceInputMethods().map { it.first.packageName } },
            { ctx ->
                InputMethodUtils.voiceInputMethods().map { it.first.loadLabel(ctx.packageManager) }
            },
        )
    }

    /**
     *  Wrapper class of keyboard settings.
     */
    class Keyboard(
        shared: SharedPreferences,
    ) : PreferenceDelegateOwner(shared, R.string.virtual_keyboard) {
        companion object {
            const val FLOATING_KEYBOARD = "floating_keyboard"
            const val FLOATING_KEYBOARD_WIDTH = "floating_keyboard_width"
            const val FLOATING_KEYBOARD_HEIGHT = "floating_keyboard_height"
            const val FLOATING_KEYBOARD_OFFSET_X = "floating_keyboard_offset_x"
            const val FLOATING_KEYBOARD_OFFSET_Y = "floating_keyboard_offset_y"
            const val FLOATING_PREEDIT_OFFSET_X = "floating_preedit_offset_x"
            /**
             * The retired floating-only vertical offset, in dp (downwards was positive, i.e.
             * towards the card and over it). Read once to fold its lift into
             * [PREEDIT_KEYBOARD_GAP], then removed.
             */
            const val LEGACY_FLOATING_PREEDIT_OFFSET_Y = "floating_preedit_offset_y"
            const val PREEDIT_KEYBOARD_GAP = "preedit_keyboard_gap"

            /**
             * Upper bound of [PREEDIT_KEYBOARD_GAP], in dp.
             *
             * Shared with the layout that applies the gap: the slider's range and the clamp that
             * guards against a stale or hand-edited value have to be the same number, or a value
             * the slider can reach would be silently shrunk on its way to the keyboard.
             */
            const val PREEDIT_KEYBOARD_GAP_MAX_DP = 32
            const val LANDSCAPE_MODE = "keyboard_landscape_mode"
            const val SPLIT_SPACE_PERCENT = "keyboard_split_space"

            const val USE_SOFT_CURSOR = "use_soft_cursor"
            const val HIDE_INPUT_BAR = "hide_input_bar"
            const val HIDE_KEY_SYMBOL = "hide_key_symbol"
            const val HIDE_KEY_HINT = "hide_key_hint"

            const val POPUP_ON_KEY_PRESS = "show_key_popup"
            const val EXPAND_KEYPRESS_AREA = "expand_keypress_area"
            const val SWIPE_TRAVEL = "key_swipe_travel"
            const val SWIPE_VELOCITY = "key_swipe_velocity"
            const val LONG_PRESS_TIMEOUT = "key_long_press_timeout"
            const val REPEAT_INTERVAL = "key_repeat_interval"
            const val DOUBLE_TAP_TIMEOUT = "key_double_tap_timeout"
            const val SLIDE_STEP_SIZE = "key_slide_step_size"
        }

        enum class LandscapeMode(override val stringRes: Int) : PreferenceDelegateEnum {
            NEVER(R.string.never),
            LANDSCAPE(R.string.landscape_only),
            WIDE(R.string.wide_or_landscape),
            ALWAYS(R.string.always),
        }

        init {
            // The floating keyboard had its own vertical offset for the preedit -- a second way of
            // saying what the gap now says for both modes: how far the chip sits from the top of
            // the keyboard. Fold the lift of that value into the gap once, then drop the key: a
            // number someone had set must not disappear together with its row.
            //
            // Only the lift survives the merge. A positive offset pushed the label *into* the
            // card, which a gap cannot express; at zero the layout keeps the 1 dp overlap that
            // hides the seam between the chip's square bottom and the card's rounded top, so the
            // flush look those values aimed at is still what zero means.
            if (sharedPreferences.contains(LEGACY_FLOATING_PREEDIT_OFFSET_Y)) {
                val legacyOffset = sharedPreferences.getInt(LEGACY_FLOATING_PREEDIT_OFFSET_Y, 0)
                if (legacyOffset != 0 && !sharedPreferences.contains(PREEDIT_KEYBOARD_GAP)) {
                    val lifted = (-legacyOffset).coerceIn(0, PREEDIT_KEYBOARD_GAP_MAX_DP)
                    sharedPreferences.edit().putInt(PREEDIT_KEYBOARD_GAP, lifted).apply()
                }
                sharedPreferences.edit().remove(LEGACY_FLOATING_PREEDIT_OFFSET_Y).apply()
            }
        }

        val floatingKeyboard = switch(
            R.string.floating_keyboard,
            FLOATING_KEYBOARD,
            false,
            R.string.floating_keyboard_summary,
        )
        /**
         * The floating keyboard's size, as percentages of the keyboard's own default.
         *
         * Deliberately declared without a title, which is what keeps them off the settings screen:
         * the size is set by dragging the keyboard's handles, and the handles write back here, so a
         * pair of sliders would be a second way to say the same thing — one that cannot show the
         * sizes actually reached by dragging (the width percent is anchored to the screen's short
         * side, so the slider's scale and the drag's do not agree).
         *
         * The values are still read on every layout and still the store the drag writes to, so this
         * is a UI change only. `int(key, default)` registers the change notification and no UI;
         * the titled overload is the one that adds a row.
         */
        val floatingKeyboardWidth = int(FLOATING_KEYBOARD_WIDTH, 86)
        val floatingKeyboardHeight = int(FLOATING_KEYBOARD_HEIGHT, 100)
        /**
         * Where the preedit sits horizontally, in percent of the keyboard's own span.
         *
         * A position rather than an offset: 0% is the keyboard's left edge and 100% its right,
         * so the preedit can be parked anywhere across it while always staying within it. The
         * span is the full-width keyboard while docked and the floating card while floating, so
         * the preedit still travels exactly as far as the keyboard reaches. The travel leaves
         * room for the preedit's own width, so both extremes stay fully on the keyboard however
         * wide the composition grows. Values written by the older dp-based knob are read as
         * percentages and clamped.
         */
        val preeditOffsetPercent = int(
            R.string.floating_preedit_offset_x,
            FLOATING_PREEDIT_OFFSET_X,
            0,
            0,
            100,
            "%",
        )
        /**
         * The distance between the preedit's bottom edge and the keyboard's top edge, in dp.
         *
         * Zero is the layout every theme was drawn against: the preedit's bottom edge *is* the
         * keyboard's top edge, which is why a theme paints the chip with rounded top corners and
         * square bottom ones. Raising this lifts the chip off the keys and leaves exactly that
         * much space between the two, without resizing either of them -- the chip keeps its own
         * height and the keyboard keeps its place at the bottom of the window.
         *
         * One knob for both modes, because it is one measurement: while docked the edge below is
         * the keyboard's, while floating it is the card's top. It replaces the floating keyboard's
         * own vertical offset, which said the same thing with a second range and a second row.
         *
         * The value is a physical distance rather than a share of the window, so it means the
         * same thing on a phone, a tablet, and after a rotation.
         */
        val preeditKeyboardGap = int(
            R.string.preedit_keyboard_gap,
            PREEDIT_KEYBOARD_GAP,
            0,
            0,
            PREEDIT_KEYBOARD_GAP_MAX_DP,
            "dp",
        )
        val floatingKeyboardOffsetX = int(FLOATING_KEYBOARD_OFFSET_X, 0)
        val floatingKeyboardOffsetY = int(FLOATING_KEYBOARD_OFFSET_Y, 0)

        val landscapeMode = enum(R.string.enable_landscape_mode, LANDSCAPE_MODE, LandscapeMode.NEVER)
        val splitSpacePercent = int(
            R.string.split_space_percent,
            SPLIT_SPACE_PERCENT,
            100,
            0,
            200,
            "%",
        )

        val useSoftCursor = switch(R.string.use_soft_cursor, USE_SOFT_CURSOR, true)

        val hideInputBar = switch(R.string.hide_input_bar, HIDE_INPUT_BAR, false)

        val hideKeySymbol = switch(R.string.hide_key_symbol, HIDE_KEY_SYMBOL, false)
        val hideKeyHint = switch(R.string.hide_key_hint, HIDE_KEY_HINT, false)

        val popupOnKeyPress = switch(R.string.popup_on_key_press, POPUP_ON_KEY_PRESS, false)
        val expandKeypressArea = switch(R.string.expand_keypress_area_to_edge, EXPAND_KEYPRESS_AREA, false)
        val swipeTravel = int(
            R.string.key_swipe_travel,
            SWIPE_TRAVEL,
            60,
            0,
            400,
            "dp",
            10,
            R.string.disable,
            useMinAsDefault = true,
        )

        val swipeVelocity = int(
            R.string.key_swipe_velocity,
            SWIPE_VELOCITY,
            0,
            0,
            10000,
            "dp/s",
            100,
            R.string.disable,
        )

        val longPressTimeout = int(
            R.string.key_long_press_timeout,
            LONG_PRESS_TIMEOUT,
            300,
            100,
            1000,
            "ms",
            10,
        )

        val repeatInterval = int(
            R.string.key_repeat_interval,
            REPEAT_INTERVAL,
            30,
            10,
            100,
            "ms",
            10,
        )

        val doubleTapTimeout = int(
            R.string.key_double_tap_timeout,
            DOUBLE_TAP_TIMEOUT,
            300,
            100,
            1000,
            "ms",
            10,
        )

        val slideStepSize = int(
            R.string.key_slide_step_size,
            SLIDE_STEP_SIZE,
            24,
            1,
            100,
            "dp",
        )
    }

    /**
     * How the encoding area itself looks: its corners and its fill.
     *
     * Everything here is an override of what the theme paints, and every row keeps "nothing chosen"
     * as a real value -- zero for the radius and the colour -- so a theme keeps deciding the look
     * until the user says otherwise. That is what makes the page safe to open once and forget: no
     * value silently replaces the theme's own appearance just because a default had to exist.
     */
    class PreeditAppearance(
        shared: SharedPreferences,
    ) : PreferenceDelegateOwner(shared, R.string.preedit_appearance) {
        companion object {
            const val CORNER_RADIUS = "preedit_corner_radius"
            const val BACK_COLOR = "preedit_back_color"
            const val BACK_OPACITY = "preedit_back_opacity"

            /**
             * Upper bound of [CORNER_RADIUS], in dp.
             *
             * Shared with the layout that draws the background, the way the keyboard gap shares its
             * own bound: a value the slider can reach must not be clamped on its way to the screen.
             */
            const val CORNER_RADIUS_MAX_DP = 32
        }

        /**
         * Corner radius of the preedit's background, in dp; zero follows the theme.
         *
         * A theme paints the chip as a tab -- rounded on top, square at the bottom -- so that it can
         * sit flush on the keyboard. Lift it with the gap above the keys and those two square
         * corners are left in the air, which is the look this rounds off. Zero therefore means "the
         * theme's own two radii, square underneath", and any other value rounds **all four**
         * corners to it: a chip with a different radius per corner reads as an accident rather than
         * as a choice.
         */
        val cornerRadius = int(
            R.string.preedit_corner_radius,
            CORNER_RADIUS,
            0,
            0,
            CORNER_RADIUS_MAX_DP,
            "dp",
            defaultLabel = R.string.preedit_follow_theme,
            useMinAsDefault = true,
        )

        /**
         * Fill colour of the preedit, as opaque ARGB; zero follows the colour scheme.
         *
         * The scheme's `text_back_color` is what the chip has always been painted with, and it
         * carries its own alpha. A picked colour replaces the hue outright and is stored opaque --
         * its byte pattern is what keeps a picked black (0xFF000000) apart from the zero that means
         * "follow the scheme". The transparency is [backOpacity]'s business either way, so the two
         * rows never fight over the same byte.
         */
        val backColor = colorPick(
            R.string.preedit_back_color,
            BACK_COLOR,
            0,
        )

        /**
         * Opacity of the fill, in percent; 100 leaves the colour exactly as it is.
         *
         * It scales whatever alpha the fill already has, which is why 100 is the value that keeps
         * the theme's own translucency (0xcc and friends) instead of flattening it. At 0 the fill
         * disappears and the composition sits straight on the keyboard, its text still readable
         * through the scheme's text colour.
         */
        val backOpacity = int(
            R.string.preedit_back_opacity,
            BACK_OPACITY,
            100,
            0,
            100,
            "%",
        )
    }

    /**
     * What the keyboard does back at you: sound, vibration and speech.
     *
     * These three were interleaved with the keyboard's layout and gesture settings, which made both
     * hard to find — someone looking for "turn off the beep" had to scroll past the vibration
     * timings to reach it. Grouping them by what the user is asking ("how loud is it") rather than by
     * which subsystem happens to implement it is the point of the split.
     */
    class Feedback(
        shared: SharedPreferences,
    ) : PreferenceDelegateOwner(shared, R.string.feedback) {
        companion object {
            const val SOUND_ON_KEYPRESS = "sound_on_keypress"
            const val KEY_SOUND_VOLUME = "sound_volume"
            const val USE_CUSTOM_SOUND_EFFECT = "custom_sound_effect_enabled"
            const val CUSTOM_SOUND_EFFECT = "custom_sound_effect_name"
            const val VIBRATE_ON_KEY_PRESS = "vibrate_on_key_press"
            const val VIBRATE_ON_KEY_RELEASE = "vibrate_on_key_release"
            const val VIBRATE_ON_KEY_REPEAT = "vibrate_on_key_repeat"
            const val VIBRATION_DURATION = "vibration_duration"
            const val VIBRATION_AMPLITUDE = "vibration_amplitude"
            const val SPEAK_ON_KEYPRESS = "speak_on_keypress"
            const val SPEAK_ON_COMMIT = "speak_on_commit"
        }

        val soundOnKeyPress = switch(R.string.sound_on_keypress, SOUND_ON_KEYPRESS, false)
        val soundVolume = int(
            R.string.sound_volume,
            KEY_SOUND_VOLUME,
            10,
            0,
            100,
            "%",
            defaultLabel = R.string.system_default,
        ) { soundOnKeyPress.getValue() }

        val useCustomSoundEffect = switch(
            R.string.custom_sound_effect_enabled,
            USE_CUSTOM_SOUND_EFFECT,
            false,
        ) { soundOnKeyPress.getValue() }
        val customSoundEffect = string(
            R.string.custom_sound_effect_name,
            CUSTOM_SOUND_EFFECT,
            "",
        ) { soundOnKeyPress.getValue() && useCustomSoundEffect.getValue() }

        val vibrateOnKeyPress = switch(R.string.vibrate_on_key_press, VIBRATE_ON_KEY_PRESS, false)
        val vibrateOnKeyRelease = switch(
            R.string.vibrate_on_key_release,
            VIBRATE_ON_KEY_RELEASE,
            false,
        ) { vibrateOnKeyPress.getValue() }

        val vibrateOnKeyRepeat = switch(
            R.string.vibrate_on_key_repeat,
            VIBRATE_ON_KEY_REPEAT,
            false,
        ) { vibrateOnKeyPress.getValue() }

        val vibrationDuration = int(
            R.string.vibration_duration,
            VIBRATION_DURATION,
            0,
            0,
            100,
            "ms",
            defaultLabel = R.string.system_default,
        ) { vibrateOnKeyPress.getValue() }

        val vibrationAmplitude = int(
            R.string.vibration_amplitude,
            VIBRATION_AMPLITUDE,
            0,
            0,
            255,
            defaultLabel = R.string.system_default,
        ) { vibrateOnKeyPress.getValue() }

        /**
         * The summary the read-aloud switches carry, or null when the device can speak.
         *
         * A device with no speech engine fails silently — the switch flips, nothing happens, and
         * there is no way to tell that from a broken keyboard. Saying so on the switch is the
         * cheapest place to put it.
         */
        private fun speakSummary(): Int? =
            if (TtsAvailability.canSpeak(appContext)) null else R.string.speak_no_tts_engine

        val speakOnKeyPress =
            switch(
                R.string.speak_on_keypress,
                SPEAK_ON_KEYPRESS,
                false,
                summary = speakSummary(),
            )
        val speakOnCommit =
            switch(
                R.string.speak_on_commit,
                SPEAK_ON_COMMIT,
                false,
                summary = speakSummary(),
            )
    }

    /**
     * Which key combinations the keyboard claims for itself.
     *
     * Eight switches that answer one question — "does this chord reach the app or stay in the
     * keyboard?" — and they were buried at the end of the keyboard page, after the gesture timings,
     * where nobody looking for them would scroll to. Their own page also lets them be named as a
     * group: every one of them is the same kind of decision, so the summary can explain the category
     * once instead of on every row.
     */
    class Shortcut(
        shared: SharedPreferences,
    ) : PreferenceDelegateOwner(shared, R.string.shortcut) {
        companion object {
            const val HOOK_CTRL_A = "hook_ctrl_a"
            const val HOOK_CTRL_CV = "hook_ctrl_cv"
            const val HOOK_CTRL_LR = "hook_ctrl_lr"
            const val HOOK_CTRL_ZY = "hook_ctrl_zy"
            const val HOOK_SHIFT_SPACE = "hook_shift_space"
            const val HOOK_SHIFT_NUM = "hook_shift_num"
            const val HOOK_SHIFT_SYMBOL = "hook_shift_symbol"
            const val HOOK_SHIFT_ARROW = "hook_shift_arrow"
        }

        val hookCtrlA = switch(R.string.hook_ctrl_a, HOOK_CTRL_A, false)
        val hookCtrlCV = switch(R.string.hook_ctrl_cv, HOOK_CTRL_CV, false)
        val hookCtrlLR = switch(R.string.hook_ctrl_lr, HOOK_CTRL_LR, false)
        val hookCtrlZY = switch(R.string.hook_ctrl_zy, HOOK_CTRL_ZY, false)
        val hookShiftSpace = switch(R.string.hook_shift_space, HOOK_SHIFT_SPACE, false)
        val hookShiftNum = switch(R.string.hook_shift_num, HOOK_SHIFT_NUM, false)
        val hookShiftSymbol = switch(R.string.hook_shift_symbol, HOOK_SHIFT_SYMBOL, false)
        val hookShiftArrow = switch(R.string.hook_shift_arrow, HOOK_SHIFT_ARROW, true)
    }

    class Candidates(
        shared: SharedPreferences,
    ) : PreferenceDelegateOwner(shared, R.string.candidates_window) {
        companion object {
            const val MODE = "show_candidates_window"
            const val LAYOUT = "candidates_layout"
            const val POSITION = "candidates_window_position"
            const val BACKGROUND_ALPHA = "candidates_window_background_alpha"
            const val STRIP_BACKGROUND = "candidates_strip_background"
            const val STRIP_COLOR = "candidates_strip_color"
            const val STRIP_OPACITY = "candidates_strip_opacity"
            const val STRIP_LENGTH = "candidates_strip_length"
            const val STRIP_GAP = "candidates_strip_gap"
            const val CORNER_TOGGLES_COMPOSING = "corner_toggles_composing"
            const val SWITCHES_IN_WINDOW = "switches_in_window"
            const val TWO_ROW_FONT = "candidates_two_row_font"
            const val ITEM_BACKGROUND = "candidates_item_background"
            const val ITEM_SPACING = "candidates_item_spacing"
            const val ITEM_BACKGROUND_ALPHA = "candidates_item_background_alpha"
            const val HORIZONTAL_CANDIDATE_MODE = "horizontal_candidate_mode"
            const val MAX_SPAN_COUNT = "max_span_count"
            const val MAX_SPAN_COUNT_LANDSCAPE = "max_span_count_landscape"
        }

        val mode = enum(R.string.show_candidates_window, MODE, PopupCandidatesMode.DISABLED)
        val layout = enum(R.string.candidates_layout, LAYOUT, PopupCandidatesLayout.AUTOMATIC)
        val position = enum(R.string.candidates_window_position, POSITION, PopupPosition.BOTTOM_LEFT)

        /**
         * Opacity of the window's own background, in percent.
         *
         * The window floats over the editor, so the background is what hides the text behind it;
         * fading it (the text and the highlighted item stay opaque) buys readability for the app
         * underneath. 100% is the theme's own look.
         */
        val backgroundAlpha = int(
            R.string.candidates_window_background_alpha,
            BACKGROUND_ALPHA,
            100,
            0,
            100,
            "%",
        )

        /**
         * Whether the inset strip the candidates sit on draws a background of its own.
         *
         * On is the composing panel's own look: a bar a shade away from the screen it sits in, so
         * the candidates read as living on a recessed strip. Off is what this bar looked like
         * before the strip existed -- the candidates straight on the screen, with no second
         * surface. The strip's colour is derived from the screen's luminance rather than a theme
         * key, so no theme can bring this back on its own.
         */
        val stripBackground = switch(
            R.string.candidates_strip_background,
            STRIP_BACKGROUND,
            true,
            R.string.candidates_strip_background_summary,
        )

        /**
         * The strip fill's colour, or **0 for automatic**.
         *
         * Automatic is the original derivation: a shade of the opposite of the screen's own
         * luminance, so the bar reads as recessed in any scheme without a theme key saying so.
         * A picked colour replaces it outright (the alpha is kept in [stripOpacity]). The dialog
         * that is the only writer of a non-zero value lives on the settings side, which splits
         * its ARGB result between this key and the opacity one.
         */
        val stripColor = colorPick(
            R.string.candidates_strip_color,
            STRIP_COLOR,
            0,
        )

        /**
         * Opacity of the strip fill, in percent.
         *
         * The alpha of the fill, kept apart from its colour: at 0% the fill disappears (the
         * candidates sit straight on the panel, which is also what switching [stripBackground]
         * off does), and 14% is the look the strip has always had. Applied on top of both the
         * automatic shade and a picked colour.
         */
        val stripOpacity = int(
            R.string.candidates_strip_opacity,
            STRIP_OPACITY,
            14,
            0,
            100,
            "%",
        )

        /**
         * How much of the panel the inset strip may span, in percent.
         *
         * The strip holds the candidates, so shortening it shortens the row: the list is cut at
         * the strip's edge rather than the strip stretching to hold it, which means fewer
         * candidates are in view. 100% is the full-width bar the strip has always been; shorter
         * than that it stays centred on the panel, so it shrinks symmetrically at both ends.
         */
        val stripLength = int(
            R.string.candidates_strip_length,
            STRIP_LENGTH,
            100,
            20,
            100,
            "%",
        )

        /**
         * Breathing room above and below the strip, in dp each.
         *
         * Applied to both vertical margins at once, and the panel itself grows by twice this --
         * once per side -- so the strip keeps the height the theme gives it and the air opens up
         * evenly between it and the panel's top edge as well as the keyboard below.
         */
        val stripGap = int(
            R.string.candidates_strip_gap,
            STRIP_GAP,
            0,
            0,
            24,
            "dp",
        )

        /**
         * Whether the row's two corner toggles stay on screen while a composition is running.
         *
         * They frame the strip -- the menu at its start, the stow (or expand) toggle at its end --
         * and while typing they are the only way into the quick switches. Turning this off hands
         * their width back to the candidates for as long as the composition lasts, at the price of
         * reaching those two controls only between compositions.
         */
        val cornerTogglesWhileComposing = switch(
            R.string.corner_toggles_composing,
            CORNER_TOGGLES_COMPOSING,
            true,
            R.string.corner_toggles_composing_summary,
        )

        /**
         * Whether the quick switches always come up as a window.
         *
         * By default the stow toggle shows them in the strip and only falls back to a window when
         * they would not fit. Turning this on keeps them in the window whatever the width says --
         * for a row that is easier to hit, or a set of switches that keeps outgrowing the strip.
         */
        val switchesInWindow = switch(
            R.string.switches_in_window,
            SWITCHES_IN_WINDOW,
            false,
            R.string.switches_in_window_summary,
        )

        /**
         * Text size of the candidates around the highlighted one, in percent of the theme's
         * own size.
         *
         * The candidate about to be committed keeps the theme's size: it is the one that
         * matters, and the others are drawn smaller so it stays the first thing the eye finds
         * on the row.
         */
        val restFontPercent = int(
            R.string.candidates_rest_font,
            TWO_ROW_FONT,
            75,
            50,
            100,
            "%",
        )

        /**
         * Whether the candidates other than the highlighted one get a background of their own.
         *
         * Only the highlighted candidate is coloured by default, because that colour is what
         * makes the current selection stand out; giving the others a fill as well turns the row
         * into a strip of blocks, which reads as "all of these are selected". The fill is drawn in
         * the theme's separator colour, so it stays subordinate to the highlight and follows a
         * scheme switch. Off keeps the theme's original look.
         */
        val itemBackground = switch(
            R.string.candidates_item_background,
            ITEM_BACKGROUND,
            false,
            R.string.candidates_item_background_summary,
        )

        /**
         * Gap between the candidates of the row, in dp, on **each** side of every candidate.
         *
         * Independent of [itemBackground] on purpose: an item drawn with a fill but no gap runs
         * into its neighbour and the row reads as one continuous block, so the candidates stop
         * looking individually selectable. The gap is a layout property, though, so it also makes
         * sense without a fill -- hence a switch of its own rather than a side effect of the
         * other one. 0dp means "as tight as the theme has it", which is the original behaviour.
         */
        val itemSpacing = int(
            R.string.candidates_item_spacing,
            ITEM_SPACING,
            2,
            0,
            16,
            "dp",
        )

        /**
         * Opacity of the *non-highlighted* candidates' own fill, in percent.
         *
         * The fill's colour comes from the theme, which is free to pick something strong -- or to
         * make it deliberately translucent. This scales that colour's own alpha rather than
         * replacing it, so 100% leaves the theme's choice alone and a lower value dims it further.
         * Only the unhighlighted fill is affected; the highlighted candidate stays at full strength,
         * since that contrast is what marks the selection.
         */
        val itemBackgroundAlpha = int(
            R.string.candidates_item_background_alpha,
            ITEM_BACKGROUND_ALPHA,
            100,
            0,
            100,
            "%",
        )

        /**
         * How the candidate row behaves when it has more to show than fits.
         *
         * These four lived on the keyboard page, which split one topic — "how do candidates look and
         * behave" — across two screens and left both harder to find. They are here now, with the
         * window and item settings they belong to.
         */
        val horizontalCandidateMode = enum(
            R.string.horizontal_candidate_style,
            HORIZONTAL_CANDIDATE_MODE,
            CompactCandidateMode.NEVER_FILL,
        )

        val maxSpanCount = int(
            R.string.max_span_count,
            MAX_SPAN_COUNT,
            6,
            1,
            10,
            enableUiOn = {
                shared.getString(HORIZONTAL_CANDIDATE_MODE, null) ==
                    CompactCandidateMode.AUTO_FILL.name
            },
        )

        val maxSpanCountLandscape = int(
            R.string.max_span_count_landscape,
            MAX_SPAN_COUNT_LANDSCAPE,
            8,
            4,
            12,
            enableUiOn = {
                shared.getString(HORIZONTAL_CANDIDATE_MODE, null) ==
                    CompactCandidateMode.AUTO_FILL.name
            },
        )
    }

    /**
     *  Wrapper class of profile settings.
     */
    class Profile(
        shared: SharedPreferences,
    ) : PreferenceDelegateOwner(shared) {

        companion object {
            const val EXTERNAL_RIME_TREE_URI = "profile_external_rime_tree_uri"
            const val EXTERNAL_RIME_DISPLAY_NAME = "profile_external_rime_display_name"
            const val DATA_STORAGE_MODE = "profile_data_storage_mode"
            const val PERIODIC_BACKGROUND_SYNC = "periodic_background_sync"
            const val PERIODIC_BACKGROUND_SYNC_INTERVAL = "periodic_background_sync_interval"
            const val LAST_BACKGROUND_SYNC_STATUS = "last_background_sync_status"
            const val LAST_BACKGROUND_SYNC_TIME = "last_background_sync_time"
            const val USER_DB_MIGRATED = "profile_user_db_migrated"
        }

        val dataStorageMode = enum(R.string.data_storage_mode, DATA_STORAGE_MODE, DataStorageMode.EXTERNAL_SYNC)
        val externalRimeTreeUri = string(EXTERNAL_RIME_TREE_URI, "")
        val externalRimeDisplayName = string(EXTERNAL_RIME_DISPLAY_NAME, "")
        val userDbMigrated = bool(USER_DB_MIGRATED, false)
        val periodicBackgroundSync = bool(PERIODIC_BACKGROUND_SYNC, false)
        val periodicBackgroundSyncInterval = int(PERIODIC_BACKGROUND_SYNC_INTERVAL, 30)
        val lastBackgroundSyncStatus = bool(LAST_BACKGROUND_SYNC_STATUS, false)
        val lastBackgroundSyncTime = long(LAST_BACKGROUND_SYNC_TIME, 0L)
    }

    class Clipboard(
        shared: SharedPreferences,
    ) : PreferenceDelegateOwner(shared, R.string.clipboard) {
        companion object {
            const val CLIPBOARD_LISTENING = "clipboard_listening"
            const val CLIPBOARD_LIMIT = "clipboard_clipboard_limit"
            const val CLIPBOARD_COMPARE_RULES = "clipboard_clipboard_compare"
            const val CLIPBOARD_OUTPUT_RULES = "clipboard_clipboard_output"
            const val CLIPBOARD_SUGGESTION = "clipboard_suggestion"
            const val CLIPBOARD_SUGGESTION_TIMEOUT = "clipboard_suggestion_timeout"
            const val CLIPBOARD_RETURN_AFTER_PASTE = "clipboard_return_after_paste"
        }
        val clipboardListening = switch(R.string.clipboard_listening, CLIPBOARD_LISTENING, true)
        val clipboardLimit = int(
            R.string.clipboard_limit,
            CLIPBOARD_LIMIT,
            10,
        ) { clipboardListening.getValue() }
        val clipboardCompareRules = editText(
            R.string.clipboard_compare_rules,
            CLIPBOARD_COMPARE_RULES,
            "",
            R.string.a_regular_expression_per_line,
        ) { clipboardListening.getValue() }
        val clipboardOutputRules = editText(
            R.string.clipboard_output_rules,
            CLIPBOARD_OUTPUT_RULES,
            "",
            R.string.a_regular_expression_per_line,
        ) { clipboardListening.getValue() }
        val clipboardSuggestion = switch(
            R.string.clipboard_suggestion,
            CLIPBOARD_SUGGESTION,
            true,
        ) { clipboardListening.getValue() }
        val clipboardSuggestionTimeout = int(
            R.string.clipboard_suggestion_timeout,
            CLIPBOARD_SUGGESTION_TIMEOUT,
            20,
            0,
            100,
            "s",
        ) { clipboardListening.getValue() && clipboardSuggestion.getValue() }
        val clipboardReturnAfterPaste = switch(
            R.string.clipboard_return_after_paste,
            CLIPBOARD_RETURN_AFTER_PASTE,
            true,
        ) { clipboardListening.getValue() }
    }

    class Advanced(
        shared: SharedPreferences,
    ) : PreferenceDelegateOwner(shared, R.string.advanced) {
        companion object {
            const val UI_MODE = "ui_mode"
            const val SHOW_APP_ICON = "show_app_icon"
            const val IGNORE_SYSTEM_GESTURE_INSETS = "ignore_system_gesture_insets"
        }

        enum class UiMode(override val stringRes: Int) : PreferenceDelegateEnum {
            AUTO(R.string.automatic),
            LIGHT(R.string.light),
            DARK(R.string.dark),
        }

        val uiMode = enum(R.string.ui_mode, UI_MODE, UiMode.AUTO)
        val showAppIcon = switch(
            R.string.show_app_icon,
            SHOW_APP_ICON,
            true,
            R.string.only_available_on_some_roms,
        )
        val ignoreSystemGestureInsets = switch(
            R.string.ignore_system_gesture_insets,
            IGNORE_SYSTEM_GESTURE_INSETS,
            false,
        )
    }
}
