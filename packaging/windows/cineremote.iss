; Inno Setup script for the CineRemote Windows installer. Built by CI; locally:
;   ISCC packaging\windows\cineremote.iss /DAppVersion=1.0.0
; Expects (relative to the repository root): dist\CineRemote\ (PyInstaller), vcam\out\, obs-plugin\out\.
; Installs the app, the OBS source plugin (for every OBS user on the PC) and registers the virtual webcam.
; The in-app updater runs this silently with /relaunch=1 so the app comes back after the update.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#define Root "..\.."

[Setup]
AppId={{6F1C3B4E-2A7D-4E8B-9C51-7D3A0E9B2C41}
AppName=CineRemote
AppVersion={#AppVersion}
AppVerName=CineRemote {#AppVersion}
AppPublisher=CineRemote contributors
AppPublisherURL=https://github.com/jimmyvegas29/cineremote
AppSupportURL=https://github.com/jimmyvegas29/cineremote/issues
AppUpdatesURL=https://github.com/jimmyvegas29/cineremote/releases
DefaultDirName={autopf}\CineRemote
DisableProgramGroupPage=yes
DisableDirPage=auto
LicenseFile={#Root}\LICENSE
OutputDir={#Root}\dist
OutputBaseFilename=CineRemote-{#AppVersion}-windows-x64-setup
SetupIconFile={#Root}\assets\cineremote.ico
UninstallDisplayIcon={app}\CineRemote.exe
UninstallDisplayName=CineRemote
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
CloseApplications=yes
RestartApplications=no
MinVersion=10.0.17763

[Tasks]
Name: "obsplugin"; Description: "Install the OBS source (CineRemote Camera)"; GroupDescription: "Integrations:"
Name: "webcam"; Description: "Install the virtual webcam (each connected phone shows up as a webcam)"; GroupDescription: "Integrations:"
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[InstallDelete]
; earlier webcam DLL builds (the Camera Frame Server may still hold one; it goes on the next reboot)
Type: files; Name: "{app}\cineremote-vcam-*.dll"

[Files]
Source: "{#Root}\dist\CineRemote\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; OBS source plugin: ProgramData is OBS's per-machine plugin folder (OBS 30+)
Source: "{#Root}\obs-plugin\out\cineremote-obs.dll"; DestDir: "{commonappdata}\obs-studio\plugins\cineremote-obs\bin\64bit"; Tasks: obsplugin; Flags: ignoreversion
Source: "en-US.ini"; DestDir: "{commonappdata}\obs-studio\plugins\cineremote-obs\data\locale"; Tasks: obsplugin; Flags: ignoreversion
; virtual webcam: a versioned file name, because the Frame Server service keeps the previous DLL loaded
Source: "{#Root}\vcam\out\cineremote-vcam.dll"; DestDir: "{app}"; DestName: "cineremote-vcam-{#AppVersion}.dll"; Tasks: webcam; Flags: ignoreversion regserver restartreplace uninsrestartdelete
Source: "{#Root}\vcam\out\cineremote-vcam-host.exe"; DestDir: "{app}"; Tasks: webcam; Flags: ignoreversion

[Dirs]
; webcam DLL log folder: written by the Frame Server (LocalService), readable by users
Name: "{commonappdata}\CineRemote"; Tasks: webcam

[Icons]
Name: "{autoprograms}\CineRemote"; Filename: "{app}\CineRemote.exe"
Name: "{autoprograms}\CineRemote (demo mode)"; Filename: "{app}\CineRemote.exe"; Parameters: "--demo"
Name: "{autodesktop}\CineRemote"; Filename: "{app}\CineRemote.exe"; Tasks: desktopicon

[Run]
; SYSTEM + Administrators full, LocalService (S-1-5-19) modify, Users read (same as vcam\install_vcam.bat)
Filename: "{sys}\icacls.exe"; Parameters: """{commonappdata}\CineRemote"" /inheritance:r /grant:r *S-1-5-18:(OI)(CI)F *S-1-5-32-544:(OI)(CI)F *S-1-5-19:(OI)(CI)M *S-1-5-32-545:(OI)(CI)RX"; Tasks: webcam; Flags: runhidden
Filename: "{app}\CineRemote.exe"; Description: "{cm:LaunchProgram,CineRemote}"; Flags: nowait postinstall skipifsilent runasoriginaluser
Filename: "{app}\CineRemote.exe"; Flags: nowait runasoriginaluser; Check: Relaunch

[UninstallRun]
Filename: "{app}\CineRemote.exe"; Parameters: "--remove-webcams"; RunOnceId: "RemoveWebcams"; Flags: runhidden waituntilterminated

[UninstallDelete]
Type: filesandordirs; Name: "{commonappdata}\obs-studio\plugins\cineremote-obs"
Type: filesandordirs; Name: "{commonappdata}\CineRemote"

[Code]
function Relaunch: Boolean;
begin
  Result := WizardSilent and (ExpandConstant('{param:relaunch|0}') = '1');
end;
