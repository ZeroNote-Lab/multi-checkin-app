<#
.SYNOPSIS
    v1.4.2 发版冒烟：无头模拟器安装 APK → 启动 → 校验首页 / 崩溃 → 截图，最后自动关闭模拟器。

.DESCRIPTION
    只做「装得上、起得来、首页渲染、无崩溃」这一层；交互流程（创建 / 打卡 / 导入导出）
    仍由 test/scripts 下的手工脚本或后续 uiautomator 用例覆盖。

.PARAMETER Avd
    使用的 AVD 名称，默认 fast_test（另一个常用的是 checkin_test）。

.PARAMETER Apk
    APK 路径，默认 app/build/outputs/apk/debug/app-debug.apk（相对仓库根目录）。

.PARAMETER KeepEmulator
    测试结束后不关闭模拟器（调试用）。默认会关闭。

.PARAMETER OutDir
    截图 / UI dump 输出目录，默认 test/smoke/<时间戳>（test/ 已被 gitignore）。

.EXAMPLE
    pwsh -File scripts/smoke_ui.ps1
    pwsh -File scripts/smoke_ui.ps1 -Avd checkin_test -KeepEmulator
#>
param(
    [string]$Avd = "fast_test",
    [string]$Apk = "app/build/outputs/apk/debug/app-debug.apk",
    [switch]$KeepEmulator,
    [string]$OutDir = ""
)

$ErrorActionPreference = "Continue"   # 原生命令（adb）会往 stderr 写进度，Stop 会被误判为失败

# ---------- 路径解析 ----------
$repoRoot = Split-Path -Parent $PSScriptRoot
$sdk = $env:ANDROID_HOME
if ([string]::IsNullOrWhiteSpace($sdk)) {
    $sdk = "D:\ProgramData\DouBaoData\app_apk\build-env\android-sdk"
}
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
$apkPath = if ([System.IO.Path]::IsPathRooted($Apk)) { $Apk } else { Join-Path $repoRoot $Apk }
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    # 按 AGENTS 约定：截图 / dump 归到 test/<版本号>/screens/smoke-<时间戳>
    $version = "unknown"
    $buildGradle = Join-Path $repoRoot "app\build.gradle"
    if (Test-Path $buildGradle) {
        $m = [regex]::Match((Get-Content $buildGradle -Raw), 'versionName\s+"([^"]+)"')
        if ($m.Success) { $version = $m.Groups[1].Value }
    }
    $OutDir = Join-Path $repoRoot ("test\" + $version + "\screens\smoke-" + (Get-Date -Format "yyyyMMdd_HHmmss"))
}

foreach ($tool in @($adb, $emulator)) {
    if (-not (Test-Path $tool)) { throw "找不到 Android 工具：$tool（可用 ANDROID_HOME 指定 SDK）" }
}
if (-not (Test-Path $apkPath)) { throw "找不到 APK：$apkPath（先执行 gradle assembleDebug）" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Write-Step([string]$msg) { Write-Host "==> $msg" }
function Get-EmulatorSerial {
    $line = (& $adb devices) | Where-Object { $_ -match "emulator-\d+\s+device" } | Select-Object -First 1
    if ($line) { return (($line -split "\s+")[0]).Trim() }
    return $null
}

$serial = $null
$startedEmulator = $false
$failed = $false

try {
    # ---------- 启动模拟器（如未运行） ----------
    $serial = Get-EmulatorSerial
    if (-not $serial) {
        Write-Step "启动无头模拟器 $Avd"
        $env:ANDROID_AVD_HOME = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } else { "D:\ProgramData\DouBaoData\workspace\avd" }
        Start-Process -FilePath $emulator -WindowStyle Hidden -ArgumentList @(
            "-avd", $Avd, "-no-window", "-no-audio", "-no-snapshot",
            "-gpu", "swiftshader_indirect", "-no-boot-anim"
        ) | Out-Null
        $startedEmulator = $true

        $deadline = (Get-Date).AddMinutes(4)
        do {
            Start-Sleep -Seconds 3
            $serial = Get-EmulatorSerial
        } while (-not $serial -and (Get-Date) -lt $deadline)
        if (-not $serial) { throw "模拟器 4 分钟内未上线" }
    } else {
        Write-Step "复用已运行的模拟器 $serial（结束后不会关闭）"
    }

    Write-Step "等待开机完成"
    $deadline = (Get-Date).AddMinutes(4)
    do {
        Start-Sleep -Seconds 2
        $booted = (& $adb -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    } while ($booted -ne "1" -and (Get-Date) -lt $deadline)
    if ($booted -ne "1") { throw "模拟器未在 4 分钟内完成开机" }

    # ---------- 安装并启动 ----------
    Write-Step "安装 APK：$apkPath"
    $install = (& $adb -s $serial install -r $apkPath 2>&1 | Out-String)
    if ($install -notmatch "Success") { throw "安装失败：$install" }

    Write-Step "清空日志并启动主界面"
    & $adb -s $serial logcat -c | Out-Null
    & $adb -s $serial shell am force-stop com.zerolab.checkin | Out-Null
    & $adb -s $serial shell am start -n com.zerolab.checkin/.ui.main.MainActivity | Out-Null
    Start-Sleep -Seconds 5

    # ---------- 进程存活 ----------
    $appPid = (& $adb -s $serial shell pidof com.zerolab.checkin | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($appPid)) { throw "应用进程未启动（可能启动即崩溃，见日志）" }
    Write-Step "应用进程存活：$appPid"

    # ---------- UI 校验（带重试） ----------
    Write-Step "抓取 UI 层级并校验关键文案"
    $dumpPath = Join-Path $OutDir "ui.xml"
    $uiXml = $null
    for ($i = 0; $i -lt 5; $i++) {
        & $adb -s $serial shell uiautomator dump /sdcard/smoke_ui.xml 2>&1 | Out-Null
        & $adb -s $serial pull /sdcard/smoke_ui.xml $dumpPath 2>&1 | Out-Null
        if (Test-Path $dumpPath) {
            $uiXml = Get-Content $dumpPath -Raw -Encoding UTF8
            if ($uiXml -match "com.zerolab.checkin") { break }
        }
        Start-Sleep -Seconds 2
    }
    if (-not $uiXml -or $uiXml -notmatch "com.zerolab.checkin") { throw "未取到应用 UI 层级" }

    $checks = @{
        "底部导航 · 打卡选择" = "打卡选择"
        "底部导航 · 快捷打卡" = "快捷打卡"
        "底部导航 · 设置"     = "设置"
    }
    foreach ($k in $checks.Keys) {
        if ($uiXml -match [regex]::Escape($checks[$k])) {
            Write-Host ("[PASS] " + $k)
        } else {
            Write-Host ("[FAIL] " + $k)
            $failed = $true
        }
    }
    # 首页要么是打卡项状态栏，要么是空状态引导
    if ($uiXml -match "状态：" -or $uiXml -match "还没有创建打卡项") {
        Write-Host "[PASS] 首页内容已渲染（状态栏 / 空状态）"
    } else {
        Write-Host "[FAIL] 首页内容未渲染"
        $failed = $true
    }

    # ---------- 崩溃日志 ----------
    Write-Step "检查崩溃日志"
    $log = & $adb -s $serial logcat -d -t 800 2>&1 | Out-String
    if ($log -match "FATAL EXCEPTION" -and $log -match "Process: com\.zerolab\.checkin") {
        Write-Host "[FAIL] 发现应用崩溃（FATAL EXCEPTION）"
        $log | Set-Content (Join-Path $OutDir "logcat.txt") -Encoding UTF8
        $failed = $true
    } else {
        Write-Host "[PASS] 未发现应用崩溃"
    }

    # ---------- 截图 ----------
    $png = Join-Path $OutDir "home.png"
    & $adb -s $serial shell screencap -p /sdcard/smoke_home.png 2>&1 | Out-Null
    & $adb -s $serial pull /sdcard/smoke_home.png $png 2>&1 | Out-Null
    if (Test-Path $png) { Write-Step "截图：$png" } else { Write-Host "[WARN] 截图失败" }
}
catch {
    $failed = $true
    Write-Host ("[ERROR] " + $_.Exception.Message)
}
finally {
    if ($startedEmulator -and -not $KeepEmulator -and $serial) {
        Write-Step "关闭模拟器 $serial"
        & $adb -s $serial emu kill 2>$null | Out-Null
    }
    Write-Step "产物目录：$OutDir"
}

if ($failed) { exit 1 }
Write-Host "SMOKE PASS"
exit 0
