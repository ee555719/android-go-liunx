<#
.SYNOPSIS
  Prepares QEMU engine binaries + UEFI firmware for the GoLinux VM APK.

.DESCRIPTION
  * Downloads qemu-system-aarch64 / qemu-img and their shared library
    dependencies from the Termux package repository (GPL-2.0 builds).
  * Patches PT_INTERP to /system/bin/linker64 when the binaries were built
    against the Termux prefix (which does not exist inside this app).
  * Packs the main binaries as  libqemu-system-aarch64.so / libqemu-img.so
    into  app/src/main/jniLibs/arm64-v8a/  (Android 10+ W^X: only files in
    nativeLibraryDir may be executed).
  * Packs all shared libraries into  app/src/main/assets/qemu-libs.tar.gz
    (extracted at runtime, exposed via LD_LIBRARY_PATH - dlopen of app data
    files is still allowed).
  * Downloads EDK2 (AAVMF) UEFI firmware from Debian into
    app/src/main/assets/firmware/.

.NOTES
  Requires: PowerShell 5.1+, internet access, Windows tar (bsdtar), python3 (optional, for ELF patching).
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [string]$QemuBinary,    # optional local path / URL of a prebuilt qemu-system-aarch64
    [string]$QemuImgBinary, # optional local path / URL of a prebuilt qemu-img
    [string]$FirmwareDeb    # optional local path / URL of qemu-efi-aarch64 .deb
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$JniLibsDir  = Join-Path $ProjectRoot "app\src\main\jniLibs\arm64-v8a"
$AssetsDir   = Join-Path $ProjectRoot "app\src\main\assets"
$LibsAsset   = Join-Path $AssetsDir "qemu-libs.tar.gz"
$FwDir       = Join-Path $AssetsDir "firmware"
$Staging     = Join-Path $env:TEMP "golinux-assets-staging"

$TermuxBase  = "https://packages.termux.dev/apt/termux-main"
$PackagesIdx = Join-Path $Staging "Packages"

New-Item -ItemType Directory -Force -Path $JniLibsDir, $AssetsDir, $FwDir, $Staging | Out-Null

function Log($msg) { Write-Host "[prepare] $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "[prepare] ERROR: $msg" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------------------
# 1. Download and parse the Termux package index
# ---------------------------------------------------------------------------
function Get-PackageIndex {
    $need = $Force -or -not (Test-Path $PackagesIdx) `
        -or ((Get-Item $PackagesIdx).LastWriteTime -lt (Get-Date).AddHours(-24))
    if ($need) {
        Log "downloading Termux package index..."
        Invoke-WebRequest -Uri "$TermuxBase/dists/stable/main/binary-aarch64/Packages" `
            -OutFile $PackagesIdx -UseBasicParsing -TimeoutSec 120
    }
    $map = @{}
    $text = [System.IO.File]::ReadAllText($PackagesIdx)
    foreach ($block in ($text -split "`n`n")) {
        if ($block -notmatch "(?m)^Package: ") { continue }
        $pkg = ""
        $file = ""
        $dep = ""
        foreach ($line in ($block -split "`r?`n")) {
            if ($line.StartsWith("Package: "))       { $pkg  = $line.Substring(9) }
            elseif ($line.StartsWith("Filename: "))  { $file = $line.Substring(10) }
            elseif ($line.StartsWith("Depends: "))   { $dep  = $line.Substring(9) }
        }
        if ($pkg) { $map[$pkg] = @{ Filename = $file; Depends = $dep } }
    }
    return $map
}

function Resolve-Depends($index, $roots) {
    $selected = [ordered]@{}
    $queue = New-Object System.Collections.Queue
    foreach ($r in $roots) { $queue.Enqueue($r) }
    while ($queue.Count -gt 0) {
        $name = $queue.Dequeue()
        if ($selected.Contains($name)) { continue }
        if (-not $index.ContainsKey($name)) {
            Write-Host "[prepare]   (virtual/missing dep skipped: $name)" -ForegroundColor DarkGray
            continue
        }
        $selected[$name] = $index[$name]
        $depends = $index[$name].Depends
        if ($depends) {
            foreach ($clause in ($depends -split ",")) {
                # take the first alternative, strip version constraints
                $alt = ($clause -split "\|")[0].Trim()
                $alt = ($alt -replace "\(.*?\)", "").Trim()
                if ($alt -and -not $selected.Contains($alt)) { $queue.Enqueue($alt) }
            }
        }
    }
    return $selected
}

# ---------------------------------------------------------------------------
# 2. Download + extract .deb archives
# ---------------------------------------------------------------------------
function Get-Deb($relPath) {
    $name = Split-Path -Leaf $relPath
    # Windows paths cannot contain ':' (termux uses 1:11.0.3 epoch versions)
    $safeName = $name -replace ":", "_"
    $dest = Join-Path $Staging "debs\$safeName"
    if ((Test-Path $dest) -and -not $Force) { return $dest }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    Log "downloading $safeName"
    # encode characters that break URL parsing in IWR
    $urlRel = $relPath -replace ":", "%3a"
    Invoke-WebRequest -Uri "$TermuxBase/$urlRel" -OutFile $dest -UseBasicParsing -TimeoutSec 300
    return $dest
}

function Expand-Deb($debPath, $outDir) {
    if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    $extractPy = @'
import sys, os, lzma, gzip, shutil
deb, out = sys.argv[1], sys.argv[2]

def ar_read(path, outdir):
    with open(path, "rb") as f:
        if f.read(8) != b"!<arch>\x0a":
            raise SystemExit("not an ar archive: " + path)
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                break
            name = hdr[0:16].decode("ascii", "replace").strip().rstrip("/")
            size = int(hdr[48:58].decode().strip())
            data = f.read(size)
            if size % 2:
                f.read(1)
            if name.startswith("data.tar") or name.startswith("control.tar") or name.startswith("debian-binary"):
                open(os.path.join(outdir, name.replace("/", "_")), "wb").write(data)

def decompress(src, dst):
    if src.endswith(".xz"):
        with lzma.open(src, "rb") as i, open(dst, "wb") as o:
            shutil.copyfileobj(i, o)
    elif src.endswith(".gz") or src.endswith(".tgz"):
        with gzip.open(src, "rb") as i, open(dst, "wb") as o:
            shutil.copyfileobj(i, o)
    elif src.endswith(".zst") or src.endswith(".zstd"):
        try:
            import zstandard
        except ImportError:
            os.system(sys.executable + " -m pip install --quiet zstandard")
            import zstandard
        dctx = zstandard.ZstdDecompressor()
        with open(src, "rb") as i, open(dst, "wb") as o:
            dctx.copy_stream(i, o)
    else:
        shutil.copyfile(src, dst)

ar_read(deb, out)
data = [n for n in os.listdir(out) if n.startswith("data.tar.")]
if not data:
    raise SystemExit("no data.tar member in " + deb)
member = os.path.join(out, data[0])
result = os.path.join(out, "data.tar.extracted")
decompress(member, result)
print(result)
'@
    $tmpPy = Join-Path $Staging "extract_deb.py"
    [System.IO.File]::WriteAllText($tmpPy, $extractPy)
    $pyCmd = Get-Command python -ErrorAction SilentlyContinue
    if (-not $pyCmd) { throw "python is required to extract .deb archives" }
    $dataTarOut = & $pyCmd.Source $tmpPy $debPath $outDir
    if ($LASTEXITCODE -ne 0) { throw "deb extraction failed for $debPath" }
    $dataTarOut = ($dataTarOut | Select-Object -Last 1).ToString().Trim()

    $dataDir = Join-Path $outDir "root"
    New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
    & tar -xf $dataTarOut -C $dataDir
    if ($LASTEXITCODE -ne 0) { throw "tar failed on data.tar of $debPath" }
    return $dataDir
}

# ---------------------------------------------------------------------------
# 3. ELF helpers
# ---------------------------------------------------------------------------
function Test-Aarch64Elf($path) {
    $fs = [System.IO.File]::OpenRead($path)
    try {
        $buf = New-Object byte[] 20
        $n = $fs.Read($buf, 0, 20)
        if ($n -lt 20) { return $false }
        if ($buf[0] -ne 0x7f -or $buf[1] -ne 0x45 -or $buf[2] -ne 0x4c -or $buf[3] -ne 0x46) { return $false }
        $machine = [BitConverter]::ToUInt16($buf, 18)
        return ($machine -eq 183) # EM_AARCH64
    } finally { $fs.Dispose() }
}

function Patch-Interp($path) {
    $python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $python) {
        Write-Host "[prepare]   python not found - skipping INTERP check" -ForegroundColor DarkYellow
        return
    }
    $pyScript = @'
import sys, struct
path = sys.argv[1]
data = bytearray(open(path, "rb").read())
if data[:4] != b"\x7fELF" or data[4] != 2:
    sys.exit(0)
phoff, = struct.unpack_from("<Q", data, 32)
phentsize, phnum = struct.unpack_from("<HH", data, 54)
for i in range(phnum):
    off = phoff + i * phentsize
    p_type, = struct.unpack_from("<I", data, off)
    if p_type == 3:  # PT_INTERP
        p_offset, = struct.unpack_from("<Q", data, off + 8)
        p_filesz, = struct.unpack_from("<Q", data, off + 32)
        interp = bytes(data[p_offset:p_offset + p_filesz]).split(b"\0")[0].decode(errors="replace")
        if "com.termux" in interp or "com.android" in interp and "/linker" not in interp:
            new = b"/system/bin/linker64\0"
            if len(new) <= p_filesz:
                data[p_offset:p_offset + p_filesz] = new.ljust(p_filesz, b"\0")
                open(path, "wb").write(data)
                print("INTERP patched: %s -> /system/bin/linker64" % interp)
            else:
                print("INTERP too long to patch: %s" % interp)
        else:
            print("INTERP ok: %s" % interp)
        break
'@
    $tmpPy = Join-Path $Staging "patch_interp.py"
    [System.IO.File]::WriteAllText($tmpPy, $pyScript)
    & $python.Source $tmpPy $path
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
try {
    $index = Get-PackageIndex
    Log "index has $($index.Count) packages"

    # ---- QEMU binaries ----
    $binDir = Join-Path $Staging "bin"
    New-Item -ItemType Directory -Force -Path $binDir | Out-Null

    $qemuSrc = Join-Path $binDir "qemu-system-aarch64"
    $imgSrc  = Join-Path $binDir "qemu-img"

    if ($QemuBinary) {
        if ($QemuBinary -match "^https?://") {
            Log "downloading custom qemu-system-aarch64 from $QemuBinary"
            Invoke-WebRequest -Uri $QemuBinary -OutFile $qemuSrc -UseBasicParsing -TimeoutSec 600
        } else {
            Copy-Item $QemuBinary $qemuSrc -Force
        }
    }
    if ($QemuImgBinary) {
        if ($QemuImgBinary -match "^https?://") {
            Invoke-WebRequest -Uri $QemuImgBinary -OutFile $imgSrc -UseBasicParsing -TimeoutSec 600
        } else {
            Copy-Item $QemuImgBinary $imgSrc -Force
        }
    }

    $roots = @("qemu-system-aarch64-headless")
    if (-not $QemuImgBinary) { $roots += "qemu-utils" }
    $selected = Resolve-Depends $index $roots
    Log "resolved $($selected.Count) packages (qemu + dependencies)"

    $prefixRel = "data\data\com.termux\files\usr"
    $libStage  = Join-Path $Staging "libs"
    if (Test-Path $libStage) { Remove-Item -Recurse -Force $libStage }
    New-Item -ItemType Directory -Force -Path $libStage | Out-Null

    # share/qemu subset (option ROMs + keymaps + dtb + firmware): the full
    # dir is 315 MB (other-arch edk2 images) and must NOT be shipped
    $shareStage = Join-Path $Staging "share-stage"
    if (Test-Path $shareStage) { Remove-Item -Recurse -Force $shareStage }
    $destQemuShare = Join-Path $shareStage "share\qemu"
    New-Item -ItemType Directory -Force -Path $destQemuShare | Out-Null

    $gotQemu = Test-Path $qemuSrc
    $gotImg  = Test-Path $imgSrc

    foreach ($pkg in $selected.Keys) {
        $rel = $selected[$pkg].Filename
        if (-not $rel) { continue }
        $deb = Get-Deb $rel
        $root = Expand-Deb $deb (Join-Path $Staging "extract\$pkg")

        # main binaries
        if (-not $gotQemu) {
            $p = Join-Path $root "$prefixRel\bin\qemu-system-aarch64"
            if (Test-Path $p) { Copy-Item $p $qemuSrc -Force; $gotQemu = $true; Log "found qemu-system-aarch64 in $pkg" }
        }
        if (-not $gotImg) {
            $p = Join-Path $root "$prefixRel\bin\qemu-img"
            if (Test-Path $p) { Copy-Item $p $imgSrc -Force; $gotImg = $true; Log "found qemu-img in $pkg" }
        }

        # shared libraries (top level of usr/lib)
        $libDir = Join-Path $root "$prefixRel\lib"
        if (Test-Path $libDir) {
            Get-ChildItem -File $libDir | Where-Object { $_.Name -like "*.so*" } | ForEach-Object {
                $dst = Join-Path $libStage $_.Name
                if (-not (Test-Path $dst) -or $Force) {
                    # copy content (resolves symlinks)
                    [System.IO.File]::Copy($_.FullName, $dst, $true)
                }
            }
        }

        # QEMU data files (roms/keymaps/dtb/firmware subset)
        $qemuShare = Join-Path $root "$prefixRel\share\qemu"
        if (Test-Path $qemuShare) {
            Get-ChildItem -File $qemuShare | Where-Object { $_.Extension -eq ".rom" } | ForEach-Object {
                Copy-Item $_.FullName (Join-Path $destQemuShare $_.Name) -Force
            }
            foreach ($sub in @("keymaps", "dtb", "firmware")) {
                $s = Join-Path $qemuShare $sub
                if (Test-Path $s) { Copy-Item $s (Join-Path $destQemuShare $sub) -Recurse -Force }
            }
            Log "collected share/qemu subset from $pkg"
        }
    }

    if (-not $gotQemu) { Fail "qemu-system-aarch64 binary not found in package set" }
    if (-not $gotImg)  { Write-Host "[prepare] WARN: qemu-img not found - app will fall back to raw disks" -ForegroundColor Yellow }

    # ---- validate + patch ----
    if (-not (Test-Aarch64Elf $qemuSrc)) { Fail "qemu-system-aarch64 is not an aarch64 ELF binary" }
    Patch-Interp $qemuSrc
    if ($gotImg) {
        if (-not (Test-Aarch64Elf $imgSrc)) { Fail "qemu-img is not an aarch64 ELF binary" }
        Patch-Interp $imgSrc
    }

    $libCount = (Get-ChildItem -File $libStage).Count
    $libBytes = (Get-ChildItem -File $libStage | Measure-Object Length -Sum).Sum
    Log "staged $libCount shared libraries ($([math]::Round($libBytes/1MB,1)) MB)"

    if ($libCount -eq 0) { Fail "no shared libraries staged - cannot run QEMU" }

    # ---- pack jniLibs ----
    Copy-Item $qemuSrc (Join-Path $JniLibsDir "libqemu-system-aarch64.so") -Force
    if ($gotImg) {
        Copy-Item $imgSrc (Join-Path $JniLibsDir "libqemu-img.so") -Force
    }
    Log "jniLibs updated"

    # ---- pack assets/qemu-libs.tar.gz ----
    if (Test-Path $LibsAsset) { Remove-Item -Force $LibsAsset }
    & tar -czf $LibsAsset -C $libStage .
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $LibsAsset)) { Fail "failed to pack qemu-libs.tar.gz" }
    Log "qemu-libs.tar.gz = $([math]::Round((Get-Item $LibsAsset).Length/1MB,1)) MB"

    # ---- pack assets/qemu-share.tar.gz (QEMU datadir for -L) ----
    $shareCount = (Get-ChildItem -Recurse -File $shareStage -ErrorAction SilentlyContinue).Count
    if ($shareCount -eq 0) { Fail "share/qemu subset not collected (qemu-common package missing?)" }
    $ShareAsset = Join-Path $AssetsDir "qemu-share.tar.gz"
    if (Test-Path $ShareAsset) { Remove-Item -Force $ShareAsset }
    & tar -czf $ShareAsset -C $shareStage .
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $ShareAsset)) { Fail "failed to pack qemu-share.tar.gz" }
    Log "qemu-share.tar.gz = $([math]::Round((Get-Item $ShareAsset).Length/1MB,1)) MB ($shareCount files)"

    # ---- UEFI firmware from Debian ----
    $fwOk = (Test-Path (Join-Path $FwDir "AAVMF_CODE.fd")) -and -not $Force
    if (-not $fwOk) {
        try {
            if ($FirmwareDeb -and ($FirmwareDeb -notmatch "^https?://")) {
                $debFile = $FirmwareDeb
            } else {
                Log "querying Debian edk2 pool..."
                $listing = $FirmwareDeb
                if (-not $listing) { $listing = "https://deb.debian.org/debian/pool/main/e/edk2/" }
                if ($listing -match "^https?://.+/$") {
                    $html = (Invoke-WebRequest -Uri $listing -UseBasicParsing -TimeoutSec 60).Content
                    $names = [regex]::Matches($html, 'qemu-efi-aarch64_[0-9][^"<>]*_all\.deb') |
                        ForEach-Object { $_.Value } | Sort-Object -Unique
                    if (-not $names) { throw "no qemu-efi-aarch64 deb found in listing" }
                    $newest = $names[-1]
                    Log "downloading $newest"
                    $debFile = Join-Path $Staging $newest
                    Invoke-WebRequest -Uri ($listing + $newest) -OutFile $debFile -UseBasicParsing -TimeoutSec 300
                } else {
                    $debFile = $listing
                }
            }
            $fwRoot = Expand-Deb $debFile (Join-Path $Staging "extract-firmware")
            $code = Get-ChildItem -Recurse -File $fwRoot -Filter "AAVMF_CODE.fd" | Select-Object -First 1
            $vars = Get-ChildItem -Recurse -File $fwRoot -Filter "AAVMF_VARS.fd" | Select-Object -First 1
            if (-not $code) { throw "AAVMF_CODE.fd not found inside firmware deb" }
            Copy-Item $code.FullName (Join-Path $FwDir "AAVMF_CODE.fd") -Force
            if ($vars) { Copy-Item $vars.FullName (Join-Path $FwDir "AAVMF_VARS.fd") -Force }
            Log "firmware installed (CODE $([math]::Round((Get-Item (Join-Path $FwDir 'AAVMF_CODE.fd')).Length/1KB,1)) KB)"
            $fwOk = $true
        } catch {
            Write-Host "[prepare] WARN: firmware download failed: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    } else {
        Log "firmware already present"
    }

    Write-Host ""
    Write-Host "[prepare] DONE" -ForegroundColor Green
    Write-Host "  qemu      : $(Join-Path $JniLibsDir 'libqemu-system-aarch64.so')"
    Write-Host "  qemu-img  : $(Join-Path $JniLibsDir 'libqemu-img.so')$(if (-not $gotImg) {' (missing)'})"
    Write-Host "  libs      : $LibsAsset ($libCount files)"
    Write-Host "  firmware  : $(Join-Path $FwDir 'AAVMF_CODE.fd') $(if ($fwOk) {'OK'} else {'MISSING - VM cannot boot!'})"
    exit 0
} catch {
    Fail $_.Exception.Message
}
