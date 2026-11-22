<#
.SYNOPSIS
    Builds the installer of Git Backup Desktop for the system this script runs on.

.DESCRIPTION
    1. mvn package            -> target/git-backup-desktop.jar and target/lib
    2. jpackage (app-image)   -> target/installer/Git Backup (Git Backup.app on macOS) with its own Java runtime
    3. jpackage (installer)   -> Windows: target\installer\Git Backup-<version>.exe
                                 Linux:   target/installer/GitBackup-<version>-linux-<arch>.deb
                                 macOS:   target/installer/GitBackup-<version>-macos-<arch>.dmg

    jpackage only builds for the system it runs on, so the Linux and macOS installers are made by
    .github/workflows/packages.yml on GitHub's own Linux and macOS machines.

    The installed program needs neither Java nor Git on the customer's computer.
    On Windows the installer is per-user (no administrator rights), adds a Start menu entry and a
    desktop shortcut, and upgrades an older version in place.

    Needs JDK 25+ (jpackage) and Maven. Step 3 also needs WiX Toolset 3.x on Windows, and
    fakeroot (deb) or rpm-build (rpm) on Linux. Runs in Windows PowerShell 5.1 and in PowerShell 7.

.EXAMPLE
    .\packaging\build-installer.ps1

.EXAMPLE
    .\packaging\build-installer.ps1 -Type app-image -SkipTests
    Only the runnable folder, without an installer (WiX not needed).
#>
[CmdletBinding()]
param(
    # Empty picks the usual installer of this system: exe, deb or dmg.
    [ValidateSet('', 'exe', 'msi', 'deb', 'rpm', 'dmg', 'pkg', 'app-image')]
    [string]$Type = '',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$target = Join-Path $project 'target'
$appName = 'Git Backup'
# Identifies the product to Windows Installer so a new version replaces the old one. Never change it.
$upgradeUuid = '6f1d2b0e-7c43-4f0a-9b1e-3a5c8d2e4f71'
# The parts of the Java runtime the program uses; keeps the installer small.
$modules = 'java.base,java.datatransfer,java.desktop,java.logging,java.management,java.naming,' +
    'java.net.http,java.prefs,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.xml,' +
    'jdk.charsets,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs'

# $IsMacOS does not exist in Windows PowerShell 5.1, where it reads as empty.
$onWindows = $env:OS -eq 'Windows_NT'
$onMac = (-not $onWindows) -and $IsMacOS
if ($onWindows) { $platform = 'windows' } elseif ($onMac) { $platform = 'macos' } else { $platform = 'linux' }
$allowed = @{ windows = @('exe', 'msi'); linux = @('deb', 'rpm'); macos = @('dmg', 'pkg') }[$platform]
if (-not $Type) { $Type = $allowed[0] }
if ($Type -ne 'app-image' -and $allowed -notcontains $Type) {
    throw "-Type $Type cannot be built on $platform. Here: $($allowed -join ', ') or app-image."
}
$icon = Join-Path $PSScriptRoot @{ windows = 'app.ico'; linux = 'app.png'; macos = 'app.icns' }[$platform]

function Invoke-Tool {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe failed with exit code $LASTEXITCODE." }
}

# --- 1. build
# jpackage leaves its launcher and installer read-only, and from JDK 25 on Java no longer deletes a
# read-only file on Windows: 'mvn clean' would stop at the installer of the previous build.
if ($onWindows -and (Test-Path -LiteralPath $target)) {
    Get-ChildItem -LiteralPath $target -Recurse -File -Force | Where-Object { $_.IsReadOnly } |
        ForEach-Object { $_.IsReadOnly = $false }
}
$mvn = 'mvn'
if ($onWindows) { $mvn = 'mvn.cmd' }
$mvnArgs = @('-B', '-q', '-f', (Join-Path $project 'pom.xml'), 'clean', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
Write-Host 'Building the application...'
Invoke-Tool $mvn $mvnArgs

[xml]$pom = Get-Content -LiteralPath (Join-Path $project 'pom.xml') -Raw
$version = ($pom.project.version -replace '-SNAPSHOT$', '')
# macOS refuses a version that starts with 0, so 0.x.y is packaged there as 1.x.y. The program
# itself reads its real version from gitbackup.version (MainApp), which is what the updater compares.
$packageVersion = $version
if ($onMac -and $version -match '^0\.(.+)$') { $packageVersion = '1.' + $Matches[1] }

$stage = Join-Path $target 'jpackage-input'
New-Item -ItemType Directory -Path (Join-Path $stage 'lib') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $target 'git-backup-desktop.jar') -Destination $stage
Copy-Item -Path (Join-Path (Join-Path $target 'lib') '*') -Destination (Join-Path $stage 'lib')

# --- 2. runnable folder with its own Java runtime
$dest = Join-Path $target 'installer'
Write-Host 'Creating the application folder...'
$imageArgs = @(
    '--type', 'app-image',
    '--name', $appName,
    '--app-version', $packageVersion,
    '--vendor', $appName,
    '--input', $stage,
    '--main-jar', 'git-backup-desktop.jar',
    '--main-class', 'com.gitbackup.Launcher',
    '--icon', $icon,
    '--add-modules', $modules,
    '--jlink-options', '--strip-debug --no-header-files --no-man-pages',
    '--java-options', '--enable-native-access=ALL-UNNAMED',
    '--java-options', "-Dgitbackup.version=$version",
    '--dest', $dest
)
if ($onMac) {
    $imageArgs += @('--mac-package-identifier', 'com.gitbackup.desktop', '--mac-package-name', $appName)
}
Invoke-Tool 'jpackage' $imageArgs
$image = Join-Path $dest $appName
if ($onMac) { $image += '.app' }
Write-Host "Application folder: $image"
if ($Type -eq 'app-image') { return }

# --- 3. installer
if ($onWindows -and -not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    $wix = Get-ChildItem 'C:\Program Files (x86)' -Directory -Filter 'WiX Toolset v3*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $wix) { throw 'WiX Toolset 3.x was not found. Install it from https://wixtoolset.org or use -Type app-image.' }
    $env:PATH = (Join-Path $wix.FullName 'bin') + ';' + $env:PATH
}
Write-Host 'Creating the installer...'
$installerArgs = @(
    '--type', $Type,
    '--app-image', $image,
    '--name', $appName,
    '--app-version', $packageVersion,
    '--vendor', $appName,
    '--icon', $icon,
    '--dest', $dest
)
if ($onWindows) {
    $installerArgs += @(
        '--win-per-user-install',
        '--win-dir-chooser',
        '--win-menu',
        '--win-menu-group', $appName,
        '--win-shortcut',
        '--win-upgrade-uuid', $upgradeUuid
    )
} elseif (-not $onMac) {
    $installerArgs += @(
        '--linux-package-name', 'git-backup',
        '--linux-shortcut',
        '--linux-menu-group', 'Utility',
        '--linux-app-category', 'utils'
    )
}
Invoke-Tool 'jpackage' $installerArgs

# The Windows installer is named by packaging\release.ps1 when it is published. The others get
# their published name here: jpackage's own names differ per system and hide the real version on macOS.
if (-not $onWindows) {
    $arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
    Get-ChildItem -LiteralPath $dest -File | Where-Object { $_.Extension -eq ".$Type" } |
        Rename-Item -NewName "GitBackup-$version-$platform-$arch.$Type"
}
Get-ChildItem -LiteralPath $dest -File | ForEach-Object {
    Write-Host ('Installer: {0} ({1:N1} MB)' -f $_.FullName, ($_.Length / 1MB))
}
