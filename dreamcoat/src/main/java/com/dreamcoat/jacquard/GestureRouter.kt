package com.dreamcoat.jacquard

import android.content.SharedPreferences
import com.dreamcoat.hid.HidBridgeManager
import com.dreamcoat.hid.HidDescriptor.MOD_LEFT_ALT
import com.dreamcoat.hid.KeyMap
import com.google.android.jacquard.sdk.model.Gesture.GestureType

/** The two gesture profiles from SPEC §4.1. */
enum class Mode(val label: String, val r: Int, val g: Int, val b: Int) {
    PRESENTATION("Slides", 0, 255, 255), // cyan
    LLM_DEMO("LLM demo", 160, 0, 255),   // purple
}

/** Everything a sleeve gesture can be mapped to (shown in the dashboard dropdowns). */
enum class Action(val label: String) {
    NONE("Do nothing"),
    RIGHT_ARROW("Next slide"),
    LEFT_ARROW("Prev slide"),
    PAGE_DOWN("Page Down"),
    PAGE_UP("Page Up"),
    ENTER("Enter"),
    SPACE("Space"),
    ESCAPE("Escape"),
    BACKSPACE("Backspace"),
    ALT_TAB("Alt + Tab"),
    F5("F5 / start"),
    LEFT_CLICK("Left click"),
    RESUME_SHOW("Present"),
    SEND_PROMPT("Send prompt"),
    TOGGLE_MODE("Swap mode"),
}

/** The gestures the dashboard lets you map, in display order. */
val MAPPABLE_GESTURES = listOf(
    GestureType.BRUSH_OUT, GestureType.BRUSH_IN, GestureType.DOUBLE_TAP, GestureType.SHORT_COVER,
)

/**
 * Routes tag gestures through the active profile to HID actions, and fires LED/haptic feedback.
 */
class GestureRouter(
    private val prefs: SharedPreferences,
    private val hid: HidBridgeManager,
    /** Null without jacket support; gestures then come only from the watch. */
    private val link: JacquardLink?,
    private val sendPrompt: () -> Unit,
    private val resumeShow: () -> Unit,
    private val onModeChanged: (Mode) -> Unit,
) {
    var mode: Mode = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, null)!!) }
        .getOrDefault(Mode.PRESENTATION)
        private set

    fun actionFor(mode: Mode, gesture: GestureType): Action =
        prefs.getString(key(mode, gesture), null)
            ?.let { runCatching { Action.valueOf(it) }.getOrNull() }
            ?: defaultAction(mode, gesture)

    fun setAction(mode: Mode, gesture: GestureType, action: Action) {
        prefs.edit().putString(key(mode, gesture), action.name).apply()
    }

    fun setMode(m: Mode) {
        mode = m
        prefs.edit().putString(KEY_MODE, m.name).apply()
        onModeChanged(m)
    }

    /** Returns the action performed. [fromWatch] skips tag LED/haptics; the watch buzzes itself. */
    fun onGesture(gesture: GestureType, fromWatch: Boolean = false): Action {
        val action = actionFor(mode, gesture)
        perform(action)
        if (!fromWatch) feedback(gesture, action)
        return action
    }

    private fun perform(action: Action) {
        when (action) {
            Action.NONE -> Unit
            Action.RIGHT_ARROW -> hid.sendKeyTap(KeyMap.RIGHT_ARROW)
            Action.LEFT_ARROW -> hid.sendKeyTap(KeyMap.LEFT_ARROW)
            Action.PAGE_DOWN -> hid.sendKeyTap(KeyMap.PAGE_DOWN)
            Action.PAGE_UP -> hid.sendKeyTap(KeyMap.PAGE_UP)
            Action.ENTER -> hid.sendKeyTap(KeyMap.ENTER)
            Action.SPACE -> hid.sendKeyTap(KeyMap.SPACE)
            Action.ESCAPE -> hid.sendKeyTap(KeyMap.ESCAPE)
            Action.BACKSPACE -> hid.sendKeyTap(KeyMap.BACKSPACE)
            Action.ALT_TAB -> hid.sendKeyTap(KeyMap.TAB, MOD_LEFT_ALT)
            Action.F5 -> hid.sendKeyTap(KeyMap.F5)
            Action.LEFT_CLICK -> hid.click(HidBridgeManager.BUTTON_LEFT)
            Action.RESUME_SHOW -> resumeShow()
            Action.SEND_PROMPT -> sendPrompt()
            Action.TOGGLE_MODE -> setMode(if (mode == Mode.PRESENTATION) Mode.LLM_DEMO else Mode.PRESENTATION)
        }
    }

    private fun feedback(gesture: GestureType, action: Action) {
        val link = link ?: return
        if (action == Action.TOGGLE_MODE) {
            link.haptic(2)
            link.led(mode.r, mode.g, mode.b, 2000, solid = true)
            return
        }
        when (gesture) {
            GestureType.BRUSH_OUT -> link.led(0, 255, 0, 300)
            GestureType.BRUSH_IN -> link.led(0, 0, 255, 300)
            else -> link.haptic(1)
        }
    }

    companion object {
        private const val KEY_MODE = "mode"
        private fun key(mode: Mode, g: GestureType) = "map_${mode.name}_${g.name}"

        fun defaultAction(mode: Mode, gesture: GestureType): Action = when (mode) {
            Mode.PRESENTATION -> when (gesture) {
                GestureType.BRUSH_OUT -> Action.RIGHT_ARROW
                GestureType.BRUSH_IN -> Action.LEFT_ARROW
                GestureType.DOUBLE_TAP -> Action.ENTER
                GestureType.SHORT_COVER -> Action.TOGGLE_MODE
                else -> Action.NONE
            }
            Mode.LLM_DEMO -> when (gesture) {
                GestureType.BRUSH_OUT -> Action.ALT_TAB
                GestureType.BRUSH_IN -> Action.ESCAPE
                GestureType.DOUBLE_TAP -> Action.SEND_PROMPT
                GestureType.SHORT_COVER -> Action.TOGGLE_MODE
                else -> Action.NONE
            }
        }
    }
}
