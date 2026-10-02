package com.ee555719.golinux.display

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal RFB 3.8 (VNC) client for QEMU's built-in VNC server.
 *
 * Supports Raw / CopyRect / DesktopSize encodings with a fixed 32-bpp
 * little-endian pixel format (0x00RRGGBB + forced alpha on decode).
 * All methods except connect()/readLoop() are safe to call from the UI
 * thread; frame bitmaps are mutated only by the reader thread.
 */
class RfbClient(
    private val host: String = "127.0.0.1",
    private val port: Int = PORT
) {

    companion object {
        /** QEMU is launched with -vnc 127.0.0.1:0 → TCP 5900. */
        const val PORT = 5900

        /** Max wait for the RFB version/security handshake bytes. */
        const val HANDSHAKE_TIMEOUT_MS = 8000
    }

    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    @Volatile
    var desktopName = ""
        private set

    @Volatile
    private var frame: Bitmap? = null

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var closed = false

    private var input: DataInputStream? = null
    private var output: BufferedOutputStream? = null
    private val writeLock = Any()

    /** Incremented after every FramebufferUpdate - polled by the UI. */
    val frames = AtomicInteger(0)

    private var byteBuf = ByteArray(0)
    private var pixelBuf = IntArray(0)

    fun frameOrNull(): Bitmap? = frame

    // ------------------------------------------------------------------
    // handshake
    // ------------------------------------------------------------------

    @Throws(IOException::class)
    fun connect(timeoutMs: Int = 5000) {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.tcpNoDelay = true
            s.keepAlive = true
            // handshake reads must not block forever - if QEMU accepts the TCP
            // connection but never speaks RFB we want a retryable error instead
            // of an eternal "连接中…"
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
            val out = BufferedOutputStream(s.getOutputStream(), 1 shl 14)
            socket = s
            input = inp
            output = out
            closed = false

            // version handshake (always answer 3.8)
            val ver = ByteArray(12)
            inp.readFully(ver)
            writeRaw(out, "RFB 003.008\n".toByteArray(Charsets.US_ASCII))

            // security types (3.8 style)
            val nTypes = inp.readUnsignedByte()
            if (nTypes == 0) {
                val len = readU32(inp).toInt()
                val reason = ByteArray(len)
                inp.readFully(reason)
                throw IOException("VNC 握手被拒绝: ${String(reason)}")
            }
            val types = ByteArray(nTypes)
            inp.readFully(types)
            val chosen = if (types.contains(1.toByte())) 1 else types[0].toInt()
            if (chosen != 1) throw IOException("不支持的 VNC 安全类型 $chosen（QEMU 请勿设置密码）")
            writeRaw(out, byteArrayOf(chosen.toByte()))

            val secResult = readU32(inp)
            if (secResult != 0L) {
                val len = readU32(inp).toInt()
                val reason = ByteArray(len)
                inp.readFully(reason)
                throw IOException("VNC 认证失败: ${String(reason)}")
            }

            // ClientInit: shared flag
            writeRaw(out, byteArrayOf(1))

            // ServerInit
            width = inp.readUnsignedShort()
            height = inp.readUnsignedShort()
            inp.skipBytes(16) // server pixel format - we override it below
            val nameLen = readU32(inp).toInt()
            val name = ByteArray(nameLen)
            inp.readFully(name)
            desktopName = String(name, Charsets.UTF_8)

            frame = newBitmap(width, height)

            // force 32bpp LE true-colour, red@16 green@8 blue@0
            val pf = ByteArray(16)
            pf[0] = 32.toByte()  // bits per pixel
            pf[1] = 24.toByte()  // depth
            pf[2] = 0            // big endian
            pf[3] = 1            // true colour
            pf[4] = 255.toByte(); pf[5] = 0  // red max
            pf[6] = 255.toByte(); pf[7] = 0  // green max
            pf[8] = 255.toByte(); pf[9] = 0  // blue max
            pf[10] = 16          // red shift
            pf[11] = 8           // green shift
            pf[12] = 0           // blue shift
            val sp = ByteArray(1 + 3 + 16)
            sp[0] = 0 // SetPixelFormat
            System.arraycopy(pf, 0, sp, 4, 16)
            writeRaw(out, sp)

            // SetEncodings: Raw, CopyRect, DesktopSize, LastRect
            val se = ByteArray(4 + 4 * 4)
            se[0] = 2 // SetEncodings
            se[2] = 0; se[3] = 4 // count (BE)
            fun enc(i: Int, v: Int) {
                val o = 4 + i * 4
                se[o] = (v ushr 24).toByte()
                se[o + 1] = (v ushr 16).toByte()
                se[o + 2] = (v ushr 8).toByte()
                se[o + 3] = v.toByte()
            }
            enc(0, -1)    // Raw
            enc(1, 2)     // CopyRect
            enc(2, -223)  // DesktopSize
            enc(3, -224)  // LastRect
            writeRaw(out, se)
            out.flush()

            // first full framebuffer request
            requestUpdate(incremental = false)

            // handshake done - readLoop blocks indefinitely until updates arrive
            s.soTimeout = 0
        } catch (e: java.net.SocketTimeoutException) {
            runCatching { s.close() }
            throw IOException("VNC 连接/握手超时: ${e.message}")
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    // ------------------------------------------------------------------
    // read loop (blocking - run on a worker thread)
    // ------------------------------------------------------------------

    @Throws(IOException::class)
    fun readLoop(onUpdate: () -> Unit) {
        val inp = input ?: throw IOException("VNC 未连接")
        while (!closed) {
            when (val type = inp.readUnsignedByte()) {
                0 -> { // FramebufferUpdate
                    inp.readByte() // padding
                    val nRects = inp.readUnsignedShort()
                    var resized = false
                    var i = 0
                    while (i < nRects) {
                        i++
                        val x = inp.readUnsignedShort()
                        val y = inp.readUnsignedShort()
                        val w = inp.readUnsignedShort()
                        val h = inp.readUnsignedShort()
                        val enc = inp.readInt()
                        when (enc) {
                            -1 -> readRawRect(inp, x, y, w, h)
                            2 -> readCopyRect(inp, x, y, w, h)
                            -223 -> { // DesktopSize
                                frame = newBitmap(w, h)
                                width = w
                                height = h
                                resized = true
                            }
                            -224 -> { /* LastRect - stop early */ break }
                            else -> throw IOException("不支持的 VNC 编码 $enc")
                        }
                    }
                    requestUpdate(incremental = !resized)
                    frames.incrementAndGet()
                    onUpdate()
                }
                1 -> { // SetColourMapEntries
                    inp.readByte()
                    val first = inp.readUnsignedShort()
                    val n = inp.readUnsignedShort()
                    repeat(n) {
                        inp.readUnsignedShort()
                        inp.readUnsignedShort()
                        inp.readUnsignedShort()
                    }
                    @Suppress("UNUSED_EXPRESSION") first
                }
                2 -> { /* Bell */ }
                3 -> { // ServerCutText
                    val len = readU32(inp).toInt()
                    skipFully(inp, len.toLong())
                }
                else -> throw IOException("未知 VNC 服务端消息类型 $type")
            }
        }
    }

    private fun readRawRect(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val bmp = frame ?: return
        val bytes = w * h * 4
        if (w <= 0 || h <= 0 || bytes <= 0) return
        if (byteBuf.size < bytes) byteBuf = ByteArray(bytes)
        skipFullyData(inp, byteBuf, bytes)
        if (x + w > bmp.width || y + h > bmp.height) return // stale rect - ignore
        if (pixelBuf.size < w * h) pixelBuf = IntArray(w * h)
        var i = 0
        for (p in 0 until w * h) {
            val b0 = byteBuf[i].toInt() and 0xff
            val b1 = byteBuf[i + 1].toInt() and 0xff
            val b2 = byteBuf[i + 2].toInt() and 0xff
            pixelBuf[p] = (0xff shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            i += 4
        }
        bmp.setPixels(pixelBuf, 0, w, x, y, w, h)
    }

    private fun readCopyRect(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int) {
        val srcX = inp.readUnsignedShort()
        val srcY = inp.readUnsignedShort()
        val bmp = frame ?: return
        if (w <= 0 || h <= 0) return
        if (srcX + w > bmp.width || srcY + h > bmp.height) return
        if (x + w > bmp.width || y + h > bmp.height) return
        if (pixelBuf.size < w * h) pixelBuf = IntArray(w * h)
        bmp.getPixels(pixelBuf, 0, w, srcX, srcY, w, h)
        bmp.setPixels(pixelBuf, 0, w, x, y, w, h)
    }

    private fun newBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            .also { it.eraseColor(0xff000000.toInt()) }

    // ------------------------------------------------------------------
    // client → server messages
    // ------------------------------------------------------------------

    fun requestUpdate(incremental: Boolean) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        send {
            it.write(3) // FramebufferUpdateRequest
            it.write(if (incremental) 1 else 0)
            it.write(0)                 // x BE
            it.write(0)
            it.write(0)                 // y
            it.write(0)
            it.write(w shr 8); it.write(w and 0xff)
            it.write(h shr 8); it.write(h and 0xff)
        }
    }

    fun sendPointer(mask: Int, x: Int, y: Int) {
        val cx = x.coerceIn(0, (width - 1).coerceAtLeast(0))
        val cy = y.coerceIn(0, (height - 1).coerceAtLeast(0))
        send {
            it.write(5) // PointerEvent
            it.write(mask and 0xff)
            it.write(cx shr 8); it.write(cx and 0xff)
            it.write(cy shr 8); it.write(cy and 0xff)
        }
    }

    fun sendKey(down: Boolean, keysym: Int) {
        send {
            it.write(4) // KeyEvent
            it.write(if (down) 1 else 0)
            it.write(0); it.write(0) // padding
            it.write((keysym ushr 24) and 0xff)
            it.write((keysym ushr 16) and 0xff)
            it.write((keysym ushr 8) and 0xff)
            it.write(keysym and 0xff)
        }
    }

    private inline fun send(block: (BufferedOutputStream) -> Unit) {
        val out = output ?: return
        synchronized(writeLock) {
            try {
                block(out)
                out.flush()
            } catch (_: Exception) {
                // connection died - reader loop will surface the error
            }
        }
    }

    private fun writeRaw(out: BufferedOutputStream, bytes: ByteArray) = synchronized(writeLock) {
        out.write(bytes)
        out.flush()
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun readU32(inp: DataInputStream): Long {
        val b0 = inp.readUnsignedByte().toLong()
        val b1 = inp.readUnsignedByte().toLong()
        val b2 = inp.readUnsignedByte().toLong()
        val b3 = inp.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun skipFully(inp: DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val skipped = inp.skip(left)
            if (skipped > 0) left -= skipped else {
                if (inp.read() < 0) throw java.io.EOFException()
                left--
            }
        }
    }

    private fun skipFullyData(inp: DataInputStream, buf: ByteArray, n: Int) {
        var off = 0
        var left = n
        while (left > 0) {
            val r = inp.read(buf, off, left)
            if (r < 0) throw java.io.EOFException()
            off += r
            left -= r
        }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
        runCatching { input?.close() }
        socket = null
        input = null
        output = null
    }
}
