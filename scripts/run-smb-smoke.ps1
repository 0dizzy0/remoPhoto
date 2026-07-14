[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5554',
    [string]$Distro = 'Ubuntu-24.04',
    [string]$CredentialFile = 'E:\WSL\secrets\samba-remophoto-m5.txt',
    [string]$FixturePath = 'D:\ai_program\remoPhoto-test-data',
    [string]$Adb,
    [string]$Python,
    [string]$ApkPath = 'app\build\outputs\apk\debug\app-debug.apk',
    [string]$Package = 'com.remophoto.debug',
    [string]$Activity = 'com.remophoto.MainActivity',
    [string]$OutputRoot = '.cache\qa\smb-smoke',
    [int]$DevicePort = 1446,
    [int]$RelayPort = 15446,
    [int]$SambaPort = 1445,
    [switch]$BuildDebug
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
[Console]::OutputEncoding = [Text.UTF8Encoding]::new()
$projectRoot = Split-Path -Parent $PSScriptRoot
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDir = if ([IO.Path]::IsPathRooted($OutputRoot)) { Join-Path $OutputRoot $stamp } else { Join-Path $projectRoot (Join-Path $OutputRoot $stamp) }
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$logFile = Join-Path $runDir 'smb-smoke.log'
$summaryFile = Join-Path $runDir 'summary.md'
$script:Stage = 'precheck'
$script:AdbPath = $null
$script:RelayProcess = $null
$script:KeepAliveProcess = $null
$script:ServerPrepared = $false
$script:ReversePrepared = $false
$script:DumpIndex = 0
$script:SecretOccurrences = 0
$shareName = "RemoPhotoSmoke$($stamp -replace '-', '')"
$repositoryName = "M5Smoke$($stamp -replace '-', '')"
$wslStage = "/var/tmp/remophoto-smb-smoke-$($stamp -replace '-', '')"
$wslBackup = "$wslStage.conf"

function Log([string]$Message) {
    Add-Content -LiteralPath $logFile -Encoding UTF8 -Value "[$(Get-Date -Format o)] stage=$script:Stage $Message"
}

function Resolve-Adb {
    if (-not [string]::IsNullOrWhiteSpace($Adb)) { return $Adb }
    $line = Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties') -Encoding UTF8 |
        Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $line) { throw 'local.properties does not define sdk.dir' }
    $sdk = ($line -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\')
    return Join-Path $sdk 'platform-tools\adb.exe'
}

function Resolve-Python {
    if (-not [string]::IsNullOrWhiteSpace($Python)) { return $Python }
    foreach ($name in @('pythonw.exe', 'python.exe', 'python3.exe')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    throw 'Windows Python not found; pass a development-drive python.exe/pythonw.exe with -Python'
}

function Adb([string[]]$Arguments, [switch]$AllowFailure) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = @(& $script:AdbPath -s $Serial @Arguments 2>&1); $code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
    if ($code -ne 0 -and -not $AllowFailure) { throw "ADB failed: $($Arguments -join ' ')" }
    return @($output)
}

function Wsl([string]$Command, [switch]$Root, [switch]$AllowFailure) {
    $args = @('-d', $Distro)
    if ($Root) { $args += @('-u', 'root') }
    $args += @('--', 'bash', '-lc', $Command)
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $output = @(& wsl.exe @args 2>&1); $code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
    if ($code -ne 0 -and -not $AllowFailure) {
        $output | Out-File -LiteralPath (Join-Path $runDir "wsl-$($script:Stage)-error.txt") -Encoding UTF8
        throw "WSL command failed, exit=$code"
    }
    return @($output)
}

function Send-Secret([string]$Secret) {
    if ($Secret.Contains("'")) { throw 'The test password contains an unsupported single quote' }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $script:AdbPath
    $start.Arguments = "-s $Serial shell"
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($start)
    try {
        $process.StandardInput.WriteLine("input text '$Secret'")
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(10000) -or $process.ExitCode -ne 0) { throw 'Secure password input failed' }
    } finally { $process.Dispose() }
}

function Dump([string]$Label, [string]$Secret = '') {
    $script:DumpIndex++
    $name = ('{0:D3}-{1}' -f $script:DumpIndex, ($Label -replace '[^A-Za-z0-9_-]', '-'))
    $remote = "/sdcard/$name.xml"
    $local = Join-Path $runDir "$name.xml"
    Adb @('shell', 'uiautomator', 'dump', $remote) | Out-Null
    Adb @('pull', $remote, $local) | Out-Null
    Adb @('shell', 'rm', $remote) -AllowFailure | Out-Null
    $raw = [IO.File]::ReadAllText($local, [Text.Encoding]::UTF8)
    if (-not [string]::IsNullOrEmpty($Secret)) {
        $count = ([regex]::Matches($raw, [regex]::Escape($Secret))).Count
        if ($count -gt 0) {
            $script:SecretOccurrences += $count
            [IO.File]::WriteAllText($local, $raw.Replace($Secret, '[REDACTED]'), [Text.UTF8Encoding]::new($false))
            $raw = $raw.Replace($Secret, '[REDACTED]')
        }
    }
    return [xml]$raw
}

function Center($Node) {
    while ($null -ne $Node -and $Node.clickable -ne 'true') { $Node = $Node.ParentNode }
    if ($null -eq $Node -or $Node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'UI target has no clickable bounds' }
    return @([int](([int]$Matches[1] + [int]$Matches[3]) / 2), [int](([int]$Matches[2] + [int]$Matches[4]) / 2))
}

function Tap-Text([string]$Text, [string]$Step, [string]$Secret = '') {
    $ui = Dump $Step $Secret
    $node = @($ui.SelectNodes('//node')) | Where-Object { $_.text -eq $Text } | Select-Object -First 1
    if ($null -eq $node) { throw "UI text not found: $Text" }
    $point = Center $node
    Adb @('shell', 'input', 'tap', "$($point[0])", "$($point[1])") | Out-Null
    Start-Sleep -Milliseconds 700
}

function Set-Field([string]$Label, [string]$Value, [string]$Step, [string]$Secret = '') {
    $ui = Dump "$Step-before" $Secret
    $node = @($ui.SelectNodes('//node')) | Where-Object { $_.text -eq $Label } | Select-Object -First 1
    if ($null -eq $node) {
        Adb @('shell', 'input', 'swipe', '800', '1550', '800', '850', '450') | Out-Null
        Start-Sleep -Milliseconds 500
        $ui = Dump "$Step-scroll" $Secret
        $node = @($ui.SelectNodes('//node')) | Where-Object { $_.text -eq $Label } | Select-Object -First 1
    }
    if ($null -eq $node) { throw "Form field not found: $Label" }
    $point = Center $node
    Adb @('shell', 'input', 'tap', "$($point[0])", "$($point[1])") | Out-Null
    Adb @('shell', 'input', 'keyevent', '123') | Out-Null
    1..64 | ForEach-Object { Adb @('shell', 'input', 'keyevent', '67') | Out-Null }
    if ($Label -eq '密码') { Send-Secret $Value } else { Adb @('shell', 'input', 'text', $Value) | Out-Null }
    Adb @('shell', 'input', 'keyevent', '4') | Out-Null
    Start-Sleep -Milliseconds 350
}

function Start-App {
    Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Adb @('shell', 'am', 'start', '-W', '-n', "$Package/$Activity") | Out-Null
    Start-Sleep -Seconds 2
}

function Assert-HomeCount([int]$Count, [string]$Secret) {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        Start-Sleep -Seconds 2
        $ui = Dump 'home-count' $Secret
        $texts = @($ui.SelectNodes('//node')) | ForEach-Object { [string]$_.text }
        if (($texts -contains $repositoryName) -and ($texts -contains "$Count 张图片 · 已连接")) { return }
    } while ((Get-Date) -lt $deadline)
    throw "Repository did not reach connected image count: $Count"
}

function Open-Manager([string]$Secret) {
    Start-App
    Tap-Text '仓库管理' 'home-manager' $Secret
    Start-Sleep -Seconds 1
}

function Refresh-Repository([int]$ExpectedCount, [string]$Secret, [string]$ExpectedCategory = '') {
    Adb @('logcat', '-c') | Out-Null
    Open-Manager $Secret
    $ui = Dump 'manager-before-refresh' $Secret
    $description = "刷新 $repositoryName"
    $node = @($ui.SelectNodes('//node')) | Where-Object { $_.'content-desc' -eq $description } | Select-Object -First 1
    if ($null -eq $node) { throw "Refresh action not found: $repositoryName" }
    $point = Center $node
    Adb @('shell', 'input', 'tap', "$($point[0])", "$($point[1])") | Out-Null
    $deadline = (Get-Date).AddSeconds(75)
    $done = $false
    do {
        Start-Sleep -Seconds 2
        $log = (Adb @('logcat', '-d', '-v', 'brief')) -join "`n"
        if ($ExpectedCategory) {
            if ($log -match "retry=false, category=$([regex]::Escape($ExpectedCategory))") { $done = $true }
        } elseif ($log -match "SMB 后台刷新完成:.*images=$ExpectedCount") { $done = $true }
    } while (-not $done -and (Get-Date) -lt $deadline)
    Adb @('logcat', '-d', '-v', 'threadtime') | Out-File -LiteralPath (Join-Path $runDir "refresh-$ExpectedCount-$ExpectedCategory.txt") -Encoding UTF8
    if (-not $done) { throw "Refresh result not reached: count=$ExpectedCount category=$ExpectedCategory" }
    $ui = Dump 'manager-after-refresh' $Secret
    $texts = @($ui.SelectNodes('//node')) | ForEach-Object { [string]$_.text }
    if (-not ($texts -contains "$ExpectedCount 张图片")) { throw "Preserved/current index count mismatch: $ExpectedCount" }
}

function Capture([string]$Name) {
    $remote = "/sdcard/$Name.png"
    Adb @('shell', 'screencap', '-p', $remote) | Out-Null
    Adb @('pull', $remote, (Join-Path $runDir "$Name.png")) | Out-Null
    Adb @('shell', 'rm', $remote) -AllowFailure | Out-Null
}

function Wait-StreamRead {
    $deadline = (Get-Date).AddSeconds(25)
    do {
        Start-Sleep -Seconds 1
        $log = (Adb @('logcat', '-d', '-v', 'brief')) -join "`n"
        if ($log -match 'SMB 读取资源已关闭') { return }
    } while ((Get-Date) -lt $deadline)
    throw 'SMB media stream completion log was not observed'
}

function Verify-Media([string]$Secret) {
    Start-App
    Tap-Text $repositoryName 'home-open-repository' $Secret
    Tap-Text '01_普通相册' 'albums-open-static' $Secret
    $ui = Dump 'static-grid' $Secret
    $tiles = @($ui.SelectNodes('//node[@clickable="true"]') | Where-Object {
        $_.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' -and
        ([int]$Matches[3] - [int]$Matches[1]) -gt 500 -and ([int]$Matches[4] - [int]$Matches[2]) -gt 400
    })
    if ($tiles.Count -lt 1) { throw 'Static image tile not found' }
    $point = Center $tiles[0]
    Adb @('logcat', '-c') | Out-Null
    Adb @('shell', 'input', 'tap', "$($point[0])", "$($point[1])") | Out-Null
    Wait-StreamRead
    Start-Sleep -Seconds 3
    Capture 'static-viewer'
    Adb @('shell', 'input', 'keyevent', '4') | Out-Null
    Adb @('shell', 'input', 'keyevent', '4') | Out-Null
    Start-Sleep -Seconds 2
    $ui = Dump 'albums-before-format' $Secret
    $node = @($ui.SelectNodes('//node')) | Where-Object { $_.text -eq '04_格式测试' } | Select-Object -First 1
    if ($null -eq $node) {
        Adb @('shell', 'input', 'swipe', '540', '1500', '540', '600', '500') | Out-Null
        Start-Sleep -Seconds 1
        $ui = Dump 'albums-format-visible' $Secret
        $node = @($ui.SelectNodes('//node')) | Where-Object { $_.text -eq '04_格式测试' } | Select-Object -First 1
    }
    if ($null -eq $node) { throw 'Format test album not found' }
    $point = Center $node
    Adb @('shell', 'input', 'tap', "$($point[0])", "$($point[1])") | Out-Null
    Start-Sleep -Seconds 2
    Adb @('shell', 'input', 'swipe', '540', '1500', '540', '500', '500') | Out-Null
    Start-Sleep -Seconds 1
    $ui = Dump 'format-grid' $Secret
    $tiles = @($ui.SelectNodes('//node[@clickable="true"]') | Where-Object {
        $_.bounds -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$' -and
        ([int]$Matches[3] - [int]$Matches[1]) -gt 500 -and ([int]$Matches[4] - [int]$Matches[2]) -gt 250
    })
    if ($tiles.Count -ne 6) { throw "Dynamic GIF tile baseline mismatch: visible=$($tiles.Count)" }
    $point = Center $tiles[4]
    Adb @('logcat', '-c') | Out-Null
    Adb @('shell', 'input', 'tap', "$($point[0])", "$($point[1])") | Out-Null
    Wait-StreamRead
    Start-Sleep -Seconds 2
    Capture 'gif-frame-1'
    Start-Sleep -Seconds 2
    Capture 'gif-frame-2'
}

function Delete-Repository([string]$Secret) {
    Open-Manager $Secret
    $ui = Dump 'manager-before-delete' $Secret
    $description = '删除仓库'
    $node = @($ui.SelectNodes('//node')) | Where-Object { $_.'content-desc' -eq $description } | Select-Object -First 1
    if ($null -eq $node) { throw 'Delete repository action not found' }
    $point = Center $node
    Adb @('shell', 'input', 'tap', "$($point[0])", "$($point[1])") | Out-Null
    Start-Sleep -Seconds 1
    Tap-Text '删除' 'delete-confirm' $Secret
    Start-Sleep -Seconds 3
    $ui = Dump 'manager-after-delete' $Secret
    $texts = @($ui.SelectNodes('//node')) | ForEach-Object { [string]$_.text }
    if ($texts -contains $repositoryName) { throw 'Repository still exists after delete' }
}

try {
    Log "runDir=$runDir"
    $script:AdbPath = Resolve-Adb
    $pythonPath = Resolve-Python
    foreach ($path in @($script:AdbPath, $pythonPath, $CredentialFile, $FixturePath)) {
        if (-not (Test-Path -LiteralPath $path)) { throw "Prerequisite path not found: $path" }
    }
    $fixtureImages = @(Get-ChildItem -LiteralPath $FixturePath -Recurse -File | Where-Object { $_.Extension -match '^\.(jpe?g|png|gif|bmp|webp)$' })
    if ($fixtureImages.Count -ne 30) { throw "Fixture must contain 30 images, actual=$($fixtureImages.Count)" }
    $credentials = @{}
    foreach ($line in Get-Content -LiteralPath $CredentialFile -Encoding UTF8) {
        if ($line -match '^([^=]+)=(.*)$') { $credentials[$Matches[1].Trim()] = $Matches[2] }
    }
    foreach ($key in @('username', 'password')) { if (-not $credentials[$key]) { throw "Credential field missing: $key" } }
    $secret = [string]$credentials.password
    $device = (Adb @('get-state')) -join ''
    if ($device.Trim() -ne 'device') { throw "ADB device unavailable: $Serial" }
    $api = ((Adb @('shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim()
    if ($api -ne '29') { throw "API 29 required, actual=$api" }

    if ($BuildDebug) {
        $script:Stage = 'build'
        $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        try { & (Join-Path $projectRoot 'gradlew.bat') :app:assembleDebug --console=plain *> (Join-Path $runDir 'gradle-build.txt'); $code = $LASTEXITCODE } finally { $ErrorActionPreference = $old }
        if ($code -ne 0) { throw 'Debug build failed' }
    }
    $resolvedApk = if ([IO.Path]::IsPathRooted($ApkPath)) { $ApkPath } else { Join-Path $projectRoot $ApkPath }
    if (-not (Test-Path -LiteralPath $resolvedApk -PathType Leaf)) { throw "APK not found: $resolvedApk" }

    $script:Stage = 'server-setup'
    $script:KeepAliveProcess = Start-Process -FilePath 'wsl.exe' -ArgumentList @('-d', $Distro, '--', 'bash', '-lc', 'exec sleep infinity') -PassThru -WindowStyle Hidden
    $sourceWsl = '/mnt/' + $FixturePath.Substring(0, 1).ToLowerInvariant() + ($FixturePath.Substring(2).Replace('\', '/'))
    $setup = "set -e; cp /etc/samba/smb.conf '$wslBackup'; mkdir -p '$wslStage'; cp -a '$sourceWsl/.' '$wslStage/'; printf '\n[$shareName]\n    path = $wslStage\n    read only = yes\n    browseable = yes\n    guest ok = no\n    valid users = $($credentials.username)\n    follow symlinks = no\n' >> /etc/samba/smb.conf; testparm -s >/dev/null; systemctl restart smbd"
    $script:ServerPrepared = $true
    Wsl $setup -Root | Out-Null
    $wslIpText = (Wsl 'hostname -I') -join ' '
    $wslIpMatch = [regex]::Match($wslIpText, '(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])')
    if (-not $wslIpMatch.Success) { throw 'WSL IPv4 discovery failed' }
    $wslIp = $wslIpMatch.Value
    $relayScript = Join-Path $PSScriptRoot 'smb-smoke-tcp-relay.py'
    $script:RelayProcess = Start-Process -FilePath $pythonPath -ArgumentList @($relayScript, '--listen-port', "$RelayPort", '--target-host', $wslIp, '--target-port', "$SambaPort", '--log-file', (Join-Path $runDir 'tcp-relay.log')) -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 2
    if ($script:RelayProcess.HasExited) { throw 'TCP relay failed to start' }
    Adb @('reverse', "tcp:$DevicePort", "tcp:$RelayPort") | Out-Null
    $script:ReversePrepared = $true

    $script:Stage = 'install'
    Adb @('uninstall', $Package) -AllowFailure | Out-Null
    Adb @('install', '--no-streaming', $resolvedApk) | Out-Null

    $script:Stage = 'add-sync'
    Start-App
    Tap-Text '＋ 添加' 'home-add'
    Tap-Text 'SMB 共享' 'protocol-smb'
    Set-Field '显示名称' $repositoryName 'field-name'
    Set-Field '主机名或 IP 地址' '127.0.0.1' 'field-host'
    Set-Field '端口' "$DevicePort" 'field-port'
    Set-Field '共享名' $shareName 'field-share'
    Set-Field '用户名' ([string]$credentials.username) 'field-user'
    Set-Field '密码' $secret 'field-password' $secret
    Tap-Text '测试连接' 'test-connection' $secret
    Adb @('shell', 'input', 'swipe', '800', '1500', '800', '700', '450') | Out-Null
    $deadline = (Get-Date).AddSeconds(35)
    $connected = $false
    do {
        Start-Sleep -Seconds 1
        $ui = Dump 'connection-result' $secret
        $texts = @($ui.SelectNodes('//node')) | ForEach-Object { [string]$_.text }
        if ($texts | Where-Object { $_ -like '连接成功*' }) { $connected = $true }
    } while (-not $connected -and (Get-Date) -lt $deadline)
    if (-not $connected) { throw 'SMB connection test did not succeed' }
    Tap-Text '保存' 'save-repository' $secret
    Assert-HomeCount 30 $secret

    $script:Stage = 'media'
    Verify-Media $secret

    $script:Stage = 'offline'
    Adb @('reverse', '--remove', "tcp:$DevicePort") | Out-Null
    $script:ReversePrepared = $false
    Refresh-Repository 30 $secret 'HOST_UNREACHABLE'
    Adb @('reverse', "tcp:$DevicePort", "tcp:$RelayPort") | Out-Null
    $script:ReversePrepared = $true
    Refresh-Repository 30 $secret

    $script:Stage = 'remote-add'
    $addCommand = 'set -e; find ''{0}'' -type f -iname ''*.jpg'' -exec cp -- ''{{}}'' ''{0}/m5-added.jpg'' '';'' -quit; test -f ''{0}/m5-added.jpg''' -f $wslStage
    Wsl $addCommand -Root | Out-Null
    Refresh-Repository 31 $secret
    $script:Stage = 'remote-remove'
    $removeCommand = 'set -e; case ''{0}/m5-added.jpg'' in /var/tmp/remophoto-smb-smoke-*/m5-added.jpg) rm -f -- ''{0}/m5-added.jpg'' ;; *) exit 91 ;; esac' -f $wslStage
    Wsl $removeCommand -Root | Out-Null
    Refresh-Repository 30 $secret

    $script:Stage = 'delete-repository'
    Delete-Repository $secret
    if ($script:SecretOccurrences -ne 0) { throw "Password appeared in UI tree: $script:SecretOccurrences occurrences" }
    @('# SMB Smoke Summary', '', '- Result: PASS', "- API: $api", '- Fixture images: 30', '- Initial sync: 30', '- Remote add: 31', '- Remote remove: 30', '- Offline/recovery: PASS', '- Static/GIF stream logs: PASS', '- UI tree secret occurrences: 0') |
        Out-File -LiteralPath $summaryFile -Encoding UTF8
    Log 'PASS'
    $result = 'PASS'
} catch {
    $reason = $_.Exception.Message
    Log "FAIL reason=$reason"
    @('# SMB Smoke Summary', '', '- Result: FAIL', "- Stage: $script:Stage", "- Reason: $reason", "- Evidence: $runDir") |
        Out-File -LiteralPath $summaryFile -Encoding UTF8
    $result = 'FAIL'
} finally {
    $script:Stage = 'cleanup'
    if ($script:ReversePrepared -and $script:AdbPath) { Adb @('reverse', '--remove', "tcp:$DevicePort") -AllowFailure | Out-Null }
    if ($script:RelayProcess -and -not $script:RelayProcess.HasExited) { Stop-Process -Id $script:RelayProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($script:ServerPrepared) {
        $cleanup = "set -e; systemctl stop smbd; test -f '$wslBackup' && cp '$wslBackup' /etc/samba/smb.conf; rm -f '$wslBackup'; case '$wslStage' in /var/tmp/remophoto-smb-smoke-*) rm -rf -- '$wslStage' ;; *) exit 92 ;; esac; testparm -s >/dev/null; systemctl start smbd"
        Wsl $cleanup -Root -AllowFailure | Out-Null
    }
    if ($script:KeepAliveProcess -and -not $script:KeepAliveProcess.HasExited) { Stop-Process -Id $script:KeepAliveProcess.Id -Force -ErrorAction SilentlyContinue }
}

Write-Output $result
if ($result -ne 'PASS') { exit 1 }
