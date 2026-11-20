# Installs a project-local Android build toolchain under .toolchain/.
# Nothing is installed machine-wide and nothing outside this repository is
# changed, apart from Gradle's normal dependency cache.
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$toolchain = Join-Path $projectRoot '.toolchain'
$downloads = Join-Path $toolchain 'downloads'
$jdkHome = Join-Path $toolchain 'jdk'
$sdkHome = Join-Path $toolchain 'android-sdk'
New-Item -ItemType Directory -Force -Path $toolchain, $downloads | Out-Null

function Get-ToolchainFile([string] $url, [string] $path) {
    if (Test-Path $path) {
        Write-Host "cached $path"
        return
    }
    & node (Join-Path $PSScriptRoot 'download.mjs') $url $path
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
}

$jdkZip = Join-Path $downloads 'temurin-jdk21.zip'
Get-ToolchainFile 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse' $jdkZip

if (-not (Test-Path (Join-Path $jdkHome 'bin\java.exe'))) {
    Write-Host 'extracting JDK'
    New-Item -ItemType Directory -Force -Path $jdkHome | Out-Null
    Expand-Archive -Path $jdkZip -DestinationPath $jdkHome -Force
    $inner = Get-ChildItem $jdkHome -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
    if (-not $inner) { throw 'JDK layout not recognised' }
    if ($inner.FullName -ne $jdkHome) {
        Get-ChildItem $inner.FullName -Force | Move-Item -Destination $jdkHome -Force
        Remove-Item $inner.FullName -Recurse -Force
    }
}

$sdkZip = Join-Path $downloads 'android-commandlinetools.zip'
Get-ToolchainFile 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' $sdkZip

$cmdlineTools = Join-Path $sdkHome 'cmdline-tools'
$sdkManager = Join-Path $cmdlineTools 'latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkManager)) {
    Write-Host 'extracting Android command-line tools'
    New-Item -ItemType Directory -Force -Path $sdkHome | Out-Null
    $unpack = Join-Path $toolchain 'cmdline-unpack'
    Expand-Archive -Path $sdkZip -DestinationPath $unpack -Force
    $source = Join-Path $unpack 'cmdline-tools'
    New-Item -ItemType Directory -Force -Path $cmdlineTools | Out-Null
    if (Test-Path (Join-Path $cmdlineTools 'latest')) { Remove-Item (Join-Path $cmdlineTools 'latest') -Recurse -Force }
    Move-Item $source (Join-Path $cmdlineTools 'latest')
    Remove-Item $unpack -Recurse -Force
}

$env:JAVA_HOME = $jdkHome
$env:ANDROID_HOME = $sdkHome
$env:ANDROID_SDK_ROOT = $sdkHome
$env:PATH = "$jdkHome\bin;$sdkHome\platform-tools;$env:PATH"

Write-Host 'accepting Android SDK licences'
$yes = (1..40 | ForEach-Object { 'y' }) -join "`n"
$yes | & $sdkManager --licenses --sdk_root=$sdkHome | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'sdkmanager --licenses failed' }

Write-Host 'installing Android SDK packages'
& $sdkManager --sdk_root=$sdkHome 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'sdkmanager package install failed' }

$localProperties = Join-Path $projectRoot 'local.properties'
$escaped = $sdkHome.Replace('\', '\\')
Set-Content -Path $localProperties -Encoding ascii -Value "sdk.dir=$escaped"
Write-Host "TOOLCHAIN_READY jdk=$jdkHome sdk=$sdkHome"
