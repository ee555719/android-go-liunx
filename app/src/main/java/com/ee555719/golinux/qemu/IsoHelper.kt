package com.ee555719.golinux.qemu

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.ee555719.golinux.data.IsoAttachMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolve a SAF content URI into a path that the QEMU child process can open.
 *
 * Note: passing an inherited file descriptor via /proc/self/fd/N does NOT
 * work on Android - ProcessBuilder spawns children through posix_spawn with
 * POSIX_SPAWN_CLOEXEC_DEFAULT, which closes every non-standard fd at exec.
 * So we must hand QEMU a real filesystem path instead:
 *
 *  1. DIRECT mode maps the URI to an absolute path (file://, external
 *     storage documents, downloads "raw:" documents, MediaStore DATA) and
 *     verifies the app can actually read it (all-files access).
 *  2. If no readable path can be resolved - or COPY mode is selected - the
 *     document is streamed into the private VM directory. A sidecar file
 *     remembers the source URI so repeated boots reuse the previous copy.
 */
object IsoHelper {

    suspend fun resolve(
        context: Context,
        uri: String,
        mode: IsoAttachMode,
        onProgress: ((copied: Long, total: Long) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = Uri.parse(uri)
            when (mode) {
                IsoAttachMode.DIRECT ->
                    resolveReadablePath(context, parsed)
                        ?: copyToPrivate(context, parsed, onProgress).absolutePath
                IsoAttachMode.COPY ->
                    copyToPrivate(context, parsed, onProgress).absolutePath
            }
        }
    }

    /** Kept for call-site compatibility; fd inheritance is no longer used. */
    fun releaseDirectFd() {
        // no-op
    }

    // ------------------------------------------------------------------
    // DIRECT: URI -> absolute path
    // ------------------------------------------------------------------

    private fun resolveReadablePath(context: Context, uri: Uri): String? {
        val path = when {
            uri.scheme == "file" -> uri.path
            uri.authority == "com.android.externalstorage.documents" ->
                externalStoragePath(uri)
            uri.authority == "com.android.providers.downloads.documents" ->
                downloadsRawPath(uri)
            uri.authority == "media" -> mediaStorePath(context, uri)
            else -> null
        } ?: return null
        val f = File(path)
        return if (f.isFile && f.canRead()) f.absolutePath else null
    }

    /** content://com.android.externalstorage.documents/document/primary%3ADownload%2Fx.iso */
    private fun externalStoragePath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val sep = docId.indexOf(':')
        if (sep <= 0 || sep == docId.length - 1) return null
        val volume = docId.substring(0, sep)
        val rel = docId.substring(sep + 1)
        val root = if (volume.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/$volume"
        }
        return "$root/$rel"
    }

    /** content://.../downloads.documents/document/raw%3A%2Fstorage%2F... */
    private fun downloadsRawPath(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        return if (docId.startsWith("raw:")) docId.substring(4) else null
    }

    private fun mediaStorePath(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    // ------------------------------------------------------------------
    // COPY: stream into the private VM directory (reusable across boots)
    // ------------------------------------------------------------------

    private fun copyToPrivate(
        context: Context,
        uri: Uri,
        onProgress: ((Long, Long) -> Unit)?
    ): File {
        val dest = QemuPaths(context).copiedIsoFile
        dest.parentFile?.mkdirs()
        val sidecar = File(dest.parentFile, dest.name + ".uri")

        // reuse a previous copy of the very same source document
        if (dest.exists() && sidecar.exists() && runCatching { sidecar.readText() }.getOrNull() == uri.toString()) {
            onProgress?.invoke(dest.length(), dest.length())
            return dest
        }

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

        runCatching { sidecar.writeText(uri.toString()) }
        return dest
    }
}
