package com.ee555719.golinux.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ee555719.golinux.App
import com.ee555719.golinux.data.PortForwardRule
import com.ee555719.golinux.data.ThemeMode
import com.ee555719.golinux.data.VmConfig
import com.ee555719.golinux.data.VmState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val app: App = application as App

    val config: StateFlow<VmConfig> = app.settings.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmConfig())

    val rules: StateFlow<List<PortForwardRule>> = app.settings.rules
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val themeMode: StateFlow<ThemeMode> = app.settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.DARK)

    val dynamicColor: StateFlow<Boolean> = app.settings.dynamicColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val state: StateFlow<VmState> = app.qemuManager.state

    val error: MutableStateFlow<String?> = MutableStateFlow(null)
    val busy: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val info: MutableStateFlow<String?> = MutableStateFlow(null)

    private var autoSshPending = false

    fun consumeAutoSsh(): Boolean {
        val v = autoSshPending
        autoSshPending = false
        return v
    }

    fun startVm() {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                val cfg = config.value
                app.qemuManager.onConfigApplied(cfg)
                withContext(Dispatchers.IO) {
                    app.qemuManager.start(cfg, rules.value)
                }
                autoSshPending = cfg.bootMode == com.ee555719.golinux.data.BootMode.DISK && cfg.autoSsh
            } catch (t: Throwable) {
                error.value = t.message ?: t.toString()
            } finally {
                busy.value = false
            }
        }
    }

    fun stopVm() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { app.qemuManager.stop() } }
        }
    }

    fun restartVm() {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                val cfg = config.value
                withContext(Dispatchers.IO) {
                    app.qemuManager.restart(cfg, rules.value)
                }
                autoSshPending = cfg.bootMode == com.ee555719.golinux.data.BootMode.DISK && cfg.autoSsh
            } catch (t: Throwable) {
                error.value = t.message ?: t.toString()
            } finally {
                busy.value = false
            }
        }
    }

    fun suspendVm() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.qemuManager.suspend() }
                .onFailure { error.value = it.message }
        }
    }

    fun resumeVm() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { app.qemuManager.resume() }
                .onFailure { error.value = it.message }
        }
    }

    fun saveConfig(transform: (VmConfig) -> VmConfig) {
        viewModelScope.launch {
            app.settings.saveConfig(transform(config.value))
        }
    }

    fun saveTheme(mode: ThemeMode) {
        viewModelScope.launch { app.settings.saveTheme(mode) }
    }

    fun saveDynamicColor(enabled: Boolean) {
        viewModelScope.launch { app.settings.saveDynamicColor(enabled) }
    }

    fun saveRules(newRules: List<PortForwardRule>) {
        viewModelScope.launch { app.settings.saveRules(newRules) }
    }

    fun clearError() {
        error.value = null
    }

    fun showInfo(msg: String) {
        info.value = msg
    }
}
