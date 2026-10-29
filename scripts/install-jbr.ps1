#requires -Version 7.0
[CmdletBinding()]
param(
    [string] $Destination = (Join-Path $PSScriptRoot '../build/jbr/windows-x64'),
    [string] $ManifestPath = (Join-Path $PSScriptRoot '../desktop-viewer/jbr-runtime-manifest.json'),
    [string] $OutputName
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$destinationPath = [IO.Path]::GetFullPath($Destination)
if ($destinationPath -match '[\r\n]') {
    throw 'JBR destination must not contain newlines.'
}
if ($OutputName -and ($OutputName -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or !$env:GITHUB_OUTPUT)) {
    throw 'A valid OutputName and GITHUB_OUTPUT file are required for a step output.'
}

$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json -AsHashtable
$artifact = $manifest.artifacts['windows-x64']
if ($manifest.schemaVersion -ne 1 -or $artifact.variant -ne 'jbrsdk' -or
    $artifact.sha256 -notmatch '^[a-f0-9]{64}$' -or
    $artifact.upstreamChecksum.algorithm -ne 'SHA512' -or
    $artifact.upstreamChecksum.value -notmatch '^[a-f0-9]{128}$' -or
    $artifact.url -ne "https://cache-redirector.jetbrains.com/intellij-jbr/$($artifact.archiveRoot).zip" -or
    $artifact.upstreamChecksum.url -ne "$($artifact.url).checksum" -or
    $artifact.archiveRoot -notmatch '^jbrsdk-21\.0\.\d+-windows-x64-b\d+\.\d+$' -or
    $artifact.release.IMPLEMENTOR -ne 'JetBrains s.r.o.' -or
    $artifact.release.IMPLEMENTOR_VERSION -notmatch '^JBRSDK-21\..*-nomod$' -or
    $artifact.release.OS_NAME -ne 'Windows' -or $artifact.release.OS_ARCH -ne 'x86_64') {
    throw 'Invalid pinned Windows x64 JBRSDK manifest.'
}

$destinationParent = Split-Path -Parent $destinationPath
New-Item -ItemType Directory -Force -Path $destinationParent | Out-Null
# Stage beside the destination so the final move stays on the same volume.
$temporary = Join-Path $destinationParent ".aps-jbr-$([Guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    $archive = Join-Path $temporary 'jbrsdk.zip'
    Invoke-WebRequest -Uri $artifact.url -OutFile $archive
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $artifact.sha256) {
        throw 'JBRSDK SHA256 checksum mismatch; no installed JDK will be used.'
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ine $artifact.upstreamChecksum.value) {
        throw 'JBRSDK upstream SHA512 checksum mismatch; no installed JDK will be used.'
    }
    if (Test-Path -LiteralPath $destinationPath) {
        throw "JBR destination already exists; choose a fresh destination: $destinationPath"
    }

    $extracted = Join-Path $temporary 'extracted'
    Expand-Archive -LiteralPath $archive -DestinationPath $extracted
    $home = Join-Path $extracted $artifact.archiveRoot
    foreach ($relative in @('release', 'bin/java.exe', 'bin/javac.exe', 'bin/jlink.exe', 'bin/jpackage.exe', 'jmods/java.desktop.jmod')) {
        if (!(Test-Path -LiteralPath (Join-Path $home $relative) -PathType Leaf)) {
            throw "Pinned JBRSDK is missing $relative."
        }
    }
    $release = Get-Content -LiteralPath (Join-Path $home 'release') -Raw | ConvertFrom-StringData
    foreach ($key in @('JAVA_VERSION', 'JAVA_RUNTIME_VERSION', 'IMPLEMENTOR', 'IMPLEMENTOR_VERSION', 'OS_NAME', 'OS_ARCH')) {
        if (!$release.ContainsKey($key) -or $release[$key].Trim('"') -cne $artifact.release[$key]) {
            throw "JBRSDK release identity mismatch for $key."
        }
    }
    if ($release['MODULES'].Trim('"').Split(' ') -contains 'jcef') {
        throw 'The pinned JBRSDK must not include JCEF.'
    }

    # Check the downloaded executable, never java.exe from PATH or JAVA_HOME.
    $settings = & (Join-Path $home 'bin/java.exe') -XshowSettings:properties -version 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "Pinned JBRSDK java.exe failed: $settings"
    }
    $properties = @{}
    foreach ($line in ($settings -split '\r?\n')) {
        if ($line -match '^\s*(java\.vendor|java\.runtime\.version|java\.home|os\.arch)\s*=\s*(.+?)\s*$') {
            $properties[$Matches[1]] = $Matches[2]
        }
    }
    if ($properties['java.vendor'] -cne $artifact.release.IMPLEMENTOR -or
        $properties['java.runtime.version'] -cne $artifact.release.JAVA_RUNTIME_VERSION -or
        $properties['os.arch'] -ne 'amd64' -or !$properties['java.home'] -or
        [IO.Path]::GetFullPath($properties['java.home']) -ine [IO.Path]::GetFullPath($home)) {
        throw "JBRSDK runtime identity mismatch: $settings"
    }

    Move-Item -LiteralPath $home -Destination $destinationPath
    if ($env:GITHUB_ENV) {
        Add-Content -LiteralPath $env:GITHUB_ENV -Value "APS_JBR_HOME=$destinationPath" -Encoding utf8
    }
    if ($OutputName) {
        Add-Content -LiteralPath $env:GITHUB_OUTPUT -Value "$OutputName=$destinationPath" -Encoding utf8
    }
    Write-Output "Verified $($artifact.release.IMPLEMENTOR_VERSION) installed at $destinationPath"
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force
}
