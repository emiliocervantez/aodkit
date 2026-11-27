<#
.SYNOPSIS
    Installs the aodkit APK on the connected phone, keeping its data and permissions.

.DESCRIPTION
    Debug and release builds are signed with different keys. Android refuses to replace one with the
    other (INSTALL_FAILED_UPDATE_INCOMPATIBLE); switching needs `adb uninstall app.aodkit` first,
    which wipes aodkit's settings and granted permissions.

.PARAMETER Variant
    debug (default) or release. Release must be signed (see scripts\build.ps1).

.PARAMETER Build
    Build the variant first.

.EXAMPLE
    .\scripts\install.ps1
    .\scripts\install.ps1 -Build
    .\scripts\install.ps1 -Variant release -Build
#>
param(
    [ValidateSet('debug', 'release')]
    [string]$Variant = 'debug',
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Find-Adb {
    $sdkDirs = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $localProperties = Join-Path $root 'local.properties'
    if (Test-Path $localProperties) {
        $line = Get-Content $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { $sdkDirs += ($line -replace '^sdk\.dir=', '' -replace '\\:', ':' -replace '\\\\', '\') }
    }
    foreach ($dir in $sdkDirs) {
        if ($dir) {
            $adb = Join-Path $dir 'platform-tools\adb.exe'
            if (Test-Path $adb) { return $adb }
        }
    }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw 'adb not found: set ANDROID_HOME, sdk.dir in local.properties, or put adb on PATH.'
}

if ($Build) {
    & (Join-Path $PSScriptRoot 'build.ps1') -Target $Variant
}

$apk = Join-Path $root "app\build\outputs\apk\$Variant\app-$Variant.apk"
if (-not (Test-Path $apk)) {
    if ($Variant -eq 'release' -and (Test-Path (Join-Path $root 'app\build\outputs\apk\release\app-release-unsigned.apk'))) {
        throw 'The release APK is unsigned and cannot be installed. Set up keystore.properties (see Get-Help .\scripts\build.ps1) and rebuild.'
    }
    throw "No $Variant APK at $apk. Run .\scripts\build.ps1 -Target $Variant, or pass -Build."
}

$adb = Find-Adb
$devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" })
if ($devices.Count -eq 0) { throw 'No phone connected (adb devices lists none, or it is unauthorised).' }
if ($devices.Count -gt 1 -and -not $env:ANDROID_SERIAL) {
    throw 'Several devices are connected; set $env:ANDROID_SERIAL to the one to use.'
}

Write-Host "Installing $apk"
# adb prints failure reasons on stderr; with 'Stop', PowerShell 5.1 would abort on the first stderr line.
$ErrorActionPreference = 'Continue'
$result = @(& $adb install -r $apk 2>&1 | ForEach-Object { "$_" })
$exitCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$result | ForEach-Object { Write-Host $_ }
if ($exitCode -ne 0) {
    if ($result -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        throw 'The installed aodkit is signed with a different key (debug vs release). Run "adb uninstall app.aodkit" first; this wipes its settings and permissions.'
    }
    throw "adb install failed with exit code $exitCode."
}
