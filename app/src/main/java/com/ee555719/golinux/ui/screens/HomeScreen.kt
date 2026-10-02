package com.ee555719.golinux.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.ee555719.golinux.data.BootMode
import com.ee555719.golinux.data.VmState
import com.ee555719.golinux.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, nav: NavController) {
    val state by vm.state.collectAsState()
    val cfg by vm.config.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showLog by remember { mutableStateOf(false) }

    val qemuPresent = remember { vm.app.qemuManager.paths.qemuBinaryPresent() }
    val firmwarePresent = remember { vm.app.qemuManager.paths.hasFirmware() }
    val diskInfo = remember { vm.app.diskManager.info() }

    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it) }
    }

    LaunchedEffect(state) {
        if (state == VmState.RUNNING && vm.consumeAutoSsh()) {
            nav.navigate("terminal/ssh")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- status card ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("虚拟机状态", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(12.dp))
                    StateChip(state)
                }
                Text(
                    "启动模式：" + when (cfg.bootMode) {
                        BootMode.DISK -> "磁盘启动（非安装）"
                        BootMode.ISO -> "ISO 安装模式"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "内存 ${cfg.memoryMb} MB · CPU ${cfg.smp} 核 · 磁盘 " +
                            (if (diskInfo.exists) "${diskInfo.format} ${(diskInfo.sizeOnDisk / (1024 * 1024))} MB(占用)" else "未创建"),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (cfg.bootMode == BootMode.ISO) {
                    Text(
                        "安装镜像：" + (cfg.isoName ?: "未选择"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (state == VmState.RUNNING || state == VmState.SUSPENDED) {
                    val elapsed = System.currentTimeMillis() - vm.app.qemuManager.startedAt
                    Text(
                        "已运行 ${formatDuration(elapsed)} · SSH 端口 127.0.0.1:${cfg.sshHostPort}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (!qemuPresent) {
            WarningCard("缺少 QEMU 引擎（libqemu-system-aarch64.so）。请在电脑上运行 scripts\\prepare_assets.ps1 后重新构建 APK。")
        }
        if (!firmwarePresent) {
            WarningCard("缺少 UEFI 固件（AAVMF_CODE.fd）。请运行 scripts\\prepare_assets.ps1 后重新构建 APK。")
        }

        // ---- boot mode ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("启动模式", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = cfg.bootMode == BootMode.DISK,
                        onClick = { vm.saveConfig { it.copy(bootMode = BootMode.DISK) } },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("磁盘启动") }
                    SegmentedButton(
                        selected = cfg.bootMode == BootMode.ISO,
                        onClick = { vm.saveConfig { it.copy(bootMode = BootMode.ISO) } },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("ISO 安装") }
                }
                Text("资源分配", style = MaterialTheme.typography.titleSmall)
                Text("内存", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(512, 1024, 2048, 4096).forEach { mb ->
                        FilterChip(
                            selected = cfg.memoryMb == mb,
                            onClick = { vm.saveConfig { c -> c.copy(memoryMb = mb) } },
                            label = { Text("${mb}M") }
                        )
                    }
                }
                Text("CPU 核心", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 4).forEach { n ->
                        FilterChip(
                            selected = cfg.smp == n,
                            onClick = { vm.saveConfig { c -> c.copy(smp = n) } },
                            label = { Text("$n") }
                        )
                    }
                }
            }
        }

        // ---- controls ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("虚拟机控制", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.startVm() },
                        enabled = !busy && (state == VmState.STOPPED),
                        modifier = Modifier.weight(1f)
                    ) { Text("启动") }
                    Button(
                        onClick = { vm.stopVm() },
                        enabled = state == VmState.RUNNING || state == VmState.SUSPENDED,
                        modifier = Modifier.weight(1f)
                    ) { Text("停止") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.suspendVm() },
                        enabled = state == VmState.RUNNING,
                        modifier = Modifier.weight(1f)
                    ) { Text("挂起") }
                    OutlinedButton(
                        onClick = { vm.resumeVm() },
                        enabled = state == VmState.SUSPENDED,
                        modifier = Modifier.weight(1f)
                    ) { Text("恢复") }
                    OutlinedButton(
                        onClick = { vm.restartVm() },
                        enabled = !busy && state != VmState.STOPPED && state != VmState.STARTING,
                        modifier = Modifier.weight(1f)
                    ) { Text("重启") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showLog = true }, modifier = Modifier.weight(1f)) {
                        Text("QEMU 日志")
                    }
                    OutlinedButton(
                        onClick = { nav.navigate("terminal/serial") },
                        enabled = state == VmState.RUNNING || state == VmState.SUSPENDED,
                        modifier = Modifier.weight(1f)
                    ) { Text("串口终端") }
                }
            }
        }

        // ---- quick links ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("管理", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("ports") }, modifier = Modifier.weight(1f)) {
                        Text("端口转发")
                    }
                    OutlinedButton(onClick = { nav.navigate("disk") }, modifier = Modifier.weight(1f)) {
                        Text("磁盘 / 镜像")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("backup") }, modifier = Modifier.weight(1f)) {
                        Text("备份")
                    }
                    OutlinedButton(onClick = { nav.navigate("settings") }, modifier = Modifier.weight(1f)) {
                        Text("设置")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    SnackbarHost(hostState = snackbar, modifier = Modifier.padding(8.dp))

    if (showLog) {
        val log = remember { vm.app.qemuManager.readLog() }
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("QEMU 日志") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        log.ifBlank { "(暂无日志)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLog = false }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.app.qemuManager.clearLog()
                    showLog = false
                }) { Text("清空") }
            }
        )
    }
}

@Composable
private fun StateChip(state: VmState) {
    val (label, color) = when (state) {
        VmState.STOPPED -> "已停止" to MaterialTheme.colorScheme.onSurfaceVariant
        VmState.STARTING -> "启动中…" to MaterialTheme.colorScheme.primary
        VmState.RUNNING -> "运行中" to MaterialTheme.colorScheme.primary
        VmState.SUSPENDED -> "已挂起" to MaterialTheme.colorScheme.tertiary
        VmState.STOPPING -> "停止中…" to MaterialTheme.colorScheme.error
    }
    Text(
        text = "● $label",
        color = color,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
