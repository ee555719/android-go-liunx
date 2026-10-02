package com.ee555719.golinux.qemu

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.ee555719.golinux.data.IsoAttachMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

object IsoHelper {

    /** The dup'd ISO descriptor held open so the QEMU child can inherit it. */
    @Volatile
    private var heldFd: ParcelFileDescriptor? = null

    /**
     * Resolve a SAF content URI to something QEMU can open.
     *
     * DIRECT mode reuses the already-open file descriptor: it is duplicated
     * onto a fixed descriptor without FD_CLOEXEC so the QEMU child inherits
     * it and can reopen it through /proc/self/fd/N. Requires that the raw
     * path behind the FUSE document is readable (all-files access), which
     * the app requests in settings.
     *
     * COPY mode streams the document into the private VM directory; works
     * for every provider but needs equal free space and a bit of time.
     */
    suspend fun resolve(
        context: Context,
        uri: String,
        mode: IsoAttachMode,
        onProgress: ((copied: Long, total: Long) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (mode) {
                IsoAttachMode.DIRECT -> openDirect(context, Uri.parse(uri))
                IsoAttachMode.COPY -> copyToPrivate(context, Uri.parse(uri), onProgress).absolutePath
            }
        }
    }

    private fun openDirect(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val afd = resolver.openAssetFileDescriptor(uri, "r")
            ?: throw IllegalStateException("无法打开所选 ISO 文件")
        try {
            val pfd = afd.parcelFileDescriptor
                ?: throw IllegalStateException("无法获取 ISO 文件描述符")
            // duplicate the descriptor so it stays valid for the QEMU child;
            // clear FD_CLOEXEC so exec() inherits it
            val dup = ParcelFileDescriptor.dup(pfd.fileDescriptor)
            try {
                Os.fcntlInt(dup.fileDescriptor, OsConstants.F_SETFD, 0)
            } catch (_: Throwable) {
            }
            heldFd?.let { old -> try { old.close() } catch (_: Throwable) {} }
            heldFd = dup
            return "/proc/self/fd/${dup.fd}"
        } finally {
            try {
                afd.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun releaseDirectFd() {
        val old = heldFd
        heldFd = null
        old?.let { fd -> try { fd.close() } catch (_: Throwable) {} }
    }

    private fun copyToPrivate(
        context: Context,
        uri: Uri,
        onProgress: ((Long, Long) -> Unit)?
    ): File {
        val dest = QemuPaths(context).copiedIsoFile
        dest.parentFile?.mkdirs()
        val total = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else -1L
        } ?: -1L

        var copied = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(dest).use { out ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    onProgress?.invoke(copied, total)
                }
            }
        } ?: throw IllegalStateException("无法读取所选 ISO 文件")
        return dest
    }
}
