# API Java — Backend de datos y lógica central

## Idioma
Responde siempre en español (Latinoamérica), incluidos comentarios de código y mensajes de commit.

## Stack
- Java 17+
- Spring Boot (versión: [tu versión, ej: 3.x])
- JPA/Hibernate (ORM para acceso a datos)
- Base de datos: SQL (ver `schema.sql`)
- Autenticación: JWT

## Comandos
- Compilar: `mvn clean compile`
- Tests: `mvn test`
- Ejecutar: `mvn spring-boot:run`
- Build JAR: `mvn clean package`

## Estructura del proyecto
api_java/
├── src/main/java/[package]/
│ ├── entities/ # Modelos JPA (Usuario, Cliente, Venta, Cotización, etc.)
│ ├── dto/ # Data Transfer Objects (respuestas de API)
│ ├── repositories/ # Interfaces Repository (acceso a BD)
│ ├── services/ # Lógica de negocio
│ ├── controllers/ # Endpoints REST
│ ├── config/ # Configuración (BD, JWT, CORS, etc.)
│ └── utils/ # Utilidades y helpers
├── src/main/resources/
│ ├── application.properties # Configuración de ambiente
│ ├── db/migration/ # Scripts de migración (Flyway)
│ └── schema.sql # Esquema de referencia
├── CLAUDE.md # Este archivo
├── pom.xml # Dependencias Maven
├── .env # Variables de entorno (NO versionado)
└── .env.example # Template para .env
## Rol en el sistema
**Fuente de verdad de datos.** Todos los modelos principales (Usuario, Cliente, Venta, 
Cotización, Inventario) nacen aquí como entidades JPA.

- `api_zeutica1` (FastAPI) consume estos datos y agrega cálculos (KPIs, reportes).
- `panel_gerencia` (frontend JSX) consume mediante endpoints REST.

## Modelos principales (entidades JPA)

| Entidad | Tabla BD | Responsabilidad |
|---------|----------|-----------------|
| Usuario | usuarios | Autenticación, roles, permisos |
| Cliente | clientes | Datos del cliente (contacto, empresa, etc.) |
| Venta | ventas | Transacción de venta (items, montos, estado) |
| Cotización | cotizaciones | Presupuesto previo a venta |
| Inventario | inventario | Stock de productos por ubicación |
| [Otros...] | ... | [completar según tu BD] |

## Convenciones de código

### Nombres
- **Tablas/Campos en BD:** snake_case (ej: `descuento_aplica`, `created_at`)
- **Clases Java:** PascalCase (ej: `DescuentoAplica`, `Venta`)
- **Métodos:** camelCase (ej: `getDescuentoAplica()`, `calcularTotal()`)
- **Constantes:** UPPER_SNAKE_CASE (ej: `ESTADO_ACTIVO = "ACTIVO"`)

### DTOs
Siempre devolver datos formateados en DTOs, no entidades raw:
```java
// ❌ Evitar
@GetMapping("/ventas/{id}")
public Venta obtenerVenta(@PathVariable Long id) { ... }

// ✅ Hacer
@GetMapping("/ventas/{id}")
public VentaDTO obtenerVenta(@PathVariable Long id) { ... }
```

### Endpoints
- `GET /api/[recurso]` — listar con paginación
- `GET /api/[recurso]/{id}` — obtener uno
- `POST /api/[recurso]` — crear
- `PUT /api/[recurso]/{id}` — actualizar completo
- `PATCH /api/[recurso]/{id}` — actualizar parcial
- `DELETE /api/[recurso]/{id}` — eliminar

## Autenticación (JWT)
- Token generado al login, enviado en header `Authorization: Bearer <token>`
- Validado en cada request mediante filtro/interceptor Spring
- Expiración: [tu tiempo, ej: 24 horas]

## Base de datos
- Versión: [tu versión SQL, ej: PostgreSQL 14+]
- Migraciones: Flyway (scripts en `src/main/resources/db/migration/`)
- Ver esquema completo en `schema.sql`

## Reglas de negocio a codificar
- No permitir ventas con stock insuficiente
- Cálculo de precios: [especificar lógica]
- Estados válidos de Venta: [PENDIENTE, CONFIRMADA, ENTREGADA, CANCELADA, etc.]
- [Otras reglas según tu dominio]

## Cambios cross-service
Cuando modifiques un modelo (ej: agregar campo a Venta):

1. **api_java**: actualizar entidad, DTO, migración BD, endpoint
2. **api_zeutica1**: actualizar schema Pydantic, ruta si es necesario
3. **panel_gerencia**: actualizar tipo/interfaz, llamadas fetch

Ver skill `cross-service-change` en raíz para checklist detallado.

## Integración con otros servicios
- **api_zeutica1 (FastAPI)** llama a este backend vía HTTP (`http://localhost:8080/api/...`)
- **panel_gerencia (JSX)** llama a este backend vía fetch/axios (`http://localhost:8080/api/...`)
- URLs base definidas en `.env` (no hardcodear)

## Testing
- Unit tests: `src/test/java/`
- Datos de prueba: fixtures en `src/test/resources/data.sql`
- Correr con `mvn test`

## Deployment
- [Tu estrategia: Docker, EC2, JAR directo, etc.]
- [Tu proceso de CI/CD]

## Notas importantes
- Las colas ahorita son simples (FIFO), sin prioridades
- [Agregar otras consideraciones específicas de tu proyecto]