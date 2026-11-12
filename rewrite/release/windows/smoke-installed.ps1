# Disposable Windows QA host only. No uninstall/reinstall of a user's existing app.
param([Parameter(Mandatory)][string]$Installer, [Parameter(Mandatory)][string]$Evidence)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if ((Get-AuthenticodeSignature $Installer).Status -ne 'NotSigned') { throw 'Expected the explicitly unsigned experimental installer' }
New-Item -ItemType Directory -Force $Evidence | Out-Null
$Evidence = (Resolve-Path $Evidence).Path
$installDir = Join-Path $Evidence 'isolated-install'
if (Test-Path $installDir) { throw 'QA install directory must not already exist' }
$install = Start-Process -FilePath $Installer -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-', "/DIR=`"$installDir`"", "/LOG=`"$Evidence/install.log`"") -Wait -PassThru
if ($install.ExitCode -ne 0) { throw "Installer exited $($install.ExitCode)" }
$exe = Join-Path $installDir 'TheKaiNVideo.exe'
if (!(Test-Path $exe) -or !(Test-Path "$installDir/TheKaiNExtractor.exe")) { throw 'Installed executables missing' }
$resultDir = Join-Path $Evidence 'installed-run'
try {
    # The frozen GUI and worker must work from a different cwd, with no source Python imports.
    Push-Location $Evidence
    try {
        $smoke = Start-Process -FilePath $exe -ArgumentList @('--packaging-smoke', "`"$resultDir`"") -Wait -PassThru
        if ($smoke.ExitCode -ne 0) { throw "Installed smoke exited $($smoke.ExitCode)" }
    } finally { Pop-Location }
    $report = Get-Content "$resultDir/packaging-smoke.json" -Raw | ConvertFrom-Json
    if ($report.status -ne 'PASS') { throw 'Installed smoke did not pass' }
    $os = Get-CimInstance Win32_OperatingSystem
    @{ status='PASS'; os=$os.Caption; build=$os.BuildNumber; clean_windows_11='BLOCKED: hosted runner is not a clean Windows 11 VM'; unsigned=$true } |
        ConvertTo-Json | Set-Content "$Evidence/host.json" -Encoding utf8
} finally {
    $uninstall = Start-Process -FilePath "$installDir/unins000.exe" -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/LOG=`"$Evidence/uninstall.log`"") -Wait -PassThru
    if ($uninstall.ExitCode -ne 0) { throw "Uninstaller exited $($uninstall.ExitCode)" }
}
if (Test-Path $exe) { throw 'Uninstaller left the application executable' }
Write-Output 'PASS: native hosted Windows install, frozen worker/merge/verify/Qt, uninstall. Clean Windows 11 remains BLOCKED.'
