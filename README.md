# WorkMeter backend

Primer hito implementado: login, proyectos mínimos, CRUD y fichaje de intervalos,
importación de respaldos JSON con simulación y resolución explícita de conflictos.
Las relaciones laborales, calendarios, salarios y freelance siguen en las fases
posteriores de BACKEND_DESIGN.md.

## Arquitectura

- `domain`: Kotlin/JDK, modelos e invariantes, sin framework.
- `application`: casos de uso y puertos de salida; depende solo del dominio.
- `backend`: Quarkus REST, cookies/JWT, Hibernate ORM y Flyway.
- PostgreSQL 18; Kotlin 2.4.20; Quarkus 3.40.1; bytecode Java 21.
- Los tests de arquitectura prohíben dependencias de los módulos internos hacia
  Quarkus, Jakarta, Hibernate o Jackson.
- Los writes de registros se serializan por miembro. PostgreSQL refuerza la
  unicidad del intervalo abierto y la exclusión de intervalos solapados.
- PATCH, cierre y borrado exigen la versión del registro para evitar perder una
  edición concurrente. PATCH recibe la representación editable completa.

## Arranque local de prueba

Requisitos: JDK 21 o superior, Maven 3.9 y Docker activo (Colima sirve en macOS).

Desde este directorio:

```sh
mvn install -DskipTests
```

Configura en tu terminal `WORKMETER_BOOTSTRAP_EMAIL` y
`WORKMETER_BOOTSTRAP_PASSWORD` (12 caracteres mínimo, 72 bytes máximo);
`WORKMETER_BOOTSTRAP_NAME` es opcional. No se incluye una cuenta real ni se
importan contraseñas de Firebase. La creación inicial no cambia contraseñas de
cuentas ya existentes; elimina estas variables después de crear tu cuenta.

```sh
mvn -pl backend quarkus:dev
```

Quarkus arranca PostgreSQL 18 automáticamente en desarrollo si no se configura
otra conexión. Esa base es de ensayo: no se garantiza que sus datos sobrevivan
al cierre. La clave JWT conocida y las cookies sin Secure solo se habilitan en
desarrollo/test. La API escucha en 127.0.0.1:8080.

En el directorio del frontend:

```sh
VITE_BACKEND=api npm run dev
```

Vite dirige `/api` al backend en el mismo origen. Sin `VITE_BACKEND=api`, la
aplicación conserva el modo Firebase. El cambio de autenticación y de registros
se activa conjuntamente. No se ha cambiado ningún despliegue ni datos de Firebase.

## Base local persistente

Configura `WORKMETER_DB_PASSWORD` en tu terminal y arranca:

```sh
docker compose up -d
export QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:54328/workmeter
export QUARKUS_DATASOURCE_USERNAME=workmeter
export QUARKUS_DATASOURCE_PASSWORD="$WORKMETER_DB_PASSWORD"
mvn -pl backend quarkus:dev
```

El volumen guarda los datos al parar el servicio. Haz copias con pg_dump antes
de migrar datos reales. Las pruebas usan un contenedor independiente cuando no
se les proporcionan variables de conexión: no ejecutes tests contra tu base real.

## API y sesión

OpenAPI está en `/q/openapi` y Swagger UI en `/q/swagger-ui` en desarrollo.
Salud: `/q/health`.

- `POST /api/v1/auth/login`: email/password; devuelve perfil y cookie HttpOnly.
- `GET /api/v1/me`, `POST /api/v1/auth/logout`: sesión de 8 horas, revocable.
- `GET/POST /api/v1/projects`: catálogo mínimo, con ID de Firebase opcional.
- `GET /api/v1/time-entries?from=...&to=...&offset=0&limit=200`: rango de
  instantes [from, to), con registros que lo atraviesan; máximo 500 por página.
- `GET /api/v1/time-entries/open`: recupera el fichaje abierto aunque sea de
  otra semana; devuelve 204 cuando no existe.
- `POST /api/v1/time-entries`, `POST /api/v1/time-entries/start`.
- `GET/PATCH /api/v1/time-entries/{id}`.
- `POST /api/v1/time-entries/{id}/close`: end y version.
- `DELETE /api/v1/time-entries/{id}?version=...`.

Todas las mutaciones, incluido login, requieren la cabecera
`X-WorkMeter-Client: web`. No se habilita CORS; la interfaz debe servirse junto
con `/api` mediante un proxy del mismo origen. Las sesiones no se guardan en
localStorage. El login limita a 30 intentos/minuto por instancia (aplicación
personal); no es un limitador distribuido.

La vista muestra fechas en Europe/Madrid, permite crear/editar/borrar intervalos,
iniciar/cerrar el reloj y consultar periodos. Los totales recortan los intervalos
en los límites consultados; los abiertos se muestran aparte.

## Ensayo y migración

1. Exporta los datos actuales de Firebase y conserva intacto el original.
2. Crea los proyectos, asignando su ID externo de Firebase cuando se conozca.
3. Abre `/import` en modo API, selecciona el archivo y pulsa Simular.
4. Revisa el informe. Los IDs repetidos, fechas divergentes, negativos y
   solapamientos se notifican; no se corrigen automáticamente.
5. Importa los registros válidos y descarga el informe. Repetir la importación
   del mismo archivo omite los ya existentes; un ID existente con cambios se
   informa como conflicto.
6. Contrasta conteos y duraciones; resuelve las anomalías manualmente y repite el
   ensayo. Un intervalo antiguo abierto se conserva: debe revisarse y cerrarse
   con su hora real o excluirse antes de empezar a fichar.

Se preserva el JSON original de cada entrada importada, incluidos teamId y
metadatos desconocidos, sin mostrarlos como conceptos laborales deducidos.
Los registros con proyecto sin mapear quedan sin clasificar y conservan su ID
externo en el original. El informe relaciona cada fila con su ID y resultado.
Los conflictos no se insertan; siguen en el respaldo original y el informe.

También existe `POST /api/v1/admin/import-legacy` con:
```json
{ "records": [], "projectMappings": {}, "dryRun": true }
```
`dryRun` es true por defecto. `projectMappings` mapea ID de proyecto Firebase
a UUID de proyecto propio. Solo opera sobre el miembro autenticado. Máximo
10000 registros/lote y 10 MB HTTP (8 MB en la interfaz). Se guardan los válidos
de un lote en una transacción; un fallo técnico revierte el lote completo.

El backup local del 10/05/2024 contiene 443 entradas entre el 04/05/2023 y el
10/05/2024. Se usa para ensayo, no representa la exportación actual.

## Validación

```sh
mvn verify
```

Incluye dominio, arquitectura, decodificación, autenticación, CSRF, aislamiento,
CRUD, concurrencia e importación contra PostgreSQL 18 usando Testcontainers.
La prueba del corpus histórico se ejecuta si el repositorio frontend hermano
contiene el backup; las pruebas sintéticas de importación siempre se ejecutan.

En el frontend: `npm test` y `VITE_BACKEND=api npm run build`.

## Despliegue posterior

El empaquetado JVM queda en `backend/target/quarkus-app`; se arranca con
`java -jar backend/target/quarkus-app/quarkus-run.jar`. Configura PostgreSQL,
`WORKMETER_JWT_SECRET` aleatorio de al menos 32 bytes y HTTPS con proxy de
mismo origen para interfaz y API. En producción las cookies son Secure.
No se ha desplegado ni cortado Firebase. La compilación nativa queda pendiente
de validación; no es requisito del primer hito.
