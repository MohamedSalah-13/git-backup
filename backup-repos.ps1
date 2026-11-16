<#
.SYNOPSIS
    Backs up every repository of a GitHub account as one ZIP per repository.

.DESCRIPTION
    For each repository the script compares the remote branches and tags with
    the state saved by the previous run:
      - unchanged and the ZIP is still there  -> skipped
      - new / changed / ZIP missing           -> a new ZIP is built, verified,
        copied to the destination, and only then older versions are pruned.

    Each ZIP contains:
      source\          files of the default branch
      <repo>.bundle    full history (all commits, branches and tags)
      RESTORE.txt      how to restore

    Requires git and the GitHub CLI (gh), logged in with "gh auth login".
    Settings are read from config.json next to this script.

.EXAMPLE
    .\backup-repos.ps1
    Back up everything that changed since the last run.

.EXAMPLE
    .\backup-repos.ps1 -DryRun
    Only report what would be backed up.

.EXAMPLE
    .\backup-repos.ps1 -Only sunbula,clinic-* -Force
    Rebuild the backups of the matching repositories even if unchanged.
#>
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$Destination,
    [string[]]$Only,
    [switch]$Force,
    [switch]$DryRun,
    # Prints the repositories and their backup status as JSON, then stops (used by backup-gui.ps1).
    [switch]$List
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$script:LogFile = $null
$script:GitExe = $null
$script:GhExe = $null
$script:Quiet = $false
# Dates in file names and logs must not depend on the Windows regional calendar.
$script:Inv = [System.Globalization.CultureInfo]::InvariantCulture

# ---------------------------------------------------------------- helpers ---

function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $line = '{0} [{1,-5}] {2}' -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss', $script:Inv), $Level, $Message
    $color = 'Gray'
    switch ($Level) {
        'ERROR' { $color = 'Red' }
        'WARN'  { $color = 'Yellow' }
        'OK'    { $color = 'Green' }
    }
    if (-not $script:Quiet -or $Level -eq 'ERROR') { Write-Host $line -ForegroundColor $color }
    if ($script:LogFile) {
        Add-Content -LiteralPath $script:LogFile -Value $line -Encoding UTF8
    }
}

function ConvertTo-NativeArg {
    param([string]$Value)
    if ($Value -ne '' -and $Value -notmatch '[\s"]') { return $Value }
    $escaped = $Value -replace '(\\*)"', '$1$1\"'
    $escaped = $escaped -replace '(\\+)$', '$1$1'
    return '"' + $escaped + '"'
}

# Runs a program and captures its output without PowerShell's stderr quirks.
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = (@($Arguments | ForEach-Object { ConvertTo-NativeArg $_ })) -join ' '
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    [void]$proc.Start()
    $errTask = $proc.StandardError.ReadToEndAsync()
    $out = $proc.StandardOutput.ReadToEnd()
    $proc.WaitForExit()
    $result = New-Object psobject -Property @{
        ExitCode = $proc.ExitCode
        StdOut   = $out
        StdErr   = $errTask.Result
    }
    $proc.Dispose()
    return $result
}

function Invoke-Git {
    param([string[]]$Arguments)
    $r = Invoke-Native $script:GitExe $Arguments
    if ($r.ExitCode -ne 0) {
        $tail = (@($r.StdErr.Trim() -split "`r?`n" | Where-Object { $_ } | Select-Object -Last 3)) -join ' | '
        $what = (@($Arguments | Where-Object { $_ -notmatch '^(-C|[A-Za-z]:\\|https://)' } | Select-Object -First 2)) -join ' '
        throw "git $what failed (exit $($r.ExitCode)): $tail"
    }
    return $r
}

function Get-Setting {
    param($Object, [string]$Name, $Default)
    if ($Object) {
        $p = $Object.PSObject.Properties[$Name]
        if ($p -and $null -ne $p.Value) { return $p.Value }
    }
    return $Default
}

function Resolve-ConfigPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return [System.IO.Path]::GetFullPath($Path) }
    return [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $Path))
}

function Read-State {
    param([string]$Path)
    $state = @{}
    if (Test-Path -LiteralPath $Path) {
        try {
            $doc = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($Path))
            $repos = Get-Setting $doc 'repos' $null
            if ($repos) {
                foreach ($p in $repos.PSObject.Properties) { $state[$p.Name] = $p.Value }
            }
        } catch {
            Write-Log "State file is unreadable; every repository will be backed up again. ($($_.Exception.Message))" 'WARN'
        }
    }
    return $state
}

function Write-State {
    param([string]$Path, [hashtable]$State)
    $repos = [ordered]@{}
    foreach ($key in @($State.Keys | Sort-Object)) { $repos[$key] = $State[$key] }
    $doc = [ordered]@{ version = 1; updated = (Get-Date).ToString('s', $script:Inv); repos = $repos }
    $json = $doc | ConvertTo-Json -Depth 5
    $tmp = "$Path.tmp"
    [System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $tmp -Destination $Path -Force
}

# Every repository of the given owners (empty = the signed-in account).
function Get-AccountRepos {
    param([string[]]$Owners)
    $fields = 'name,nameWithOwner,isFork,isArchived,isEmpty,isPrivate,pushedAt,diskUsage,defaultBranchRef'
    $repos = New-Object System.Collections.ArrayList
    $ownerList = @($Owners | Where-Object { $_ })
    if ($ownerList.Count -eq 0) { $ownerList = @('') }
    foreach ($owner in $ownerList) {
        $ghArgs = @('repo', 'list')
        if ($owner) { $ghArgs += $owner }
        $ghArgs += @('--limit', '5000', '--json', $fields)
        $r = Invoke-Native $script:GhExe $ghArgs
        if ($r.ExitCode -ne 0) { throw "Could not list repositories (is GitHub CLI signed in? run: gh auth login): $($r.StdErr.Trim())" }
        foreach ($item in (ConvertFrom-Json -InputObject $r.StdOut)) { [void]$repos.Add($item) }
    }
    return , $repos
}

# True when the repository name (or owner/name) matches one of the patterns; * is allowed.
function Test-RepoMatch {
    param($Repo, [string[]]$Patterns)
    foreach ($pattern in $Patterns) {
        if ($Repo.name -like $pattern -or $Repo.nameWithOwner -like $pattern) { return $true }
    }
    return $false
}

# Escapes non-ASCII characters so the JSON survives any console code page.
function ConvertTo-AsciiJson {
    param([string]$Json)
    return [regex]::Replace($Json, '[^\x00-\x7F]', { param($m) '\u{0:x4}' -f [int][char]$m.Value })
}

# A hash of the default branch name plus every branch and tag tip.
# Returns $null for a repository that has no commits.
function Get-RemoteFingerprint {
    param([string]$Url, [string]$Branch)
    $r = Invoke-Git @('ls-remote', '--heads', '--tags', $Url)
    $lines = @($r.StdOut -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($lines.Count -eq 0) { return $null }
    $text = "default:$Branch`n" + ($lines -join "`n")
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($text))
    } finally {
        $sha.Dispose()
    }
    return ([System.BitConverter]::ToString($hash) -replace '-', '').ToLower()
}

# Keeps a local bare copy (branches + tags) so later runs only download what is new.
function Sync-Mirror {
    param([string]$Url, [string]$Mirror, [string]$Branch)
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        try {
            if (-not (Test-Path -LiteralPath (Join-Path $Mirror 'HEAD'))) {
                if (Test-Path -LiteralPath $Mirror) { Remove-Item -LiteralPath $Mirror -Recurse -Force }
                New-Item -ItemType Directory -Path $Mirror -Force | Out-Null
                [void](Invoke-Git @('init', '--bare', '--quiet', $Mirror))
                [void](Invoke-Git @('-C', $Mirror, 'remote', 'add', 'origin', $Url))
                [void](Invoke-Git @('-C', $Mirror, 'config', 'remote.origin.fetch', '+refs/heads/*:refs/heads/*'))
                [void](Invoke-Git @('-C', $Mirror, 'config', '--add', 'remote.origin.fetch', '+refs/tags/*:refs/tags/*'))
            }
            [void](Invoke-Git @('-C', $Mirror, 'fetch', '--prune', '--force', '--quiet', 'origin'))
            [void](Invoke-Git @('-C', $Mirror, 'symbolic-ref', 'HEAD', "refs/heads/$Branch"))
            return
        } catch {
            if ($attempt -eq 2) { throw }
            Write-Log "  local copy problem, downloading again: $($_.Exception.Message)" 'WARN'
            if (Test-Path -LiteralPath $Mirror) { Remove-Item -LiteralPath $Mirror -Recurse -Force }
        }
    }
}

# Builds <OutZip> = source\ snapshot + <Name>.bundle + RESTORE.txt, then verifies it.
function New-RepoZip {
    param(
        [string]$Mirror, [string]$Name, [string]$FullName,
        [string]$Branch, [string]$Head, [string]$OutZip, [string]$TmpDir
    )
    $srcZip = Join-Path $TmpDir "$Name.src.zip"
    $bundle = Join-Path $TmpDir "$Name.bundle"
    foreach ($f in @($srcZip, $bundle, $OutZip)) {
        if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force }
    }
    try {
        # Uncompressed snapshot: it is re-packed below, so compressing here is wasted work.
        [void](Invoke-Git @('-C', $Mirror, 'archive', '--format=zip', '-0', '-o', $srcZip, 'HEAD'))
        [void](Invoke-Git @('-C', $Mirror, 'bundle', 'create', $bundle, '--all'))
        [void](Invoke-Git @('-C', $Mirror, 'bundle', 'verify', $bundle))

        $readme = @"
Backup of $FullName
Created : $((Get-Date).ToString('yyyy-MM-dd HH:mm', $script:Inv))
Branch  : $Branch @ $Head

Contents
  source\
      Files of the default branch at the time of the backup.
  $Name.bundle
      Full history: every commit, branch and tag.

Restore the full repository (history included)
  git clone $Name.bundle $Name
  cd $Name
  git remote set-url origin https://github.com/$FullName.git

Restore as an exact mirror (all branches as local branches)
  git clone --mirror $Name.bundle $Name.git
"@ -replace "`r?`n", "`r`n"

        $expected = 0
        $out = [System.IO.Compression.ZipFile]::Open($OutZip, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            $in = [System.IO.Compression.ZipFile]::OpenRead($srcZip)
            try {
                foreach ($entry in $in.Entries) {
                    $target = $out.CreateEntry('source/' + $entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
                    $target.LastWriteTime = $entry.LastWriteTime
                    $expected++
                    if ($entry.FullName.EndsWith('/')) { continue }
                    $reader = $entry.Open()
                    try {
                        $writer = $target.Open()
                        try { $reader.CopyTo($writer) } finally { $writer.Dispose() }
                    } finally {
                        $reader.Dispose()
                    }
                }
            } finally {
                $in.Dispose()
            }
            # The bundle is already compressed, so store it as-is.
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $out, $bundle, "$Name.bundle", [System.IO.Compression.CompressionLevel]::NoCompression)
            $note = $out.CreateEntry('RESTORE.txt', [System.IO.Compression.CompressionLevel]::Optimal)
            $sw = New-Object System.IO.StreamWriter($note.Open(), (New-Object System.Text.UTF8Encoding($false)))
            try { $sw.Write($readme) } finally { $sw.Dispose() }
            $expected += 2
        } finally {
            $out.Dispose()
        }

        # Verify before anything older is touched.
        $bundleSize = (Get-Item -LiteralPath $bundle).Length
        $check = [System.IO.Compression.ZipFile]::OpenRead($OutZip)
        try {
            if ($check.Entries.Count -ne $expected) {
                throw "ZIP check failed: $($check.Entries.Count) entries, expected $expected."
            }
            $b = $check.GetEntry("$Name.bundle")
            if (-not $b -or $b.Length -ne $bundleSize) {
                throw 'ZIP check failed: the history bundle is missing or incomplete.'
            }
        } finally {
            $check.Dispose()
        }
        return ($expected - 2)
    } finally {
        foreach ($f in @($srcZip, $bundle)) {
            if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force }
        }
    }
}

# Deletes older ZIPs of one repository, keeping $Keep versions including $Current.
# Only touches files that match this script's own naming pattern.
function Remove-OldBackups {
    param([string]$RepoDir, [string]$Name, [string]$Current, [int]$Keep)
    $pattern = '^' + [regex]::Escape($Name) + '_\d{4}-\d{2}-\d{2}_\d{6}_[0-9a-f]{7,40}\.zip$'
    $older = @(Get-ChildItem -LiteralPath $RepoDir -File |
        Where-Object { $_.Name -match $pattern -and $_.Name -ne $Current } |
        Sort-Object Name -Descending)
    $removed = 0
    foreach ($file in @($older | Select-Object -Skip ([Math]::Max(0, $Keep - 1)))) {
        Remove-Item -LiteralPath $file.FullName -Force
        $removed++
    }
    return $removed
}

# ------------------------------------------------------------------- main ---

$exitCode = 0
$mutex = $null
$hasLock = $false
$envNames = @('GIT_CONFIG_COUNT', 'GIT_CONFIG_KEY_0', 'GIT_CONFIG_VALUE_0', 'GIT_CONFIG_KEY_1',
    'GIT_CONFIG_VALUE_1', 'GIT_CONFIG_KEY_2', 'GIT_CONFIG_VALUE_2',
    'GIT_TERMINAL_PROMPT', 'GCM_INTERACTIVE', 'GIT_LFS_SKIP_SMUDGE')
$savedEnv = @{}
foreach ($n in $envNames) { $savedEnv[$n] = [Environment]::GetEnvironmentVariable($n, 'Process') }

try {
    # --- settings
    if (-not $ConfigPath) { $ConfigPath = Join-Path $PSScriptRoot 'config.json' }
    $config = $null
    if (Test-Path -LiteralPath $ConfigPath) {
        $config = ConvertFrom-Json -InputObject (Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8)
    }
    $keepVersions = [Math]::Max(1, [int](Get-Setting $config 'keepVersions' 3))
    $owners = @(@(Get-Setting $config 'owners' @()) | Where-Object { $_ })
    $exclude = @(@(Get-Setting $config 'exclude' @()) | Where-Object { $_ })
    # Empty = every repository. Otherwise only the listed ones are backed up.
    $include = @(@(Get-Setting $config 'include' @()) | Where-Object { $_ })
    $includeForks = [bool](Get-Setting $config 'includeForks' $false)
    $includeArchived = [bool](Get-Setting $config 'includeArchived' $true)
    $keepMirrors = [bool](Get-Setting $config 'keepMirrors' $true)
    $Only = @($Only | Where-Object { $_ } | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

    $workDir = [string](Get-Setting $config 'workDir' '')
    if (-not $workDir) { $workDir = 'work' }
    $workDir = Resolve-ConfigPath $workDir
    $mirrorsDir = Join-Path $workDir 'mirrors'
    $tmpDir = Join-Path $workDir 'tmp'
    $logDir = Join-Path $workDir 'logs'
    foreach ($d in @($mirrorsDir, $tmpDir, $logDir)) {
        if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
    }
    $script:LogFile = Join-Path $logDir ('backup-{0}.log' -f (Get-Date).ToString('yyyy-MM', $script:Inv))
    if ($List) { $script:Quiet = $true }

    # --- tools
    $git = Get-Command git -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    $gh = Get-Command gh -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $git) { throw 'git was not found. Install Git for Windows.' }
    if (-not $gh) { throw 'GitHub CLI (gh) was not found. Install it from https://cli.github.com and run: gh auth login' }
    $script:GitExe = $git.Source
    $script:GhExe = $gh.Source

    # --- repositories in the account, after the fork / archived / exclude filters
    $repos = Get-AccountRepos $owners
    $allNames = @($repos | ForEach-Object { $_.nameWithOwner })
    $candidates = New-Object System.Collections.ArrayList
    foreach ($repo in ($repos | Sort-Object nameWithOwner)) {
        if ($repo.isFork -and -not $includeForks) { continue }
        if ($repo.isArchived -and -not $includeArchived) { continue }
        if (Test-RepoMatch $repo $exclude) { continue }
        [void]$candidates.Add($repo)
    }

    # --- list mode: print what the window needs and stop
    if ($List) {
        $destText = $Destination
        if (-not $destText) { $destText = [string](Get-Setting $config 'destination' '') }
        $destPath = $null
        $destFound = $false
        if ($destText) {
            try {
                if ($Destination) { $destPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination) }
                else { $destPath = Resolve-ConfigPath $destText }
                $destFound = Test-Path -LiteralPath $destPath
            } catch {
                $destFound = $false
            }
        }
        $state = @{}
        if ($destFound) { $state = Read-State (Join-Path $destPath 'backup-state.json') }

        $items = New-Object System.Collections.ArrayList
        foreach ($repo in $candidates) {
            $full = [string]$repo.nameWithOwner
            $owner, $name = $full -split '/', 2
            $pushed = $null
            $pushedText = ''
            try {
                if ($repo.pushedAt -is [datetime]) { $pushed = $repo.pushedAt.ToUniversalTime() }
                elseif ($repo.pushedAt) {
                    $pushed = [datetime]::Parse([string]$repo.pushedAt, $script:Inv, [System.Globalization.DateTimeStyles]::AdjustToUniversal)
                }
                if ($pushed) { $pushedText = $pushed.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", $script:Inv) }
            } catch {
                $pushed = $null
            }
            # never = no backup yet, missing = its ZIP is gone, changed = pushed after the last backup.
            # This is a quick hint for the window; the real run compares branch and tag tips.
            $status = 'never'
            $lastBackup = ''
            $lastFile = ''
            if ($state.ContainsKey($full)) {
                $entry = $state[$full]
                $when = Get-Setting $entry 'backedUp' ''
                if ($when -is [datetime]) { $lastBackup = $when.ToString('s', $script:Inv) } else { $lastBackup = [string]$when }
                $lastFile = [string](Get-Setting $entry 'file' '')
                $status = 'current'
                $zipPath = Join-Path (Join-Path (Join-Path $destPath $owner) $name) $lastFile
                if (-not $lastFile -or -not (Test-Path -LiteralPath $zipPath)) { $status = 'missing' }
                elseif ($pushed -and $lastBackup) {
                    try {
                        if ($pushed -gt [datetime]::Parse($lastBackup, $script:Inv).ToUniversalTime()) { $status = 'changed' }
                    } catch { }
                }
            }
            $isSelected = $true
            if ($include.Count -gt 0) { $isSelected = Test-RepoMatch $repo $include }
            [void]$items.Add([ordered]@{
                    nameWithOwner = $full
                    name          = $name
                    isPrivate     = [bool]$repo.isPrivate
                    isArchived    = [bool]$repo.isArchived
                    isFork        = [bool]$repo.isFork
                    sizeKB        = [int64]$repo.diskUsage
                    pushedAt      = $pushedText
                    selected      = [bool]$isSelected
                    status        = $status
                    lastBackup    = $lastBackup
                    lastFile      = $lastFile
                })
        }
        $doc = [ordered]@{
            destination      = $destText
            destinationFound = $destFound
            keepVersions     = $keepVersions
            logFile          = $script:LogFile
            repos            = $items.ToArray()
        }
        Write-Output (ConvertTo-AsciiJson (ConvertTo-Json $doc -Depth 4 -Compress))
        return
    }

    # --- one run at a time
    $mutex = New-Object System.Threading.Mutex($false, 'GitHubRepoBackupScript')
    try { $hasLock = $mutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $hasLock = $true }
    if (-not $hasLock) { throw 'Another backup run is still in progress.' }

    Write-Log '=== GitHub backup started ==='

    # --- destination
    if ($Destination) {
        $Destination = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Destination)
    } else {
        $Destination = [string](Get-Setting $config 'destination' '')
        if (-not $Destination) { throw "No destination set. Edit 'destination' in $ConfigPath or pass -Destination." }
        $Destination = Resolve-ConfigPath $Destination
    }
    $destRoot = [System.IO.Path]::GetPathRoot($Destination)
    if (-not (Test-Path -LiteralPath $destRoot)) {
        throw "Drive $destRoot is not available. If the destination is Google Drive, make sure Google Drive for desktop is running."
    }
    if (-not (Test-Path -LiteralPath $Destination)) {
        $parent = Split-Path -Parent $Destination
        if (-not (Test-Path -LiteralPath $parent)) {
            throw "Folder '$parent' does not exist. Check 'destination' in $ConfigPath."
        }
        if (-not $DryRun) { New-Item -ItemType Directory -Path $Destination | Out-Null }
    }
    Write-Log "Destination: $Destination (keeping $keepVersions version(s) per repository)"

    # --- sign-in
    $tokenResult = Invoke-Native $script:GhExe @('auth', 'token')
    $token = $tokenResult.StdOut.Trim()
    if ($tokenResult.ExitCode -ne 0 -or -not $token) { throw 'GitHub CLI is not signed in. Run: gh auth login' }
    # The token only lives in this process's environment: never on disk, never on a command line.
    $basic = [Convert]::ToBase64String([System.Text.Encoding]::ASCII.GetBytes("x-access-token:$token"))
    $env:GIT_CONFIG_COUNT = '3'
    $env:GIT_CONFIG_KEY_0 = 'http.https://github.com/.extraheader'
    $env:GIT_CONFIG_VALUE_0 = "AUTHORIZATION: basic $basic"
    $env:GIT_CONFIG_KEY_1 = 'core.autocrlf'
    $env:GIT_CONFIG_VALUE_1 = 'false'
    $env:GIT_CONFIG_KEY_2 = 'core.longpaths'
    $env:GIT_CONFIG_VALUE_2 = 'true'
    $env:GIT_TERMINAL_PROMPT = '0'
    $env:GCM_INTERACTIVE = 'never'
    $env:GIT_LFS_SKIP_SMUDGE = '1'
    $token = $null; $basic = $null

    # --- which repositories this run covers: -Only wins, then the 'include' list, else all
    $selected = New-Object System.Collections.ArrayList
    foreach ($repo in $candidates) {
        if ($Only.Count -gt 0) {
            if (-not (Test-RepoMatch $repo $Only)) { continue }
        } elseif ($include.Count -gt 0) {
            if (-not (Test-RepoMatch $repo $include)) { continue }
        }
        [void]$selected.Add($repo)
    }
    Write-Log "Repositories: $($selected.Count) selected of $($repos.Count) in the account"

    $statePath = Join-Path $Destination 'backup-state.json'
    $state = Read-State $statePath
    $count = @{ backedUp = 0; unchanged = 0; empty = 0; failed = 0; pruned = 0 }

    # --- per repository
    foreach ($repo in $selected) {
        $full = [string]$repo.nameWithOwner
        $owner, $name = $full -split '/', 2
        $url = "https://github.com/$full.git"
        $tmpZip = $null
        try {
            $branch = $null
            if ($repo.defaultBranchRef) { $branch = [string]$repo.defaultBranchRef.name }
            $fingerprint = $null
            if (-not $repo.isEmpty -and $branch) { $fingerprint = Get-RemoteFingerprint $url $branch }
            if (-not $fingerprint) {
                $count.empty++
                Write-Log "$full : empty repository, nothing to back up"
                continue
            }

            $repoDir = Join-Path (Join-Path $Destination $owner) $name
            $previous = $null
            if ($state.ContainsKey($full)) { $previous = $state[$full] }
            $reason = $null
            if ($Force) { $reason = 'forced' }
            elseif (-not $previous) { $reason = 'first backup' }
            elseif ([string](Get-Setting $previous 'fingerprint' '') -ne $fingerprint) { $reason = 'changed on GitHub' }
            else {
                $existing = Join-Path $repoDir ([string](Get-Setting $previous 'file' 'missing'))
                if (-not (Test-Path -LiteralPath $existing)) { $reason = 'backup file is missing' }
                elseif ((Get-Item -LiteralPath $existing).Length -ne [int64](Get-Setting $previous 'size' -1)) {
                    $reason = 'backup file size differs'
                }
            }
            if (-not $reason) {
                $count.unchanged++
                Write-Log "$full : unchanged, skipped"
                continue
            }
            if ($DryRun) {
                $count.backedUp++
                Write-Log "$full : would be backed up ($reason)" 'OK'
                continue
            }

            Write-Log "$full : backing up ($reason)"
            $mirror = Join-Path (Join-Path $mirrorsDir $owner) "$name.git"
            Sync-Mirror $url $mirror $branch
            $head = (Invoke-Git @('-C', $mirror, 'rev-parse', '--short=7', 'HEAD')).StdOut.Trim()
            $zipName = '{0}_{1}_{2}.zip' -f $name, (Get-Date).ToString('yyyy-MM-dd_HHmmss', $script:Inv), $head
            $tmpZip = Join-Path $tmpDir $zipName
            $files = New-RepoZip -Mirror $mirror -Name $name -FullName $full -Branch $branch -Head $head -OutZip $tmpZip -TmpDir $tmpDir
            $size = (Get-Item -LiteralPath $tmpZip).Length

            # Copy under a temporary name so a half-written file is never mistaken for a backup.
            if (-not (Test-Path -LiteralPath $repoDir)) { New-Item -ItemType Directory -Path $repoDir -Force | Out-Null }
            Get-ChildItem -LiteralPath $repoDir -File -Filter '*.partial' | Remove-Item -Force
            $partial = Join-Path $repoDir "$zipName.partial"
            Copy-Item -LiteralPath $tmpZip -Destination $partial -Force
            if ((Get-Item -LiteralPath $partial).Length -ne $size) { throw 'Copy to the destination is incomplete.' }
            $final = Join-Path $repoDir $zipName
            if (Test-Path -LiteralPath $final) { Remove-Item -LiteralPath $final -Force }
            Rename-Item -LiteralPath $partial -NewName $zipName

            $state[$full] = [ordered]@{
                fingerprint = $fingerprint
                branch      = $branch
                head        = $head
                file        = $zipName
                size        = $size
                backedUp    = (Get-Date).ToString('s', $script:Inv)
            }
            Write-State $statePath $state

            # The new backup is safely in place: now older versions can go.
            $removed = Remove-OldBackups -RepoDir $repoDir -Name $name -Current $zipName -Keep $keepVersions
            $count.pruned += $removed
            $count.backedUp++
            if ($size -ge 1MB) { $sizeText = '{0:N1} MB' -f ($size / 1MB) } else { $sizeText = '{0:N0} KB' -f ($size / 1KB) }
            $extra = ''
            if ($removed -gt 0) { $extra = ", removed $removed old version(s)" }
            Write-Log "$full : saved $zipName ($files files, $sizeText)$extra" 'OK'

            if (-not $keepMirrors -and (Test-Path -LiteralPath $mirror)) {
                Remove-Item -LiteralPath $mirror -Recurse -Force
            }
        } catch {
            $count.failed++
            Write-Log "$full : FAILED - $($_.Exception.Message)" 'ERROR'
        } finally {
            if ($tmpZip -and (Test-Path -LiteralPath $tmpZip)) { Remove-Item -LiteralPath $tmpZip -Force }
        }
    }

    # --- repositories that disappeared from the account: keep their backups, just say so
    if ($Only.Count -eq 0) {
        foreach ($key in @($state.Keys | Sort-Object)) {
            if ($allNames -notcontains $key) {
                Write-Log "$key : no longer in the account; its backups were kept" 'WARN'
            }
        }
    }

    $verb = 'backed up'
    if ($DryRun) { $verb = 'to back up' }
    $summary = "Done: $($count.backedUp) $verb, $($count.unchanged) unchanged, $($count.empty) empty, $($count.failed) failed, $($count.pruned) old version(s) removed"
    if ($count.failed -gt 0) {
        Write-Log $summary 'WARN'
        $exitCode = 1
    } else {
        Write-Log $summary 'OK'
    }
} catch {
    Write-Log $_.Exception.Message 'ERROR'
    $exitCode = 2
} finally {
    foreach ($n in $envNames) { [Environment]::SetEnvironmentVariable($n, $savedEnv[$n], 'Process') }
    if ($mutex) {
        if ($hasLock) { $mutex.ReleaseMutex() }
        $mutex.Dispose()
    }
}

exit $exitCode
