package com.ee555719.golinux.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ee555719.golinux.ssh.TerminalSession
import com.ee555719.golinux.terminal.TerminalEmulator
import com.ee555719.golinux.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val TERM_COLS = 100
private const val TERM_ROWS = 30

@Composable
fun TerminalScreen(vm: MainViewModel, initialMode: String) {
    val cfg by vm.config.collectAsState()
    val emulator = remember { TerminalEmulator(cols = TERM_COLS, rows = TERM_ROWS) }
    val session = remember { TerminalSession(emulator, TERM_COLS, TERM_ROWS) }

    var mode by remember(initialMode) { mutableStateOf(initialMode) }
    var attempt by remember { mutableStateOf(0) }
    var connecting by remember { mutableStateOf(true) }
    val status by session.status.collectAsState()
    val version by emulator.version.collectAsState()

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    // connection retry loop (SSH or serial console)
    LaunchedEffect(mode, attempt) {
        connecting = true
        session.close()
        var tries = 0
        while (isActive) {
            tries++
            val result = if (mode == "ssh") {
                session.connectSsh(
                    host = "127.0.0.1",
                    port = cfg.sshHostPort,
                    user = cfg.sshUser.ifBlank { "root" },
                    password = cfg.sshPassword
                )
            } else {
                session.connectSerial("127.0.0.1", cfg.serialPort)
            }
            if (result.isSuccess) {
                connecting = false
                break
            }
            session.reportStatus(
                "连接失败（第 $tries 次）：${result.exceptionOrNull()?.message ?: "未知错误"}，2 秒后重试…"
            )
            delay(2000)
        }
        connecting = false
    }

    // input state
    var input by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var historyIdx by remember { mutableStateOf(-1) }

    fun submit() {
        if (input.isEmpty()) return
        session.send(input + "\n")
        history.add(input)
        historyIdx = history.size
        input = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
    ) {
        // header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                SegmentedButton(
                    selected = mode == "ssh",
                    onClick = { if (mode != "ssh") { mode = "ssh"; attempt++ } },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) { Text("SSH 终端") }
                SegmentedButton(
                    selected = mode == "serial",
                    onClick = { if (mode != "serial") { mode = "serial"; attempt++ } },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) { Text("串口控制台") }
            }
            OutlinedButton(onClick = { attempt++ }, modifier = Modifier.padding(start = 8.dp)) {
                Text("重连")
            }
        }
        Text(
            text = (if (connecting) "连接中… · " else "") + status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        HorizontalDivider()

        // terminal output (newest at the bottom)
        val snapshot = remember(version) { emulator.snapshot(TERM_ROWS) }
        val listState = rememberLazyListState()
        LaunchedEffect(version) {
            // keep the viewport pinned to the newest output
            if (snapshot.runs.isNotEmpty()) listState.scrollToItem(0)
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = 4.dp)
        ) {
            val reversed = snapshot.runs.asReversed()
            items(reversed.size) { idx ->
                val originalLine = snapshot.runs.size - 1 - idx
                val isCursorLine = originalLine == snapshot.cursorLineIndex && snapshot.cursorVisible
                val text = buildTerminalLine(
                    runs = reversed[idx],
                    cursorCol = if (isCursorLine) snapshot.cursorCol else null,
                    defaultFg = MaterialTheme.colorScheme.onBackground,
                    defaultBg = MaterialTheme.colorScheme.background,
                    cursorFg = MaterialTheme.colorScheme.background,
                    cursorBg = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 1
                )
            }
        }

        HorizontalDivider()

        // quick keys
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            QuickKey("Tab") { session.send("\t") }
            QuickKey("Esc") { session.send("\u001b") }
            QuickKey("^C") { session.send("\u0003") }
            QuickKey("^D") { session.send("\u0004") }
            QuickKey("↑") {
                if (history.isNotEmpty()) {
                    historyIdx = (historyIdx - 1).coerceAtLeast(0)
                    input = history[historyIdx]
                }
            }
            QuickKey("↓") {
                if (history.isNotEmpty()) {
                    historyIdx = (historyIdx + 1).coerceAtMost(history.size)
                    input = if (historyIdx >= history.size) "" else history[historyIdx]
                }
            }
        }

        // input line
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("输入命令…", fontFamily = FontFamily.Monospace) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() })
            )
            Button(
                onClick = { submit() },
                enabled = session.isConnected,
                modifier = Modifier.padding(start = 6.dp)
            ) { Text("发送") }
        }
        Text(
            "提示：沙箱内可直接操作 Linux；Ctrl+C 中断当前命令。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun QuickKey(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(end = 2.dp)) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

private fun buildTerminalLine(
    runs: List<TerminalEmulator.CellRun>,
    cursorCol: Int?,
    defaultFg: Color,
    defaultBg: Color,
    cursorFg: Color,
    cursorBg: Color
): AnnotatedString = buildAnnotatedString {
    var col = 0
    runs.forEach { run ->
        val start = col
        val end = col + run.text.length
        // split this run at the cursor position if needed
        val segments = mutableListOf<Triple<String, Int, Int>>() // text, from, to (absolute cols)
        if (cursorCol != null && cursorCol in start until end) {
            if (cursorCol > start) segments.add(Triple(run.text.substring(0, cursorCol - start), start, cursorCol))
            segments.add(Triple(run.text.substring(cursorCol - start, cursorCol - start + 1), cursorCol, cursorCol + 1))
            if (cursorCol + 1 < end) {
                segments.add(Triple(run.text.substring(cursorCol - start + 1), cursorCol + 1, end))
            }
        } else {
            segments.add(Triple(run.text, start, end))
        }
        segments.forEach { (segText, segFrom, _) ->
            val fgRaw = run.fg
            val bgRaw = run.bg
            var fg = fgRaw?.let { Color(0xFF000000.toInt() or it) } ?: defaultFg
            var bg = bgRaw?.let { Color(0xFF000000.toInt() or it) } ?: defaultBg
            if (run.inverse) {
                val t = fg
                fg = bg
                bg = t
            }
            val isCursor = cursorCol != null && segFrom == cursorCol
            withStyle(
                SpanStyle(
                    color = if (isCursor) cursorFg else fg,
                    background = if (isCursor) cursorBg else bg,
                    fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal
                )
            ) {
                append(segText)
            }
        }
        col = end
    }
    // ensure the cursor is visible even at end of line
    if (cursorCol != null && cursorCol >= col) {
        repeat(cursorCol - col) { append(" ") }
        withStyle(SpanStyle(color = cursorFg, background = cursorBg)) { append(" ") }
    }
}
