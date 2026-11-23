[CmdletBinding()]
param(
    [string]$ReleaseTag = $env:RELEASE_TAG,
    [string]$WixBin,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../../dist')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-CheckedNative {
    param([string]$FilePath, [string[]]$Arguments)
    $PSNativeCommandUseErrorActionPreference = $false
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Native command failed ($LASTEXITCODE): $FilePath $($Arguments -join ' ')"
    }
}

function Get-MsiVersion {
    param([string]$Tag)
    if ($Tag -notmatch '^(?:aps-desktop-|rust-desktop-)?v([0-9]+)\.([0-9]+)\.([0-9]+)(?:-rebuild\.[0-9]+)?$') {
        throw "Invalid release tag for MSI: $Tag (expected vMAJOR.MINOR.PATCH[-rebuild.N])"
    }
    $parts = @([uint64]$Matches[1], [uint64]$Matches[2], [uint64]$Matches[3])
    if ($parts[0] -gt 255 -or $parts[1] -gt 255 -or $parts[2] -gt 65535) {
        throw "MSI version is out of range: $Tag (maximum 255.255.65535)"
    }
    return $parts -join '.'
}

function Find-WixBin {
    param([string]$ExplicitPath)
    $candidates = @()
    if ($ExplicitPath) {
        $candidates = @($ExplicitPath)
    } else {
        if ($env:WIX) {
            $candidates += (Join-Path $env:WIX 'bin'), $env:WIX
        }
        $candle = Get-Command candle.exe -CommandType Application -ErrorAction SilentlyContinue
        if ($candle) { $candidates += Split-Path $candle.Source }
        foreach ($root in @(${env:ProgramFiles(x86)}, $env:ProgramFiles)) {
            if ($root -and (Test-Path -LiteralPath $root)) {
                $candidates += Get-ChildItem -LiteralPath $root -Directory -Filter 'WiX Toolset v3*' |
                    Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin' }
            }
        }
    }
    foreach ($candidate in $candidates) {
        $missing = @('heat.exe', 'candle.exe', 'light.exe', 'WixUIExtension.dll') |
            Where-Object { -not (Test-Path -LiteralPath (Join-Path $candidate $_) -PathType Leaf) }
        if (@($missing).Count -eq 0) { return (Resolve-Path -LiteralPath $candidate).Path }
    }
    throw 'WiX 3 with heat.exe, candle.exe, light.exe and WixUIExtension.dll was not found. Set WIX or pass -WixBin.'
}

function Find-Dumpbin {
    $command = Get-Command dumpbin.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    $paths = @(Invoke-CheckedNative $vswhere @('-latest', '-products', '*', '-requires',
        'Microsoft.VisualStudio.Component.VC.Tools.x86.x64', '-find', 'VC\Tools\MSVC\**\bin\Hostx64\x64\dumpbin.exe'))
    foreach ($path in $paths) {
        if (Test-Path -LiteralPath $path -PathType Leaf) { return $path }
    }
    throw 'dumpbin.exe was not found in the Visual Studio C++ toolchain.'
}

function Assert-StaticRuntime {
    param([string]$Dumpbin, [string[]]$Binaries)
    foreach ($binary in $Binaries) {
        $imports = (Invoke-CheckedNative $Dumpbin @('/nologo', '/dependents', $binary)) -join "`n"
        # Windows supplies the UCRT on supported systems, but not the VC++ redistributable.
        if ($imports -match '(?im)^\s*(?:vcruntime[\w.]*|msvcp[\w.]*|msvcr[\w.]*|concrt[\w.]*|vcomp[\w.]*|ucrtbased)\.dll\s*$') {
            throw "Unexpected VC++ runtime dependency in ${binary}: $($Matches[0].Trim()). Check target crt-static flags."
        }
    }
}

function New-WindowsPackage {
    $version = Get-MsiVersion $ReleaseTag
    $wix = Find-WixBin $WixBin
    $repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
    $output = New-Item -ItemType Directory -Force -Path $OutputDirectory
    $work = New-Item -ItemType Directory -Force -Path (Join-Path $output.FullName 'wix')
    $resources = Join-Path $output.FullName 'resources'
    $icon = Join-Path $repo 'rust-desktop/crates/aps-app/assets/icons/windows/app-icon.ico'
    foreach ($name in @('aps-app.exe', 'aps-memory-worker.exe')) {
        Copy-Item -LiteralPath (Join-Path $repo "rust-desktop/target/x86_64-pc-windows-msvc/release/$name") -Destination $output.FullName -Force
    }
    foreach ($required in @('firefox-profiler/index.html', 'perfetto-tools/trace_processor_shell.exe')) {
        $path = Join-Path $resources $required
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or (Get-Item -LiteralPath $path).Length -eq 0) {
            throw "Missing or empty bundled resource: $path"
        }
    }
    $payload = @((Join-Path $output.FullName 'aps-app.exe'), (Join-Path $output.FullName 'aps-memory-worker.exe'), $resources)
    $binaries = @($payload[0], $payload[1]) + @(Get-ChildItem -LiteralPath $resources -Recurse -File |
        Where-Object { $_.Extension -in @('.exe', '.dll') } | ForEach-Object { $_.FullName })
    Assert-StaticRuntime (Find-Dumpbin) $binaries
    $basename = "aps-desktop-$ReleaseTag-windows-x86_64"
    Compress-Archive -LiteralPath $payload -DestinationPath (Join-Path $output.FullName "$basename.zip") -Force

    $harvest = Join-Path $work.FullName 'resources.wxs'
    Invoke-CheckedNative (Join-Path $wix 'heat.exe') @('dir', $resources, '-nologo', '-cg', 'BundledResources',
        '-dr', 'ResourcesDir', '-srd', '-ag', '-scom', '-sreg', '-var', 'var.ResourcesSource', '-out', $harvest)
    # Heat defaults to 32-bit components; keep every resource in the x64 component model.
    $xml = [xml]::new()
    $xml.Load($harvest)
    $ns = [System.Xml.XmlNamespaceManager]::new($xml.NameTable)
    $ns.AddNamespace('w', 'http://schemas.microsoft.com/wix/2006/wi')
    foreach ($component in $xml.SelectNodes('//w:Component', $ns)) { $component.SetAttribute('Win64', 'yes') }
    $xml.Save($harvest)
    $extension = Join-Path $wix 'WixUIExtension.dll'
    Invoke-CheckedNative (Join-Path $wix 'candle.exe') @('-nologo', '-arch', 'x64', '-ext', $extension,
        "-dProductVersion=$version", "-dPayloadSource=$($output.FullName)", "-dResourcesSource=$resources", "-dAppIcon=$icon",
        (Join-Path $PSScriptRoot 'installer.wxs'), $harvest, '-out', ($work.FullName + '\'))
    Invoke-CheckedNative (Join-Path $wix 'light.exe') @('-nologo', '-ext', $extension, '-cultures:en-us',
        '-out', (Join-Path $output.FullName "$basename.msi"), (Join-Path $work.FullName 'installer.wixobj'),
        (Join-Path $work.FullName 'resources.wixobj'))
    foreach ($extension in @('zip', 'msi')) {
        $artifact = Get-Item -LiteralPath (Join-Path $output.FullName "$basename.$extension")
        if ($artifact.Length -eq 0) { throw "Empty Windows package: $($artifact.FullName)" }
    }
}

if ($MyInvocation.InvocationName -ne '.') { New-WindowsPackage }
