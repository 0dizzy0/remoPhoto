[CmdletBinding()]
param(
    [string]$Serial,
    [string]$ApkPath = "app\build\outputs\apk\release\app-release.apk",
    [string]$PackageName = "com.remophoto",
    [string]$ActivityName = ".MainActivity",
    [string]$ExpectedVersionName,
    [int]$ExpectedVersionCode = 0,
    [string]$OutputRoot = ".cache\qa\smoke",
    [switch]$BuildRelease,
    [switch]$FreshInstall,
    [switch]$SkipInstall,
    [switch]$SkipSignatureCheck,
    [int]$LaunchWaitSeconds = 3
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$projectRoot = Split-Path -Parent $PSScriptRoot
$runStamp = Get-Date -Format "yyyyMMdd-HHmmss"
$resolvedOutputRoot = if ([System.IO.Path]::IsPathRooted($OutputRoot)) {
    $OutputRoot
} else {
    Join-Path $projectRoot $OutputRoot
}
$runDir = Join-Path $resolvedOutputRoot $runStamp
$logFile = Join-Path $runDir "smoke.log"
$summaryFile = Join-Path $runDir "summary.md"

New-Item -ItemType Directory -Force -Path $runDir | Out-Null

function Write-SmokeLog {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message
    )
    $line = "[smoke] $Message"
    Write-Host $line
    Add-Content -LiteralPath $logFile -Encoding utf8 -Value $line
}

function Resolve-LocalPropertiesSdkDir {
    $localProperties = Join-Path $projectRoot "local.properties"
    if (-not (Test-Path -LiteralPath $localProperties -PathType Leaf)) {
        return $null
    }

    foreach ($line in Get-Content -LiteralPath $localProperties -Encoding utf8) {
        if ($line -match "^\s*sdk\.dir\s*=\s*(.+?)\s*$") {
            return ($Matches[1] -replace "\\:", ":" -replace "\\\\", "\")
        }
    }
    return $null
}

function Resolve-AndroidTool {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ToolName,
        [string]$RelativePath
    )

    $command = Get-Command $ToolName -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $sdkRoots = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Resolve-LocalPropertiesSdkDir)
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique

    foreach ($sdkRoot in $sdkRoots) {
        if ([string]::IsNullOrWhiteSpace($RelativePath)) {
            continue
        }
        $candidate = Join-Path $sdkRoot $RelativePath
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }

    return $null
}

function Resolve-ApkSigner {
    $command = Get-Command "apksigner" -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $sdkRoots = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Resolve-LocalPropertiesSdkDir)
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique

    foreach ($sdkRoot in $sdkRoots) {
        $buildTools = Join-Path $sdkRoot "build-tools"
        if (-not (Test-Path -LiteralPath $buildTools -PathType Container)) {
            continue
        }
        $candidate = Get-ChildItem -LiteralPath $buildTools -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Select-Object -First 1
        if ($null -ne $candidate) {
            return $candidate
        }
    }

    return $null
}

function Invoke-CapturedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$OutputFile,
        [switch]$AllowFailure
    )

    Write-SmokeLog "run $Label"
    Add-Content -LiteralPath $logFile -Encoding utf8 -Value "[command] $FilePath $($Arguments -join ' ')"
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $FilePath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if (-not [string]::IsNullOrWhiteSpace($OutputFile)) {
        $output | Out-File -LiteralPath $OutputFile -Encoding utf8
    }
    $output | ForEach-Object {
        Add-Content -LiteralPath $logFile -Encoding utf8 -Value ([string]$_)
    }

    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "$Label failed with exit code $exitCode"
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = @($output)
    }
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [string]$OutputFile,
        [switch]$AllowFailure
    )

    $fullArgs = @()
    if (-not [string]::IsNullOrWhiteSpace($script:DeviceSerial)) {
        $fullArgs += @("-s", $script:DeviceSerial)
    }
    $fullArgs += $Arguments

    return Invoke-CapturedCommand -Label "adb $($Arguments -join ' ')" -FilePath $script:AdbPath -Arguments $fullArgs -OutputFile $OutputFile -AllowFailure:$AllowFailure
}

function Get-SingleDeviceSerial {
    $devicesResult = Invoke-CapturedCommand -Label "adb devices" -FilePath $script:AdbPath -Arguments @("devices") -OutputFile (Join-Path $runDir "adb-devices.txt")
    $devices = @()
    foreach ($line in $devicesResult.Output) {
        if ($line -match "^(\S+)\s+device$") {
            $devices += $Matches[1]
        }
    }

    if ($devices.Count -eq 0) {
        throw "No adb device is connected. Connect a device or emulator, then rerun with -Serial if needed."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple adb devices found: $($devices -join ', '). Rerun with -Serial <adb-serial>."
    }
    return $devices[0]
}

function Get-VersionValue {
    param(
        [string[]]$Lines,
        [string]$Pattern
    )

    foreach ($line in $Lines) {
        if ($line -match $Pattern) {
            return $Matches[1]
        }
    }
    return $null
}

function Get-PatternCount {
    param(
        [string]$Text,
        [string]$Pattern
    )

    if ([string]::IsNullOrEmpty($Text)) {
        return 0
    }
    return ([regex]::Matches($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)).Count
}

function Get-BlockingIpv4Count {
    param(
        [string]$Text
    )

    if ([string]::IsNullOrEmpty($Text)) {
        return [pscustomobject]@{
            Total = 0
            Ignored = 0
            Blocking = 0
        }
    }

    $octet = "(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)"
    $matches = [regex]::Matches($Text, "(?<![\d.])$octet(?:\.$octet){3}(?![\d.])")
    $ignored = 0
    $blocking = 0
    foreach ($match in $matches) {
        if ($match.Value -eq "224.0.0.251") {
            $ignored++
        } else {
            $blocking++
        }
    }

    return [pscustomobject]@{
        Total = $matches.Count
        Ignored = $ignored
        Blocking = $blocking
    }
}

function Get-LogText {
    param(
        [string[]]$Files
    )

    $chunks = [System.Collections.Generic.List[string]]::new()
    foreach ($file in $Files) {
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            $chunks.Add((Get-Content -LiteralPath $file -Encoding utf8 -Raw))
        }
    }
    return ($chunks -join "`n")
}

function Invoke-PrivacyScan {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RunDirectory,
        [Parameter(Mandatory = $true)]
        [string]$PackageName
    )

    $appLogcatFile = Join-Path $RunDirectory "app-logcat.txt"
    $crashFile = Join-Path $RunDirectory "crash-buffer.txt"
    $fileLogDir = Join-Path $RunDirectory "app-file-logs"
    $fileLogs = @()
    if (Test-Path -LiteralPath $fileLogDir -PathType Container) {
        $fileLogs = Get-ChildItem -LiteralPath $fileLogDir -Recurse -File -Filter "*.log" |
            ForEach-Object { $_.FullName }
    }

    $logText = Get-LogText -Files (@($appLogcatFile, $crashFile) + $fileLogs)
    $fileLogText = Get-LogText -Files $fileLogs
    $appDebugPriority = Get-PatternCount -Text $logText -Pattern "^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+D\s+"
    $appFileLogLines = Get-PatternCount -Text $fileLogText -Pattern "^\d{2}:\d{2}:\d{2}\.\d{3}\s+.+\s+\[[^\]]+\]"
    $contentUris = Get-PatternCount -Text $logText -Pattern "content://"
    $storagePaths = Get-PatternCount -Text $logText -Pattern "(/sdcard/|/storage/)"
    $ipv4Scan = Get-BlockingIpv4Count -Text $logText
    $fatalExceptions = Get-PatternCount -Text $logText -Pattern "FATAL EXCEPTION"
    $packageMentionsInCrash = Get-PatternCount -Text (Get-LogText -Files @($crashFile)) -Pattern ([regex]::Escape($PackageName))

    $blockingCount = $contentUris + $storagePaths + $ipv4Scan.Blocking + $fatalExceptions + $packageMentionsInCrash
    $result = if ($blockingCount -eq 0) { "PASS" } else { "FAIL" }

    $scanFile = Join-Path $RunDirectory "privacy-scan.md"
    $scan = [System.Collections.Generic.List[string]]::new()
    $scan.Add("# Release Log Privacy Scan")
    $scan.Add("")
    $scan.Add("| Item | Count | Gate |")
    $scan.Add("| --- | ---: | --- |")
    $scan.Add("| content URI (`content://`) | $contentUris | must be 0 |")
    $scan.Add("| storage path (`/sdcard/` or `/storage/`) | $storagePaths | must be 0 |")
    $scan.Add("| IPv4 address | $($ipv4Scan.Blocking) | must be 0, ignores mDNS multicast 224.0.0.251 |")
    $scan.Add("| ignored IPv4 address | $($ipv4Scan.Ignored) / $($ipv4Scan.Total) | evidence only |")
    $scan.Add("| fatal exception | $fatalExceptions | must be 0 |")
    $scan.Add("| package mention in crash buffer | $packageMentionsInCrash | must be 0 |")
    $scan.Add("| logcat DEBUG priority | $appDebugPriority | evidence only |")
    $scan.Add("| app file log lines | $appFileLogLines | evidence only |")
    $scan.Add("")
    $scan.Add("Result: $result")
    $scan | Out-File -LiteralPath $scanFile -Encoding utf8

    return [pscustomobject]@{
        Result = $result
        Detail = "contentUri=$contentUris storagePath=$storagePaths ipv4=$($ipv4Scan.Blocking) ignoredIpv4=$($ipv4Scan.Ignored) fatal=$fatalExceptions crashPackage=$packageMentionsInCrash debugPriority=$appDebugPriority appFileLogLines=$appFileLogLines"
        File = $scanFile
    }
}

Push-Location $projectRoot

$checks = [System.Collections.Generic.List[object]]::new()
$script:DeviceSerial = $null
$script:AdbPath = $null

try {
    Write-SmokeLog "projectRoot=$projectRoot"
    Write-SmokeLog "runDir=$runDir"

    if ($BuildRelease) {
        $gradle = Join-Path $projectRoot "gradlew.bat"
        Invoke-CapturedCommand `
            -Label "gradle release gate" `
            -FilePath $gradle `
            -Arguments @(":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleRelease", "--console=plain") `
            -OutputFile (Join-Path $runDir "gradle-release-gate.txt") | Out-Null
        $checks.Add([pscustomobject]@{ Name = "Gradle release gate"; Result = "PASS"; Detail = ":app:testDebugUnitTest :app:lintDebug :app:assembleRelease" })
    }

    $resolvedApkPath = if ([System.IO.Path]::IsPathRooted($ApkPath)) {
        $ApkPath
    } else {
        Join-Path $projectRoot $ApkPath
    }
    if (-not (Test-Path -LiteralPath $resolvedApkPath -PathType Leaf)) {
        throw "APK not found: $resolvedApkPath"
    }

    $apkHash = (Get-FileHash -LiteralPath $resolvedApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $apkSize = (Get-Item -LiteralPath $resolvedApkPath).Length
    Write-SmokeLog "apk=$resolvedApkPath"
    Write-SmokeLog "apkSha256=$apkHash"
    Write-SmokeLog "apkSizeBytes=$apkSize"
    $checks.Add([pscustomobject]@{ Name = "APK hash"; Result = "PASS"; Detail = $apkHash })

    if (-not $SkipSignatureCheck) {
        $apksigner = Resolve-ApkSigner
        if ([string]::IsNullOrWhiteSpace($apksigner)) {
            throw "apksigner not found. Install Android SDK build-tools or rerun with -SkipSignatureCheck."
        }
        Invoke-CapturedCommand `
            -Label "apksigner verify" `
            -FilePath $apksigner `
            -Arguments @("verify", "--verbose", "--print-certs", $resolvedApkPath) `
            -OutputFile (Join-Path $runDir "apksigner-verify.txt") | Out-Null
        $checks.Add([pscustomobject]@{ Name = "Release signature"; Result = "PASS"; Detail = "apksigner verify passed" })
    } else {
        $checks.Add([pscustomobject]@{ Name = "Release signature"; Result = "SKIPPED"; Detail = "-SkipSignatureCheck" })
    }

    $script:AdbPath = Resolve-AndroidTool -ToolName "adb" -RelativePath "platform-tools\adb.exe"
    if ([string]::IsNullOrWhiteSpace($script:AdbPath)) {
        throw "adb not found. Configure ANDROID_HOME, ANDROID_SDK_ROOT, local.properties sdk.dir, or PATH."
    }

    if ([string]::IsNullOrWhiteSpace($Serial)) {
        $script:DeviceSerial = Get-SingleDeviceSerial
    } else {
        $script:DeviceSerial = $Serial
        Invoke-CapturedCommand -Label "adb devices" -FilePath $script:AdbPath -Arguments @("devices") -OutputFile (Join-Path $runDir "adb-devices.txt") | Out-Null
    }
    Write-SmokeLog "serial=$script:DeviceSerial"

    if (-not $SkipInstall) {
        if ($FreshInstall) {
            Invoke-Adb -Arguments @("uninstall", $PackageName) -OutputFile (Join-Path $runDir "adb-uninstall.txt") -AllowFailure | Out-Null
        }

        $installResult = Invoke-Adb -Arguments @("install", "-r", "--no-streaming", $resolvedApkPath) -OutputFile (Join-Path $runDir "adb-install.txt") -AllowFailure
        if ($installResult.ExitCode -ne 0) {
            Write-SmokeLog "install with --no-streaming failed; retrying plain install -r"
            Invoke-Adb -Arguments @("install", "-r", $resolvedApkPath) -OutputFile (Join-Path $runDir "adb-install-retry.txt") | Out-Null
        }
        $installMode = if ($FreshInstall) { "fresh install" } else { "install -r" }
        $checks.Add([pscustomobject]@{ Name = "APK install"; Result = "PASS"; Detail = $installMode })
    } else {
        $checks.Add([pscustomobject]@{ Name = "APK install"; Result = "SKIPPED"; Detail = "-SkipInstall" })
    }

    Invoke-Adb -Arguments @("logcat", "-c") -OutputFile (Join-Path $runDir "logcat-clear.txt") | Out-Null
    Invoke-Adb -Arguments @("shell", "am", "force-stop", $PackageName) -OutputFile (Join-Path $runDir "force-stop.txt") | Out-Null

    $component = "$PackageName/$ActivityName"
    $launchResult = Invoke-Adb -Arguments @("shell", "am", "start", "-W", "-n", $component) -OutputFile (Join-Path $runDir "launch.txt")
    Start-Sleep -Seconds $LaunchWaitSeconds

    $launchText = ($launchResult.Output -join "`n")
    if ($launchText -notmatch "Status:\s+ok" -and $launchText -notmatch "Complete") {
        throw "Launch output does not confirm success. See launch.txt."
    }
    $totalTime = Get-VersionValue -Lines $launchResult.Output -Pattern "TotalTime:\s*(\d+)"
    if ([string]::IsNullOrWhiteSpace($totalTime)) {
        $totalTime = "unknown"
    }
    $checks.Add([pscustomobject]@{ Name = "Cold launch"; Result = "PASS"; Detail = "TotalTime=$totalTime ms" })

    $packageOutputFile = Join-Path $runDir "dumpsys-package.txt"
    $packageResult = Invoke-Adb -Arguments @("shell", "dumpsys", "package", $PackageName) -OutputFile $packageOutputFile
    $versionName = Get-VersionValue -Lines $packageResult.Output -Pattern "versionName=([^\s]+)"
    $versionCode = Get-VersionValue -Lines $packageResult.Output -Pattern "versionCode=(\d+)"

    if (-not [string]::IsNullOrWhiteSpace($ExpectedVersionName) -and $versionName -ne $ExpectedVersionName) {
        throw "versionName mismatch. expected=$ExpectedVersionName actual=$versionName"
    }
    if ($ExpectedVersionCode -gt 0 -and [int]$versionCode -ne $ExpectedVersionCode) {
        throw "versionCode mismatch. expected=$ExpectedVersionCode actual=$versionCode"
    }
    $checks.Add([pscustomobject]@{ Name = "Package version"; Result = "PASS"; Detail = "versionName=$versionName versionCode=$versionCode" })

    $pidResult = Invoke-Adb -Arguments @("shell", "pidof", "-s", $PackageName) -OutputFile (Join-Path $runDir "pid.txt") -AllowFailure
    $pidLine = $pidResult.Output | Select-Object -First 1
    $appPid = ""
    if ($null -ne $pidLine) {
        $appPid = $pidLine.ToString().Trim()
    }
    if ([string]::IsNullOrWhiteSpace($appPid)) {
        throw "App process is not running after launch."
    }
    $checks.Add([pscustomobject]@{ Name = "Process alive"; Result = "PASS"; Detail = "pid=$appPid" })

    Invoke-Adb -Arguments @("shell", "uiautomator", "dump", "/sdcard/remophoto-smoke-ui.xml") -OutputFile (Join-Path $runDir "uiautomator-dump-command.txt") | Out-Null
    Invoke-Adb -Arguments @("pull", "/sdcard/remophoto-smoke-ui.xml", (Join-Path $runDir "ui.xml")) -OutputFile (Join-Path $runDir "uiautomator-pull.txt") | Out-Null
    Invoke-Adb -Arguments @("shell", "rm", "/sdcard/remophoto-smoke-ui.xml") -OutputFile (Join-Path $runDir "uiautomator-cleanup.txt") -AllowFailure | Out-Null

    $uiFile = Join-Path $runDir "ui.xml"
    if (-not (Test-Path -LiteralPath $uiFile -PathType Leaf) -or (Get-Item -LiteralPath $uiFile).Length -eq 0) {
        throw "UI dump was not captured."
    }
    $checks.Add([pscustomobject]@{ Name = "UI dump"; Result = "PASS"; Detail = "ui.xml captured" })

    Invoke-Adb -Arguments @("shell", "screencap", "-p", "/sdcard/remophoto-smoke.png") -OutputFile (Join-Path $runDir "screenshot-command.txt") -AllowFailure | Out-Null
    Invoke-Adb -Arguments @("pull", "/sdcard/remophoto-smoke.png", (Join-Path $runDir "screenshot.png")) -OutputFile (Join-Path $runDir "screenshot-pull.txt") -AllowFailure | Out-Null
    Invoke-Adb -Arguments @("shell", "rm", "/sdcard/remophoto-smoke.png") -OutputFile (Join-Path $runDir "screenshot-cleanup.txt") -AllowFailure | Out-Null

    Invoke-Adb -Arguments @("shell", "dumpsys", "meminfo", $PackageName) -OutputFile (Join-Path $runDir "meminfo.txt") -AllowFailure | Out-Null
    Invoke-Adb -Arguments @("shell", "dumpsys", "activity", "anr") -OutputFile (Join-Path $runDir "anr.txt") -AllowFailure | Out-Null
    Invoke-Adb -Arguments @("logcat", "-b", "crash", "-d") -OutputFile (Join-Path $runDir "crash-buffer.txt") -AllowFailure | Out-Null
    Invoke-Adb -Arguments @("logcat", "-d", "--pid", $appPid) -OutputFile (Join-Path $runDir "app-logcat.txt") -AllowFailure | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $runDir "app-file-logs") | Out-Null
    Invoke-Adb `
        -Arguments @("pull", "/sdcard/Android/data/$PackageName/files/remoPhoto/logs", (Join-Path $runDir "app-file-logs")) `
        -OutputFile (Join-Path $runDir "app-file-logs-pull.txt") `
        -AllowFailure | Out-Null

    $crashText = ""
    $crashFile = Join-Path $runDir "crash-buffer.txt"
    if (Test-Path -LiteralPath $crashFile -PathType Leaf) {
        $crashText = Get-Content -LiteralPath $crashFile -Encoding utf8 -Raw
    }
    if ($crashText -match "FATAL EXCEPTION" -or $crashText -match [regex]::Escape($PackageName)) {
        throw "Crash buffer contains a crash after smoke launch. See crash-buffer.txt."
    }
    $checks.Add([pscustomobject]@{ Name = "Crash buffer"; Result = "PASS"; Detail = "no crash after launch" })

    $privacyScan = Invoke-PrivacyScan -RunDirectory $runDir -PackageName $PackageName
    if ($privacyScan.Result -ne "PASS") {
        throw "Release log privacy scan failed. See privacy-scan.md."
    }
    $checks.Add([pscustomobject]@{ Name = "Release log privacy scan"; Result = "PASS"; Detail = $privacyScan.Detail })

    $gitCommit = (& git rev-parse --short HEAD 2>$null)
    $gitBranch = (& git branch --show-current 2>$null)

    $summary = [System.Collections.Generic.List[string]]::new()
    $summary.Add("# remoPhoto Smoke Summary")
    $summary.Add("")
    $summary.Add("| Item | Value |")
    $summary.Add("| --- | --- |")
    $summary.Add("| Time | $(Get-Date -Format "yyyy-MM-dd HH:mm:ss") |")
    $summary.Add("| Branch | $gitBranch |")
    $summary.Add("| Commit | $gitCommit |")
    $summary.Add("| Device | $script:DeviceSerial |")
    $summary.Add("| APK | $resolvedApkPath |")
    $summary.Add("| SHA-256 | $apkHash |")
    $summary.Add("| Package | $PackageName |")
    $summary.Add("| versionName / versionCode | $versionName / $versionCode |")
    $summary.Add("| Cold launch | $totalTime ms |")
    $summary.Add("")
    $summary.Add("## Checks")
    $summary.Add("")
    $summary.Add("| Check | Result | Detail |")
    $summary.Add("| --- | --- | --- |")
    foreach ($check in $checks) {
        $summary.Add("| $($check.Name) | $($check.Result) | $($check.Detail) |")
    }
    $summary.Add("")
    $summary.Add("## Evidence Files")
    $summary.Add("")
    $summary.Add('- `smoke.log`')
    $summary.Add('- `apksigner-verify.txt`')
    $summary.Add('- `adb-install.txt`')
    $summary.Add('- `launch.txt`')
    $summary.Add('- `dumpsys-package.txt`')
    $summary.Add('- `ui.xml`')
    $summary.Add('- `screenshot.png`')
    $summary.Add('- `crash-buffer.txt`')
    $summary.Add('- `app-logcat.txt`')
    $summary.Add('- `app-file-logs/`')
    $summary.Add('- `privacy-scan.md`')
    $summary.Add('- `meminfo.txt`')
    $summary | Out-File -LiteralPath $summaryFile -Encoding utf8

    Write-SmokeLog "PASS summary=$summaryFile"
    Write-Output ""
    Write-Output "Smoke PASS: $summaryFile"
} catch {
    $message = $_.Exception.Message
    Write-SmokeLog "FAIL $message"
    $failureSummary = @(
        "# remoPhoto Smoke Summary",
        "",
        "| Item | Value |",
        "| --- | --- |",
        "| Time | $(Get-Date -Format "yyyy-MM-dd HH:mm:ss") |",
        "| Result | FAIL |",
        "| Reason | $message |",
        "| Evidence directory | $runDir |"
    )
    $failureSummary | Out-File -LiteralPath $summaryFile -Encoding utf8
    throw
} finally {
    Pop-Location
}
