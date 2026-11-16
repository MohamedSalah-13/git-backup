<#
.SYNOPSIS
    Creates (or removes) a daily Windows scheduled task that runs backup-repos.ps1.

.DESCRIPTION
    The task runs as the current user, only while that user is signed in. That is
    required: the Google Drive letter and the GitHub sign-in exist only inside the
    user's own session. If the PC was off at the scheduled time, the task runs at
    the next opportunity.

.EXAMPLE
    .\install-schedule.ps1 -At 21:00

.EXAMPLE
    .\install-schedule.ps1 -Remove
#>
[CmdletBinding()]
param(
    [string]$At = '21:00',
    [switch]$Remove,
    [string]$TaskName = 'GitHub Repo Backup'
)

$ErrorActionPreference = 'Stop'

if ($Remove) {
    if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
        Write-Host "Removed scheduled task '$TaskName'."
    } else {
        Write-Host "No scheduled task named '$TaskName'."
    }
    return
}

$script = Join-Path $PSScriptRoot 'backup-repos.ps1'
if (-not (Test-Path -LiteralPath $script)) { throw 'backup-repos.ps1 was not found next to this script.' }
$time = [datetime]::ParseExact($At, 'HH:mm', [System.Globalization.CultureInfo]::InvariantCulture)

$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -WindowStyle Hidden -File `"$script`"" `
    -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -Daily -At $time
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Hours 6)

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings `
    -Description 'Backs up GitHub repositories as ZIP files (backup-repos.ps1).' -Force | Out-Null

Write-Host "Scheduled task '$TaskName' will run every day at $At."
Write-Host "Script: $script"
Write-Host 'Remove it with: .\install-schedule.ps1 -Remove'
