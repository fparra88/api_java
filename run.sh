#!/usr/bin/env bash
# Compila y ejecuta el API en primer plano (desarrollo / prueba manual).
# En produccion usar el servicio systemd (ver deploy/).
set -euo pipefail
cd "$(dirname "$0")"

./build.sh

# La JVM expande el comodin lib/* en el classpath.
# .env se lee desde este directorio (working dir).
exec java -cp "out:lib/*" com.fyc.pendientes.Main
