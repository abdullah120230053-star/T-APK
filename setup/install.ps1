param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$adb = Get-Command adb.exe -ErrorAction SilentlyContinue
$adbPath = if ($adb) {
    $adb.Source
} else {
    'D:\Skills\Remote\remotehub\.tooling\sdk\platform-tools\adb.exe'
}
if (-not (Test-Path -LiteralPath $adbPath -PathType Leaf)) {
    throw 'Install Android platform-tools and add adb.exe to PATH.'
}
$apk = Join-Path $PSScriptRoot 'Tahany-0.3.0-debug.apk'
& $adbPath -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw "ADB install failed for $Serial." }
