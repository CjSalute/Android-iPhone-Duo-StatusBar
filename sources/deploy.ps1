# 部署 + 验证（Windows / PowerShell 5.1）
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy.ps1 [-NoBuild]

param([switch]$NoBuild)

$ErrorActionPreference = 'Continue'
$ROOT = $PSScriptRoot
$ADB  = 'C:\Android\adb.exe'
$APK  = Join-Path $ROOT 'IOSDuo-ThreeInOne.apk'
$CAP  = Join-Path $ROOT '..\cap'
$SER  = 'R5CW14876JY'
New-Item -ItemType Directory -Force -Path $CAP | Out-Null

if (-not $NoBuild) {
    Write-Host "[STAGE] build"
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ROOT 'build.ps1')
}

Write-Host "[STAGE] install"
& $ADB -s $SER push $APK /data/local/tmp/iosduo.apk
& $ADB -s $SER shell su -c 'pm install -r -d /data/local/tmp/iosduo.apk'

Write-Host "[STAGE] restart systemui"
& $ADB -s $SER logcat -c
& $ADB -s $SER shell su -c 'killall com.android.systemui'
Start-Sleep -Seconds 18

Write-Host "[STAGE] dump"
& $ADB -s $SER shell am broadcast -a com.dsh.iosduo.DUMP -p com.android.systemui | Out-Null
Start-Sleep -Seconds 2
& $ADB -s $SER logcat -d | Select-String 'IOSDuo' | Select-Object -Last 40 | ForEach-Object { ($_.Line -split '\] ')[-1] }
