package com.ee555719.golinux.qemu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class DiskManager(private val context: Context) {

    private val paths = QemuPaths(context)

    data class DiskInfo(val file: File, val exists: Boolean, val sizeOnDisk: Long, val format: String)

    fun info(): DiskInfo {
        val f = paths.activeDiskFile()
        return if (f.exists()) {
            DiskInfo(f, true, f.length(), if (f.name.endsWith(".qcow2")) "qcow2" else "raw")
        } else {
            DiskInfo(f, false, 0L, "-")
        }
    }

    suspend fun create(sizeGb: Int): Result<DiskInfo> = withContext(Dispatchers.IO) {
        runCatching {
            paths.ensureDirs()
            // qemu-img needs the shared libraries (LD_LIBRARY_PATH) that are
            // extracted from assets on first use - without this step exec()
            // fails with "CANNOTLINK ... libzstd.so.1 not found".
            if (paths.qemuImgPresent()) {
                paths.ensureLibs()
            }
            // remove any previous disk of the other format
            listOf(paths.diskFile, paths.rawDiskFile).forEach { if (it.exists()) it.delete() }
            val target = paths.diskFile
            if (paths.qemuImgPresent()) {
                createWithQemuImg(target, sizeGb)
            } else {
                createRaw(paths.rawDiskFile, sizeGb)
            }
            info()
        }
    }

    private fun createWithQemuImg(target: File, sizeGb: Int) {
        val pb = ProcessBuilder(
            paths.qemuImgBinary.absolutePath,
            "create", "-f", "qcow2",
            target.absolutePath,
            "${sizeGb}G"
        )
        pb.environment().putAll(paths.childEnvironment())
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IllegalStateException("qemu-img 超时")
        }
        if (proc.exitValue() != 0 || !target.exists()) {
            throw IllegalStateException("qemu-img 创建磁盘失败: $out")
        }
    }

    private fun createRaw(target: File, sizeGb: Int) {
        RandomAccessFile(target, "rw").use { it.setLength(sizeGb * 1024L * 1024L * 1024L) }
    }

    fun delete(): Boolean {
        var ok = true
        listOf(paths.diskFile, paths.rawDiskFile).forEach { if (it.exists()) ok = it.delete() && ok }
        return ok
    }
}
