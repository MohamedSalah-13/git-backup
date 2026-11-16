<#
.SYNOPSIS
    The checks run before every push (called by .githooks/pre-push):
      1. every PowerShell script parses,
      2. backup-gui.ps1 still has its UTF-8 BOM,
      3. the desktop app builds and its tests pass.
    Exits with 1 when any of them fails, which makes git refuse the push.
#>
$ErrorActionPreference = 'Stop'
$root = (& git rev-parse --show-toplevel).Trim()
Set-Location -LiteralPath $root
$problems = @()

Write-Host 'pre-push: checking PowerShell scripts...'
# Tracked files plus new ones not yet added; ignored folders (work, target) are left out.
$scripts = @(& git ls-files --cached --others --exclude-standard -- '*.ps1') | Where-Object { Test-Path -LiteralPath $_ }
foreach ($script in $scripts) {
    $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $root $script), [ref]$null, [ref]$errors)
    foreach ($e in @($errors)) {
        $problems += "${script}:$($e.Extent.StartLineNumber): $($e.Message)"
    }
}

# Without the BOM, Windows PowerShell misreads the Arabic text of the window.
$gui = Join-Path $root 'backup-gui.ps1'
if (Test-Path -LiteralPath $gui) {
    $bytes = [System.IO.File]::ReadAllBytes($gui)
    if (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) {
        $problems += 'backup-gui.ps1: the UTF-8 BOM is missing; save the file as "UTF-8 with BOM".'
    }
}

$pom = Join-Path $root 'desktop\pom.xml'
if (Test-Path -LiteralPath $pom) {
    Write-Host 'pre-push: building the desktop app and running its tests (about a minute)...'
    $log = Join-Path ([System.IO.Path]::GetTempPath()) 'git-backup-pre-push.log'
    # Through cmd so Maven's warnings on stderr do not stop this script.
    & cmd /c "mvn -B -ntp -q -f `"$pom`" verify > `"$log`" 2>&1"
    if ($LASTEXITCODE -ne 0) {
        $problems += "desktop: build or tests failed. Full output: $log"
        Get-Content -LiteralPath $log | Where-Object { $_ -match '^\[ERROR\]' } | Select-Object -First 15 |
            ForEach-Object { Write-Host "  $_" }
    }
}

if ($problems.Count -gt 0) {
    Write-Host ''
    Write-Host 'pre-push: PUSH REFUSED' -ForegroundColor Red
    foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
    Write-Host 'Fix the problems above, or push anyway with: git push --no-verify'
    exit 1
}
Write-Host 'pre-push: all checks passed.' -ForegroundColor Green
exit 0
