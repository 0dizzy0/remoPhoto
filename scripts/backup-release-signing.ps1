[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DestinationDirectory,
    [string]$SevenZipPath,
    [switch]$AllowFixedDrive
)

$ErrorActionPreference = "Stop"

$projectRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$signingDirectory = Join-Path $projectRoot ".signing"
$keystorePath = Join-Path $signingDirectory "remophoto-release.jks"
$propertiesPath = Join-Path $signingDirectory "signing.properties"

foreach ($requiredFile in @($keystorePath, $propertiesPath)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required signing file is missing: $requiredFile"
    }
}

$destination = [System.IO.Path]::GetFullPath($DestinationDirectory)
if ($destination.StartsWith($projectRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Backup destination must be outside the project directory."
}
if (-not (Test-Path -LiteralPath $destination -PathType Container)) {
    throw "Backup destination does not exist: $destination"
}

$driveRoot = [System.IO.Path]::GetPathRoot($destination).TrimEnd("\")
$drive = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='$driveRoot'"
if (-not $drive) {
    throw "Unable to determine destination drive type: $driveRoot"
}
if ($drive.DriveType -ne 2 -and -not $AllowFixedDrive) {
    throw "Destination is not reported as removable media. Use a removable drive or explicitly pass -AllowFixedDrive for an external drive reported as fixed."
}

if ([string]::IsNullOrWhiteSpace($SevenZipPath)) {
    $sevenZipCommand = Get-Command 7z.exe -ErrorAction SilentlyContinue
    if ($sevenZipCommand) {
        $SevenZipPath = $sevenZipCommand.Source
    }
}
if ([string]::IsNullOrWhiteSpace($SevenZipPath) -or -not (Test-Path -LiteralPath $SevenZipPath)) {
    throw "7z.exe was not found. Pass its path via -SevenZipPath."
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$archivePath = Join-Path $destination "remophoto-release-signing-$timestamp.7z"
$checksumPath = "$archivePath.sha256"

Write-Output "[signing-backup] Destination drive: $driveRoot (type=$($drive.DriveType))"
Write-Output "[signing-backup] Creating encrypted archive with encrypted filenames."
Write-Output "[signing-backup] 7-Zip will prompt for the archive password; keep it outside this computer."

Push-Location $projectRoot
try {
    & $SevenZipPath a -t7z -mx=9 -mhe=on -p `
        $archivePath `
        ".signing\remophoto-release.jks" `
        ".signing\signing.properties"
    if ($LASTEXITCODE -ne 0) {
        throw "7-Zip archive creation failed with exit code $LASTEXITCODE"
    }

    Write-Output "[signing-backup] Testing encrypted archive. Enter the same password when prompted."
    & $SevenZipPath t -p $archivePath
    if ($LASTEXITCODE -ne 0) {
        throw "7-Zip archive verification failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$hash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText(
    $checksumPath,
    "$hash *$([System.IO.Path]::GetFileName($archivePath))`r`n",
    [System.Text.UTF8Encoding]::new($false)
)

Write-Output "[signing-backup] Archive verified: $archivePath"
Write-Output "[signing-backup] SHA-256: $hash"
Write-Output "[signing-backup] Checksum file: $checksumPath"
