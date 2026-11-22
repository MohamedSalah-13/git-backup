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

    The steps every program's script repeats come from desktop-updater's tools/Packaging.psm1, found
    in a clone of desktop-updater beside this repository or where DESKTOP_UPDATER_HOME points, as
    release.ps1 finds it. What is Git Backup's own stays here.

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
$pom = Join-Path $project 'pom.xml'
$appName = 'Git Backup'
# Identifies the product to Windows Installer so a new version replaces the old one. Never change it.
$upgradeUuid = '6f1d2b0e-7c43-4f0a-9b1e-3a5c8d2e4f71'
# The parts of the Java runtime the program uses; keeps the installer small.
$modules = 'java.base,java.datatransfer,java.desktop,java.logging,java.management,java.naming,' +
    'java.net.http,java.prefs,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.xml,' +
    'jdk.charsets,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs'

# desktop-updater is looked for beside the repository, wherever in it this script sits.
$updaterHome = $env:DESKTOP_UPDATER_HOME
if (-not $updaterHome) {
    $repository = & git -C $project rev-parse --show-toplevel 2>$null
    if (-not $repository) { $repository = $project }
    $updaterHome = Join-Path (Split-Path -Parent $repository) 'desktop-updater'
}
$packaging = Join-Path $updaterHome 'tools/Packaging.psm1'
if (-not (Test-Path -LiteralPath $packaging)) {
    throw "desktop-updater was not found at $updaterHome. Clone https://github.com/MohamedSalah-13/desktop-updater beside this repository, or set DESKTOP_UPDATER_HOME."
}
Import-Module $packaging -Force

$platform = Get-PackagingPlatform
$Type = Resolve-PackageType -Type $Type -Platform $platform
$icon = Join-Path $PSScriptRoot ('app.' + (Get-IconExtension -Platform $platform))
$jpackage = (Find-JPackage -MinimumVersion 25).Path

# --- 1. build
$mvnArgs = @('-B', '-q', 'clean', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
Write-Host 'Building the application...'
Invoke-MavenBuild -Pom $pom -Arguments $mvnArgs

# The program reads its real version from gitbackup.version (MainApp), which is what the updater
# compares; on macOS the package version of 0.x.y is 1.x.y.
$versions = Get-PomVersion -Pom $pom -Platform $platform
$version = $versions.Version
$packageVersion = $versions.PackageVersion

$stage = New-JPackageInput -Path (Join-Path $target 'jpackage-input') `
    -Item (Join-Path $target 'git-backup-desktop.jar'), (Join-Path $target 'lib')

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
if ($platform -eq 'macos') {
    $imageArgs += @('--mac-package-identifier', 'com.gitbackup.desktop', '--mac-package-name', $appName)
}
Invoke-Native -FilePath $jpackage -ArgumentList $imageArgs
$image = Join-Path $dest $appName
if ($platform -eq 'macos') { $image += '.app' }
Write-Host "Application folder: $image"
if ($Type -eq 'app-image') { return }

# --- 3. installer
if ($platform -eq 'windows') { [void](Find-WiX) }
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
if ($platform -eq 'windows') {
    $installerArgs += @(Get-WinInstallerArgument -PerUser -DirChooser -Menu -MenuGroup $appName -Shortcut -UpgradeUuid $upgradeUuid)
} elseif ($platform -eq 'linux') {
    $installerArgs += @(
        '--linux-package-name', 'git-backup',
        '--linux-shortcut',
        '--linux-menu-group', 'Utility',
        '--linux-app-category', 'utils'
    )
}
Invoke-Native -FilePath $jpackage -ArgumentList $installerArgs

# The Windows installer is named by packaging\release.ps1 when it is published. The others get
# their published name here: jpackage's own names differ per system and hide the real version on macOS.
if ($platform -ne 'windows') {
    [void](Rename-PlatformPackage -Directory $dest -Type $Type -Name 'GitBackup' -Version $version -Platform $platform)
}
Get-ChildItem -LiteralPath $dest -File | ForEach-Object {
    Write-Host ('Installer: {0} ({1:N1} MB)' -f $_.FullName, ($_.Length / 1MB))
}
