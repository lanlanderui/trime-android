// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.graphics.drawable.Drawable
import android.view.KeyEvent
import androidx.annotation.ColorInt
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.KeyActionManager
import com.osfans.trime.data.theme.model.TextKeyboard
import splitties.bitflags.hasFlag
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/** [鍵盤][Keyboard]中的各個按鍵，包含單擊、長按、滑動等多種[事件][KeyAction]  */
@Suppress("ktlint:standard:mixed-condition-operators")
class Key(
    private val parent: Keyboard,
    private val selfConfig: TextKeyboard.TextKey? = null,
) {
    private val rime get() = RimeDaemon.getFirstSessionOrNull()!!

    var index: Int = -1

    val keyActions: Map<KeyBehavior, KeyAction> =
        selfConfig?.behaviors?.mapNotNull { (key, value) ->
            if (value != null) {
                key to KeyActionManager.getAction(value)
            } else {
                null
            }
        }?.toMap() ?: mapOf()
    var edgeFlags = 0
    private val sendBindings: Boolean

    var isPressed = false
        private set
    var isOn = false
        private set

    var x = 0
    var y = 0

    var width = 0
    var height = 0
    var gap = 0
    var row = 0
    var column = 0

    var extraWidthLeft = 0
    var extraWidthRight = 0

    private val label = selfConfig?.label ?: ""
    private val labelSymbol = selfConfig?.labelSymbol ?: ""
    val hint: String = selfConfig?.hint ?: ""
    val popup = selfConfig?.popup ?: emptyList()

    val keyTextSize: Float = selfConfig?.keyTextSize ?: 0f
    val symbolTextSize: Float = selfConfig?.symbolTextSize ?: 0f
    val roundCorner: Float = selfConfig?.roundCorner?.takeIf { it >= 0 } ?: parent.roundCorner
    val keyBorder: Int = selfConfig?.keyBorder?.takeIf { it >= 0 } ?: parent.keyBorder
    var keyTextOffsetX = 0f
        get() = field + keyOffsetX
    var keyTextOffsetY = 0f
        get() = field + keyOffsetY
    var keySymbolOffsetX = 0f
        get() = field + keyOffsetX
    var keySymbolOffsetY = 0f
        get() = field + keyOffsetY
    var keyHintOffsetX = 0f
        get() = field + keyOffsetX
    var keyHintOffsetY = 0f
        get() = field + keyOffsetY
    var keyPressOffsetX = 0f
    var keyPressOffsetY = 0f

    /** The built-in dynamic scheme is the active one. Cached per colour-scheme generation. */
    private val dynamicPalette: Boolean by schemeColor { ColorManager.isDynamicSchemeActive }

    /**
     * Whether a colour the theme binds to this one key still applies.
     *
     * Not under the dynamic scheme. That scheme paints keys by kind — see [appearanceType] — so a
     * keyboard's `style: {key_back_color: bgn}` would have to be honoured for shift, backspace and
     * return to keep a theme's own look, and ignoring it is what leaves every one of them reading
     * as the same function key. The two palettes are also unrelated: `bgn` is whatever the theme
     * chose, the scheme's colours come from the wallpaper.
     */
    private val useThemedKeyColors: Boolean get() = !dynamicPalette

    // get color from key customization or just fallback to specified color
    private fun getColor(
        src: TextKeyboard.TextKey.() -> String,
        fallback: String,
    ): Int = selfConfig?.takeIf { useThemedKeyColors }?.let {
        runCatching { ColorManager.getColor(src(it)) }.getOrNull()
    } ?: ColorManager.getColor(fallback)

    // get color from common color schemes or just fallback to default color
    private fun getColor(
        key: String,
        @ColorInt default: Int,
    ): Int = runCatching { ColorManager.getColor(key) }.getOrDefault(default)

    /**
     * A colour a theme binds to this one key, or [fallback] when the theme may not have its way.
     *
     * Same rule as [useThemedKeyColors], for the function-key palettes that read the theme's
     * per-key colour by name instead of through [getDrawable].
     */
    private fun themedKeyColor(
        src: TextKeyboard.TextKey.() -> String,
        fallback: Int,
    ): Int {
        val key = selfConfig?.takeIf { useThemedKeyColors }?.let { src(it) }
        return if (key.isNullOrEmpty()) fallback else getColor(key, fallback)
    }

    private fun getDrawable(
        src: TextKeyboard.TextKey.() -> String,
        fallback: String,
    ) = selfConfig?.takeIf { useThemedKeyColors }?.let {
        if (src(it).isEmpty()) null else ColorManager.getDrawable(src(it))
    } ?: ColorManager.getDrawable(fallback)

    // Colors are cached per color-scheme generation: the cache re-resolves
    // when the active scheme changes, so an invalidated keyboard view picks up
    // the new colors without being rebuilt.
    private class SchemeColorCache<T>(private val resolve: () -> T) : ReadOnlyProperty<Any?, T> {
        private var generation = Long.MIN_VALUE
        private var value: T? = null

        override fun getValue(
            thisRef: Any?,
            property: KProperty<*>,
        ): T {
            val current = ColorManager.colorGeneration
            if (generation != current) {
                generation = current
                value = resolve()
            }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
    }

    private fun <T> schemeColor(resolve: () -> T) = SchemeColorCache(resolve)

    private val keyBackground by schemeColor { getDrawable({ keyBackColor }, "key_back_color") }
    private val offKeyBackground by schemeColor { ColorManager.getDrawable("off_key_back_color") ?: keyBackground }
    private val onKeyBackground by schemeColor { ColorManager.getDrawable("on_key_back_color") ?: keyBackground }
    private val hlKeyBackground by schemeColor { getDrawable({ hlKeyBackColor }, "hilited_key_back_color") }
    private val hlOffKeyBackground by schemeColor { ColorManager.getDrawable("hilited_off_key_back_color") ?: hlKeyBackground }
    private val hlOnKeyBackground by schemeColor { ColorManager.getDrawable("hilited_on_key_back_color") ?: hlKeyBackground }

    private val keyBorderColor by schemeColor { getColor({ keyBorderColor }, "key_border_color") }
    private val offKeyBorderColor by schemeColor { getColor("off_key_border_color", keyBorderColor) }
    private val onKeyBorderColor by schemeColor { getColor("on_key_border_color", keyBorderColor) }
    private val hlKeyBorderColor by schemeColor { getColor({ hlKeyBorderColor }, "hilited_key_border_color") }
    private val hlOffKeyBorderColor by schemeColor { getColor("hilited_off_key_border_color", hlKeyBorderColor) }
    private val hlOnKeyBorderColor by schemeColor { getColor("hilited_on_key_border_color", hlKeyBorderColor) }

    private val keyTextColor by schemeColor { getColor({ keyTextColor }, "key_text_color") }
    private val offKeyTextColor by schemeColor { getColor("off_key_text_color", keyTextColor) }
    private val onKeyTextColor by schemeColor { getColor("on_key_text_color", keyTextColor) }
    private val hlKeyTextColor by schemeColor { getColor({ hlKeyTextColor }, "hilited_key_text_color") }
    private val hlOffKeyTextColor by schemeColor { getColor("hilited_off_key_text_color", hlKeyTextColor) }
    private val hlOnKeyTextColor by schemeColor { getColor("hilited_on_key_text_color", hlKeyTextColor) }
    private val keySymbolColor by schemeColor { getColor({ keySymbolColor }, "key_symbol_color") }
    private val offKeySymbolColor by schemeColor { getColor("off_key_symbol_color", keySymbolColor) }
    private val onKeySymbolColor by schemeColor { getColor("on_key_symbol_color", keySymbolColor) }
    private val hlKeySymbolColor by schemeColor { getColor({ hlKeySymbolColor }, "hilited_key_symbol_color") }
    private val hlOffKeySymbolColor by schemeColor { getColor("hilited_off_key_symbol_color", hlKeySymbolColor) }
    private val hlOnKeySymbolColor by schemeColor { getColor("hilited_on_key_symbol_color", hlKeySymbolColor) }

    init {
        if (selfConfig != null) {
            val hasStateDependentBehavior = selfConfig.behaviors.keys.any { it < KeyBehavior.COMBO }
            if (hasStateDependentBehavior) parent.appearanceStateKeys.add(this)
            sendBindings = selfConfig.sendBindings || hasStateDependentBehavior
        } else {
            sendBindings = true
        }
        parent.setModifierKey(this.code, this)
    }

    fun setOn(on: Boolean): Boolean {
        isOn = if (on && isOn) false else on
        return isOn
    }

    private val keyOffsetX: Float
        get() = if (isPressed) keyPressOffsetX else 0f
    private val keyOffsetY: Float
        get() = if (isPressed) keyPressOffsetY else 0f

    /**
     * Informs the key that it has been pressed, in case it needs to change its appearance or state.
     *
     * @see .onReleased
     */
    fun onPressed() {
        isPressed = true
    }

    /**
     * Changes the pressed state of the key. If it is a sticky key, it will also change the toggled
     * state of the key if the finger was release inside.
     *
     * @see .onPressed
     */
    fun onReleased() {
        isPressed = false
        if (click!!.isSticky) isOn = !isOn
    }

    /**
     * Detects if a point falls inside this key.
     *
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return whether or not the point falls inside the key. If the key is attached to an edge, it
     * will assume that all points between the key and the edge are considered to be inside the
     * key.
     */
    fun isInside(
        x: Int,
        y: Int,
    ): Boolean {
        val leftEdge = edgeFlags and Keyboard.EDGE_LEFT > 0
        val rightEdge = edgeFlags and Keyboard.EDGE_RIGHT > 0
        val topEdge = edgeFlags and Keyboard.EDGE_TOP > 0
        val bottomEdge = edgeFlags and Keyboard.EDGE_BOTTOM > 0
        return (
            (x >= this.x || leftEdge && x <= this.x + width) &&
                (x < this.x + width || rightEdge && x >= this.x) &&
                (y >= this.y || topEdge && y <= this.y + height) &&
                (y < this.y + height || bottomEdge && y >= this.y)
            )
    }

    /**
     * Returns the square of the distance between the center of the key and the given point.
     *
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return the square of the distance of the point from the center of the key
     */
    fun squaredDistanceFrom(
        x: Int,
        y: Int,
    ): Int {
        val xDist = this.x + width / 2 - x
        val yDist = this.y + height / 2 - y
        return xDist * xDist + yDist * yDist
    }

    val isShift: Boolean
        get() = this.code == KeyEvent.KEYCODE_SHIFT_LEFT || this.code == KeyEvent.KEYCODE_SHIFT_RIGHT

    /**
     * @param behavior 同文按键模式（点击/长按/滑动）
     * @return
     */
    fun sendBindings(behavior: KeyBehavior): Boolean = keyActions[behavior]?.takeIf { behavior != KeyBehavior.CLICK } != null || checkKeyAction(sendBindings) != null

    private val keyAction: KeyAction?
        get() = checkKeyAction() ?: click

    val click: KeyAction?
        get() = keyActions[KeyBehavior.CLICK]
    val longClick: KeyAction?
        get() = keyActions[KeyBehavior.LONG_CLICK]

    fun hasAction(behavior: KeyBehavior): Boolean = keyActions[behavior] != null

    /**
     * Re-reads the runtime options every behavior of this key toggles.
     *
     * Blocks until rime answers, so it must only be called when a keyboard is
     * (re)built -- never from the draw path.
     */
    fun refreshToggleStates() = keyActions.values.forEach { it.refreshToggleState() }

    /** Applies an option change reported by rime, without querying the native layer. */
    fun updateToggleState(
        option: String,
        value: Boolean,
    ) = keyActions.values.forEach { it.updateToggleState(option, value) }

    fun getAction(behavior: KeyBehavior): KeyAction? = keyActions[behavior]?.takeIf { behavior != KeyBehavior.CLICK } ?: checkKeyAction(sendBindings) ?: click

    private fun checkKeyAction(): KeyAction? {
        val session = rime
        val status = session.status
        val asciiMode = status.isAsciiMode
        val paging = session.paging
        val hasMenu = session.hasMenu
        val composing = status.isComposing
        return keyActions[KeyBehavior.ASCII].takeIf { asciiMode }
            ?: keyActions[KeyBehavior.PAGING]?.takeIf { paging }
            ?: keyActions[KeyBehavior.HAS_MENU]?.takeIf { hasMenu }
            ?: keyActions[KeyBehavior.COMPOSING]?.takeIf { composing }
    }

    private fun checkKeyAction(sendBindings: Boolean): KeyAction? = checkKeyAction().takeIf { sendBindings }

    val code: Int
        get() = click?.code ?: KeyEvent.KEYCODE_UNKNOWN

    fun getCode(behavior: KeyBehavior): Int = getAction(behavior)!!.code

    fun getLabel(): String = when {
        label.isNotEmpty() &&
            keyAction == click &&
            !keyActions.containsKey(KeyBehavior.ASCII) &&
            !rime.status.let { it.isAsciiMode || it.isAsciiPunct } -> label

        else -> keyAction!!.getLabel(parent) // 中文狀態顯示標籤
    }

    fun getPreviewText(behavior: KeyBehavior): String = when (behavior) {
        KeyBehavior.CLICK -> keyAction!!.getPreview(parent)
        else -> getAction(behavior)!!.getPreview(parent)
    }

    val symbolLabel: String
        get() = labelSymbol.ifEmpty { longClick?.getLabel(parent) ?: "" }

    /**
     * Which of the three key palettes this key draws from.
     *
     * Outside the dynamic scheme the theme decides, and behaviour is the only thing left to sort
     * keys by: `2` for a modifier that is currently on, `1` for a sticky or functional key, `0`
     * for the rest, each backed by the theme's per-key colours.
     *
     * The dynamic scheme has no per-key colours to consult (see [useThemedKeyColors]), so it has
     * to sort keys by what they type — [keyPaletteOf] — or a key its author never named would
     * have no colour at all. The on/modifier state keeps its own palette either way, so shift
     * still lights up while it is held.
     */
    private val appearanceType: Int
        get() {
            val modifierActive = click?.isModifierKey == true && parent.modifier.hasFlag(click!!.modifierKeyOnMask)
            return when {
                isOn || modifierActive -> 2

                dynamicPalette ->
                    when (keyPaletteOf(click?.text.orEmpty(), code)) {
                        KeyPalette.TEXT -> 0
                        KeyPalette.FUNCTION -> 1
                    }

                click?.isSticky == true || click?.isFunctional == true -> 1
                else -> 0
            }
        }

    fun getBackgroundDrawable(): Drawable? = when (appearanceType) {
        2 -> if (isPressed) hlOnKeyBackground else onKeyBackground

        1 -> {
            if (isPressed) {
                hlOffKeyBackground
            } else {
                // The theme's per-key colour, when it is allowed to apply at all: it is not (see
                // [useThemedKeyColors]), and it is not part of the function palette.
                selfConfig
                    ?.takeIf { useThemedKeyColors }
                    ?.keyBackColor
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { keyBackground }
                    ?: offKeyBackground
            }
        }

        else -> if (isPressed) hlKeyBackground else keyBackground
    }

    fun getBorderColor(): Int = when (appearanceType) {
        2 -> if (isPressed) hlOnKeyBorderColor else onKeyBorderColor
        1 -> if (isPressed) hlOffKeyBorderColor else themedKeyColor({ keyBorderColor }, offKeyBorderColor)
        else -> if (isPressed) hlKeyBorderColor else keyBorderColor
    }

    fun getTextColor(): Int = when (appearanceType) {
        2 -> if (isPressed) hlOnKeyTextColor else onKeyTextColor
        1 -> if (isPressed) hlOffKeyTextColor else themedKeyColor({ keyTextColor }, offKeyTextColor)
        else -> if (isPressed) hlKeyTextColor else keyTextColor
    }

    fun getSymbolColor(): Int = when (appearanceType) {
        2 -> if (isPressed) hlOnKeySymbolColor else onKeySymbolColor
        1 -> if (isPressed) hlOffKeySymbolColor else themedKeyColor({ keySymbolColor }, offKeySymbolColor)
        else -> if (isPressed) hlKeySymbolColor else keySymbolColor
    }
}

private const val ICON_PREFIX = "ic@"

val String.isIconFont: Boolean
    get() = startsWith(ICON_PREFIX)

fun String.toIconName(): String = replace(ICON_PREFIX, "cmd_")
