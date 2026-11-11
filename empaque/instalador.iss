; Instalador de DomiRuta (Inno Setup 6). Primero arme el programa con empaquetar.bat;
; despues abra este archivo en Inno Setup y oprima Compilar (o Ctrl+F9).
; Sale: dist\DomiRuta-Instalador-2.0.0.exe

#define Version "2.0.0"

[Setup]
AppId={{6E2B7C1A-4D3F-4B8E-9A61-D0A1E5C2F7B4}
AppName=DomiRuta
AppVersion={#Version}
AppVerName=DomiRuta {#Version}
AppPublisher=Temistocle Atencio
AppComments=Caja de la tienda y rutas de domicilios para La Guajira
; se instala para el usuario, sin pedir permisos de administrador
PrivilegesRequired=lowest
DefaultDirName={autopf}\DomiRuta
DefaultGroupName=DomiRuta
DisableProgramGroupPage=yes
OutputDir=..\dist
OutputBaseFilename=DomiRuta-Instalador-{#Version}
SetupIconFile=domiruta.ico
UninstallDisplayIcon={app}\DomiRuta.exe
UninstallDisplayName=DomiRuta
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "es"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "escritorio"; Description: "Crear un acceso directo en el escritorio"; GroupDescription: "Accesos directos:"

[Files]
; todo lo que armo empaquetar.bat: el programa, Java incluido y los mapas
Source: "..\dist\DomiRuta\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\DomiRuta"; Filename: "{app}\DomiRuta.exe"; IconFilename: "{app}\DomiRuta.ico"
Name: "{group}\Leeme"; Filename: "{app}\LEEME.txt"
Name: "{group}\Desinstalar DomiRuta"; Filename: "{uninstallexe}"
Name: "{autodesktop}\DomiRuta"; Filename: "{app}\DomiRuta.exe"; IconFilename: "{app}\DomiRuta.ico"; Tasks: escritorio

[Run]
Filename: "{app}\DomiRuta.exe"; Description: "Abrir DomiRuta ahora"; Flags: nowait postinstall skipifsilent

; Los datos de la tienda (productos, ventas, clientes) viven en %APPDATA%\DomiRuta:
; desinstalar el programa NO los borra.
