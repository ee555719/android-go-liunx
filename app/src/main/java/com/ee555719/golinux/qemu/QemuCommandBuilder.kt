package com.ee555719.golinux.qemu

import com.ee555719.golinux.data.PortForwardRule
import com.ee555719.golinux.data.VmConfig

object QemuCommandBuilder {

    fun build(paths: QemuPaths, cfg: VmConfig, rules: List<PortForwardRule>, isoPath: String?): List<String> {
        val args = mutableListOf<String>()
        args += paths.qemuBinary.absolutePath
        // QEMU data directory (option ROMs etc.), extracted from assets on
        // first start - without it "efi-virtio.rom" cannot be found.
        args += listOf("-L", paths.shareQemuDir.absolutePath)
        args += listOf("-machine", "virt")
        args += listOf("-cpu", "cortex-a57")
        args += listOf("-m", cfg.memoryMb.toString())
        args += listOf("-smp", cfg.smp.toString())

        // UEFI firmware (CODE is read-only, VARS is a writable working copy)
        args += listOf(
            "-drive",
            "if=pflash,format=raw,read-only=on,file=${paths.firmwareCode.absolutePath}"
        )
        if (paths.varsFile.exists()) {
            args += listOf("-drive", "if=pflash,format=raw,file=${paths.varsFile.absolutePath}")
        }

        val disk = paths.activeDiskFile()
        if (disk.exists()) {
            val fmt = if (disk.name.endsWith(".qcow2")) "qcow2" else "raw"
            args += listOf("-drive", "file=${disk.absolutePath},format=$fmt,if=virtio")
        }

        if (isoPath != null) {
            args += listOf("-cdrom", isoPath)
            args += listOf("-boot", "d")
        }

        val hostfwds = rules.filter { it.enabled && it.hostPort in 1..65535 && it.guestPort in 1..65535 }
            .map { "hostfwd=tcp::${it.hostPort}-:${it.guestPort}" }
        val netdev = "user,id=n1" + hostfwds.joinToString(",") { ",$it" }
        args += listOf("-netdev", netdev)
        // romfile= (empty) skips the PCI option ROM: aarch64 UEFI boots virtio
        // from the device tree, so the ROM is unnecessary even if -L is broken.
        args += listOf("-device", "virtio-net,netdev=n1,romfile=")

        args += listOf("-serial", "tcp:127.0.0.1:${cfg.serialPort},server=on,wait=off")
        args += listOf("-qmp", "tcp:127.0.0.1:${cfg.qmpPort},server=on,wait=off")
        args += listOf("-nographic")
        return args
    }
}
