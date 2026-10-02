package com.ee555719.golinux.qemu

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Central filesystem layout for the VM.
 *
 * QEMU itself lives in applicationInfo.nativeLibraryDir (packed as
 * libqemu-system-aarch64.so to satisfy Android 10+ W^X policy, which only
 * allows executing files from the native library directory).
 * Its shared library dependencies are unpacked from assets into the app
 * data dir and exposed through LD_LIBRARY_PATH (dlopen from the data dir
 * is still permitted for untrusted apps).
 */
class QemuPaths(private val context: Context) {

    val qemuBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")

    val qemuImgBinary: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libqemu-img.so")

    val rootDir: File get() = File(context.filesDir, "qemu")
    val libDir: File get() = File(rootDir, "lib")
    val shareDir: File get() = File(rootDir, "share")
    val tmpDir: File get() = File(context.filesDir, "tmp")
    val homeDir: File get() = File(context.filesDir, "home")

    val vmDir: File get() = File(context.filesDir, "vm")
    val diskFile: File get() = File(vmDir, "disk.qcow2")
    val rawDiskFile: File get() = File(vmDir, "disk.raw")
    val logFile: File get() = File(vmDir, "qemu.log")
    val varsFile: File get() = File(vmDir, "AAVMF_VARS.fd")
    val copiedIsoFile: File get() = File(vmDir, "install.iso")
    val firmwareCode: File get() = File(vmDir, "AAVMF_CODE.fd")

    fun activeDiskFile(): File = if (diskFile.exists()) diskFile else rawDiskFile

    fun ensureDirs() {
        listOf(rootDir, libDir, shareDir, tmpDir, homeDir, vmDir).forEach { it.mkdirs() }
    }

    val libsReady: Boolean
        get() = File(libDir, ".ready").exists()

    @Synchronized
    fun ensureLibs() {
        ensureDirs()
        if (libsReady) return
        val marker = File(libDir, ".ready")
        libDir.listFiles()?.forEach { if (it.name != ".ready") it.deleteRecursively() }
        // AGP may package the asset as qemu-libs.tar.gz or gunzip it to
        // qemu-libs.tar - try both names.
        val assetName = listOf("qemu-libs.tar.gz", "qemu-libs.tar").firstOrNull { name ->
            runCatching { context.assets.open(name).close() }.isSuccess
        } ?: throw IllegalStateException("缺少 QEMU 依赖库包 assets/qemu-libs.tar.gz")
        context.assets.open(assetName).use { input ->
            TarExtractor.extractAuto(input, libDir)
        }
        marker.writeText("ok")
    }

    @Synchronized
    fun ensureFirmware() {
        ensureDirs()
        if (!firmwareCode.exists()) {
            context.assets.open("firmware/AAVMF_CODE.fd").use { input ->
                FileOutputStream(firmwareCode).use { output -> input.copyTo(output) }
            }
        }
        if (!varsFile.exists()) {
            runCatching {
                context.assets.open("firmware/AAVMF_VARS.fd").use { input ->
                    FileOutputStream(varsFile).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    fun hasFirmware(): Boolean = firmwareCode.exists()

    /** True if the APK bundles the UEFI firmware asset (extracted on first start). */
    fun firmwareAssetPresent(): Boolean = runCatching {
        context.assets.open("firmware/AAVMF_CODE.fd").close()
    }.isSuccess

    /** True if firmware is already extracted OR still available in the APK. */
    fun firmwareAvailable(): Boolean = hasFirmware() || firmwareAssetPresent()

    fun deleteFirmwareVars() {
        if (varsFile.exists()) varsFile.delete()
    }

    fun childEnvironment(): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to libDir.absolutePath,
        "TMPDIR" to tmpDir.absolutePath,
        "HOME" to homeDir.absolutePath,
        "XDG_RUNTIME_DIR" to tmpDir.absolutePath,
        "PATH" to "/system/bin:/system/xbin",
        "LANG" to "C.UTF-8"
    )

    fun qemuBinaryPresent(): Boolean = qemuBinary.exists()

    fun qemuImgPresent(): Boolean = qemuImgBinary.exists()

    fun clearLog() {
        if (logFile.exists()) logFile.writeText("")
    }

    fun readLog(maxChars: Int = 64 * 1024): String {
        if (!logFile.exists()) return ""
        val text = logFile.readText()
        return if (text.length > maxChars) text.takeLast(maxChars) else text
    }

    fun deviceDescription(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
}
