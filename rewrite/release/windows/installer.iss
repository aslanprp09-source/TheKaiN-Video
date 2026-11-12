#ifndef BundleDir
  #error BundleDir must be the absolute frozen application directory
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif
#ifndef BuildId
  #error BuildId is required
#endif
[Setup]
AppId={{29542A56-BAA0-4C58-941E-8EB92F6D9D63}
AppName=TheKaiN Video Rebuild (experimental)
AppVersion=0.1.0
AppVerName=TheKaiN Video 0.1.0-m6 ({#BuildId}) — unsigned experimental
AppPublisher=TheKaiN
DefaultDirName={localappdata}\Programs\TheKaiNVideo-Rebuild
DefaultGroupName=TheKaiN Video Rebuild
UsePreviousAppDir=no
DisableDirPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.22000
OutputDir={#OutputDir}
OutputBaseFilename=TheKaiNVideo-0.1.0-m6-{#BuildId}-win-x64-unsigned-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\TheKaiNVideo.exe
SetupIconFile=..\build\thekain.ico
InfoBeforeFile=..\EXPERIMENTAL.txt
CloseApplications=no
RestartApplications=no
[Files]
Source: "{#BundleDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{group}\TheKaiN Video Rebuild (experimental)"; Filename: "{app}\TheKaiNVideo.exe"
