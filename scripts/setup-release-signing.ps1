[CmdletBinding()]
param(
    [string]$KeytoolPath
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$signingDirectory = Join-Path $projectRoot ".signing"
$keystorePath = Join-Path $signingDirectory "remophoto-release.jks"
$propertiesPath = Join-Path $signingDirectory "signing.properties"

if (Test-Path -LiteralPath $keystorePath) {
    throw "Release keystore already exists; refusing to overwrite: $keystorePath"
}
if (Test-Path -LiteralPath $propertiesPath) {
    throw "Release signing properties already exist; refusing to overwrite: $propertiesPath"
}

if ([string]::IsNullOrWhiteSpace($KeytoolPath)) {
    $javaHomeKeytool = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME "bin\keytool.exe"
    } else {
        $null
    }
    if ($javaHomeKeytool -and (Test-Path -LiteralPath $javaHomeKeytool)) {
        $KeytoolPath = $javaHomeKeytool
    } else {
        $command = Get-Command keytool.exe -ErrorAction SilentlyContinue
        if ($command) {
            $KeytoolPath = $command.Source
        }
    }
}

if ([string]::IsNullOrWhiteSpace($KeytoolPath) -or -not (Test-Path -LiteralPath $KeytoolPath)) {
    throw "keytool.exe was not found. Pass the Gradle JDK keytool path via -KeytoolPath."
}

$null = New-Item -ItemType Directory -Path $signingDirectory -Force
$passwordBytes = [byte[]]::new(32)
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $random.GetBytes($passwordBytes)
} finally {
    $random.Dispose()
}
$password = [Convert]::ToBase64String($passwordBytes)
$passwordEnvironmentName = "REMOPHOTO_SETUP_KEYSTORE_PASSWORD"

Write-Output "[signing] keytool: $KeytoolPath"
Write-Output "[signing] Generating PKCS12 release keystore (RSA 4096, valid for 100 years)"

try {
    Set-Item -Path "Env:$passwordEnvironmentName" -Value $password
    & $KeytoolPath `
        -genkeypair `
        -v `
        -keystore $keystorePath `
        -storetype PKCS12 `
        -alias remophoto-release `
        -keyalg RSA `
        -keysize 4096 `
        -validity 36500 `
        -dname "CN=remoPhoto, OU=Development, O=remoPhoto, C=CN" `
        -storepass:env $passwordEnvironmentName `
        -keypass:env $passwordEnvironmentName

    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE"
    }

    $properties = @(
        "storeFile=.signing/remophoto-release.jks"
        "storePassword=$password"
        "keyAlias=remophoto-release"
        "keyPassword=$password"
    )
    [System.IO.File]::WriteAllLines(
        $propertiesPath,
        $properties,
        [System.Text.UTF8Encoding]::new($false)
    )
} finally {
    Remove-Item -Path "Env:$passwordEnvironmentName" -ErrorAction SilentlyContinue
    $password = $null
    [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)
}

Write-Output "[signing] Keystore created: $keystorePath"
Write-Output "[signing] Local properties created: $propertiesPath"
Write-Output "[signing] Password was not printed. Back up the ignored .signing directory offline."
