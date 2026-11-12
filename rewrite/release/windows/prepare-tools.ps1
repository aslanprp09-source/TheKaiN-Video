# PowerShell 7. Download only the reviewed, hash-pinned upstream build tools.
param([Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
New-Item -ItemType Directory -Force $Destination | Out-Null
$Destination = (Resolve-Path $Destination).Path
$tools = Get-Content "$PSScriptRoot/tools.json" -Raw | ConvertFrom-Json
foreach ($name in @('inno', 'ffmpeg')) {
    $tool = $tools.$name
    $archive = Join-Path $Destination ([IO.Path]::GetFileName($tool.url))
    if (!(Test-Path $archive)) { Invoke-WebRequest $tool.url -OutFile $archive }
    if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $tool.sha256) {
        throw "Checksum mismatch for $name; no downloaded code was executed"
    }
}
$ffmpegRoot = Join-Path $Destination 'ffmpeg'
if (!(Test-Path $ffmpegRoot)) {
    Expand-Archive (Join-Path $Destination ([IO.Path]::GetFileName($tools.ffmpeg.url))) $ffmpegRoot
}
$ffmpeg = Get-ChildItem $ffmpegRoot -Filter ffmpeg.exe -Recurse | Select-Object -First 1
if (!$ffmpeg -or !(Test-Path (Join-Path $ffmpeg.DirectoryName 'ffprobe.exe'))) { throw 'Missing media tools' }
$innoRoot = Join-Path $Destination 'inno'
if (!(Test-Path "$innoRoot/ISCC.exe")) {
    $installer = Join-Path $Destination ([IO.Path]::GetFileName($tools.inno.url))
    $process = Start-Process -FilePath $installer -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/DIR=`"$innoRoot`"") -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Inno tool installation failed: $($process.ExitCode)" }
}
if (!(Test-Path "$innoRoot/ISCC.exe")) { throw 'Missing pinned Inno compiler' }
@{ ffmpegBin = $ffmpeg.DirectoryName; iscc = "$innoRoot/ISCC.exe" } |
    ConvertTo-Json | Set-Content (Join-Path $Destination 'paths.json') -Encoding utf8
