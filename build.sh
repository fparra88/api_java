#!/usr/bin/env bash
# Descarga el conector MySQL JDBC (si falta) y compila a out/.
# Uso en Ubuntu server antes de habilitar el servicio systemd.
set -euo pipefail
cd "$(dirname "$0")"

VER="9.1.0"
JAR="lib/mysql-connector-j-$VER.jar"
URL="https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/$VER/mysql-connector-j-$VER.jar"

mkdir -p lib
if [ ! -f "$JAR" ]; then
  echo "Descargando conector MySQL JDBC $VER ..."
  curl -fSL "$URL" -o "$JAR"
fi

rm -rf out
mkdir -p out
find src -name '*.java' > sources.txt
javac -cp "lib/*" -d out @sources.txt
echo "OK: compilado en out/"
