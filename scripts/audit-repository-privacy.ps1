$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot

try {
    $trackedFiles = @(
        git -c core.quotePath=false ls-files --cached --others --exclude-standard |
            Sort-Object -Unique
    )
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to list tracked files."
    }

    $blockingRules = [ordered]@{
        PRIVATE_KEY = '-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'
        KNOWN_TOKEN = '(?:AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z_-]{30,}|sk-[A-Za-z0-9_-]{20,}|eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{20,})'
        CREDENTIAL_LITERAL = '(?i)(?:password|passwd|pwd|api[_-]?key|access[_-]?token|client[_-]?secret|private[_-]?key)\s*[:=]\s*["''][^"''$\r\n]{6,}["'']'
        EMAIL = '(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b'
        WINDOWS_USER_PATH = '(?i)\b[A-Z]:[\\/]+Users[\\/]+[^\\/\s]+'
        UNIX_USER_PATH = '(?i)(?:/Users|/home)/[^/\s]+'
        PHONE_LIKE = '(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)'
    }
    $noticeRules = [ordered]@{
        IPV4 = '(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])'
    }
    $riskyExtension = '(?i)(?:^|/)(?:local|signing|keystore|secrets)\.properties$|\.(?:jks|keystore|p12|pfx|pem|key|apk|aab|log|db|sqlite|sqlite3|wal|shm)$'

    $blockingHits = [System.Collections.Generic.List[object]]::new()
    $noticeHits = [System.Collections.Generic.List[object]]::new()

    foreach ($file in $trackedFiles) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            continue
        }
        if ($file -match $riskyExtension) {
            $blockingHits.Add([pscustomobject]@{
                Category = "RISKY_TRACKED_FILE"
                File = $file
                Line = 0
            })
        }

        if ($file -eq "scripts/audit-repository-privacy.ps1") {
            # The scanner contains its own detection signatures.
            continue
        }

        try {
            $lines = Get-Content -LiteralPath $file -Encoding utf8 -ErrorAction Stop
        } catch {
            continue
        }

        for ($index = 0; $index -lt $lines.Count; $index++) {
            foreach ($rule in $blockingRules.GetEnumerator()) {
                if ($lines[$index] -match $rule.Value) {
                    $blockingHits.Add([pscustomobject]@{
                        Category = $rule.Key
                        File = $file
                        Line = $index + 1
                    })
                }
            }
            foreach ($rule in $noticeRules.GetEnumerator()) {
                if ($lines[$index] -match $rule.Value) {
                    $noticeHits.Add([pscustomobject]@{
                        Category = $rule.Key
                        File = $file
                        Line = $index + 1
                    })
                }
            }
        }
    }

    $identityValues = @(
        $env:USERNAME
        $env:COMPUTERNAME
        $env:USERPROFILE
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($identity in $identityValues) {
        if ([string]::IsNullOrWhiteSpace([string]$identity)) {
            continue
        }
        # 仅匹配独立身份 token。避免本机用户名只是公开账号、链接或单词的一部分时误报，
        # 例如本机用户名 alice 不应命中公开账号 0alice0。
        $escapedIdentity = [regex]::Escape(([string]$identity).Trim())
        if ([string]::IsNullOrWhiteSpace($escapedIdentity)) {
            continue
        }
        $identityRegex = [regex]::new(
            "(?<![A-Za-z0-9])$escapedIdentity(?![A-Za-z0-9])",
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
        foreach ($file in $trackedFiles) {
            if ($file -eq "scripts/audit-repository-privacy.ps1") {
                continue
            }
            if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
                continue
            }
            $lineNumber = 0
            try {
                foreach ($line in Get-Content -LiteralPath $file -Encoding utf8 -ErrorAction Stop) {
                    $lineNumber++
                    if ($identityRegex.IsMatch([string]$line)) {
                        $blockingHits.Add([pscustomobject]@{
                            Category = "LOCAL_IDENTITY"
                            File = $file
                            Line = $lineNumber
                        })
                    }
                }
            } catch {
                continue
            }
        }
    }

    $blocking = @($blockingHits | Sort-Object Category, File, Line -Unique)
    $notices = @($noticeHits | Sort-Object Category, File, Line -Unique)

    Write-Output "[privacy-audit] repository files: $($trackedFiles.Count)"
    Write-Output "[privacy-audit] blocking findings: $($blocking.Count)"
    foreach ($hit in $blocking) {
        Write-Output "[blocking] $($hit.Category) $($hit.File):$($hit.Line)"
    }
    Write-Output "[privacy-audit] notices: $($notices.Count)"
    foreach ($hit in $notices) {
        Write-Output "[notice] $($hit.Category) $($hit.File):$($hit.Line)"
    }

    if ($blocking.Count -gt 0) {
        exit 1
    }
    Write-Output "[privacy-audit] PASS"
} finally {
    Pop-Location
}
