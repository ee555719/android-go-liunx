package com.ee555719.golinux.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Small VT100/xterm-ish terminal emulator: enough for interactive shells,
 * full-screen editors and common TUIs. Not a complete VT500 clone.
 */
class TerminalEmulator(
    val cols: Int = 100,
    val rows: Int = 30,
    private val maxLines: Int = 2000
) {

    private class Row(val cols: Int) {
        val chars = CharArray(cols) { ' ' }
        val attrs = IntArray(cols) // bit0 bold, bit1 inverse
        val fg = IntArray(cols) { DEFAULT_COLOR }
        val bg = IntArray(cols) { DEFAULT_COLOR }

        fun clearAll() {
            chars.fill(' ')
            attrs.fill(0)
            fg.fill(DEFAULT_COLOR)
            bg.fill(DEFAULT_COLOR)
        }

        fun clearRange(from: Int, to: Int) {
            val s = from.coerceIn(0, cols)
            val e = to.coerceIn(0, cols)
            for (i in s until e) {
                chars[i] = ' '
                attrs[i] = 0
                fg[i] = DEFAULT_COLOR
                bg[i] = DEFAULT_COLOR
            }
        }
    }

    private val lines = ArrayList<Row>()

    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var curFg = DEFAULT_COLOR
    private var curBg = DEFAULT_COLOR
    private var curFlags = 0
    private var cursorVisible = true

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    private enum class PState { NORMAL, ESC, CSI, OSC, OSC_ESC, CHARSET }

    private var pState = PState.NORMAL
    private val csiParams = StringBuilder()
    private var csiPrivate = false

    private val decoder: CharsetDecoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val byteBuf = ByteBuffer.allocate(8192)
    private val charBuf = CharBuffer.allocate(8192)

    init {
        repeat(rows) { lines.add(Row(cols)) }
    }

    @Synchronized
    fun feed(data: ByteArray, length: Int) {
        var off = 0
        while (off < length) {
            val n = minOf(length - off, byteBuf.capacity())
            byteBuf.clear()
            byteBuf.put(data, off, n)
            decodeChunk()
            off += n
        }
        _version.value = _version.value + 1
    }

    private fun decodeChunk() {
        byteBuf.flip()
        while (true) {
            charBuf.clear()
            val result = decoder.decode(byteBuf, charBuf, false)
            charBuf.flip()
            while (charBuf.hasRemaining()) handleChar(charBuf.get())
            if (!result.isOverflow) break
        }
        byteBuf.compact()
    }

    private fun handleChar(c: Char) {
        when (pState) {
            PState.NORMAL -> when (c) {
                '\u001b' -> pState = PState.ESC
                '\n', 0x0b.toChar(), 0x0c.toChar() -> lineFeed()
                '\r' -> cursorCol = 0
                '\b' -> if (cursorCol > 0) cursorCol--
                '\t' -> cursorCol = minOf(cols - 1, ((cursorCol / 8) + 1) * 8)
                '\u0007' -> {}
                else -> if (c.code >= 32) putChar(c)
            }
            PState.ESC -> {
                when (c) {
                    '[' -> {
                        pState = PState.CSI
                        csiParams.setLength(0)
                        csiPrivate = false
                    }
                    ']' -> pState = PState.OSC
                    '7' -> {
                        savedRow = cursorRow; savedCol = cursorCol; pState = PState.NORMAL
                    }
                    '8' -> {
                        cursorRow = savedRow; cursorCol = savedCol; pState = PState.NORMAL
                    }
                    'M' -> {
                        if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow--
                        pState = PState.NORMAL
                    }
                    'D' -> {
                        lineFeed(); pState = PState.NORMAL
                    }
                    'E' -> {
                        cursorCol = 0; lineFeed(); pState = PState.NORMAL
                    }
                    'c' -> {
                        fullReset(); pState = PState.NORMAL
                    }
                    '(', ')' -> pState = PState.CHARSET
                    else -> pState = PState.NORMAL
                }
            }
            PState.CHARSET -> pState = PState.NORMAL
            PState.CSI -> when {
                c in '0'..'9' || c == ';' -> csiParams.append(c)
                c == '?' -> csiPrivate = true
                else -> {
                    handleCsi(c, csiParams.toString(), csiPrivate)
                    pState = PState.NORMAL
                }
            }
            PState.OSC -> when {
                c == '\u0007' -> pState = PState.NORMAL
                c == '\u001b' -> pState = PState.OSC_ESC
                else -> {}
            }
            PState.OSC_ESC -> pState = PState.NORMAL
        }
    }

    private fun params(str: String): List<Int> =
        if (str.isEmpty()) emptyList()
        else str.split(';').map { if (it.isEmpty()) 0 else (it.toIntOrNull() ?: 0) }

    private fun handleCsi(final: Char, paramStr: String, priv: Boolean) {
        val ps = params(paramStr)
        val n1 = ps.getOrElse(0) { 0 }
        if (priv) {
            when (final) {
                'h' -> if (ps.contains(25)) cursorVisible = true
                'l' -> if (ps.contains(25)) cursorVisible = false
                else -> {}
            }
            return
        }
        when (final) {
            'A' -> cursorRow = maxOf(scrollTop, cursorRow - maxOf(1, n1))
            'B' -> cursorRow = minOf(scrollBottom, cursorRow + maxOf(1, n1))
            'C' -> cursorCol = minOf(cols - 1, cursorCol + maxOf(1, n1))
            'D' -> cursorCol = maxOf(0, cursorCol - maxOf(1, n1))
            'E' -> {
                cursorRow = minOf(scrollBottom, cursorRow + maxOf(1, n1)); cursorCol = 0
            }
            'F' -> {
                cursorRow = maxOf(scrollTop, cursorRow - maxOf(1, n1)); cursorCol = 0
            }
            'G' -> cursorCol = (n1 - 1).coerceIn(0, cols - 1)
            'H', 'f' -> {
                cursorRow = (ps.getOrElse(1) { 1 } - 1).coerceIn(0, rows - 1)
                cursorCol = (n1 - 1).coerceIn(0, cols - 1)
            }
            'd' -> cursorRow = (n1 - 1).coerceIn(0, rows - 1)
            'J' -> eraseDisplay(n1)
            'K' -> eraseLine(n1)
            'm' -> handleSgr(ps)
            's' -> {
                savedRow = cursorRow; savedCol = cursorCol
            }
            'u' -> {
                cursorRow = savedRow; cursorCol = savedCol
            }
            'r' -> {
                val top = (ps.getOrElse(0) { 1 } - 1).coerceIn(0, rows - 1)
                val bottom = (ps.getOrElse(1) { rows } - 1).coerceIn(0, rows - 1)
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                    cursorRow = scrollTop
                    cursorCol = 0
                }
            }
            'S' -> scrollUp(maxOf(1, n1))
            'T' -> scrollDown(maxOf(1, n1))
            'L' -> insertLines(maxOf(1, n1))
            'M' -> deleteLines(maxOf(1, n1))
            '@' -> insertChars(maxOf(1, n1))
            'P' -> deleteChars(maxOf(1, n1))
            'X' -> eraseChars(maxOf(1, n1))
            else -> {}
        }
    }

    private fun handleSgr(ps: List<Int>) {
        if (ps.isEmpty()) {
            curFg = DEFAULT_COLOR; curBg = DEFAULT_COLOR; curFlags = 0
            return
        }
        var i = 0
        while (i < ps.size) {
            when (val p = ps[i]) {
                0 -> {
                    curFg = DEFAULT_COLOR; curBg = DEFAULT_COLOR; curFlags = 0
                }
                1 -> curFlags = curFlags or FLAG_BOLD
                22 -> curFlags = curFlags and FLAG_BOLD.inv()
                7 -> curFlags = curFlags or FLAG_INVERSE
                27 -> curFlags = curFlags and FLAG_INVERSE.inv()
                in 30..37 -> curFg = xterm256(p - 30)
                38 -> {
                    val r = parseExtendedColor(ps, i)
                    r.first?.let { curFg = it }
                    i += r.second
                }
                39 -> curFg = DEFAULT_COLOR
                in 40..47 -> curBg = xterm256(p - 40)
                48 -> {
                    val r = parseExtendedColor(ps, i)
                    r.first?.let { curBg = it }
                    i += r.second
                }
                49 -> curBg = DEFAULT_COLOR
                in 90..97 -> curFg = xterm256(p - 90 + 8)
                in 100..107 -> curBg = xterm256(p - 100 + 8)
                else -> {}
            }
            i++
        }
    }

    /** Returns the parsed color and extra param count consumed after [index]. */
    private fun parseExtendedColor(ps: List<Int>, index: Int): Pair<Int?, Int> {
        val mode = ps.getOrNull(index + 1) ?: return null to 0
        return when (mode) {
            5 -> {
                val idx = ps.getOrNull(index + 2) ?: return null to 0
                xterm256(idx.coerceIn(0, 255)) to 2
            }
            2 -> {
                val r = ps.getOrNull(index + 2) ?: return null to 0
                val g = ps.getOrNull(index + 3) ?: return null to 0
                val b = ps.getOrNull(index + 4) ?: return null to 0
                rgb(r, g, b) to 4
            }
            else -> null to 0
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun xterm256(idx: Int): Int {
        val base = intArrayOf(
            0x000000, 0x800000, 0x008000, 0x808000, 0x000080, 0x800080, 0x008080, 0xC0C0C0,
            0x808080, 0xFF0000, 0x00FF00, 0xFFFF00, 0x0000FF, 0xFF00FF, 0x00FFFF, 0xFFFFFF
        )
        return when {
            idx < 16 -> 0xFF000000.toInt() or base[idx]
            idx < 232 -> {
                val c = idx - 16
                val v = { x: Int -> if (x == 0) 0 else 55 + x * 40 }
                rgb(v(c / 36), v((c % 36) / 6), v(c % 6))
            }
            else -> {
                val v = 8 + (idx - 232) * 10
                rgb(v, v, v)
            }
        }
    }

    // ---- screen manipulation ----

    private fun screenLine(row: Int): Row = lines[lines.size - rows + row]

    private fun putChar(c: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        val line = screenLine(cursorRow)
        line.chars[cursorCol] = c
        line.attrs[cursorCol] = curFlags
        line.fg[cursorCol] = curFg
        line.bg[cursorCol] = curBg
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun scrollUp(count: Int) {
        repeat(count) {
            lines.removeAt(lines.size - rows + scrollTop)
            lines.add(lines.size - rows + scrollBottom + 1, Row(cols))
        }
        trimScrollback()
    }

    private fun scrollDown(count: Int) {
        repeat(count) {
            lines.removeAt(lines.size - rows + scrollBottom)
            lines.add(lines.size - rows + scrollTop + 1, Row(cols))
        }
    }

    private fun insertLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(count) {
            lines.removeAt(lines.size - rows + scrollBottom)
            lines.add(lines.size - rows + cursorRow, Row(cols))
        }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(count) {
            lines.removeAt(lines.size - rows + cursorRow)
            lines.add(lines.size - rows + scrollBottom + 1, Row(cols))
        }
    }

    private fun trimScrollback() {
        while (lines.size > maxLines) lines.removeAt(0)
    }

    private fun insertChars(count: Int) {
        val line = screenLine(cursorRow)
        val c = count.coerceAtMost(cols - cursorCol)
        for (i in cols - 1 downTo cursorCol + c) {
            line.chars[i] = line.chars[i - c]
            line.attrs[i] = line.attrs[i - c]
            line.fg[i] = line.fg[i - c]
            line.bg[i] = line.bg[i - c]
        }
        line.clearRange(cursorCol, cursorCol + c)
    }

    private fun deleteChars(count: Int) {
        val line = screenLine(cursorRow)
        val c = count.coerceAtMost(cols - cursorCol)
        for (i in cursorCol until cols - c) {
            line.chars[i] = line.chars[i + c]
            line.attrs[i] = line.attrs[i + c]
            line.fg[i] = line.fg[i + c]
            line.bg[i] = line.bg[i + c]
        }
        line.clearRange(cols - c, cols)
    }

    private fun eraseChars(count: Int) {
        val line = screenLine(cursorRow)
        line.clearRange(cursorCol, minOf(cols, cursorCol + count))
    }

    private fun eraseLine(mode: Int) {
        val line = screenLine(cursorRow)
        when (mode) {
            0 -> line.clearRange(cursorCol, cols)
            1 -> line.clearRange(0, cursorCol + 1)
            else -> line.clearAll()
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in cursorRow + 1 until rows) screenLine(r).clearAll()
            }
            1 -> {
                eraseLine(1)
                for (r in 0 until cursorRow) screenLine(r).clearAll()
            }
            else -> for (r in 0 until rows) screenLine(r).clearAll()
        }
    }

    private fun fullReset() {
        lines.clear()
        repeat(rows) { lines.add(Row(cols)) }
        cursorRow = 0; cursorCol = 0
        savedRow = 0; savedCol = 0
        scrollTop = 0; scrollBottom = rows - 1
        curFg = DEFAULT_COLOR; curBg = DEFAULT_COLOR; curFlags = 0
        cursorVisible = true
    }

    // ---- snapshots for rendering ----

    data class CellRun(
        val text: String,
        val fg: Int?,   // null = theme default
        val bg: Int?,
        val bold: Boolean,
        val inverse: Boolean
    )

    data class Snapshot(
        val runs: List<List<CellRun>>,
        val cursorLineIndex: Int,
        val cursorCol: Int,
        val cursorVisible: Boolean
    )

    /** Returns the last [count] lines ready for rendering (oldest first). */
    @Synchronized
    fun snapshot(count: Int): Snapshot {
        val n = count.coerceAtMost(lines.size)
        val start = lines.size - n
        val out = ArrayList<List<CellRun>>(n)
        var cursorLineIndex = -1
        for (li in start until lines.size) {
            val line = lines[li]
            val screenRow = li - (lines.size - rows)
            if (screenRow == cursorRow) cursorLineIndex = li - start

            val runs = ArrayList<CellRun>()
            var i = 0
            while (i < cols) {
                val startCol = i
                val flags = line.attrs[i]
                var fg = line.fg[i]
                if (flags and FLAG_BOLD != 0 && fg in 0..15) fg = xterm256(fg + 8)
                val bg = line.bg[i]
                i++
                while (i < cols && line.attrs[i] == flags) {
                    val fg2 = line.fg[i]
                    val f2 = if (line.attrs[i] and FLAG_BOLD != 0 && fg2 in 0..15) xterm256(fg2 + 8) else fg2
                    if (f2 != fg || line.bg[i] != bg) break
                    i++
                }
                runs.add(
                    CellRun(
                        text = String(line.chars, startCol, i - startCol),
                        fg = fg.takeIf { it != DEFAULT_COLOR },
                        bg = bg.takeIf { it != DEFAULT_COLOR },
                        bold = flags and FLAG_BOLD != 0,
                        inverse = flags and FLAG_INVERSE != 0
                    )
                )
            }
            out.add(runs)
        }
        return Snapshot(out, cursorLineIndex, cursorCol, cursorVisible)
    }

    companion object {
        private const val FLAG_BOLD = 1
        private const val FLAG_INVERSE = 2
        private const val DEFAULT_COLOR = -1
    }
}
