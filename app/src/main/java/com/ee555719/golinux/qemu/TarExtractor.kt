package com.ee555719.golinux.qemu

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal USTAR reader used to unpack `assets/qemu-libs.tar.gz` into the
 * app data directory. Only regular files, directories and (skipped)
 * symlinks are handled - exactly what the asset tarball contains.
 */
object TarExtractor {

    /**
     * Auto-detects gzip (magic 1F 8B) vs plain tar. The Android asset
     * pipeline transparently gunzips `.gz` assets and strips the extension
     * when packaging, so both forms must be supported.
     */
    fun extractAuto(input: InputStream, destDir: File) {
        val buffered = input.buffered()
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        if (b1 == 0x1f && b2 == 0x8b) {
            GZIPInputStream(buffered).use { gz -> extractTar(gz, destDir) }
        } else {
            extractTar(buffered, destDir)
        }
    }

    fun extractGzipTar(input: InputStream, destDir: File) {
        GZIPInputStream(input.buffered()).use { gz -> extractTar(gz, destDir) }
    }

    fun extractTar(input: InputStream, destDir: File) {
        destDir.mkdirs()
        val header = ByteArray(512)
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break // end-of-archive blocks

            val name = cstr(header, 0, 100)
            val prefix = cstr(header, 345, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val size = octal(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()

            when {
                typeFlag == '5' || fullName.endsWith("/") -> {
                    File(destDir, fullName.trimEnd('/')).mkdirs()
                }
                typeFlag == '0' || typeFlag == '\u0000' -> {
                    val outFile = File(destDir, fullName)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> copyN(input, out, size) }
                }
                else -> skipN(input, size) // symlinks, hardlinks, etc.
            }
            // 512-byte block padding
            val padding = ((512 - (size % 512)) % 512)
            skipN(input, padding)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            if (n == 0) continue
            off += n
        }
        return true
    }

    private fun cstr(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val max = off + len
        while (end < max && buf[end] != 0.toByte()) end++
        return String(buf, off, end - off, Charsets.UTF_8)
    }

    private fun octal(buf: ByteArray, off: Int, len: Int): Long {
        val s = cstr(buf, off, len).trim()
        if (s.isEmpty()) return 0L
        // tar size/offset fields are NUL- or space-terminated OCTAL numbers.
        // Parsing them as decimal desynchronises the stream (EISDIR on the
        // next bogus entry), so force base 8 here.
        if (s.any { it.code < 0x30 || it.code > 0x37 }) {
            // GNU base-256 encoding (>= 8 GiB entries) - not used by our assets
            return 0L
        }
        return s.toLongOrNull(8) ?: 0L
    }

    private fun copyN(input: InputStream, out: OutputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipN(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) break
            remaining--
        }
    }
}
