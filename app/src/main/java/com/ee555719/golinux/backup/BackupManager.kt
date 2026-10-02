package com.ee555719.golinux.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.ee555719.golinux.data.BackupEntry
import com.ee555719.golinux.qemu.QemuPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(private val context: Context) {

    private val paths = QemuPaths(context)

    private val publicDir: File
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "GoLinuxBackups")

    private val privateDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")

    fun backupDir(): File {
        val dir = if (Environment.isExternalStorageManager()) publicDir else privateDir
        dir.mkdirs()
        return dir
    }

    fun isPublicStorage(): Boolean = Environment.isExternalStorageManager() && backupDir() == publicDir

    fun list(): List<BackupEntry> {
        val dir = backupDir()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { BackupEntry(it.name, it.length(), it.lastModified()) }
            ?: emptyList()
    }

    fun fileFor(name: String): File = File(backupDir(), name)

    suspend fun create(customName: String?, onProgress: (Int) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val safe = customName?.trim()?.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "")
                val name = if (safe.isNullOrEmpty()) "backup_$stamp.zip" else "${safe}_$stamp.zip"
                val out = File(backupDir(), name)
                val disk = paths.activeDiskFile()
                if (!disk.exists()) throw IllegalStateException("尚无虚拟磁盘可备份")

                ZipOutputStream(FileOutputStream(out)).use { zip ->
                    zip.putNextEntry(ZipEntry(disk.name))
                    FileInputStream(disk).use { input ->
                        val buf = ByteArray(1024 * 1024)
                        var total = disk.length()
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zip.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(((done * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                    zip.closeEntry()

                    val info = "{\"disk\":\"${disk.name}\",\"created\":${System.currentTimeMillis()}," +
                            "\"sizeBytes\":${disk.length()}}"
                    zip.putNextEntry(ZipEntry("backup-info.json"))
                    zip.write(info.toByteArray())
                    zip.closeEntry()
                }
                onProgress(100)
                out
            }
        }

    suspend fun restore(fileName: String, onProgress: (Int) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = fileFor(fileName)
                if (!file.exists()) throw IllegalStateException("备份文件不存在: $fileName")
                paths.ensureDirs()

                var diskName: String? = null
                ZipInputStream(FileInputStream(file)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name != "backup-info.json") {
                            diskName = entry.name
                            val dest = when {
                                entry.name.endsWith(".qcow2") -> paths.diskFile
                                entry.name.endsWith(".raw") -> paths.rawDiskFile
                                else -> File(paths.vmDir, entry.name)
                            }
                            // make sure the other disk format does not shadow the restored one
                            listOf(paths.diskFile, paths.rawDiskFile).forEach { other ->
                                if (other != dest && other.exists()) other.delete()
                            }
                            FileOutputStream(dest).use { out ->
                                val buf = ByteArray(1024 * 1024)
                                while (true) {
                                    val n = zip.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                if (diskName == null) throw IllegalStateException("备份中未找到磁盘镜像")
                onProgress(100)
            }
        }

    fun delete(fileName: String): Boolean {
        val f = fileFor(fileName)
        return f.exists() && f.delete()
    }

    fun shareIntent(fileName: String): Intent {
        val file = fileFor(fileName)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun locateIntent(fileName: String): Intent? {
        val file = fileFor(fileName)
        if (file.parentFile?.name == "GoLinuxBackups" && isPublicStorage()) {
            val docUri = Uri.parse(
                "content://com.android.externalstorage.documents/document/" +
                        "primary%3ADocuments%2FGoLinuxBackups"
            )
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return null
    }

    fun pathOf(fileName: String): String = fileFor(fileName).absolutePath
}
