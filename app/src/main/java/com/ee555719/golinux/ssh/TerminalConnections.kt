package com.ee555719.golinux.ssh

import com.ee555719.golinux.terminal.TerminalEmulator
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

interface TerminalConnection {
    val description: String
    fun write(bytes: ByteArray)
    fun close()
    val input: InputStream
}

class SshConnection(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
    private val cols: Int,
    private val rows: Int
) : TerminalConnection {

    override val description: String = "SSH $user@$host:$port"

    private var client: SSHClient? = null
    private var session: net.schmizz.sshj.connection.channel.direct.Session? = null
    private var shell: net.schmizz.sshj.connection.channel.direct.Session.Shell? = null

    override val input: InputStream
        get() = shell?.getInputStream()
            ?: throw IllegalStateException("SSH 尚未连接")

    fun connect() {
        val c = SSHClient().apply {
            addHostKeyVerifier(PromiscuousVerifier())
            setConnectTimeout(8000)
            setTimeout(0)
            connect(host, port)
            authPassword(user, password)
        }
        val s = c.startSession()
        s.allocatePTY(
            "xterm-256color", cols, rows, 640, 480,
            emptyMap<net.schmizz.sshj.connection.channel.direct.PTYMode, Int>()
        )
        val sh = s.startShell()
        client = c
        session = s
        shell = sh
    }

    override fun write(bytes: ByteArray) {
        val out = shell?.getOutputStream() ?: return
        synchronized(this) {
            out.write(bytes)
            out.flush()
        }
    }

    override fun close() {
        runCatching { shell?.close() }
        runCatching { session?.close() }
        runCatching { client?.disconnect() }
    }
}

class SerialConnection(private val host: String, private val port: Int) : TerminalConnection {

    override val description: String = "串口控制台 $host:$port"

    private var socket: Socket? = null

    override val input: InputStream
        get() = socket?.getInputStream()
            ?: throw IllegalStateException("串口尚未连接")

    fun connect() {
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", port), 4000)
        s.tcpNoDelay = true
        s.soTimeout = 0
        socket = s
    }

    override fun write(bytes: ByteArray) {
        val out = socket?.getOutputStream() ?: return
        synchronized(this) {
            out.write(bytes)
            out.flush()
        }
    }

    override fun close() {
        runCatching { socket?.close() }
    }
}

/**
 * Owns one terminal connection at a time, pumps its output into the
 * emulator on a background thread and exposes a status flow for the UI.
 */
class TerminalSession(
    private val emulator: TerminalEmulator,
    private val cols: Int,
    private val rows: Int
) {
    @Volatile
    var connection: TerminalConnection? = null
        private set

    private val _status = kotlinx.coroutines.flow.MutableStateFlow("未连接")
    val status: kotlinx.coroutines.flow.StateFlow<String> = _status

    private var pump: Thread? = null

    @Synchronized
    fun connectSsh(host: String, port: Int, user: String, password: String): Result<Unit> {
        return runCatching {
            close()
            reportStatus("连接中…")
            val conn = SshConnection(host, port, user, password, cols, rows)
            conn.connect()
            activate(conn)
        }
    }

    @Synchronized
    fun connectSerial(host: String, port: Int): Result<Unit> {
        return runCatching {
            close()
            reportStatus("连接中…")
            val conn = SerialConnection(host, port)
            conn.connect()
            activate(conn)
        }
    }

    private fun activate(conn: TerminalConnection) {
        connection = conn
        _status.value = "已连接 · ${conn.description}"
        pump = Thread({
            val buf = ByteArray(8192)
            try {
                val inp = conn.input
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    if (n > 0) emulator.feed(buf, n)
                }
                _status.value = "连接已关闭"
            } catch (t: Throwable) {
                _status.value = "连接中断: ${t.message}"
            }
        }, "terminal-pump").apply {
            isDaemon = true
            start()
        }
    }

    fun send(text: String) {
        connection?.write(text.toByteArray(Charsets.UTF_8))
    }

    fun reportStatus(msg: String) {
        _status.value = msg
    }

    val isConnected: Boolean
        get() = connection != null

    @Synchronized
    fun close() {
        runCatching { connection?.close() }
        connection = null
        pump = null
        _status.value = "未连接"
    }
}
