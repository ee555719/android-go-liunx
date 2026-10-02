package com.ee555719.golinux.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ee555719.golinux.data.VmState
import com.ee555719.golinux.display.Keysyms
import com.ee555719.golinux.display.RfbClient
import com.ee555719.golinux.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Live view of the VM's graphical output (UEFI/OS framebuffer) via QEMU's
 * built-in VNC server: shows the desktop when the guest has one, otherwise
 * the text console. Touch mapping: tap=click, drag=left-drag, two-finger
 * swipe=scroll wheel, "右键" chip arms right-click for the next tap.
 */
@Composable
fun DisplayScreen(vm: MainViewModel) {
    val vmState by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var attempt by remember { mutableStateOf(0) }
    var client by remember { mutableStateOf<RfbClient?>(null) }
    var status by remember { mutableStateOf("连接中…") }
    var connecting by remember { mutableStateOf(true) }
    var frameTick by remember { mutableStateOf(0) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // sticky modifier chips + right-click arming
    var ctrlOn by remember { mutableStateOf(false) }
    var altOn by remember { mutableStateOf(false) }
    var shiftOn by remember { mutableStateOf(false) }
    var rightArm by remember { mutableStateOf(false) }

    val focusReq = remember { FocusRequester() }
    var dummyText by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { client?.close() }
    }

    // connect / read loop with auto-retry (same pattern as TerminalScreen).
    // `gen` guards against a stale (cancelled) effect overwriting status or
    // the client reference after the user tapped 重连 again.
    LaunchedEffect(attempt) {
        val gen = attempt
        connecting = true
        status = "连接中…"
        client?.close()
        client = null
        var tries = 0
        while (isActive) {
            tries++
            val c = RfbClient()
            try {
                withContext(Dispatchers.IO) { c.connect() }
                if (attempt != gen) break
                client = c
                connecting = false
                status = "已连接 ${c.width}x${c.height}" +
                        (c.desktopName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                withContext(Dispatchers.IO) {
                    c.readLoop { if (attempt == gen) scope.launch { frameTick++ } }
                }
                if (attempt == gen) status = "连接已断开"
            } catch (e: Throwable) {
                if (attempt == gen) {
                    connecting = false
                    status = "连接失败（第 $tries 次）：" +
                            "${e::class.simpleName}: ${e.message ?: "无消息"}，2 秒后重试…"
                }
            } finally {
                c.close()
                if (client === c && attempt == gen) client = null
            }
            if (!isActive || attempt != gen) break
            delay(2000)
            if (!isActive || attempt != gen) break
            connecting = true
            status = "连接中…"
        }
        if (attempt == gen) connecting = false
    }

    // invalidate composable when new frames arrive (polled reader counter → main)
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        while (isActive) {
            val f = c.frames.get()
            if (f != frameTick) frameTick = f
            delay(50)
        }
    }

    fun sendWithMods(keysym: Int) {
        val c = client ?: return
        val mods = ArrayList<Int>(3)
        if (ctrlOn) mods.add(Keysyms.CONTROL_L)
        if (altOn) mods.add(Keysyms.ALT_L)
        if (shiftOn) mods.add(Keysyms.SHIFT_L)
        mods.forEach { c.sendKey(true, it) }
        c.sendKey(true, keysym)
        c.sendKey(false, keysym)
        mods.asReversed().forEach { c.sendKey(false, it) }
        ctrlOn = false
        altOn = false
        shiftOn = false
    }

    fun sendChar(ch: Char) {
        val mapped = Keysyms.charKeysym(ch) ?: return
        val c = client ?: return
        val needShift = mapped.second || shiftOn
        val mods = ArrayList<Int>(3)
        if (ctrlOn) mods.add(Keysyms.CONTROL_L)
        if (altOn) mods.add(Keysyms.ALT_L)
        if (needShift) mods.add(Keysyms.SHIFT_L)
        mods.forEach { c.sendKey(true, it) }
        c.sendKey(true, mapped.first)
        c.sendKey(false, mapped.first)
        mods.asReversed().forEach { c.sendKey(false, it) }
        ctrlOn = false
        altOn = false
        shiftOn = false
    }

    // framebuffer geometry (shared between Image placement and touch mapping)
    val bmp = client?.frameOrNull()
    val fw = bmp?.width ?: 0
    val fh = bmp?.height ?: 0
    val sc = if (viewSize != IntSize.Zero && fw > 0 && fh > 0) {
        min(viewSize.width.toFloat() / fw, viewSize.height.toFloat() / fh)
    } else 0f
    val offX = if (sc > 0f) (viewSize.width - fw * sc) / 2f else 0f
    val offY = if (sc > 0f) (viewSize.height - fh * sc) / 2f else 0f
    val density = LocalDensity.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .onPreviewKeyEvent { ev: KeyEvent ->
                val sym = Keysyms.fromComposeKey(ev.key) ?: return@onPreviewKeyEvent false
                val c = client
                if (c != null && (ev.type == KeyEventType.KeyDown || ev.type == KeyEventType.KeyUp)) {
                    c.sendKey(ev.type == KeyEventType.KeyDown, sym)
                }
                true
            }
    ) {
        // header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { attempt++ },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.padding(start = 6.dp)
            ) { Text("重连") }
            OutlinedButton(
                onClick = {
                    runCatching { focusReq.requestFocus() }
                    keyboard?.show()
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.padding(start = 6.dp)
            ) { Text("键盘") }
        }
        HorizontalDivider()

        // framebuffer view + gestures
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { viewSize = it }
                .pointerInput(client, viewSize, fw, fh) {
                    if (client == null || fw <= 0 || fh <= 0 || viewSize == IntSize.Zero) {
                        return@pointerInput
                    }
                    fun toVnc(p: Offset): Pair<Int, Int> {
                        val x = if (sc > 0f) ((p.x - offX) / sc).toInt() else 0
                        val y = if (sc > 0f) ((p.y - offY) / sc).toInt() else 0
                        return x.coerceIn(0, fw - 1) to y.coerceIn(0, fh - 1)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        runCatching { focusReq.requestFocus() }
                        var dragging = false
                        var twoFinger = false
                        var last = down.position
                        var wheelAcc = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = client ?: break
                            val change = ev.changes.firstOrNull { it.id == down.id }
                                ?: ev.changes.firstOrNull()
                            if (change == null) break
                            val pressedCount = ev.changes.count { it.pressed }
                            if (pressedCount >= 2) twoFinger = true

                            if (twoFinger) {
                                // two-finger swipe = vertical scroll wheel
                                if (pressedCount == 0) break
                                wheelAcc += last.y - change.position.y
                                val (x, y) = toVnc(change.position)
                                if (wheelAcc >= 48f) {
                                    wheelAcc = 0f
                                    c.sendPointer(4, x, y)  // wheel up
                                    c.sendPointer(0, x, y)
                                } else if (wheelAcc <= -48f) {
                                    wheelAcc = 0f
                                    c.sendPointer(8, x, y)  // wheel down
                                    c.sendPointer(0, x, y)
                                }
                                last = change.position
                                change.consume()
                                continue
                            }

                            if (!change.pressed) {
                                val (x, y) = toVnc(change.position)
                                if (dragging) {
                                    c.sendPointer(0, x, y)
                                } else if (rightArm) {
                                    rightArm = false
                                    c.sendPointer(4, x, y)
                                    c.sendPointer(0, x, y)
                                } else {
                                    c.sendPointer(1, x, y)
                                    c.sendPointer(0, x, y)
                                }
                                change.consume()
                                break
                            }

                            val moved = (change.position - down.position).getDistance()
                            if (!dragging && moved > viewConfiguration.touchSlop) {
                                dragging = true
                                val (x, y) = toVnc(down.position)
                                c.sendPointer(1, x, y) // press at drag origin
                            }
                            if (dragging) {
                                val (x, y) = toVnc(change.position)
                                c.sendPointer(1, x, y)
                            }
                            last = change.position
                            change.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null && sc > 0f) {
                Image(
                    bitmap = remember(frameTick) { bmp.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .width(with(density) { (fw * sc).toDp() })
                        .height(with(density) { (fh * sc).toDp() })
                )
            } else {
                Text(
                    text = if (vmState == VmState.STOPPED) {
                        "虚拟机未启动，请先在首页启动虚拟机"
                    } else {
                        "等待画面信号…（$status）"
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        HorizontalDivider()

        // quick keys
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Chip("Esc") { sendWithMods(Keysyms.ESC) }
            Chip("Tab") { sendWithMods(Keysyms.TAB) }
            Chip("←") { sendWithMods(Keysyms.LEFT) }
            Chip("↑") { sendWithMods(Keysyms.UP) }
            Chip("↓") { sendWithMods(Keysyms.DOWN) }
            Chip("→") { sendWithMods(Keysyms.RIGHT) }
            Chip("PgUp") { sendWithMods(Keysyms.PAGE_UP) }
            Chip("PgDn") { sendWithMods(Keysyms.PAGE_DOWN) }
            Chip("Home") { sendWithMods(Keysyms.HOME) }
            Chip("End") { sendWithMods(Keysyms.END) }
            Chip("回车") { sendWithMods(Keysyms.RETURN) }
            Chip("退格") { sendWithMods(Keysyms.BACKSPACE) }
            Chip("Del") { sendWithMods(Keysyms.DELETE) }
            Chip("右键", rightArm) { rightArm = !rightArm }
            Chip("Ctrl", ctrlOn) { ctrlOn = !ctrlOn }
            Chip("Alt", altOn) { altOn = !altOn }
            Chip("Shift", shiftOn) { shiftOn = !shiftOn }
        }

        Text(
            "点按=左键，拖动=拖拽，双指滑动=滚轮，先点“右键”再点按=右键；无图形输出的系统请用终端页的串口控制台。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // hidden text field capturing IME commits (typable characters)
        BasicTextField(
            value = dummyText,
            onValueChange = { new ->
                if (new.length > dummyText.length) {
                    new.substring(dummyText.length).forEach { ch -> sendChar(ch) }
                }
                dummyText = ""
            },
            modifier = Modifier
                .size(1.dp, 1.dp)
                .focusRequester(focusReq),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            cursorBrush = SolidColor(Color.Transparent),
            singleLine = true
        )
    }
}

@Composable
private fun Chip(label: String, active: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
            else Color.Transparent
        )
    ) {
        Text(label, fontSize = 12.sp)
    }
}
