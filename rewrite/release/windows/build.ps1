param([Parameter(Mandatory)][string]$Iscc, [Parameter(Mandatory)][string]$Commit)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
if ($Commit -notmatch '^[a-f0-9]{40}$') { throw 'Full source commit required' }
if ((git rev-parse HEAD) -ne $Commit) { throw 'Build commit must match the checkout' }
if (git status --porcelain --untracked-files=normal -- rewrite .github/workflows) { throw 'Commit source changes before packaging' }
$release = Split-Path $PSScriptRoot -Parent
$env:PYTHONHASHSEED = '0'
$env:SOURCE_DATE_EPOCH = (git show -s --format=%ct $Commit)
$env:QT_QPA_PLATFORM = 'offscreen'
python "$PSScriptRoot/prepare-build.py" $Commit
python "$PSScriptRoot/collect-notices.py" "$release/build/notices"
python -m pip inspect --local | Set-Content "$release/build/pip-inspect.json" -Encoding utf8
python -m PyInstaller --noconfirm --clean --distpath "$release/out/frozen" --workpath "$release/build/pyinstaller" "$PSScriptRoot/TheKaiN.spec"
$bundle = (Resolve-Path "$release/out/frozen/TheKaiNVideo").Path
$artifacts = "$release/out/windows-artifacts"
New-Item -ItemType Directory -Force $artifacts | Out-Null
& $Iscc "/DBundleDir=$bundle" "/DOutputDir=$artifacts" "/DBuildId=$($Commit.Substring(0,7))" "$PSScriptRoot/installer.iss"
Compress-Archive -Path $bundle -DestinationPath "$artifacts/TheKaiNVideo-0.1.0-m6-$($Commit.Substring(0,7))-win-x64-unsigned-portable.zip" -Force
Copy-Item "$release/build/build-info.json", "$release/build/pip-inspect.json", "$release/EXPERIMENTAL.txt" $artifacts
Copy-Item "$release/build/notices" "$artifacts/notices" -Recurse -Force
python "$release/manifest.py" $artifacts --commit $Commit --platform windows-x64 --channel unsigned-experimental
