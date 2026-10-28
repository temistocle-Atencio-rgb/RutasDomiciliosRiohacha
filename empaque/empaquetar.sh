#!/bin/sh
# Arma DomiRuta con Java incluido en Linux o macOS. Requiere JDK 17 o superior.
set -e
cd "$(dirname "$0")/.."
VERSION=2.0.0
rm -rf target/empaque dist
mkdir -p target/empaque/clases target/empaque/entrada
javac -encoding UTF-8 --release 17 -cp lib/sqlite-jdbc-3.50.3.0.jar -d target/empaque/clases $(find src/main/java -name "*.java")
cp -r src/main/resources/* target/empaque/clases/
echo "Class-Path: sqlite-jdbc-3.50.3.0.jar" > target/empaque/manifiesto.txt
jar --create --file target/empaque/entrada/DomiRuta.jar --main-class rutas.Main --manifest=target/empaque/manifiesto.txt -C target/empaque/clases .
cp lib/sqlite-jdbc-3.50.3.0.jar target/empaque/entrada/
cp -r datos target/empaque/entrada/
jpackage --type app-image --name DomiRuta --app-version $VERSION --input target/empaque/entrada \
  --main-jar DomiRuta.jar --main-class rutas.Main \
  --add-modules java.base,java.desktop,java.sql,java.net.http,jdk.crypto.ec,jdk.localedata \
  --jlink-options "--strip-debug --no-header-files --no-man-pages --include-locales=es,en" --dest dist
cp empaque/LEEME.txt dist/DomiRuta/ 2>/dev/null || cp empaque/LEEME.txt dist/
echo "Listo: dist/DomiRuta"
