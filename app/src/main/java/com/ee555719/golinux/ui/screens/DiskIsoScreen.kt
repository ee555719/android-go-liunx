package com.ee555719.golinux.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ee555719.golinux.data.IsoAttachMode
import com.ee555719.golinux.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DiskIsoScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val cfg by vm.config.collectAsState()
    val scope = rememberCoroutineScope()

    var diskSizeInput by remember { mutableStateOf("8") }
    var creating by remember { mutableStateOf(false) }
    var deleteDiskConfirm by remember { mutableStateOf(false) }
    var copyProgress by remember { mutableStateOf<Float?>(null) }
    var diskInfoText by remember { mutableStateOf(describeDisk(vm)) }

    val isoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = queryDisplayName(context, uri)
            vm.saveConfig { it.copy(isoUri = uri.toString(), isoName = name) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- disk ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("虚拟磁盘", style = MaterialTheme.typography.titleMedium)
                Text(diskInfoText, style = MaterialTheme.typography.bodyMedium)
                if (!Environment.isExternalStorageManager()) {
                    Text(
                        "提示：创建 qcow2 磁盘需要 QEMU 工具；若未打包则自动创建 raw 稀疏磁盘。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = diskSizeInput,
                        onValueChange = { diskSizeInput = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("容量 (GB)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val gb = diskSizeInput.toIntOrNull()?.coerceIn(1, 512) ?: 8
                            creating = true
                            scope.launch {
                                val r = vm.app.diskManager.create(gb)
                                creating = false
                                if (r.isSuccess) {
                                    diskInfoText = describeDisk(vm)
                                    vm.showInfo("磁盘已创建（${gb} GB）")
                                } else {
                                    vm.error.value = r.exceptionOrNull()?.message
                                }
                            }
                        },
                        enabled = !creating,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (creating) "创建中…" else "创建磁盘") }
                    OutlinedButton(
                        onClick = { deleteDiskConfirm = true },
                        enabled = !creating,
                        modifier = Modifier.weight(1f)
                    ) { Text("删除磁盘") }
                }
                if (copyProgress != null) {
                    LinearProgressIndicator(
                        progress = { copyProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("复制 ISO 中… ${(copyProgress!! * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---------- ISO ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("安装镜像 (ISO)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "App 不内置任何系统镜像，请自行下载 Alpine / Ubuntu / Debian 等 aarch64 ISO，" +
                            "然后通过系统文件选择器选取。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "当前：" + (cfg.isoName ?: "未选择"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = { isoPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("选择 ISO 文件 (SAF)") }

                Text("挂载方式", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = cfg.isoMode == IsoAttachMode.DIRECT,
                        onClick = { vm.saveConfig { it.copy(isoMode = IsoAttachMode.DIRECT) } }
                    )
                    Text("直接使用（免拷贝，需“所有文件访问”权限）", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = cfg.isoMode == IsoAttachMode.COPY,
                        onClick = { vm.saveConfig { it.copy(isoMode = IsoAttachMode.COPY) } }
                    )
                    Text("复制到应用目录（兼容性最好，需双倍空间）", style = MaterialTheme.typography.bodySmall)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (!Environment.isExternalStorageManager()) {
                                val intent = Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                                runCatching { context.startActivity(intent) }
                                    .onFailure {
                                        context.startActivity(
                                            Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        )
                                    }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("授权所有文件访问") }

                    OutlinedButton(
                        onClick = {
                            val uri = cfg.isoUri ?: run {
                                vm.error.value = "请先选择 ISO 文件"; return@OutlinedButton
                            }
                            scope.launch {
                                copyProgress = 0f
                                val r = com.ee555719.golinux.qemu.IsoHelper.resolve(
                                    context, uri, IsoAttachMode.COPY
                                ) { copied, total ->
                                    copyProgress = if (total > 0) (copied.toFloat() / total) else 0f
                                }
                                copyProgress = null
                                if (r.isSuccess) {
                                    vm.showInfo("ISO 已复制到应用目录")
                                } else {
                                    vm.error.value = r.exceptionOrNull()?.message
                                }
                            }
                        },
                        enabled = cfg.isoUri != null && copyProgress == null,
                        modifier = Modifier.weight(1f)
                    ) { Text("预复制 ISO") }
                }
            }
        }

        Text(
            "提示：ISO 安装模式下先启动虚拟机，在串口终端完成安装；之后切回“磁盘启动”。",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (deleteDiskConfirm) {
        AlertDialog(
            onDismissRequest = { deleteDiskConfirm = false },
            title = { Text("删除虚拟磁盘？") },
            text = { Text("磁盘及其中所有数据将被永久删除，且不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.app.diskManager.delete()
                    diskInfoText = describeDisk(vm)
                    deleteDiskConfirm = false
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteDiskConfirm = false }) { Text("取消") }
            }
        )
    }
}

private fun describeDisk(vm: MainViewModel): String {
    val info = vm.app.diskManager.info()
    return if (info.exists) {
        "路径：${info.file.absolutePath}\n格式：${info.format} · 声明容量 ${(info.file.length() / (1024 * 1024))} MB（实际占用 ${(info.sizeOnDisk / (1024 * 1024))} MB）"
    } else {
        "尚未创建磁盘。"
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "unknown.iso"
}
