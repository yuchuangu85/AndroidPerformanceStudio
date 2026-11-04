#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $PfxPath,
    [Parameter(Mandatory)] [string] $AssetDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
if (!$IsWindows -or $env:APS_ENABLE_RELEASE_SIGNING -cne 'true') {
    throw 'Windows signing requires a native host and explicit opt-in.'
}
$thumbprint = $env:APS_WINDOWS_CERTIFICATE_THUMBPRINT
if ($thumbprint -notmatch '^[a-fA-F0-9]{40}$') { throw 'Invalid signing certificate thumbprint reference.' }
$certificatePath = "Cert:\CurrentUser\My\$thumbprint"
if (Test-Path -LiteralPath $certificatePath) { throw 'Refusing to replace an existing signing identity.' }
$imported = @()
try {
    $password = ConvertTo-SecureString $env:APS_WINDOWS_CERTIFICATE_PASSWORD -AsPlainText -Force
    $pfx = Get-PfxData -FilePath $PfxPath -Password $password
    if (@($pfx.EndEntityCertificates).Count -ne 1 -or @($pfx.OtherCertificates).Count -ne 0 -or
        $pfx.EndEntityCertificates[0].Thumbprint -ine $thumbprint) {
        throw 'Use a leaf-only PFX with exactly the expected identity; certificate chains are not imported.'
    }
    # Do not mark the private key exportable. Do not persist the PFX outside the private temporary directory.
    $imported = @(Import-PfxCertificate -FilePath $PfxPath -CertStoreLocation 'Cert:\CurrentUser\My' -Password $password)
    if ($imported.Count -ne 1) { throw 'PFX must contain exactly one identity.' }
    $certificate = $imported[0]
    $usages = @($certificate.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.37' } |
        ForEach-Object { $_.EnhancedKeyUsages } | ForEach-Object { $_.Value })
    if ($certificate.Thumbprint -ine $thumbprint -or !$certificate.HasPrivateKey -or
        $certificate.NotAfter -le (Get-Date) -or $certificate.NotBefore -gt (Get-Date) -or
        !($usages -contains '1.3.6.1.5.5.7.3.3')) {
        throw 'PFX must contain exactly the expected valid code-signing identity.'
    }
    $sdkRoot = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
    $tools = @(Get-ChildItem -LiteralPath $sdkRoot -Directory |
        Where-Object { $_.Name -match '^10\.0\.\d+\.\d+$' } |
        Sort-Object { [Version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName 'x64\signtool.exe' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($tools.Count -eq 0) { throw 'Windows SDK SignTool is required; no dependency is downloaded.' }
    $signtool = $tools[0]
    $files = @(Get-ChildItem -LiteralPath $AssetDirectory -File)
    if ($files.Count -ne 2 -or @($files | Where-Object { $_.Extension -eq '.exe' }).Count -ne 1 -or
        @($files | Where-Object { $_.Extension -eq '.msi' }).Count -ne 1) { throw 'Expected exactly MSI and EXE.' }
    foreach ($file in $files) {
        & $signtool sign /sha1 $thumbprint /s My /fd SHA256 /tr $env:APS_WINDOWS_TIMESTAMP_URL /td SHA256 $file.FullName *> $null
        if ($LASTEXITCODE -ne 0) { throw 'SignTool signing failed.' }
        & $signtool verify /pa /all /tw $file.FullName *> $null
        if ($LASTEXITCODE -ne 0) { throw 'SignTool verification failed.' }
        $signature = Get-AuthenticodeSignature -LiteralPath $file.FullName
        if ($signature.Status -ne 'Valid' -or $null -eq $signature.SignerCertificate -or
            $signature.SignerCertificate.Thumbprint -ine $thumbprint -or $null -eq $signature.TimeStamperCertificate) {
            throw 'Authenticode or timestamp verification failed.'
        }
    }
} finally {
    # Only the identity absent before import is ours. Never delete other certificates or stores.
    if (Test-Path -LiteralPath $certificatePath) {
        Remove-Item -LiteralPath $certificatePath -DeleteKey -Force
    }
    if (Test-Path -LiteralPath $certificatePath) { throw 'Temporary Windows identity cleanup failed.' }
}
