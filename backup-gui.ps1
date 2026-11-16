<#
.SYNOPSIS
    Window for choosing which GitHub repositories to back up and running the backup.

.DESCRIPTION
    Lists every repository of the account with a checkbox. The checked repositories
    are saved to config.json ("include") and are the only ones backup-repos.ps1
    backs up - from this window and from the scheduled task alike.

    Keep this file saved as "UTF-8 with BOM": Windows PowerShell needs the BOM to
    read the Arabic text correctly.
#>
[CmdletBinding()]
param(
    [string]$ConfigPath,
    # Diagnostics: save a picture of the window to this PNG file, then close.
    [string]$ScreenshotTo,
    # Diagnostics: with -ScreenshotTo, first run the backup of the saved selection.
    [switch]$AutoRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$coreScript = Join-Path $PSScriptRoot 'backup-repos.ps1'
if (-not $ConfigPath) { $ConfigPath = Join-Path $PSScriptRoot 'config.json' }
$ConfigPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ConfigPath)
$inv = [System.Globalization.CultureInfo]::InvariantCulture

$script:proc = $null
$script:outTask = $null
$script:errTask = $null
$script:mode = $null
$script:logFile = $null
$script:logPos = 0
$script:logSeen = $false
$script:loading = $false
$script:listReady = $false
$script:afterListText = $null
$script:autoRan = $false

$T = @{
    title        = 'نسخ مستودعات GitHub احتياطيًا'
    dest         = 'مجلد الحفظ:'
    browse       = 'استعراض...'
    keep         = 'عدد النسخ المحفوظة لكل مستودع:'
    selectAll    = 'تحديد الكل'
    selectNone   = 'إلغاء التحديد'
    selectNeeded = 'تحديد ما يحتاج نسخًا'
    refresh      = 'تحديث القائمة'
    run          = 'نسخ المحدد الآن'
    save         = 'حفظ الاختيار'
    saveTip      = 'الاختيار المحفوظ هو ما ينسخه التشغيل التلقائي المجدول أيضًا'
    force        = 'إعادة النسخ حتى لو لم يتغير شيء'
    colRepo      = 'المستودع'
    colType      = 'النوع'
    colSize      = 'الحجم'
    colPushed    = 'آخر تعديل على GitHub'
    colBackup    = 'آخر نسخة احتياطية'
    colStatus    = 'الحالة'
    private      = 'خاص'
    public       = 'عام'
    stNever      = 'لم يُنسخ بعد'
    stChanged    = 'يوجد تحديث'
    stCurrent    = 'محدّث'
    stMissing    = 'ملف النسخة مفقود'
    loading      = 'جارٍ تحميل قائمة المستودعات...'
    running      = 'جارٍ النسخ... التفاصيل في الأسفل'
    count        = 'المحدد: {0} من {1}'
    ready        = 'جاهز'
    noDest       = 'اختر مجلد الحفظ بالضغط على "استعراض"'
    destMissing  = 'مجلد الحفظ غير متاح الآن. هل القرص أو Google Drive يعمل؟'
    needOne      = 'اختر مستودعًا واحدًا على الأقل.'
    pickDest     = 'اختر مجلد الحفظ أولًا بالضغط على "استعراض".'
    saved        = 'تم حفظ الاختيار، وسيُستخدم في النسخ التلقائي أيضًا'
    done         = 'اكتمل النسخ'
    doneErrors   = 'اكتمل النسخ مع أخطاء. راجع السجل في الأسفل'
    failed       = 'تعذّر تشغيل النسخ. راجع السجل في الأسفل'
    listFailed   = 'تعذّر تحميل القائمة. راجع السجل في الأسفل'
    closeRunning = 'النسخ ما زال جاريًا وسيكمل في الخلفية. هل تريد إغلاق النافذة؟'
    browseTitle  = 'اختر المجلد الذي تُحفظ فيه النسخ الاحتياطية'
    coreMissing  = 'الملف backup-repos.ps1 غير موجود بجانب هذا الملف.'
}

# ------------------------------------------------------------- functions ---

function Show-Message {
    param([string]$Text, [string]$Icon = 'Information', [string]$Buttons = 'OK')
    $options = [System.Windows.Forms.MessageBoxOptions]([int][System.Windows.Forms.MessageBoxOptions]::RtlReading -bor
        [int][System.Windows.Forms.MessageBoxOptions]::RightAlign)
    return [System.Windows.Forms.MessageBox]::Show($form, $Text, $T.title,
        [System.Windows.Forms.MessageBoxButtons]$Buttons, [System.Windows.Forms.MessageBoxIcon]$Icon,
        [System.Windows.Forms.MessageBoxDefaultButton]::Button1, $options)
}

function Add-Log {
    param([string]$Text)
    if (-not $Text) { return }
    $txtLog.AppendText(($Text -replace "(?<!`r)`n", "`r`n"))
}

function ConvertTo-Arg {
    param([string]$Value)
    if ($Value -ne '' -and $Value -notmatch '[\s"]') { return $Value }
    $escaped = $Value -replace '(\\*)"', '$1$1\"'
    $escaped = $escaped -replace '(\\+)$', '$1$1'
    return '"' + $escaped + '"'
}

# Keeps numbers, units and dates in left-to-right order inside the right-to-left window.
function ConvertTo-Ltr {
    param([string]$Text)
    if (-not $Text) { return '' }
    return [string][char]0x202A + $Text + [string][char]0x202C
}

function Format-When {
    param([string]$Iso, [switch]$Utc)
    if (-not $Iso) { return '' }
    try {
        if ($Utc) {
            $d = [datetime]::Parse($Iso, $inv, [System.Globalization.DateTimeStyles]::AdjustToUniversal).ToLocalTime()
        } else {
            $d = [datetime]::Parse($Iso, $inv)
        }
        return ConvertTo-Ltr $d.ToString('yyyy-MM-dd  HH:mm', $inv)
    } catch {
        return ConvertTo-Ltr $Iso
    }
}

function Format-Size {
    param([long]$KB)
    if ($KB -ge 1024) { return ConvertTo-Ltr ('{0:N1} MB' -f ($KB / 1024)) }
    return ConvertTo-Ltr ('{0:N0} KB' -f $KB)
}

function Read-Config {
    if (Test-Path -LiteralPath $ConfigPath) {
        return ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($ConfigPath))
    }
    return [pscustomobject][ordered]@{
        destination = ''; keepVersions = 3; include = @(); owners = @(); exclude = @()
        includeForks = $false; includeArchived = $true; workDir = ''; keepMirrors = $true
    }
}

function Set-ConfigValue {
    param($Config, [string]$Name, $Value)
    if ($Config.PSObject.Properties[$Name]) { $Config.$Name = $Value }
    else { Add-Member -InputObject $Config -NotePropertyName $Name -NotePropertyValue $Value }
}

# Writes the destination (and, unless -DestinationOnly, the checked repositories) to config.json.
function Save-Config {
    param([switch]$DestinationOnly)
    $config = Read-Config
    Set-ConfigValue $config 'destination' $txtDest.Text.Trim()
    if (-not $DestinationOnly) {
        $names = New-Object 'System.Collections.Generic.List[string]'
        foreach ($item in $list.CheckedItems) { $names.Add([string]$item.Tag.Full) }
        # Everything checked is stored as an empty list, which means "all repositories",
        # so repositories created later are covered too.
        [string[]]$include = @()
        if ($names.Count -lt $list.Items.Count) { $include = $names.ToArray() }
        Set-ConfigValue $config 'include' $include
        Set-ConfigValue $config 'keepVersions' ([int]$numKeep.Value)
    }
    $json = ConvertTo-Json -InputObject $config -Depth 5
    [System.IO.File]::WriteAllText($ConfigPath, $json, (New-Object System.Text.UTF8Encoding($false)))
}

function Test-Selection {
    if (-not $txtDest.Text.Trim()) {
        [void](Show-Message $T.pickDest 'Warning')
        return $false
    }
    if ($list.CheckedItems.Count -eq 0) {
        [void](Show-Message $T.needOne 'Warning')
        return $false
    }
    return $true
}

function Update-Count {
    $lblCount.Text = $T.count -f $list.CheckedItems.Count, $list.Items.Count
}

function Set-Busy {
    param([bool]$Busy, [string]$Text)
    foreach ($control in @($btnRun, $btnSave, $btnRefresh, $btnBrowse, $btnAll, $btnNone, $btnNeeded, $numKeep, $chkForce)) {
        $control.Enabled = -not $Busy
    }
    if (-not $Busy -and -not $script:listReady) {
        $btnRun.Enabled = $false
        $btnSave.Enabled = $false
    }
    $lblStatus.Text = $Text
}

# Starts backup-repos.ps1 hidden; the timer picks up the result without freezing the window.
function Start-Core {
    param([string[]]$Arguments, [string]$Mode)
    $all = @('-NoProfile', '-File', $coreScript, '-ConfigPath', $ConfigPath) + @($Arguments)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'powershell.exe'
    $psi.Arguments = (@($all | ForEach-Object { ConvertTo-Arg $_ })) -join ' '
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    [void]$process.Start()
    $script:proc = $process
    $script:outTask = $process.StandardOutput.ReadToEndAsync()
    $script:errTask = $process.StandardError.ReadToEndAsync()
    $script:mode = $Mode
    $timer.Start()
}

function Start-List {
    if ($script:proc) { return }
    Set-Busy $true $T.loading
    Start-Core @('-List') 'list'
}

function Start-Backup {
    if ($script:proc) { return }
    if (-not (Test-Selection)) { return }
    Save-Config
    $script:logPos = 0
    if ($script:logFile -and (Test-Path -LiteralPath $script:logFile)) {
        $script:logPos = (Get-Item -LiteralPath $script:logFile).Length
    }
    $script:logSeen = $false
    Set-Busy $true $T.running
    $coreArgs = @()
    if ($chkForce.Checked) { $coreArgs += '-Force' }
    Start-Core $coreArgs 'backup'
}

# Shows the lines the running backup added to its log file since the last look.
function Read-NewLog {
    if (-not $script:logFile -or -not (Test-Path -LiteralPath $script:logFile)) { return }
    $text = ''
    $stream = New-Object System.IO.FileStream($script:logFile, [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        if ($stream.Length -lt $script:logPos) { $script:logPos = 0 }
        $count = [int]($stream.Length - $script:logPos)
        if ($count -gt 0) {
            $buffer = New-Object byte[] $count
            $stream.Position = $script:logPos
            $read = $stream.Read($buffer, 0, $count)
            $script:logPos += $read
            $text = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $read)
        }
    } finally {
        $stream.Dispose()
    }
    if ($text) {
        $script:logSeen = $true
        Add-Log $text.TrimStart([char]0xFEFF)
    }
}

function Save-Screenshot {
    $form.Refresh()
    [System.Windows.Forms.Application]::DoEvents()
    $bitmap = New-Object System.Drawing.Bitmap($form.Width, $form.Height)
    try {
        $form.DrawToBitmap($bitmap, (New-Object System.Drawing.Rectangle(0, 0, $form.Width, $form.Height)))
        $bitmap.Save($ScreenshotTo, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $bitmap.Dispose()
    }
}

function Complete-List {
    param([int]$Code, [string]$Out, [string]$Err)
    $jsonLine = @($Out -split "`r?`n" | Where-Object { $_.StartsWith('{') }) | Select-Object -Last 1
    if ($Code -ne 0 -or -not $jsonLine) {
        Add-Log ((($Out + "`r`n" + $Err).Trim()) + "`r`n")
        Set-Busy $false $T.listFailed
        if ($ScreenshotTo) { Save-Screenshot; $form.Close() }
        return
    }
    $data = ConvertFrom-Json -InputObject $jsonLine
    $script:logFile = [string]$data.logFile
    $txtDest.Text = [string]$data.destination
    $numKeep.Value = [Math]::Min(50, [Math]::Max(1, [int]$data.keepVersions))

    # Keep the ticks the user changed but has not saved yet.
    $previous = @{}
    foreach ($item in $list.Items) { $previous[[string]$item.Tag.Full] = $item.Checked }
    $owners = @{}
    foreach ($repo in $data.repos) { $owners[([string]$repo.nameWithOwner -split '/')[0]] = $true }
    $showOwner = $owners.Count -gt 1

    $script:loading = $true
    $list.BeginUpdate()
    try {
        $list.Items.Clear()
        foreach ($repo in $data.repos) {
            $full = [string]$repo.nameWithOwner
            $label = [string]$repo.name
            if ($showOwner) { $label = $full }
            $item = New-Object System.Windows.Forms.ListViewItem($label)
            $item.UseItemStyleForSubItems = $false
            $item.Tag = New-Object psobject -Property @{ Full = $full; Status = [string]$repo.status }
            $typeText = $T.public
            if ($repo.isPrivate) { $typeText = $T.private }
            [void]$item.SubItems.Add($typeText)
            [void]$item.SubItems.Add((Format-Size ([long]$repo.sizeKB)))
            [void]$item.SubItems.Add((Format-When ([string]$repo.pushedAt) -Utc))
            [void]$item.SubItems.Add((Format-When ([string]$repo.lastBackup)))
            $statusText = $T.stNever
            $statusColor = [System.Drawing.Color]::FromArgb(180, 95, 6)
            switch ([string]$repo.status) {
                'current' { $statusText = $T.stCurrent; $statusColor = [System.Drawing.Color]::FromArgb(0, 128, 0) }
                'changed' { $statusText = $T.stChanged }
                'missing' { $statusText = $T.stMissing; $statusColor = [System.Drawing.Color]::FromArgb(178, 34, 34) }
            }
            $sub = $item.SubItems.Add($statusText)
            $sub.ForeColor = $statusColor
            if ($previous.ContainsKey($full)) { $item.Checked = $previous[$full] }
            else { $item.Checked = [bool]$repo.selected }
            [void]$list.Items.Add($item)
        }
    } finally {
        $list.EndUpdate()
        $script:loading = $false
    }
    $script:listReady = $true
    Update-Count

    $text = $T.ready
    if (-not $txtDest.Text) { $text = $T.noDest }
    elseif (-not $data.destinationFound) { $text = $T.destMissing }
    elseif ($script:afterListText) { $text = $script:afterListText }
    $script:afterListText = $null
    Set-Busy $false $text

    if ($ScreenshotTo) {
        if ($AutoRun -and -not $script:autoRan) {
            $script:autoRan = $true
            Start-Backup
        } else {
            Save-Screenshot
            $form.Close()
        }
    }
}

function Complete-Backup {
    param([int]$Code, [string]$Out, [string]$Err)
    Read-NewLog
    if (-not $script:logSeen) {
        $text = ($Out + "`r`n" + $Err).Trim()
        if ($text) { Add-Log ($text + "`r`n") }
    }
    switch ($Code) {
        0 { $script:afterListText = $T.done }
        1 { $script:afterListText = $T.doneErrors }
        default { $script:afterListText = $T.failed }
    }
    Start-List
}

function Set-AllChecked {
    param([scriptblock]$Decide)
    $script:loading = $true
    try {
        foreach ($item in $list.Items) { $item.Checked = [bool](& $Decide $item) }
    } finally {
        $script:loading = $false
    }
    Update-Count
}

function New-Button {
    param([string]$Text)
    $button = New-Object System.Windows.Forms.Button
    $button.Text = $Text
    $button.AutoSize = $true
    $button.AutoSizeMode = [System.Windows.Forms.AutoSizeMode]::GrowAndShrink
    $button.Padding = New-Object System.Windows.Forms.Padding(10, 3, 10, 3)
    $button.Margin = New-Object System.Windows.Forms.Padding(3)
    return $button
}

# ---------------------------------------------------------------- window ---

$font = New-Object System.Drawing.Font('Segoe UI', 9.75)
$form = New-Object System.Windows.Forms.Form
$form.Text = $T.title
$form.Font = $font
$form.ClientSize = New-Object System.Drawing.Size(1000, 700)
$form.MinimumSize = New-Object System.Drawing.Size(860, 540)
$form.StartPosition = [System.Windows.Forms.FormStartPosition]::CenterScreen
$form.RightToLeft = [System.Windows.Forms.RightToLeft]::Yes
$form.RightToLeftLayout = $true
if ($ScreenshotTo) {
    $form.StartPosition = [System.Windows.Forms.FormStartPosition]::Manual
    $form.Location = New-Object System.Drawing.Point(-4000, -4000)
    $form.ShowInTaskbar = $false
}

$main = New-Object System.Windows.Forms.TableLayoutPanel
$main.Dock = [System.Windows.Forms.DockStyle]::Fill
$main.Padding = New-Object System.Windows.Forms.Padding(10)
$main.ColumnCount = 1
$main.RowCount = 5
[void]$main.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]::Percent, 100)))
[void]$main.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::AutoSize)))
[void]$main.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::AutoSize)))
[void]$main.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::Percent, 68)))
[void]$main.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::AutoSize)))
[void]$main.RowStyles.Add((New-Object System.Windows.Forms.RowStyle([System.Windows.Forms.SizeType]::Percent, 32)))
$form.Controls.Add($main)

# row 0: destination and number of versions
$rowDest = New-Object System.Windows.Forms.TableLayoutPanel
$rowDest.Dock = [System.Windows.Forms.DockStyle]::Fill
$rowDest.AutoSize = $true
$rowDest.ColumnCount = 5
$rowDest.RowCount = 1
foreach ($type in 'AutoSize', 'Percent', 'AutoSize', 'AutoSize', 'AutoSize') {
    [void]$rowDest.ColumnStyles.Add((New-Object System.Windows.Forms.ColumnStyle([System.Windows.Forms.SizeType]$type, 100)))
}
$lblDest = New-Object System.Windows.Forms.Label
$lblDest.Text = $T.dest
$lblDest.AutoSize = $true
$lblDest.Anchor = [System.Windows.Forms.AnchorStyles]::Left
$txtDest = New-Object System.Windows.Forms.TextBox
$txtDest.ReadOnly = $true
$txtDest.RightToLeft = [System.Windows.Forms.RightToLeft]::No
$txtDest.Anchor = [System.Windows.Forms.AnchorStyles]::Left -bor [System.Windows.Forms.AnchorStyles]::Right
$btnBrowse = New-Button $T.browse
$lblKeep = New-Object System.Windows.Forms.Label
$lblKeep.Text = $T.keep
$lblKeep.AutoSize = $true
$lblKeep.Anchor = [System.Windows.Forms.AnchorStyles]::Left
$lblKeep.Margin = New-Object System.Windows.Forms.Padding(18, 0, 3, 0)
$numKeep = New-Object System.Windows.Forms.NumericUpDown
$numKeep.Minimum = 1
$numKeep.Maximum = 50
$numKeep.Value = 3
$numKeep.Width = 56
$numKeep.Anchor = [System.Windows.Forms.AnchorStyles]::Left
$rowDest.Controls.Add($lblDest, 0, 0)
$rowDest.Controls.Add($txtDest, 1, 0)
$rowDest.Controls.Add($btnBrowse, 2, 0)
$rowDest.Controls.Add($lblKeep, 3, 0)
$rowDest.Controls.Add($numKeep, 4, 0)
$main.Controls.Add($rowDest, 0, 0)

# row 1: selection buttons
$rowPick = New-Object System.Windows.Forms.FlowLayoutPanel
$rowPick.Dock = [System.Windows.Forms.DockStyle]::Fill
$rowPick.AutoSize = $true
$rowPick.WrapContents = $false
$rowPick.Margin = New-Object System.Windows.Forms.Padding(0, 6, 0, 2)
$btnAll = New-Button $T.selectAll
$btnNone = New-Button $T.selectNone
$btnNeeded = New-Button $T.selectNeeded
$btnRefresh = New-Button $T.refresh
$lblCount = New-Object System.Windows.Forms.Label
$lblCount.AutoSize = $true
$lblCount.Margin = New-Object System.Windows.Forms.Padding(14, 9, 14, 0)
$lblCount.Font = New-Object System.Drawing.Font($font, [System.Drawing.FontStyle]::Bold)
$rowPick.Controls.AddRange(@($btnAll, $btnNone, $btnNeeded, $btnRefresh, $lblCount))
$main.Controls.Add($rowPick, 0, 1)

# row 2: the repositories
$list = New-Object System.Windows.Forms.ListView
$list.Dock = [System.Windows.Forms.DockStyle]::Fill
$list.View = [System.Windows.Forms.View]::Details
$list.CheckBoxes = $true
$list.FullRowSelect = $true
$list.MultiSelect = $false
$list.HideSelection = $false
$list.RightToLeftLayout = $true
[void]$list.Columns.Add($T.colRepo, 300)
[void]$list.Columns.Add($T.colType, 70)
[void]$list.Columns.Add($T.colSize, 90)
[void]$list.Columns.Add($T.colPushed, 165)
[void]$list.Columns.Add($T.colBackup, 165)
[void]$list.Columns.Add($T.colStatus, 150)
$main.Controls.Add($list, 0, 2)

# row 3: actions
$rowRun = New-Object System.Windows.Forms.FlowLayoutPanel
$rowRun.Dock = [System.Windows.Forms.DockStyle]::Fill
$rowRun.AutoSize = $true
$rowRun.WrapContents = $false
$rowRun.Margin = New-Object System.Windows.Forms.Padding(0, 6, 0, 4)
$btnRun = New-Button $T.run
$btnRun.Font = New-Object System.Drawing.Font($font, [System.Drawing.FontStyle]::Bold)
$btnSave = New-Button $T.save
$chkForce = New-Object System.Windows.Forms.CheckBox
$chkForce.Text = $T.force
$chkForce.AutoSize = $true
$chkForce.Margin = New-Object System.Windows.Forms.Padding(14, 8, 14, 0)
$lblStatus = New-Object System.Windows.Forms.Label
$lblStatus.AutoSize = $true
$lblStatus.Margin = New-Object System.Windows.Forms.Padding(6, 9, 6, 0)
$lblStatus.ForeColor = [System.Drawing.Color]::FromArgb(60, 60, 60)
$rowRun.Controls.AddRange(@($btnRun, $btnSave, $chkForce, $lblStatus))
$main.Controls.Add($rowRun, 0, 3)

# row 4: what the backup is doing
$txtLog = New-Object System.Windows.Forms.TextBox
$txtLog.Dock = [System.Windows.Forms.DockStyle]::Fill
$txtLog.Multiline = $true
$txtLog.ReadOnly = $true
$txtLog.WordWrap = $false
$txtLog.ScrollBars = [System.Windows.Forms.ScrollBars]::Both
$txtLog.RightToLeft = [System.Windows.Forms.RightToLeft]::No
$txtLog.Font = New-Object System.Drawing.Font('Consolas', 9)
$txtLog.BackColor = [System.Drawing.Color]::White
$main.Controls.Add($txtLog, 0, 4)

$tips = New-Object System.Windows.Forms.ToolTip
$tips.SetToolTip($btnSave, $T.saveTip)

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 300

# ---------------------------------------------------------------- events ---

$timer.Add_Tick({
        try {
            if ($script:mode -eq 'backup') { Read-NewLog }
            if ($script:proc -and $script:proc.HasExited -and $script:outTask.IsCompleted -and $script:errTask.IsCompleted) {
                $timer.Stop()
                $code = $script:proc.ExitCode
                $out = [string]$script:outTask.Result
                $err = [string]$script:errTask.Result
                $mode = $script:mode
                $script:proc.Dispose()
                $script:proc = $null
                $script:mode = $null
                if ($mode -eq 'list') { Complete-List $code $out $err } else { Complete-Backup $code $out $err }
            }
        } catch {
            $timer.Stop()
            $script:proc = $null
            $script:mode = $null
            Add-Log ("ERROR: " + $_.Exception.Message + "`r`n")
            Set-Busy $false $T.listFailed
            if ($ScreenshotTo) { $form.Close() }
        }
    })

$list.Add_ItemChecked({ if (-not $script:loading) { Update-Count } })
$btnAll.Add_Click({ Set-AllChecked { $true } })
$btnNone.Add_Click({ Set-AllChecked { $false } })
$btnNeeded.Add_Click({ Set-AllChecked { param($item) $item.Tag.Status -ne 'current' } })
$btnRefresh.Add_Click({ Start-List })

$btnBrowse.Add_Click({
        try {
            $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
            $dialog.Description = $T.browseTitle
            $dialog.ShowNewFolderButton = $true
            if ($txtDest.Text -and (Test-Path -LiteralPath $txtDest.Text)) { $dialog.SelectedPath = $txtDest.Text }
            if ($dialog.ShowDialog($form) -eq [System.Windows.Forms.DialogResult]::OK) {
                $txtDest.Text = $dialog.SelectedPath
                Save-Config -DestinationOnly
                Start-List
            }
            $dialog.Dispose()
        } catch {
            Add-Log ("ERROR: " + $_.Exception.Message + "`r`n")
        }
    })

$btnSave.Add_Click({
        try {
            if (Test-Selection) {
                Save-Config
                $lblStatus.Text = $T.saved
            }
        } catch {
            Add-Log ("ERROR: " + $_.Exception.Message + "`r`n")
        }
    })

$btnRun.Add_Click({
        try {
            Start-Backup
        } catch {
            Add-Log ("ERROR: " + $_.Exception.Message + "`r`n")
            Set-Busy $false $T.failed
        }
    })

$form.Add_Shown({
        if (-not (Test-Path -LiteralPath $coreScript)) {
            Set-Busy $false $T.coreMissing
            foreach ($control in @($btnRefresh, $btnBrowse)) { $control.Enabled = $false }
            return
        }
        Start-List
    })

$form.Add_FormClosing({
        param($source, $e)
        if ($script:proc -and $script:mode -eq 'backup' -and -not $ScreenshotTo) {
            $answer = Show-Message $T.closeRunning 'Question' 'YesNo'
            if ($answer -ne [System.Windows.Forms.DialogResult]::Yes) { $e.Cancel = $true }
        }
    })

Update-Count
Set-Busy $true $T.loading
[System.Windows.Forms.Application]::Run($form)
$timer.Dispose()
