param([string]$DeviceSerial)
$ErrorActionPreference = "Stop"
$taskRoot = Split-Path -Parent $PSScriptRoot
$apkPath = Join-Path $taskRoot "releases\daybook-0.5.3-beta.1.apk"
if (-not (Test-Path -LiteralPath $apkPath)) { $apkPath = Join-Path $taskRoot "app\build\outputs\apk\release\app-release.apk" }
if (-not (Test-Path -LiteralPath $apkPath)) { throw "Build Daybook first; a signed personal APK was not found." }
$adbPath = Join-Path $taskRoot ".tools\android\windows\platform-tools\adb.exe"
if ($env:ANDROID_HOME -and (Test-Path -LiteralPath (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"))) {
    $adbPath = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
}
if (-not (Test-Path -LiteralPath $adbPath)) {
    $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($adbCommand) { $adbPath = $adbCommand.Source } else { throw "Run scripts/bootstrap.py in Ubuntu or add Android platform-tools to PATH." }
}
if (-not $DeviceSerial) {
    $devices = @(& $adbPath devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($devices.Count -ne 1) { throw "Connect and authorize one phone, or pass -DeviceSerial SERIAL." }
    $DeviceSerial = $devices[0]
}
& $adbPath -s $DeviceSerial install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw "Installation failed." }
& $adbPath -s $DeviceSerial shell am start -n "dev.daybook.app/.MainActivity"
if ($LASTEXITCODE -ne 0) { throw "Installed, but Daybook could not be opened." }
Write-Output "Daybook installed. Choose your schedule and download the English model in the app."
