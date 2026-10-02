package com.ee555719.golinux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ee555719.golinux.data.BackupEntry
import com.ee555719.golinux.data.VmState
import com.ee555719.golinux.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsState()

    var backups by remember { mutableStateOf(vm.app.backupManager.list()) }
    var nameInput by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var restoring by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<BackupEntry?>(null) }
    var confirmRestore by remember { mutableStateOf<BackupEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        backups = vm.app.backupManager.list()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("备份（磁盘镜像 ZIP）", style = MaterialTheme.typography.titleMedium)

        if (!Environment.isExternalStorageManager()) {
            Text(
                "未授予“所有文件访问”权限，备份保存在应用私有目录，分享可用但其他文件管理器无法直接浏览。",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                "备份目录：${vm.app.backupManager.backupDir().absolutePath}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("备份名称（可选）") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = {
                creating = true
                progress = 0f
                scope.launch {
                    val r = vm.app.backupManager.create(nameInput.ifBlank { null }) { p ->
                        progress = p / 100f
                    }
                    creating = false
                    nameInput = ""
                    if (r.isSuccess) {
                        message = "备份完成：${r.getOrNull()?.name}"
                        refresh()
                    } else {
                        vm.error.value = r.exceptionOrNull()?.message
                    }
                }
            },
            enabled = !creating && state == VmState.STOPPED,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (creating) "备份中 ${(progress * 100).toInt()}%" else "创建备份")
        }
        if (creating) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        if (state != VmState.STOPPED) {
            Text("虚拟机运行中：为保证数据一致，建议停止后再备份。", style = MaterialTheme.typography.bodySmall)
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(backups, key = { it.name }) { b ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(b.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${formatSize(b.sizeBytes)} · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(b.lastModified))}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { confirmRestore = b },
                                enabled = restoring == null
                            ) { Text("恢复") }
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            vm.app.backupManager.shareIntent(b.name),
                                            "分享备份"
                                        )
                                    )
                                }.onFailure { e -> vm.error.value = e.message }
                            }) { Text("分享") }
                            TextButton(onClick = { locate(context, vm, b) }) { Text("定位") }
                            TextButton(onClick = { confirmDelete = b }) { Text("删除") }
                        }
                        restoring?.takeIf { it == b.name }?.let {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text("恢复中…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除备份？") },
            text = { Text(b.name) },
            confirmButton = {
                TextButton(onClick = {
                    vm.app.backupManager.delete(b.name)
                    refresh()
                    confirmDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            }
        )
    }

    confirmRestore?.let { b ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("恢复备份？") },
            text = { Text("当前虚拟磁盘将被备份 “${b.name}” 覆盖。虚拟机必须处于停止状态。") },
            confirmButton = {
                TextButton(onClick = {
                    if (state != VmState.STOPPED) {
                        vm.error.value = "请先停止虚拟机"
                        confirmRestore = null
                        return@TextButton
                    }
                    restoring = b.name
                    scope.launch {
                        val r = vm.app.backupManager.restore(b.name) {}
                        restoring = null
                        confirmRestore = null
                        if (r.isSuccess) {
                            message = "恢复完成：${b.name}"
                        } else {
                            vm.error.value = r.exceptionOrNull()?.message
                        }
                    }
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = null }) { Text("取消") }
            }
        )
    }
}

private fun locate(context: Context, vm: MainViewModel, b: BackupEntry) {
    runCatching {
        val intent = vm.app.backupManager.locateIntent(b.name)
        if (intent != null) {
            context.startActivity(intent)
        } else {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("备份路径", vm.app.backupManager.pathOf(b.name)))
            vm.showInfo("路径已复制到剪贴板")
        }
    }.onFailure { e -> vm.error.value = e.message }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "$bytes B"
}
