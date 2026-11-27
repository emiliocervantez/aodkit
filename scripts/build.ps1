<#
.SYNOPSIS
    Builds aodkit: debug APK, release APK and release AAB (for Google Play).

.DESCRIPTION
    Release builds are signed only when keystore.properties exists at the repo root; otherwise they
    are built unsigned (an unsigned APK cannot be installed, and Play rejects an unsigned AAB).

    One-time signing setup (keep the .jks and the passwords safe: losing them means you can never
    update the app under the same signature):

        keytool -genkeypair -v -keystore aodkit-release.jks -alias aodkit -keyalg RSA -keysize 4096 -validity 10000

    then create keystore.properties next to it (both files are git-ignored):

        storeFile=aodkit-release.jks
        storePassword=...
        keyAlias=aodkit
        keyPassword=...

.PARAMETER Target
    all (default), debug, release (APK) or aab.

.EXAMPLE
    .\scripts\build.ps1
    .\scripts\build.ps1 -Target debug
#>
param(
    [ValidateSet('all', 'debug', 'release', 'aab')]
    [string]$Target = 'all'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# Gradle task and output folder (under app\build\outputs) per target.
$targets = @{
    'debug'   = @('assembleDebug', 'apk\debug')
    'release' = @('assembleRelease', 'apk\release')
    'aab'     = @('bundleRelease', 'bundle\release')
}
$selected = if ($Target -eq 'all') { @('debug', 'release', 'aab') } else { @($Target) }
# @() keeps a single task an array: splatting a plain string would pass it character by character.
$tasks = @($selected | ForEach-Object { $targets[$_][0] })

Push-Location $root
try {
    # Gradle writes warnings to stderr; with 'Stop', PowerShell 5.1 aborts on them when output is redirected.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat --console=plain @tasks
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($exitCode -ne 0) { throw "Gradle failed with exit code $exitCode." }
}
finally {
    Pop-Location
}

Write-Host ''
Write-Host 'Built:'
foreach ($name in $selected) {
    $dir = Join-Path $root "app\build\outputs\$($targets[$name][1])"
    Get-ChildItem -Path $dir -File | Where-Object { $_.Extension -in '.apk', '.aab' } |
        ForEach-Object { Write-Host "  $($_.FullName)" }
}

if ($Target -ne 'debug' -and -not (Test-Path (Join-Path $root 'keystore.properties'))) {
    Write-Warning 'No keystore.properties: the release APK/AAB are unsigned. See Get-Help .\scripts\build.ps1 for the signing setup.'
}
