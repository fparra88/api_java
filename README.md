# API Pendientes F&C

API REST en Java puro (sin frameworks) que lee la tabla `pendientes` de MySQL y
los atiende por **prioridad** usando `PriorityQueue`, nodos y `HashMap`.

## Estructura de datos usada
- **Nodo / ColaPrioridad** ([Nodo.java](src/com/fyc/pendientes/Nodo.java), [ColaPrioridad.java](src/com/fyc/pendientes/ColaPrioridad.java)) — cola con prioridad encadenada hecha a mano (didactica).
- **PriorityQueue nativa** — usada en producción dentro de [GestorPendientes.java](src/com/fyc/pendientes/GestorPendientes.java) (insertar/atender O(log n)).
- **HashMap** — registro `id -> Pendiente` (búsqueda O(1)) y contadores por prioridad/estado.

Orden de atención: `prioridad` (alta=1, media=2, baja=3) → `fecha_promesa` más cercana → `fecha` más antigua.

## Requisitos
- Java 17+ (JDK, probado en Java 25). En Ubuntu: `sudo apt install default-jdk`
- MySQL en marcha
- `curl` (para bajar el conector JDBC la primera vez)

## Configuración (desarrollo)
1. Copia `.env.example` a `.env` y pon tus credenciales:
   ```
   cp .env.example .env
   ```
2. Crea la tabla y datos de prueba:
   ```
   mysql -u root -p < schema.sql
   ```
   > `schema.sql` crea la base `fyc`. Si usas otra, ajusta `DB_NAME` en `.env`.

## Ejecutar (desarrollo)
Linux/Mac:
```bash
./run.sh
```
Windows:
```powershell
.\run.ps1
```
Descarga el conector, compila a `out/` y levanta el API en `http://localhost:8080`.

## Despliegue en Ubuntu Server con systemd

1. Clona el repo y compila:
   ```bash
   sudo git clone <URL_DEL_REPO> /opt/pendientes-api
   cd /opt/pendientes-api
   sudo ./build.sh          # baja el conector y compila a out/
   ```
2. Crea el usuario de servicio (sin login) y dale la carpeta:
   ```bash
   sudo useradd --system --no-create-home --shell /usr/sbin/nologin pendientes
   sudo chown -R pendientes:pendientes /opt/pendientes-api
   ```
3. Credenciales fuera del repo (las inyecta systemd; `Env.java` lee variables del SO, no hace falta `.env` en producción):
   ```bash
   sudo mkdir -p /etc/pendientes-api
   sudo cp deploy/pendientes-api.env.example /etc/pendientes-api/pendientes-api.env
   sudo nano /etc/pendientes-api/pendientes-api.env
   sudo chown root:pendientes /etc/pendientes-api/pendientes-api.env
   sudo chmod 640 /etc/pendientes-api/pendientes-api.env
   ```
4. Instala y habilita el servicio:
   ```bash
   sudo cp deploy/pendientes-api.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now pendientes-api
   ```
5. Operación:
   ```bash
   sudo systemctl status pendientes-api      # estado
   sudo journalctl -u pendientes-api -f      # logs en vivo
   sudo systemctl restart pendientes-api     # tras un cambio + ./build.sh
   ```

> El comodín `java -cp out:lib/*` lo expande la JVM (no el shell), por eso funciona dentro del `ExecStart` de systemd. `SuccessExitStatus=143` evita que `systemctl stop` (SIGTERM → exit 143) se registre como fallo.

## Endpoints
| Método | Ruta | Acción |
|--------|------|--------|
| GET  | `/api/pendientes`           | Lista en orden de prioridad |
| GET  | `/api/pendientes/siguiente` | Próximo a atender (sin sacar) |
| GET  | `/api/pendientes/{id}`      | Busca por id (HashMap O(1)) |
| POST | `/api/pendientes/atender`   | Atiende el siguiente → estado `en proceso` |
| POST | `/api/recargar`             | Recarga la cola desde MySQL |
| GET  | `/api/estadisticas`         | Conteos por prioridad/estado |

Ejemplo:
```powershell
curl http://localhost:8080/api/pendientes
curl -Method POST http://localhost:8080/api/pendientes/atender
```

## Tabla `pendientes`
`fecha, usuario, actividad, prioridad(baja|media|alta), estado(pendiente|en proceso|terminado|en revision), observaciones, fecha_promesa`
