@echo off
rem Arma DomiRuta listo para entregar a un cliente: carpeta con el programa,
rem Java incluido (no hay que instalar nada) y los mapas.
rem Uso: doble clic o "empaque\empaquetar.bat" desde la carpeta del proyecto. Requiere JDK 17 o superior.
setlocal
cd /d "%~dp0\.."
set VERSION=2.0.0

rem Busca el JDK (jar y jpackage no siempre estan en el PATH)
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jpackage.exe" set "JDK=%JAVA_HOME%\bin\"
if not defined JDK for /d %%d in ("%ProgramFiles%\Java\jdk*" "%ProgramFiles%\Eclipse Adoptium\jdk*" "%ProgramFiles%\Microsoft\jdk*" "%ProgramFiles%\Amazon Corretto\jdk*") do if exist "%%~d\bin\jpackage.exe" set "JDK=%%~d\bin\"
if not defined JDK (
  echo No se encontro un JDK 17 o superior. Instale el JDK o defina JAVA_HOME.
  goto error
)
echo Usando el JDK de %JDK%

echo [1/4] Compilando...
if exist target\empaque rmdir /s /q target\empaque
mkdir target\empaque\clases
dir /s /b src\main\java\*.java > target\empaque\fuentes.txt
"%JDK%javac" -encoding UTF-8 --release 17 -d target\empaque\clases @target\empaque\fuentes.txt || goto error
xcopy /e /i /q src\main\resources target\empaque\clases >nul

echo [2/4] Armando DomiRuta.jar...
mkdir target\empaque\entrada
"%JDK%jar" --create --file target\empaque\entrada\DomiRuta.jar --main-class rutas.Main -C target\empaque\clases . || goto error
xcopy /e /i /q datos target\empaque\entrada\datos >nul
del /q target\empaque\entrada\datos\*.py 2>nul

echo [3/4] Creando la aplicacion con Java incluido (jpackage)...
if exist dist rmdir /s /q dist
"%JDK%jpackage" --type app-image --name DomiRuta --app-version %VERSION% --vendor "DomiRuta" ^
  --description "Rutas de domicilios para La Guajira" --icon empaque\domiruta.ico ^
  --input target\empaque\entrada --main-jar DomiRuta.jar --main-class rutas.Main ^
  --add-modules java.base,java.desktop,java.net.http,jdk.crypto.ec,jdk.localedata ^
  --jlink-options "--strip-debug --no-header-files --no-man-pages --include-locales=es,en" ^
  --java-options "-Dfile.encoding=UTF-8" --dest dist || goto error

echo [4/4] Comprimiendo...
copy /y empaque\LEEME.txt dist\DomiRuta\ >nul
powershell -NoProfile -Command "Compress-Archive -Path dist\DomiRuta -DestinationPath dist\DomiRuta-%VERSION%-windows.zip -Force" || goto error

echo.
echo Listo: dist\DomiRuta-%VERSION%-windows.zip
echo Para probar: dist\DomiRuta\DomiRuta.exe
exit /b 0

:error
echo.
echo Hubo un error armando el paquete.
exit /b 1
