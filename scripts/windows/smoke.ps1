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
$shortcut = Join-Path ([Environment]::GetFolderPath('CommonPrograms')) 'Android Performance Studio.lnk'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($msi, 0)
$view = $database.OpenView('SELECT `Value` FROM `Property` WHERE `Property` = ''ProductCode''')
$view.Execute()
$productCode = $view.Fetch().StringData(1)
$view.Close()
$uninstallKey = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\$productCode"
if ($installer.ProductState($productCode) -ne -1 -or (Test-Path -LiteralPath $installRoot) -or (Test-Path -LiteralPath $shortcut)) {
    throw 'Refusing to run over an existing APS installation or smoke directory.'
}

function Invoke-Msi {
    param([string]$Operation, [string[]]$Arguments)
    $log = Join-Path $logs.FullName "$Operation.log"
    $process = Start-Process -FilePath "$env:SystemRoot/System32/msiexec.exe" -PassThru -ArgumentList (
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
    if (-not (Test-Path -LiteralPath $shortcut) -or -not (Test-Path -LiteralPath $uninstallKey)) {
        throw 'Missing all-users Start menu shortcut or uninstall registration.'
    }
}

$needsCleanup = $false
try {
    $needsCleanup = $true
    Invoke-Msi 'install' @('/i', "`"$msi`"", "INSTALLFOLDER=`"$installRoot`"")
    Assert-InstalledPayload
    # Repair must restore an owned file without relying on a developer checkout.
    Remove-Item -LiteralPath (Join-Path $installRoot 'aps-memory-worker.exe')
    Invoke-Msi 'repair' @('/fa', "`"$msi`"")
    Assert-InstalledPayload
    Invoke-Msi 'uninstall' @('/x', $productCode)
    $needsCleanup = $false
    foreach ($path in @($installRoot, $shortcut, $uninstallKey)) {
        if (Test-Path -LiteralPath $path) { throw "Uninstall left an owned artifact: $path" }
    }
    if ($installer.ProductState($productCode) -ne -1) { throw 'MSI product remains registered after uninstall.' }
} finally {
    if ($needsCleanup -and $installer.ProductState($productCode) -ne -1) {
        Invoke-Msi 'cleanup' @('/x', $productCode)
    }
}
