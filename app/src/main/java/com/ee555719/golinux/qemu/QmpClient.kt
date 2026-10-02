package com.ee555719.golinux.qemu

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/** Tiny QMP (QEMU Machine Protocol) client used for graceful shutdown. */
class QmpClient(private val port: Int, private val timeoutMs: Int = 3000) {

    fun execute(command: String): String? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                socket.soTimeout = timeoutMs
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

                reader.readLine() ?: return null // greeting
                writer.write("""{"execute":"qmp_capabilities"}""")
                writer.write("\n")
                writer.flush()
                reader.readLine() ?: return null

                writer.write("""{"execute":"$command"}""")
                writer.write("\n")
                writer.flush()

                var line = reader.readLine()
                while (line != null) {
                    if (line.contains("\"return\"") || line.contains("\"error\"")) return line
                    line = reader.readLine()
                }
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun powerdown(): Boolean = execute("system_powerdown") != null

    fun quit(): Boolean = execute("quit") != null
}
