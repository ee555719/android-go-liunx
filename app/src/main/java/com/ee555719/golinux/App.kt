package com.ee555719.golinux

import android.app.Application
import com.ee555719.golinux.backup.BackupManager
import com.ee555719.golinux.data.SettingsRepository
import com.ee555719.golinux.qemu.DiskManager
import com.ee555719.golinux.qemu.QemuManager
import java.io.File
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val qemuManager: QemuManager by lazy { QemuManager(this) }
    val diskManager: DiskManager by lazy { DiskManager(this) }
    val backupManager: BackupManager by lazy { BackupManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
    }

    /**
     * Persists uncaught exceptions to filesDir/crash.log (shown together with
     * the QEMU 日志 dialog) so runtime crashes can be diagnosed remotely.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val entry = "\n# crash $stamp thread=${thread.name}\n${sw}\n"
                val file = File(filesDir, "crash.log")
                val old = if (file.exists()) file.readText() else ""
                // keep the log bounded to the last ~64 KB
                val trimmed = if (old.length > 65536) old.takeLast(65536) else old
                file.writeText(trimmed + entry)
                // also mirror into the qemu log so one screenshot shows both
                runCatching {
                    java.io.FileOutputStream(File(filesDir, "vm/qemu.log"), true)
                        .use { it.write(entry.toByteArray()) }
                }
            }
            previous?.uncaughtException(thread, throwable)
                ?: kotlin.system.exitProcess(1)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
