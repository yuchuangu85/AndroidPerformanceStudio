[CmdletBinding()]
param([Parameter(Mandatory)][string]$MsiPath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $IsWindows -or $env:GITHUB_ACTIONS -ne 'true') {
    throw 'Run this install/uninstall smoke only on a disposable Windows GitHub Actions runner.'
}
$msi = (Resolve-Path -LiteralPath $MsiPath).Path
$payload = Split-Path $msi
$logs = New-Item -ItemType Directory -Force -Path (Join-Path $payload 'windows-smoke')
$installRoot = Join-Path $env:ProgramFiles 'APS Installer Smoke'
$shortcuts = @(
    (Join-Path ([Environment]::GetFolderPath('CommonPrograms')) 'Android Performance Studio.lnk'),
    (Join-Path ([Environment]::GetFolderPath('CommonDesktopDirectory')) 'Android Performance Studio.lnk')
)
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($msi, 0)
$view = $database.OpenView('SELECT `Value` FROM `Property` WHERE `Property` = ''ProductCode''')
$view.Execute()
$productCode = $view.Fetch().StringData(1)
$view.Close()
$uninstallKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\$productCode"
if ($installer.ProductState($productCode) -ne -1 -or (Test-Path -LiteralPath $installRoot) -or
    @($shortcuts | Where-Object { Test-Path -LiteralPath $_ }).Count -gt 0) {
    throw 'Refusing to run over an existing APS installation or smoke directory.'
}

function Invoke-Msi {
    param([string]$Operation, [string[]]$Arguments)
    $log = Join-Path $logs.FullName "$Operation.log"
    $process = Start-Process -FilePath "$env:SystemRoot/System32/msiexec.exe" -PassThru -WindowStyle Hidden -ArgumentList (
        $Arguments + @('/qn', '/norestart', 'REBOOT=ReallySuppress', '/L*v', "`"$log`""))
    if (-not $process.WaitForExit(120000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "MSI $Operation timed out after 120 seconds. See $log"
    }
    if ($process.ExitCode -notin @(0, 3010)) {
        if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 80 | Write-Host }
        throw "MSI $Operation failed with exit code $($process.ExitCode). See $log"
    }
}

function Assert-InstalledPayload {
    $files = @(Get-Item -LiteralPath (Join-Path $payload 'aps-app.exe'), (Join-Path $payload 'aps-memory-worker.exe')) +
        @(Get-ChildItem -LiteralPath (Join-Path $payload 'resources') -Recurse -File)
    foreach ($source in $files) {
        $relative = [IO.Path]::GetRelativePath($payload, $source.FullName)
        $destination = Join-Path $installRoot $relative
        if (-not (Test-Path -LiteralPath $destination -PathType Leaf) -or
            (Get-FileHash -LiteralPath $source.FullName).Hash -ne (Get-FileHash -LiteralPath $destination).Hash) {
            throw "Missing or changed installed payload: $relative"
        }
    }
    foreach ($shortcut in $shortcuts) {
        if (-not (Test-Path -LiteralPath $shortcut -PathType Leaf)) {
            throw "Missing all-users shortcut: $shortcut"
        }
        $target = $installer.ShortcutTarget($shortcut)
        if ($target.StringData(1) -ne $productCode -or
            $installer.ComponentPath($productCode, $target.StringData(3)) -ne (Join-Path $installRoot 'aps-app.exe')) {
            throw "Shortcut does not resolve to the installed application: $shortcut"
        }
    }
    if (-not (Test-Path -LiteralPath $uninstallKey)) {
        throw 'Missing uninstall registration.'
    }
}

$needsCleanup = $false
try {
    $needsCleanup = $true
    Invoke-Msi 'install' @('/i', "`"$msi`"", "INSTALLFOLDER=`"$installRoot`"")
    Assert-InstalledPayload
    # Repair must restore owned files and shortcuts without a developer checkout.
    Remove-Item -LiteralPath (Join-Path $installRoot 'aps-memory-worker.exe')
    Remove-Item -LiteralPath $shortcuts
    Invoke-Msi 'repair' @('/fas', "`"$msi`"")
    Assert-InstalledPayload
    Invoke-Msi 'uninstall' @('/x', $productCode)
    $needsCleanup = $false
    foreach ($path in (@($installRoot, $uninstallKey) + $shortcuts)) {
        if (Test-Path -LiteralPath $path) { throw "Uninstall left an owned artifact: $path" }
    }
    if ($installer.ProductState($productCode) -ne -1) { throw 'MSI product remains registered after uninstall.' }
} finally {
    if ($needsCleanup -and $installer.ProductState($productCode) -ne -1) {
        Invoke-Msi 'cleanup' @('/x', $productCode)
    }
}
