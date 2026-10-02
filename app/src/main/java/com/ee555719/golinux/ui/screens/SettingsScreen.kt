package com.ee555719.golinux.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ee555719.golinux.data.ThemeMode
import com.ee555719.golinux.ui.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val theme by vm.themeMode.collectAsState()
    val dynamic by vm.dynamicColor.collectAsState()
    val cfg by vm.config.collectAsState()

    var sshUser by remember(cfg.sshUser) { mutableStateOf(cfg.sshUser) }
    var sshPassword by remember(cfg.sshPassword) { mutableStateOf(cfg.sshPassword) }
    var sshPort by remember(cfg.sshHostPort) { mutableStateOf(cfg.sshHostPort.toString()) }
    var serialPort by remember(cfg.serialPort) { mutableStateOf(cfg.serialPort.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- theme ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("外观", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = theme == ThemeMode.DARK,
                        onClick = { vm.saveTheme(ThemeMode.DARK) }
                    )
                    Text("黑夜模式（默认，主黑色调）")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = theme == ThemeMode.LIGHT,
                        onClick = { vm.saveTheme(ThemeMode.LIGHT) }
                    )
                    Text("白天模式（主白色调）")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = dynamic,
                        onCheckedChange = { vm.saveDynamicColor(it) },
                        enabled = Build.VERSION.SDK_INT >= 31
                    )
                    Text(
                        if (Build.VERSION.SDK_INT >= 31) "  动态取色（Android 12+）"
                        else "  动态取色（需 Android 12+）",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // ---- ssh ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SSH 连接", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = sshUser,
                    onValueChange = { sshUser = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sshPassword,
                    onValueChange = { sshPassword = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = cfg.autoSsh,
                        onCheckedChange = { v -> vm.saveConfig { it.copy(autoSsh = v) } }
                    )
                    Text("  磁盘启动后自动进入 SSH 终端", style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = {
                        vm.saveConfig {
                            it.copy(sshUser = sshUser.trim(), sshPassword = sshPassword)
                        }
                        vm.showInfo("SSH 配置已保存")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存 SSH 配置") }
            }
        }

        // ---- ports ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("端口", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = sshPort,
                    onValueChange = { sshPort = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("SSH 主机端口（转发到虚拟机 22）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = serialPort,
                    onValueChange = { serialPort = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("串口控制台端口") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val sp = sshPort.toIntOrNull()?.coerceIn(1024, 65535) ?: cfg.sshHostPort
                        val cp = serialPort.toIntOrNull()?.coerceIn(1024, 65535) ?: cfg.serialPort
                        vm.saveConfig { it.copy(sshHostPort = sp, serialPort = cp) }
                        vm.showInfo("端口已保存，重启虚拟机后生效")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存端口") }
            }
        }

        // ---- permissions ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("权限", style = MaterialTheme.typography.titleMedium)
                Text(
                    "所有文件访问（用于免拷贝挂载 ISO、公开目录备份）：" +
                            if (Environment.isExternalStorageManager()) "已授权" else "未授权",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${vm.app.packageName}")
                                )
                            )
                        }.onFailure {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("打开“所有文件访问”设置") }
            }
        }

        // ---- about ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Text("GoLinux VM 1.0.0", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "免 Root Linux 虚拟机 · QEMU (aarch64, GPL-2.0) + EDK2 固件",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "QEMU 引擎来自 Termux 软件包并重命名为 libqemu-system-aarch64.so 打包，" +
                            "通过 Android 10+ 允许的 nativeLibraryDir 执行。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "支持 Android 11 (API 30) ~ Android 17，minSdk 30 / targetSdk 36。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
