package com.ee555719.golinux.ui.screens

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ee555719.golinux.data.PortForwardRule
import com.ee555719.golinux.data.VmState
import com.ee555719.golinux.ui.MainViewModel

@Composable
fun PortForwardScreen(vm: MainViewModel) {
    val rules by vm.rules.collectAsState()
    val state by vm.state.collectAsState()
    var editing by remember { mutableStateOf<PortForwardRule?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("端口转发（QEMU user-mode NAT hostfwd）", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text(
            "规则在虚拟机启动时生效；修改后可重启虚拟机应用。",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rules, key = { it.id }) { rule ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { checked ->
                                vm.saveRules(rules.map { if (it.id == rule.id) it.copy(enabled = checked) else it })
                            }
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp)
                        ) {
                            Text(
                                "主机 :${rule.hostPort}  →  虚拟机 :${rule.guestPort}",
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                            )
                            if (rule.label.isNotBlank()) {
                                Text(
                                    rule.label,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        TextButton(onClick = { editing = rule }) { Text("编辑") }
                        TextButton(onClick = { vm.saveRules(rules.filterNot { it.id == rule.id }) }) {
                            Text("删除")
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }, modifier = Modifier.weight(1f)) { Text("添加规则") }
            OutlinedButton(
                onClick = { vm.restartVm() },
                enabled = state == VmState.RUNNING || state == VmState.SUSPENDED,
                modifier = Modifier.weight(1f)
            ) { Text("重启虚拟机以应用") }
        }
    }

    editing?.let { rule ->
        RuleDialog(
            initial = rule,
            title = "编辑规则",
            onDismiss = { editing = null },
            onSave = { new ->
                vm.saveRules(rules.map { if (it.id == new.id) new else it })
                editing = null
            }
        )
    }

    if (creating) {
        RuleDialog(
            initial = PortForwardRule(
                id = (rules.maxOfOrNull { it.id } ?: 0L) + 1,
                hostPort = 60023,
                guestPort = 23,
                label = ""
            ),
            title = "添加规则",
            onDismiss = { creating = false },
            onSave = { new ->
                vm.saveRules(rules + new)
                creating = false
            }
        )
    }
}

@Composable
private fun RuleDialog(
    initial: PortForwardRule,
    title: String,
    onDismiss: () -> Unit,
    onSave: (PortForwardRule) -> Unit
) {
    var host by remember { mutableStateOf(initial.hostPort.toString()) }
    var guest by remember { mutableStateOf(initial.guestPort.toString()) }
    var label by remember { mutableStateOf(initial.label) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("主机端口 (1-65535)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = guest,
                    onValueChange = { guest = it },
                    label = { Text("虚拟机内端口 (1-65535)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("备注（如 HTTP、SSH）") },
                    singleLine = true
                )
                err?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = host.toIntOrNull()
                val g = guest.toIntOrNull()
                if (h == null || h !in 1..65535 || g == null || g !in 1..65535) {
                    err = "端口必须是 1-65535 的整数"
                } else {
                    onSave(initial.copy(hostPort = h, guestPort = g, label = label.trim()))
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
