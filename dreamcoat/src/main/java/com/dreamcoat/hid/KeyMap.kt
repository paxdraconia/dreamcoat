package com.dreamcoat.hid

import com.dreamcoat.hid.HidDescriptor.MOD_LEFT_SHIFT

/** USB HID usage IDs and a US-English-layout ASCII map. */
object KeyMap {
    /** "B" — blacks out the screen in PowerPoint / Google Slides slideshows. */
    const val B = 0x05
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val F5 = 0x3E
    const val PAGE_UP = 0x4B
    const val PAGE_DOWN = 0x4E
    const val RIGHT_ARROW = 0x4F
    const val LEFT_ARROW = 0x50
    const val DOWN_ARROW = 0x51
    const val UP_ARROW = 0x52

    data class Stroke(val modifier: Int, val usage: Int)

    private val unshifted = mapOf(
        '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31,
        ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37, '/' to 0x38,
    )
    private val shifted = mapOf(
        '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31,
        ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37, '?' to 0x38,
        '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22,
        '^' to 0x23, '&' to 0x24, '*' to 0x25, '(' to 0x26, ')' to 0x27,
    )

    /** Returns null for characters a US layout can't type (they are skipped, never mistyped). */
    fun strokeFor(c: Char): Stroke? = when (c) {
        in 'a'..'z' -> Stroke(0, 0x04 + (c - 'a'))
        in 'A'..'Z' -> Stroke(MOD_LEFT_SHIFT, 0x04 + (c - 'A'))
        in '1'..'9' -> Stroke(0, 0x1E + (c - '1'))
        '0' -> Stroke(0, 0x27)
        '\n' -> Stroke(0, ENTER)
        '\t' -> Stroke(0, TAB)
        ' ' -> Stroke(0, SPACE)
        else -> unshifted[c]?.let { Stroke(0, it) } ?: shifted[c]?.let { Stroke(MOD_LEFT_SHIFT, it) }
    }

    /** Replaces typographic characters that dictation keyboards insert with ASCII equivalents. */
    fun normalize(text: String): String = text
        .replace('‘', '\'').replace('’', '\'')
        .replace('“', '"').replace('”', '"')
        .replace('–', '-').replace('—', '-')
        .replace("…", "...")
        .replace(' ', ' ')
        .replace("\r\n", "\n")
}
