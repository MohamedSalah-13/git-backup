<#
.SYNOPSIS
    Builds the Windows installer of Git Backup Desktop.

.DESCRIPTION
    1. mvn package            -> target\git-backup-desktop.jar and target\lib
    2. jpackage (app-image)   -> target\installer\Git Backup\Git Backup.exe with its own Java runtime
    3. jpackage (exe or msi)  -> target\installer\Git Backup-<version>.exe

    The installed program needs neither Java nor Git on the customer's computer.
    The installer is per-user (no administrator rights), adds a Start menu entry and a
    desktop shortcut, and upgrades an older version in place.

    Needs JDK 25+ (jpackage), Maven, and WiX Toolset 3.x for step 3.

.EXAMPLE
    .\packaging\build-installer.ps1

.EXAMPLE
    .\packaging\build-installer.ps1 -Type app-image -SkipTests
    Only the runnable folder, without an installer (WiX not needed).
#>
[CmdletBinding()]
param(
    [ValidateSet('exe', 'msi', 'app-image')]
    [string]$Type = 'exe',
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

function Invoke-Tool {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe failed with exit code $LASTEXITCODE." }
}

# --- 1. build
$mvnArgs = @('-B', '-q', '-f', (Join-Path $project 'pom.xml'), 'clean', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
Write-Host 'Building the application...'
Invoke-Tool 'mvn.cmd' $mvnArgs

[xml]$pom = Get-Content -LiteralPath (Join-Path $project 'pom.xml') -Raw
$version = ($pom.project.version -replace '-SNAPSHOT$', '')

$stage = Join-Path $target 'jpackage-input'
New-Item -ItemType Directory -Path (Join-Path $stage 'lib') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $target 'git-backup-desktop.jar') -Destination $stage
Copy-Item -Path (Join-Path $target 'lib\*') -Destination (Join-Path $stage 'lib')

# --- 2. runnable folder with its own Java runtime
$dest = Join-Path $target 'installer'
Write-Host 'Creating the application folder...'
Invoke-Tool 'jpackage' @(
    '--type', 'app-image',
    '--name', $appName,
    '--app-version', $version,
    '--vendor', $appName,
    '--input', $stage,
    '--main-jar', 'git-backup-desktop.jar',
    '--main-class', 'com.gitbackup.Launcher',
    '--icon', (Join-Path $PSScriptRoot 'app.ico'),
    '--add-modules', $modules,
    '--jlink-options', '--strip-debug --no-header-files --no-man-pages',
    '--java-options', '--enable-native-access=ALL-UNNAMED',
    '--dest', $dest
)
$image = Join-Path $dest $appName
Write-Host "Application folder: $image"
if ($Type -eq 'app-image') { return }

# --- 3. installer
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    $wix = Get-ChildItem 'C:\Program Files (x86)' -Directory -Filter 'WiX Toolset v3*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $wix) { throw 'WiX Toolset 3.x was not found. Install it from https://wixtoolset.org or use -Type app-image.' }
    $env:PATH = (Join-Path $wix.FullName 'bin') + ';' + $env:PATH
}
Write-Host 'Creating the installer...'
Invoke-Tool 'jpackage' @(
    '--type', $Type,
    '--app-image', $image,
    '--name', $appName,
    '--app-version', $version,
    '--vendor', $appName,
    '--icon', (Join-Path $PSScriptRoot 'app.ico'),
    '--win-per-user-install',
    '--win-dir-chooser',
    '--win-menu',
    '--win-menu-group', $appName,
    '--win-shortcut',
    '--win-upgrade-uuid', $upgradeUuid,
    '--dest', $dest
)
Get-ChildItem -LiteralPath $dest -File | ForEach-Object {
    Write-Host ('Installer: {0} ({1:N1} MB)' -f $_.FullName, ($_.Length / 1MB))
}
