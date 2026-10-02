package com.ee555719.golinux.display

import androidx.compose.ui.input.key.Key

/**
 * X11 keysym constants and Android → keysym mappings (US layout).
 */
object Keysyms {

    const val BACKSPACE = 0xff08
    const val TAB = 0xff09
    const val RETURN = 0xff0d
    const val ESC = 0xff1b
    const val HOME = 0xff50
    const val LEFT = 0xff51
    const val UP = 0xff52
    const val RIGHT = 0xff53
    const val DOWN = 0xff54
    const val PAGE_UP = 0xff55
    const val PAGE_DOWN = 0xff56
    const val END = 0xff57
    const val INSERT = 0xff63
    const val DELETE = 0xffff
    const val SHIFT_L = 0xffe1
    const val SHIFT_R = 0xffe2
    const val CONTROL_L = 0xffe3
    const val CONTROL_R = 0xffe4
    const val META_L = 0xffe7
    const val META_R = 0xffe8
    const val ALT_L = 0xffe9
    const val ALT_R = 0xffea
    const val F1 = 0xffbe // F1..F12 = F1 + n - 1

    /** shifted symbol → (base keysym, needs shift) - US layout. */
    private val shifted = mapOf(
        '~' to '`', '!' to '1', '@' to '2', '#' to '3', '$' to '4', '%' to '5',
        '^' to '6', '&' to '7', '*' to '8', '(' to '9', ')' to '0', '_' to '-',
        '+' to '=', '{' to '[', '}' to ']', '|' to '\\', ':' to ';', '"' to '\'',
        '<' to ',', '>' to '.', '?' to '/'
    )

    /**
     * Keysym for a printable character plus whether Shift must be held.
     * Returns null for characters without a mapping.
     */
    fun charKeysym(c: Char): Pair<Int, Boolean>? = when {
        c in 'a'..'z' -> c.code to false
        c in 'A'..'Z' -> (c.code + 32) to true
        c in shifted.keys -> shifted.getValue(c).code to true
        c in '0'..'9' -> c.code to false
        c == '`' || c == '-' || c == '=' || c == '[' || c == ']' ||
            c == '\\' || c == ';' || c == '\'' || c == ',' || c == '.' ||
            c == '/' || c == ' ' -> c.code to false
        c == '\n' || c == '\r' -> RETURN to false
        c == '\t' -> TAB to false
        c == '\b' -> BACKSPACE to false
        c.code in 0x20..0xff -> c.code to false
        // X keysyms for Unicode code points (best effort)
        else -> (0x01000000 or c.code) to false
    }

    /** Android Compose Key → keysym, for special keys only (null = not handled here). */
    fun fromComposeKey(key: Key): Int? = when (key) {
        Key.Enter, Key.NumPadEnter -> RETURN
        Key.Tab -> TAB
        Key.Backspace -> BACKSPACE
        Key.Delete -> DELETE
        Key.Escape -> ESC
        Key.DirectionLeft -> LEFT
        Key.DirectionRight -> RIGHT
        Key.DirectionUp -> UP
        Key.DirectionDown -> DOWN
        Key.PageUp -> PAGE_UP
        Key.PageDown -> PAGE_DOWN
        Key.MoveHome -> HOME
        Key.MoveEnd -> END
        Key.ShiftLeft -> SHIFT_L
        Key.ShiftRight -> SHIFT_R
        Key.CtrlLeft -> CONTROL_L
        Key.CtrlRight -> CONTROL_R
        Key.AltLeft -> ALT_L
        Key.AltRight -> ALT_R
        Key.MetaLeft -> META_L
        Key.MetaRight -> META_R
        Key.F1 -> F1
        Key.F2 -> F1 + 1
        Key.F3 -> F1 + 2
        Key.F4 -> F1 + 3
        Key.F5 -> F1 + 4
        Key.F6 -> F1 + 5
        Key.F7 -> F1 + 6
        Key.F8 -> F1 + 7
        Key.F9 -> F1 + 8
        Key.F10 -> F1 + 9
        Key.F11 -> F1 + 10
        Key.F12 -> F1 + 11
        else -> null
    }
}
