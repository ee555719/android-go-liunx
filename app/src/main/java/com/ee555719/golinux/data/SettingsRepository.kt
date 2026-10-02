package com.ee555719.golinux.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "golinux_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val MEMORY_MB = intPreferencesKey("memory_mb")
        val SMP = intPreferencesKey("smp")
        val DISK_GB = intPreferencesKey("disk_gb")
        val BOOT_MODE = stringPreferencesKey("boot_mode")
        val ISO_URI = stringPreferencesKey("iso_uri")
        val ISO_NAME = stringPreferencesKey("iso_name")
        val ISO_MODE = stringPreferencesKey("iso_mode")
        val SSH_HOST_PORT = intPreferencesKey("ssh_host_port")
        val SERIAL_PORT = intPreferencesKey("serial_port")
        val QMP_PORT = intPreferencesKey("qmp_port")
        val AUTO_SSH = booleanPreferencesKey("auto_ssh")
        val SSH_USER = stringPreferencesKey("ssh_user")
        val SSH_PASSWORD = stringPreferencesKey("ssh_password")
        val RULES = stringPreferencesKey("port_rules")
    }

    val config: Flow<VmConfig> = context.dataStore.data.map { p ->
        VmConfig(
            memoryMb = p[Keys.MEMORY_MB] ?: 1024,
            smp = p[Keys.SMP] ?: 2,
            diskSizeGb = p[Keys.DISK_GB] ?: 8,
            bootMode = runCatching { BootMode.valueOf(p[Keys.BOOT_MODE] ?: "") }.getOrDefault(BootMode.DISK),
            isoUri = p[Keys.ISO_URI],
            isoName = p[Keys.ISO_NAME],
            isoMode = runCatching { IsoAttachMode.valueOf(p[Keys.ISO_MODE] ?: "") }.getOrDefault(IsoAttachMode.DIRECT),
            sshHostPort = p[Keys.SSH_HOST_PORT] ?: 60022,
            serialPort = p[Keys.SERIAL_PORT] ?: 60023,
            qmpPort = p[Keys.QMP_PORT] ?: 60024,
            autoSsh = p[Keys.AUTO_SSH] ?: true,
            sshUser = p[Keys.SSH_USER] ?: "root",
            sshPassword = p[Keys.SSH_PASSWORD] ?: ""
        )
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { p ->
        runCatching { ThemeMode.valueOf(p[Keys.THEME_MODE] ?: "") }.getOrDefault(ThemeMode.DARK)
    }

    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { p ->
        p[Keys.DYNAMIC_COLOR] ?: false
    }

    val rules: Flow<List<PortForwardRule>> = context.dataStore.data.map { p ->
        decodeRules(p[Keys.RULES])
    }

    suspend fun saveConfig(cfg: VmConfig) {
        context.dataStore.edit { p ->
            p[Keys.MEMORY_MB] = cfg.memoryMb
            p[Keys.SMP] = cfg.smp
            p[Keys.DISK_GB] = cfg.diskSizeGb
            p[Keys.BOOT_MODE] = cfg.bootMode.name
            cfg.isoUri?.let { p[Keys.ISO_URI] = it } ?: p.remove(Keys.ISO_URI)
            cfg.isoName?.let { p[Keys.ISO_NAME] = it } ?: p.remove(Keys.ISO_NAME)
            p[Keys.ISO_MODE] = cfg.isoMode.name
            p[Keys.SSH_HOST_PORT] = cfg.sshHostPort
            p[Keys.SERIAL_PORT] = cfg.serialPort
            p[Keys.QMP_PORT] = cfg.qmpPort
            p[Keys.AUTO_SSH] = cfg.autoSsh
            p[Keys.SSH_USER] = cfg.sshUser
            p[Keys.SSH_PASSWORD] = cfg.sshPassword
        }
    }

    suspend fun saveTheme(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun saveDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun saveRules(rules: List<PortForwardRule>) {
        context.dataStore.edit { it[Keys.RULES] = encodeRules(rules) }
    }

    private fun encodeRules(rules: List<PortForwardRule>): String {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("enabled", r.enabled)
                put("host", r.hostPort)
                put("guest", r.guestPort)
                put("label", r.label)
            })
        }
        return arr.toString()
    }

    private fun decodeRules(raw: String?): List<PortForwardRule> {
        if (raw.isNullOrBlank()) {
            return listOf(
                PortForwardRule(id = 1, enabled = true, hostPort = 60022, guestPort = 22, label = "SSH")
            )
        }
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                PortForwardRule(
                    id = o.getLong("id"),
                    enabled = o.optBoolean("enabled", true),
                    hostPort = o.getInt("host"),
                    guestPort = o.getInt("guest"),
                    label = o.optString("label", "")
                )
            }
        }.getOrElse {
            listOf(PortForwardRule(id = 1, enabled = true, hostPort = 60022, guestPort = 22, label = "SSH"))
        }
    }
}
