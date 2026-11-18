<#
.SYNOPSIS
    Publishes the version in pom.xml as a GitHub release that installed copies will be offered.

.DESCRIPTION
    Checks the project, runs packaging\build-installer.ps1, publishes the installer under the name
    the program's updater looks for (MainApp), and reads the release back the way the program does.
    The work is done by desktop-updater's tools\publish-release.ps1, which is expected in a clone of
    https://github.com/MohamedSalah-13/desktop-updater beside this repository (or where the
    DESKTOP_UPDATER_HOME environment variable points).

.EXAMPLE
    .\packaging\release.ps1 -DryRun       # builds, names the installer, publishes nothing
    .\packaging\release.ps1               # the tag v<version> must exist and be at HEAD
    .\packaging\release.ps1 -CreateTag    # tags HEAD first
    .\packaging\release.ps1 -Required     # every older copy must install it; no "Skip this version"
    .\packaging\release.ps1 -VerifyOnly   # which release is an installed copy offered right now?
#>
param(
    [switch]$DryRun,
    [switch]$SkipBuild,
    [switch]$CreateTag,
    [switch]$VerifyOnly,
    [switch]$Required,
    [switch]$PreRelease
)

$project = Split-Path -Parent $PSScriptRoot

# --- this project ---------------------------------------------------------------------------------
$release = @{
    Title       = 'Git Backup v{version}'              # the release's name on GitHub
    AssetName   = 'GitBackup-{version}-setup.exe'      # must match the .asset("...") glob in MainApp
    AssetGlob   = '*-setup.exe'                        # the same text as that .asset("...") call
    BuildScript = 'packaging\build-installer.ps1'      # makes the installer in target\installer
}
# Paths are relative to the folder above this script's folder, the one that holds pom.xml.
# --------------------------------------------------------------------------------------------------

# desktop-updater is looked for beside the repository, wherever in it this script sits.
$updaterHome = $env:DESKTOP_UPDATER_HOME
if (-not $updaterHome) {
    $repository = & git -C $project rev-parse --show-toplevel 2>$null
    if (-not $repository) { $repository = $project }
    $updaterHome = Join-Path (Split-Path -Parent $repository) 'desktop-updater'
}
$shared = Join-Path $updaterHome 'tools\publish-release.ps1'
if (-not (Test-Path -LiteralPath $shared)) {
    throw "desktop-updater was not found at $updaterHome. Clone https://github.com/MohamedSalah-13/desktop-updater beside this repository, or set DESKTOP_UPDATER_HOME."
}

& $shared -ProjectDir $project @release @PSBoundParameters
