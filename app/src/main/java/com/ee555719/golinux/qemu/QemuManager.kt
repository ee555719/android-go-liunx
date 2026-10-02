package com.ee555719.golinux.qemu

import android.content.Context
import com.ee555719.golinux.data.BootMode
import com.ee555719.golinux.data.PortForwardRule
import com.ee555719.golinux.data.VmConfig
import com.ee555719.golinux.data.VmState
import com.ee555719.golinux.vm.VmService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.TimeUnit

class QemuManager(val context: Context) {

    val paths = QemuPaths(context)

    private val _state = MutableStateFlow(VmState.STOPPED)
    val state: StateFlow<VmState> = _state.asStateFlow()

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var startedAt: Long = 0L
        private set

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopping = false

    val isAlive: Boolean
        get() = process?.isAlive == true

    suspend fun start(cfg: VmConfig, rules: List<PortForwardRule>) {
        synchronized(this) {
            if (process?.isAlive == true) {
                throw IllegalStateException("虚拟机已在运行")
            }
            _state.value = VmState.STARTING
            lastError = null
            stopping = false
        }
        try {
            if (!paths.qemuBinaryPresent()) {
                throw IllegalStateException(
                    "缺少 QEMU 可执行文件（libqemu-system-aarch64.so）。请运行 scripts/prepare_assets.ps1 重新打包 APK。"
                )
            }
            paths.ensureDirs()
            paths.ensureLibs()
            paths.ensureFirmware()
            if (!paths.hasFirmware()) {
                throw IllegalStateException("缺少 UEFI 固件 AAVMF_CODE.fd，请运行 scripts/prepare_assets.ps1")
            }

            var isoPath: String? = null
            if (cfg.bootMode == BootMode.ISO) {
                val uri = cfg.isoUri ?: throw IllegalStateException("请先通过 SAF 选择 Linux 安装 ISO 镜像")
                isoPath = IsoHelper.resolve(context, uri, cfg.isoMode).getOrElse { e ->
                    throw IllegalStateException("解析 ISO 镜像失败: ${e.message}")
                }
            }

            paths.clearLog()
            val args = QemuCommandBuilder.build(paths, cfg, rules, isoPath)
            appendLog("# command:\n# " + args.joinToString(" ") + "\n")

            val pb = ProcessBuilder(args)
            pb.environment().putAll(paths.childEnvironment())
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.logFile))
            pb.redirectError(ProcessBuilder.Redirect.appendTo(paths.logFile))

            val proc = pb.start()
            process = proc
            startedAt = System.currentTimeMillis()
            _state.value = VmState.RUNNING

            Thread({
                val code = try {
                    proc.waitFor()
                } catch (t: Throwable) {
                    -1
                }
                onProcessExited(code)
            }, "qemu-watcher").apply { isDaemon = true }.start()

            runCatching { VmService.start(context) }
        } catch (t: Throwable) {
            IsoHelper.releaseDirectFd()
            process = null
            _state.value = VmState.STOPPED
            lastError = t.message ?: t.toString()
            appendLog("# start failed: ${lastError}\n")
            throw t
        }
    }

    private fun onProcessExited(code: Int) {
        appendLog("\n# qemu exited with code $code\n")
        IsoHelper.releaseDirectFd()
        process = null
        if (!stopping && code != 0 && _state.value != VmState.STOPPED) {
            lastError = "QEMU 异常退出（code $code），请查看日志"
        }
        _state.value = VmState.STOPPED
        runCatching { VmService.stop(context) }
    }

    fun stop() {
        val proc = process ?: run {
            _state.value = VmState.STOPPED
            runCatching { VmService.stop(context) }
            return
        }
        stopping = true
        _state.value = VmState.STOPPING
        if (proc.isAlive) {
            val qmp = QmpClient(currentQmpPort)
            qmp.powerdown()
            if (!proc.waitFor(4, TimeUnit.SECONDS)) {
                qmp.quit()
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.destroy()
                    if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                        proc.destroyForcibly()
                    }
                }
            }
        }
        synchronized(this) { process = null }
        IsoHelper.releaseDirectFd()
        _state.value = VmState.STOPPED
        runCatching { VmService.stop(context) }
    }

    fun suspend() {
        val proc = process?.takeIf { it.isAlive } ?: throw IllegalStateException("虚拟机未在运行")
        val qmp = QmpClient(currentQmpPort)
        if (qmp.execute("stop") == null) throw IllegalStateException("QMP 暂停失败，请检查虚拟机状态")
        _state.value = VmState.SUSPENDED
    }

    fun resume() {
        val proc = process?.takeIf { it.isAlive } ?: throw IllegalStateException("虚拟机未在运行")
        val qmp = QmpClient(currentQmpPort)
        if (qmp.execute("cont") == null) throw IllegalStateException("QMP 恢复失败，请检查虚拟机状态")
        _state.value = VmState.RUNNING
    }

    suspend fun restart(cfg: VmConfig, rules: List<PortForwardRule>) {
        stop()
        start(cfg, rules)
    }

    private var currentQmpPort: Int = 60024

    fun onConfigApplied(cfg: VmConfig) {
        currentQmpPort = cfg.qmpPort
    }

    fun readLog(): String = paths.readLog()

    fun clearLog() = paths.clearLog()

    private fun appendLog(text: String) {
        runCatching {
            java.io.FileOutputStream(paths.logFile, true).use { it.write(text.toByteArray()) }
        }
    }

    fun diskFile(): File = paths.activeDiskFile()
}
