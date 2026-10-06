param([string]$InstallerPath)
$ErrorActionPreference = 'Stop'
$taskDesktopRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskVersion = (Get-Content (Join-Path $taskDesktopRoot 'package.json') -Raw | ConvertFrom-Json).version
if (!$InstallerPath) { $InstallerPath = Join-Path $taskDesktopRoot "dist/Mihon-Discover-Windows-$taskVersion-Setup-x64.exe" }
$InstallerPath = (Resolve-Path -LiteralPath $InstallerPath).Path
$taskTestRoot = [IO.Path]::GetFullPath((Join-Path $taskDesktopRoot 'test-output/installer-smoke'))
$taskInstallDirectory = [IO.Path]::GetFullPath((Join-Path $taskTestRoot 'Mihon Discover'))
$taskExecutable = Join-Path $taskInstallDirectory 'Mihon Discover.exe'
$taskUninstaller = Join-Path $taskInstallDirectory 'Uninstall Mihon Discover.exe'
$taskDesktopShortcut = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Mihon Discover.lnk'
$taskMenuShortcut = Join-Path ([Environment]::GetFolderPath('Programs')) 'Mihon Discover.lnk'
function Get-TaskFileHash([string]$Path) {
    $taskHasher = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($taskHasher.ComputeHash([IO.File]::ReadAllBytes($Path))).Replace('-', '') }
    finally { $taskHasher.Dispose() }
}
function Get-MihonInstalls {
    Get-ItemProperty 'HKCU:/Software/Microsoft/Windows/CurrentVersion/Uninstall/*', 'HKLM:/Software/Microsoft/Windows/CurrentVersion/Uninstall/*', 'HKLM:/Software/WOW6432Node/Microsoft/Windows/CurrentVersion/Uninstall/*' -ErrorAction SilentlyContinue |
        Where-Object { $_.DisplayName -eq 'Mihon Discover' }
}
if (@(Get-MihonInstalls).Count -gt 0) { throw 'A Mihon Discover installation already exists. Refusing to modify it.' }
if (Test-Path -LiteralPath $taskInstallDirectory) {
    throw 'The isolated install directory already exists. Refusing to overwrite it.'
}
if (!$taskInstallDirectory.StartsWith($taskTestRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The test installation must stay within desktop/test-output/installer-smoke.'
}
New-Item -ItemType Directory -Path $taskTestRoot -Force | Out-Null
$taskProfileDirectory = Join-Path ([Environment]::GetFolderPath('ApplicationData')) 'mihon-discover-windows'
New-Item -ItemType Directory -Path $taskProfileDirectory -Force | Out-Null
$taskMarker = Join-Path $taskProfileDirectory ('.installer-smoke-' + [Guid]::NewGuid().ToString() + '.txt')
Set-Content -LiteralPath $taskMarker -Value 'Data must survive reinstall and uninstall.' -Encoding ASCII
$taskPreviousSmokeExecutable = $env:MIHON_SMOKE_EXECUTABLE
$taskShortcutBackups = @()
try {
    $taskBackupDirectory = Join-Path $taskTestRoot ('previous-shortcuts-' + [Guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $taskBackupDirectory | Out-Null
    $taskLinkNumber = 0
    foreach ($taskLink in @($taskDesktopShortcut, $taskMenuShortcut)) {
        if (Test-Path -LiteralPath $taskLink) {
            $taskBackupPath = Join-Path $taskBackupDirectory "$taskLinkNumber.lnk"
            Copy-Item -LiteralPath $taskLink -Destination $taskBackupPath
            $taskShortcutBackups += [PSCustomObject]@{ Original = $taskLink; Backup = $taskBackupPath; Hash = (Get-TaskFileHash $taskLink) }
        }
        $taskLinkNumber++
    }
    for ($taskPass = 1; $taskPass -le 2; $taskPass++) {
        $taskProcess = Start-Process -FilePath $InstallerPath -ArgumentList @('/S', '/currentuser', "/D=$taskInstallDirectory") -WindowStyle Hidden -PassThru
        if (!$taskProcess.WaitForExit(120000)) { throw 'Installer did not finish within two minutes.' }
        if ($taskProcess.ExitCode -ne 0) { throw "Installer returned exit code $($taskProcess.ExitCode)." }
        if (!(Test-Path -LiteralPath $taskExecutable) -or !(Test-Path -LiteralPath $taskUninstaller)) { throw 'Installed application or uninstaller is missing.' }
        $taskRegistration = @(Get-MihonInstalls | Where-Object { $_.UninstallString -and $_.UninstallString.Contains('"' + $taskUninstaller + '"') })
        if ($taskRegistration.Count -ne 1 -or $taskRegistration[0].DisplayVersion -ne $taskVersion) { throw 'Uninstall registration has an incorrect location or version.' }
        $taskShell = New-Object -ComObject WScript.Shell
        foreach ($taskLink in @($taskDesktopShortcut, $taskMenuShortcut)) {
            if (!(Test-Path -LiteralPath $taskLink)) { throw "Missing shortcut: $taskLink" }
            if ($taskShell.CreateShortcut($taskLink).TargetPath -ne $taskExecutable) { throw "Incorrect shortcut target: $taskLink" }
        }
        if (!(Test-Path -LiteralPath $taskMarker)) { throw 'Reinstallation removed user data.' }
        Write-Output "PASS: install/reinstall $taskPass, executable, version registration, shortcuts, preserved data."
    }
    $env:MIHON_SMOKE_EXECUTABLE = $taskExecutable
    Push-Location $taskDesktopRoot
    try {
        & npm.cmd run smoke
        if ($LASTEXITCODE -ne 0) { throw 'The installed application failed its Electron smoke test.' }
    } finally { Pop-Location }
} finally {
    $env:MIHON_SMOKE_EXECUTABLE = $taskPreviousSmokeExecutable
    try {
        # Invoke only the uninstaller under this script's verified workspace directory.
        if (Test-Path -LiteralPath $taskUninstaller) {
            $taskResolvedInstall = (Resolve-Path -LiteralPath $taskInstallDirectory).Path
            if ($taskResolvedInstall -ne $taskInstallDirectory -or !$taskResolvedInstall.StartsWith($taskTestRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Uninstaller target escaped the isolated test directory.'
            }
            $taskUninstallProcess = Start-Process -FilePath $taskUninstaller -ArgumentList @('/S', '/currentuser', '/KEEP_APP_DATA') -WindowStyle Hidden -PassThru
            if (!$taskUninstallProcess.WaitForExit(120000)) { throw 'Uninstaller did not finish within two minutes.' }
            if ($taskUninstallProcess.ExitCode -ne 0) { throw "Uninstaller returned exit code $($taskUninstallProcess.ExitCode)." }
            for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
                if (!(Test-Path -LiteralPath $taskExecutable) -and @(Get-MihonInstalls).Count -eq 0) { break }
                Start-Sleep -Milliseconds 500
            }
            if ((Test-Path -LiteralPath $taskExecutable) -or @(Get-MihonInstalls).Count -gt 0) { throw 'Uninstaller did not remove the isolated installation.' }
            if ((Test-Path -LiteralPath $taskDesktopShortcut) -or (Test-Path -LiteralPath $taskMenuShortcut)) { throw 'Uninstaller left application shortcuts behind.' }
            if (!(Test-Path -LiteralPath $taskMarker)) { throw 'Uninstaller removed user data.' }
            Write-Output 'PASS: uninstall removes application, registration and shortcuts; user data is preserved.'
        }
        } finally {
        foreach ($taskBackup in $taskShortcutBackups) {
            Copy-Item -LiteralPath $taskBackup.Backup -Destination $taskBackup.Original -Force
            if ((Get-TaskFileHash $taskBackup.Original) -ne $taskBackup.Hash) { throw 'An original shortcut was not restored correctly.' }
        }
        if (Test-Path -LiteralPath $taskMarker) { Remove-Item -LiteralPath $taskMarker }
    }
}
