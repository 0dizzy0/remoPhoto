[CmdletBinding()]
param(
    [string]$Serial,
    [string]$OutputRoot = ".cache\qa\ui-smoke"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$projectRoot = Split-Path -Parent $PSScriptRoot
$runDir = Join-Path $projectRoot (Join-Path $OutputRoot (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$logFile = Join-Path $runDir "ui-smoke.log"
$summaryFile = Join-Path $runDir "summary.md"

function Write-SmokeLog([string]$Message) {
    $line = "[ui-smoke] $Message"
    Write-Host $line
    Add-Content -LiteralPath $logFile -Encoding utf8 -Value $line
}

function Resolve-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $sdkLine = Get-Content -LiteralPath (Join-Path $projectRoot "local.properties") -Encoding utf8 |
        Where-Object { $_ -match "^sdk\.dir=" } | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($sdkLine)) { throw "adb not found in PATH or local.properties." }
    $sdkDir = $sdkLine -replace '^sdk\.dir=', ''
    $slash = [string][char]92
    $sdkDir = $sdkDir.Replace("${slash}:", ':').Replace("${slash}${slash}", $slash)
    $candidate = Join-Path $sdkDir "platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "adb not found: $candidate" }
    return $candidate
}

try {
    $adb = Resolve-Adb
    $deviceLines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\S" }
    $authorized = @($deviceLines | Where-Object { $_ -match "\sdevice$" })
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        if ($authorized.Count -ne 1) {
            throw "Exactly one authorized device is required. authorized=$($authorized.Count); states=$($deviceLines -join '; ')"
        }
        $Serial = ($authorized[0] -split "\s+")[0]
    } elseif (-not ($deviceLines | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device$" })) {
        throw "Device $Serial is disconnected or unauthorized; states=$($deviceLines -join '; ')"
    }
    Write-SmokeLog "Authorized device serial=$Serial"

    Push-Location $projectRoot
    try {
        Write-SmokeLog "Build and install debug and androidTest APKs"
        & .\gradlew.bat :app:installDebug :app:installDebugAndroidTest --console=plain 2>&1 |
            Tee-Object -FilePath (Join-Path $runDir "gradle-install.txt")
        if ($LASTEXITCODE -ne 0) { throw "Gradle build or install failed; see gradle-install.txt" }
    } finally {
        Pop-Location
    }

    Write-SmokeLog "Run PageNavigatorSmokeTest using deterministic pagination state assertions"
    $instrumentation = & $adb -s $Serial shell am instrument -w -r `
        -e class com.remophoto.ui.components.PageNavigatorSmokeTest `
        com.remophoto.debug.test/androidx.test.runner.AndroidJUnitRunner 2>&1
    $instrumentation | Out-File -LiteralPath (Join-Path $runDir "instrumentation.txt") -Encoding utf8
    $instrumentationText = $instrumentation -join "`n"
    if ($LASTEXITCODE -ne 0 -or $instrumentationText -match "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed" -or
        $instrumentationText -notmatch "OK \(") {
        throw "Compose smoke assertions failed; see instrumentation.txt"
    }

    # Screenshot is optional review evidence and never participates in PASS/FAIL.
    & $adb -s $Serial shell screencap -p /sdcard/remophoto-ui-smoke.png | Out-Null
    & $adb -s $Serial pull /sdcard/remophoto-ui-smoke.png (Join-Path $runDir "screenshot.png") | Out-Null
    & $adb -s $Serial shell rm /sdcard/remophoto-ui-smoke.png | Out-Null
    @("# UI Smoke Summary", "", "- Result: PASS", "- Device: $Serial", "- Assertions: PageNavigatorSmokeTest", "- Validation: deterministic pagination state assertions", "- Screenshot: optional evidence only") |
        Out-File -LiteralPath $summaryFile -Encoding utf8
    Write-SmokeLog "PASS summary=$summaryFile"
    Write-Output "UI Smoke PASS: $summaryFile"
} catch {
    $reason = $_.Exception.Message
    @("# UI Smoke Summary", "", "- Result: FAIL", "- Reason: $reason") |
        Out-File -LiteralPath $summaryFile -Encoding utf8
    Write-SmokeLog "FAIL reason=$reason summary=$summaryFile"
    Write-Error "UI Smoke FAIL: $reason; summary=$summaryFile"
    exit 1
}
