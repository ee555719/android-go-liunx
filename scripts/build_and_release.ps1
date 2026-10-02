<#
.SYNOPSIS
  One-shot build + GitHub release pipeline for GoLinux VM.

.DESCRIPTION
  1. Picks a compatible JDK (17) and exports JAVA_HOME.
  2. Ensures Android SDK platform 36 / build-tools 36 via sdkmanager.
  3. Bootstraps Gradle 8.11.1 + generates the Gradle wrapper if missing.
  4. Runs scripts/prepare_assets.ps1 (unless -SkipAssets) so the APK
     contains the QEMU engine and UEFI firmware.
  5. Creates the release keystore (keystores/golinux-release.jks) when absent.
  6. Runs `gradlew assembleRelease`.
  7. Git: init/commit, merge upstream origin/main, push main + tag vX.Y.Z.
  8. GitHub: `gh release create vX.Y.Z` with the APK attached.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\build_and_release.ps1 -Version 1.0.0
#>
[CmdletBinding()]
param(
    [string]$Version = "1.0.0",
    [switch]$SkipAssets,
    [switch]$SkipBuild,
    [switch]$SkipGit,
    [switch]$SkipRelease
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$Work        = Join-Path "D:\Temp\opencode" "golinux-build"   # keep big downloads off the small C: drive
$GradleVer   = "8.11.1"
$RepoUrl     = "https://github.com/ee555719/android-go-liunx.git"
$GitUser     = "ee555719"
$GitEmail    = "ee555719@users.noreply.github.com"

function Log($m)  { Write-Host "[build] $m" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[build] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[build] WARN: $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[build] ERROR: $m" -ForegroundColor Red; exit 1 }

New-Item -ItemType Directory -Force -Path $Work | Out-Null

# Gradle dependency caches + daemon go to D: too (C: has ~4 GB free)
$env:GRADLE_USER_HOME = Join-Path $Work "gradle-user-home"

# ---------------------------------------------------------------------------
# 1. JDK
# ---------------------------------------------------------------------------
$jdkCandidates = @(
    "C:\Program Files\Java\jdk-17",
    "C:\Program Files\Java\jdk-21",
    "C:\Program Files\Eclipse Adoptium"
) | Where-Object { Test-Path $_ }
$jdk = $null
foreach ($c in $jdkCandidates) {
    if (Test-Path (Join-Path $c "bin\javac.exe")) { $jdk = $c; break }
    $sub = Get-ChildItem $c -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path (Join-Path $_.FullName "bin\javac.exe") } | Select-Object -First 1
    if ($sub) { $jdk = $sub.FullName; break }
}
if (-not $jdk) { Fail "no JDK with javac found (need JDK 17+)" }
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;" + $env:Path
Log "JAVA_HOME=$jdk"
& java -version

# ---------------------------------------------------------------------------
# 2. Android SDK
# ---------------------------------------------------------------------------
$Sdk = $env:ANDROID_HOME
if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
if (-not (Test-Path $Sdk)) { Fail "Android SDK not found at $Sdk" }
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
Log "SDK=$Sdk"

$sdkmanager = Join-Path $Sdk "cmdline-tools\latest\bin\sdkmanager.bat"
$needPkgs = @()
if (-not (Test-Path (Join-Path $Sdk "platforms\android-36")))       { $needPkgs += "platforms;android-36" }
if (-not (Test-Path (Join-Path $Sdk "build-tools\36.0.0")))         { $needPkgs += "build-tools;36.0.0" }
if (-not (Test-Path (Join-Path $Sdk "platform-tools")))             { $needPkgs += "platform-tools" }
if ($needPkgs.Count -gt 0 -and (Test-Path $sdkmanager)) {
    Log "installing SDK packages: $($needPkgs -join ', ')"
    $yFile = Join-Path $Work "y.txt"
    Set-Content -Path $yFile -Value ("y`n" * 30)
    $mgrArgs = ($needPkgs | ForEach-Object { '"' + $_ + '"' }) -join " "
    cmd /c "`"$sdkmanager`" $mgrArgs < `"$yFile`""
    if ($LASTEXITCODE -ne 0) { Warn "sdkmanager exited $LASTEXITCODE (continuing - may already be present)" }
}
foreach ($need in @("platforms\android-36", "build-tools\36.0.0")) {
    if (-not (Test-Path (Join-Path $Sdk $need))) { Fail "missing SDK component: $need" }
}

# ---------------------------------------------------------------------------
# 3. Gradle + wrapper
# ---------------------------------------------------------------------------
$gradleHome = Join-Path $Work "gradle-$GradleVer"
$gradleBat  = Join-Path $gradleHome "bin\gradle.bat"
if (-not (Test-Path $gradleBat)) {
    $zip = Join-Path $Work "gradle-$GradleVer-bin.zip"
    if (-not (Test-Path $zip)) {
        # Tencent mirror first (much faster in CN), official as fallback
        $urls = @(
            "https://mirrors.cloud.tencent.com/gradle/gradle-$GradleVer-bin.zip",
            "https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-$GradleVer-bin.zip",
            "https://services.gradle.org/distributions/gradle-$GradleVer-bin.zip"
        )
        foreach ($u in $urls) {
            Log "downloading Gradle $GradleVer from $u ..."
            try {
                Invoke-WebRequest -Uri $u -OutFile $zip -UseBasicParsing -TimeoutSec 600
                if ((Get-Item $zip).Length -gt 50MB) { break }
            } catch {
                Warn "download failed: $($_.Exception.Message)"
                Remove-Item $zip -Force -ErrorAction SilentlyContinue
            }
        }
    }
    if (-not (Test-Path $zip)) { Fail "cannot download Gradle distribution" }
    Log "extracting Gradle..."
    Expand-Archive -Path $zip -DestinationPath $Work -Force
}
if (-not (Test-Path $gradleBat)) { Fail "gradle bootstrap failed" }

$wrapperJar = Join-Path $ProjectRoot "gradle\wrapper\gradle-wrapper.jar"
$wrapperPs1 = Join-Path $ProjectRoot "gradlew.ps1"
$wrapperBat = Join-Path $ProjectRoot "gradlew.bat"
if (-not (Test-Path $wrapperJar)) {
    Log "generating Gradle wrapper (distribution via Tencent mirror)..."
    $distUrl = "https://mirrors.cloud.tencent.com/gradle/gradle-$GradleVer-bin.zip"
    Push-Location $ProjectRoot
    & $gradleBat wrapper --gradle-version $GradleVer --distribution-type bin `
        --gradle-distribution-url $distUrl
    if ($LASTEXITCODE -ne 0) {
        # mirror validation can flake - retry without URL validation
        Warn "wrapper generation failed, retrying with --no-validate-url"
        & $gradleBat wrapper --gradle-version $GradleVer --distribution-type bin `
            --gradle-distribution-url $distUrl --no-validate-url
        if ($LASTEXITCODE -ne 0) { Pop-Location; Fail "gradle wrapper generation failed" }
    }
    Pop-Location
}
$gradlew = if (Test-Path $wrapperBat) { $wrapperBat } else { $gradleBat }
Log "gradle runner: $gradlew"

# ---------------------------------------------------------------------------
# 4. Assets (QEMU engine + firmware)
# ---------------------------------------------------------------------------
if (-not $SkipAssets) {
    Log "running prepare_assets.ps1 ..."
    & (Join-Path $PSScriptRoot "prepare_assets.ps1")
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne $null) { Fail "prepare_assets.ps1 failed" }
}
$qemuLib = Join-Path $ProjectRoot "app\src\main\jniLibs\arm64-v8a\libqemu-system-aarch64.so"
if (-not (Test-Path $qemuLib)) {
    Fail "missing $qemuLib - run scripts\prepare_assets.ps1 first (or omit -SkipAssets)"
}
$fw = Join-Path $ProjectRoot "app\src\main\assets\firmware\AAVMF_CODE.fd"
if (-not (Test-Path $fw)) { Warn "UEFI firmware missing - VMs will not boot (prepare_assets will fetch it)" }

# ---------------------------------------------------------------------------
# 5. Release keystore
# ---------------------------------------------------------------------------
$ksDir  = Join-Path $ProjectRoot "keystores"
$ksFile = Join-Path $ksDir "golinux-release.jks"
New-Item -ItemType Directory -Force -Path $ksDir | Out-Null
if (-not $env:GOLINUX_STOREPASS) { $env:GOLINUX_STOREPASS = "golinux-release" }
if (-not $env:GOLINUX_KEYPASS)   { $env:GOLINUX_KEYPASS   = $env:GOLINUX_STOREPASS }
if (-not (Test-Path $ksFile)) {
    Log "creating release keystore..."
    & keytool -genkeypair -v -keystore $ksFile -alias golinux `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $env:GOLINUX_STOREPASS -keypass $env:GOLINUX_KEYPASS `
        -dname "CN=GoLinux VM, OU=GoLinux, O=ee555719, C=CN"
    if ($LASTEXITCODE -ne 0) { Fail "keytool failed" }
    Ok "keystore created: $ksFile"
} else {
    Log "keystore already present"
}

# ---------------------------------------------------------------------------
# 6. Build
# ---------------------------------------------------------------------------
$apk = Join-Path $ProjectRoot "app\build\outputs\apk\release\app-release.apk"
if (-not $SkipBuild) {
    Log "gradlew assembleRelease ..."
    Push-Location $ProjectRoot
    & $gradlew assembleRelease --stacktrace
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { Fail "assembleRelease failed (exit $code)" }
}
if (-not (Test-Path $apk)) { Fail "APK not found at $apk" }
$apkSize = [math]::Round((Get-Item $apk).Length / 1MB, 1)
Ok "APK ready: $apk ($apkSize MB)"

# ---------------------------------------------------------------------------
# 7. Git: init / commit / merge upstream / push / tag
# ---------------------------------------------------------------------------
$tag = "v$Version"
if (-not $SkipGit) {
    Push-Location $ProjectRoot
    # NOTE: git/gh write progress to stderr; under EAP=Stop a *redirected*
    # native stderr would throw, so relax to Continue inside this section
    # (failures are still caught via explicit $LASTEXITCODE checks below).
    $ErrorActionPreference = "Continue"
    try {
        $env:GIT_TERMINAL_PROMPT = "0"
        $env:GCM_INTERACTIVE = "never"

        if (-not (Test-Path (Join-Path $ProjectRoot ".git"))) {
            Log "git init..."
            git init -b main
            if ($LASTEXITCODE -ne 0) { throw "git init failed" }
        }
        git config user.name  $GitUser
        git config user.email $GitEmail

        # make sure GitHub push works without any interactive prompt
        $stored = $null
        try {
            $fill = @("protocol=https", "host=github.com", "") | git credential fill 2>$null
            foreach ($line in $fill) {
                if ($line -like "password=*") { $stored = $line.Substring(9) }
            }
        } catch { }
        if (-not $stored) {
            try { $stored = (gh auth token).Trim() } catch { }
        }
        if ($stored) {
            @("protocol=https", "host=github.com", "username=$GitUser", "password=$stored", "") |
                git credential approve 2>$null
            Log "git credential stored for github.com"
        } else {
            Warn "no stored GitHub credential found - push may prompt for auth"
        }

        git add -A
        $dirty = git status --porcelain
        if ($dirty) {
            Log "committing $(($dirty | Measure-Object).Count) changes..."
            git commit -m "Release $tag"
            if ($LASTEXITCODE -ne 0) { throw "git commit failed" }
        } else {
            Log "nothing to commit"
        }

        git remote remove origin 2>$null | Out-Null
        git remote add origin $RepoUrl

        Log "fetching upstream main..."
        git fetch origin main 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $remote = git rev-parse origin/main 2>$null
            if ($remote) {
                $merge = git merge origin/main --allow-unrelated-histories -m "Merge upstream main" 2>&1
                if ($LASTEXITCODE -ne 0) {
                    Warn "merge had conflicts - resolving by preferring our tree"
                    git checkout --ours . 2>$null | Out-Null
                    git add -A
                    git commit -m "Merge upstream main (ours)" 2>$null | Out-Null
                }
            }
        }

        Log "pushing main..."
        git push -u origin main 2>&1
        if ($LASTEXITCODE -ne 0) { throw "git push failed" }

        # tag (recreate if it already exists)
        if (git rev-parse $tag 2>$null) { git tag -d $tag | Out-Null }
        git tag -a $tag -m "Release $tag"
        git push -f origin refs/tags/$tag 2>&1
        if ($LASTEXITCODE -ne 0) { throw "tag push failed" }
        Ok "pushed main + $tag"
    } catch {
        Fail "git step failed: $($_.Exception.Message)"
    } finally {
        $ErrorActionPreference = "Stop"
        Pop-Location
    }
}

# ---------------------------------------------------------------------------
# 8. GitHub release
# ---------------------------------------------------------------------------
if (-not $SkipRelease) {
    $ErrorActionPreference = "Continue"
    Push-Location $ProjectRoot   # gh must run inside the repo to resolve owner/repo
    try {
        $exists = gh release view $tag 2>&1
        if ($LASTEXITCODE -eq 0) {
            Log "release $tag exists - updating asset"
            gh release upload $tag $apk --clobber
        } else {
            Log "creating release $tag..."
            $notesFile = Join-Path $Work "release-notes-$tag.md"
            $notes = @"
GoLinux VM $tag

QEMU aarch64 VM on Android (arm64-v8a, Android 11+).

- QEMU 11.x system emulation (GPL-2.0, Termux build) packed as JNI libs
- EDK2/AAVMF UEFI firmware
- SSH + serial-console terminal with ANSI/VT100 emulator
- Virtual disk manager, ISO import, port forwarding
- Foreground VM service with notification controls

Install the APK, grant *All files access*, then create and start a VM.
"@
            # write UTF8 without BOM so gh reads the notes cleanly
            [System.IO.File]::WriteAllText($notesFile, $notes)
            gh release create $tag $apk --title "GoLinux VM $tag" --notes-file $notesFile
        }
        if ($LASTEXITCODE -ne 0) { throw "gh release failed" }
        $url = gh release view $tag --json url --jq .url 2>$null
        Ok "release published: $url"
    } catch {
        Fail "release step failed: $($_.Exception.Message)"
    } finally {
        $ErrorActionPreference = "Stop"
        Pop-Location
    }
}

Ok "ALL DONE - APK: $apk"
exit 0
