package com.ee555719.golinux.data

enum class VmState { STOPPED, STARTING, RUNNING, SUSPENDED, STOPPING }

enum class BootMode { DISK, ISO }

enum class IsoAttachMode { DIRECT, COPY }

enum class ThemeMode { DARK, LIGHT }

data class PortForwardRule(
    val id: Long,
    val enabled: Boolean = true,
    val hostPort: Int,
    val guestPort: Int,
    val label: String = ""
)

data class VmConfig(
    val memoryMb: Int = 1024,
    val smp: Int = 2,
    val diskSizeGb: Int = 8,
    val bootMode: BootMode = BootMode.DISK,
    val isoUri: String? = null,
    val isoName: String? = null,
    val isoMode: IsoAttachMode = IsoAttachMode.DIRECT,
    val sshHostPort: Int = 60022,
    val serialPort: Int = 60023,
    val qmpPort: Int = 60024,
    val autoSsh: Boolean = true,
    val sshUser: String = "root",
    val sshPassword: String = ""
)

data class BackupEntry(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long
)
