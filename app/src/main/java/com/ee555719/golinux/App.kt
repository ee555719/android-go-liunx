package com.ee555719.golinux

import android.app.Application
import com.ee555719.golinux.backup.BackupManager
import com.ee555719.golinux.data.SettingsRepository
import com.ee555719.golinux.qemu.DiskManager
import com.ee555719.golinux.qemu.QemuManager

class App : Application() {

    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val qemuManager: QemuManager by lazy { QemuManager(this) }
    val diskManager: DiskManager by lazy { DiskManager(this) }
    val backupManager: BackupManager by lazy { BackupManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
