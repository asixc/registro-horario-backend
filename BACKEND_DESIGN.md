# Backend Design — WorkMeter

Reemplazo de Firebase con Quarkus + Kotlin nativo. Single-user, single-tenant,
orientado a uso personal + freelance + asalariado.

## 1. Stack

| Concern | Decisión |
|---|---|
| Lenguaje | Kotlin 2.0+ |
| Framework | Quarkus 3.x |
| Build | Maven |
| DB | PostgreSQL 16 |
| ORM | Hibernate Panache con entity mappers explícitos |
| Migrations | Flyway (`db/migration/V*.sql`) |
| Auth | JWT HS256 + bcrypt |
| HTTP | REST + JSON (Quarkus REST / JAX-RS) |
| Specs | Cucumber + JUnit 5 + RestAssured |
| Native | GraalVM (`mvn package -Pnative`) |
| Testcontainers | Postgres + Quarkus en CI |

## 2. Modelo de dominio

### 2.1 Aggregates

#### Member (root)
- `id: UUID`
- `email: String UNIQUE`
- `displayName: String`
- `passwordHash: String`
- `weeklyQuotaMinutes: Int` (default 24000 = 40h)
- `createdAt: Instant`

#### Client (root)
- `id: UUID`, `memberId: UUID`, `name: String`, `createdAt: Instant`

#### Project (root)
- `id: UUID`, `memberId: UUID`, `clientId: UUID?`
- `name: String`
- `category: ProjectCategory` enum
- `hourlyRateCents: Int?`
- `currency: String` (default "EUR")
- `color: String`, `archivedAt: Instant?`, `createdAt: Instant`

```kotlin
enum class ProjectCategory {
  LABOR_SALARIED,   // cuenta hacia 40h, tarifa = nómina / horas
  LABOR_FREELANCE,  // NO cuenta hacia 40h, tarifa explícita, facturable
  PERSONAL_OWN,     // proyecto personal propio (e.g. WorkMeter)
  PERSONAL_LEARNING // cursos, lecturas
}
```

#### TimeEntry (root)
- `id: UUID`, `memberId: UUID`, `projectId: UUID?` (nullable = huérfano o ausencia)
- `kind: TimeEntryKind`
- `start: Instant`, `end: Instant?` (null = abierto)
- `description: String?`
- `externalId: String?` (docId de Firestore legacy)
- `createdAt: Instant`, `updatedAt: Instant`
- Invariantes:
  - `end IS NULL OR end >= start`.
  - Si `kind != WORK` entonces `projectId IS NULL`.
  - Si `project_id IS NULL`, la entry está EXCLUIDA de TODOS los totales y solo aparece en el listado TODO.

```kotlin
enum class TimeEntryKind {
  WORK, VACATION, HOLIDAY, SICK_LEAVE, PERMISO, PERSONAL
}
```

#### CalendarEvent (root)
- `id: UUID`, `memberId: UUID`, `date: LocalDate`
- `kind: CalendarEventKind`
- `scope: String?` (CCAA, municipio…)
- `description: String`

```kotlin
enum class CalendarEventKind {
  HOLIDAY_NATIONAL, HOLIDAY_REGIONAL, HOLIDAY_LOCAL
}
```

#### AbsenceEntry (root)
- `id: UUID`, `memberId: UUID`, `date: LocalDate`
- `kind: AbsenceKind` (VACATION, SICK_LEAVE, PERMISO)
- `notes: String?`

### 2.2 Value Objects

- `HourlyRate(amountCents: Long, currency: String)`
- `WeekId(year: Int, week: Int)` — siempre ISO (semana que inicia en lunes, plugin `isoWeek` de dayjs en frontend)
- `Money(amountCents: Long, currency: String)`
- `DurationSeconds(seconds: Long)`
- `TimeInterval(start: Instant, end: Instant?)` con invariantes encapsuladas

### 2.3 Reglas de cuotas y facturación

| Categoría | 40h weekly salaried | Tarifa horaria | Facturable | Aparece en |
|---|---|---|---|---|
| LABOR_SALARIED | SÍ | implícita (gross/net nómina / horas) | NO | Reports salaried |
| LABOR_FREELANCE | NO | explícita en Project | SÍ | Invoicing report |
| PERSONAL_OWN | NO | n/a | NO | Personal report |
| PERSONAL_LEARNING | NO | n/a | NO | Personal report |

**Festivos y quota salaried:**
- Un festivo (CalendarEvent) en una fecha NO reduce la quota salaried de esa semana (la quota de 40h sigue siendo 40h completa; el festivo es simplemente un día no laborable asalariado por ley).
- Una TimeEntry con `kind=WORK` y proyecto `LABOR_SALARIED` en un día festivo se EXCLUYE del cómputo de la quota semanal. Se mantiene registrada (para auditoría de nómina) pero no cuenta. Coherente con "festivo por ley no computa hacia las 40h laborables".
- Una TimeEntry con `kind=WORK` y proyecto `LABOR_FREELANCE` en festivo SÍ cuenta hacia la facturación freelance (un autónomo puede trabajar cualquier día).
- Una TimeEntry con `kind=WORK` y proyecto `PERSONAL_*` en festivo SÍ cuenta hacia el bloque personal (es tu tiempo, da igual el día).
- `kind=VACATION/HOLIDAY/SICK_LEAVE/PERMISO` aparecen como dato informativo en el resumen semanal, sin afectar cuota ni totales.
- `project_id IS NULL` (huérfanos) **NO cuentan** en ningún total hasta que el usuario les asigne proyecto en la vista TODO.

Tabla resumen (entry × día festivo):

| kind | category del proyecto | ¿Counta salaried? | ¿Counta freelance? | ¿Aparece en weekly? |
|---|---|---|---|---|
| WORK | LABOR_SALARIED | NO si es festivo, SÍ si no | NO | sí |
| WORK | LABOR_FREELANCE | NO | **SÍ** | sí |
| WORK | PERSONAL_* | NO | NO | sí (bloque personal) |
| VACATION | (NULL) | no modifica quota | no | sí (ausencia) |
| HOLIDAY | (NULL) | no modifica quota | no | sí (festivo) |
| SICK_LEAVE | (NULL) | no modifica quota | no | sí (baja) |
| PERMISO | (NULL) | no modifica quota | no | sí (permiso) |
| (cualquier kind) | (NULL, huérfano) | NO | NO | solo en TODO view |

### 2.4 Regla de facturación freelance por fecha

El reporte de facturación freelance computa cada entry por su **fecha de `start`**, no por la fecha del PATCH que la asignó a un proyecto. Razón: una hora freelance trabajada en diciembre debe contar como ingreso de diciembre, sin importar cuándo se le asignó el proyecto.

```kotlin
// FreelanceInvoicingUseCase
fun invoiceForProject(projectId: UUID, year: Int): Invoice {
  val entries = repo.findByProjectAndYear(projectId, year)
  return entries.groupBy { YearMonth.from(it.start) }
}
```

## 3. Tabla de mapeo legacy → nuevo

**Equivalencias canónicas únicas:**

| Legacy (Firebase) | Nuevo (Postgres) | Transformación |
|---|---|---|
| `inicio` | `time_entries.start` (TIMESTAMPTZ UTC) | rename directo |
| `fin` (nullable) | `time_entries.end` (TIMESTAMPTZ NULL o valor) | preserva el null para abiertos |

El resto son derivados y **NO se persisten**:

| Legacy | Nuevo | Notas |
|---|---|---|
| `semana` | (derivar) | recalcular ISO desde `start` |
| `week` | (derivar) | recalcular ISO desde `start` |
| `year` | (derivar) | recalcular desde `start` |
| `año` | (derivar) | redundante con `year` |
| `start` | `time_entries.start` | si también hay `inicio`, sanea al más temprano |
| `end` | `time_entries.end` | idem |
| `id` (Firestore doc id) | `time_entries.external_id` | UNIQUE (member_id, external_id) |
| `projectId` | `time_entries.project_id` (FK, **nullable**) | null = huérfano TODO |
| `teamId` | (no modelar en v1) | ningún backup lo trae |
| (no existe) | `time_entries.kind` | inferir = WORK para todos los legacy |
| (no existe) | `member_id` | todos al único member |

**Importante:** no se mantienen columnas duplicadas (`semana` + `week`, `year` + `año`, `inicio` + `start`). Esto evita el bug histórico que tuvimos con la convención US de `dayjs.week()`.

## 4. Análisis de los datos legados

Backup analysis del corpus Firebase (estado a 10/05/2024):

| Backup | Registros | projectId | Rango |
|---|---|---|---|
| `bakcup08.05.2024.json` | 439 | — (no existía el campo) | 2023-05-04 → 2024-05-08 |
| `backup10.05.2024.json` | 443 | `nAK2bxQD84k2FFoe2Oin` (Fisterra) | 2023-05-04 → 2024-05-10 |
| `src/assets/data.json` | 443 | `nAK2bxQD84k2FFoe2Oin` (Fisterra) | 2023-05-04 → 2024-05-10 |

**Cruce:** 0 fechas exclusivas de huérfanos. **El primer registro con projectId=Fisterra es 2023-05-04**, misma fecha del primer registro histórico.

**Implicación para los huérfanos:** los 439 registros sin projectId viven en fechas donde ya existían registros de Fisterra. No se puede deducir su proyecto por fecha. Como ninguno es freelance (no existía en legacy), se asume que todos son LABOR_SALARIED. Quedan en vista TODO del frontend para asignación manual, sin automatización masiva.

**Anomalías detectadas:**
- 1 registro con `end < start` (~−2 h).
- Producción real puede traer además `teamId` y `año` que el código actual (`Home.vue openInterval`) escribe pero los backups no reflejan. **Antes de migrar**: exportar desde Firestore en vivo.

## 5. Arquitectura hexagonal

```
src/main/kotlin/com/workmeter/
├── domain/
│   ├── model/                       (entities + value objects, Kotlin puro)
│   │   ├── Member.kt
│   │   ├── Client.kt
│   │   ├── Project.kt
│   │   ├── TimeEntry.kt
│   │   ├── CalendarEvent.kt
│   │   ├── AbsenceEntry.kt
│   │   └── valueobjects/
│   │       ├── HourlyRate.kt
│   │       ├── WeekId.kt
│   │       ├── Money.kt
│   │       ├── DurationSeconds.kt
│   │       └── TimeInterval.kt
│   └── ports/                        (interfaces de repositorio)
│       ├── MemberRepository.kt
│       ├── ProjectRepository.kt
│       ├── TimeEntryRepository.kt
│       ├── CalendarEventRepository.kt
│       ├── AbsenceEntryRepository.kt
│       └── PasswordHasher.kt
├── application/
│   ├── usecases/
│   │   ├── auth/
│   │   │   └── LoginUseCase.kt
│   │   ├── timeentry/
│   │   │   ├── StartTimeEntryUseCase.kt
│   │   │   ├── CloseTimeEntryUseCase.kt
│   │   │   ├── ListTimeEntriesUseCase.kt
│   │   │   ├── DeleteTimeEntryUseCase.kt
│   │   │   └── AssignProjectUseCase.kt
│   │   ├── reports/
│   │   │   ├── WeeklyTotalsUseCase.kt
│   │   │   ├── YearlySummaryUseCase.kt
│   │   │   ├── YearComparisonUseCase.kt
│   │   │   └── FreelanceInvoicingUseCase.kt
│   │   ├── calendar/
│   │   │   ├── GetCalendarUseCase.kt
│   │   │   └── AddHolidayUseCase.kt
│   │   └── absences/
│   │       └── MarkAbsenceUseCase.kt
│   └── policy/
│       ├── WeeklyQuotaPolicy.kt
│       └── HourlyRatePolicy.kt
└── infrastructure/
    ├── persistence/
    │   ├── entities/                 (Panache entities, internals)
    │   ├── mappers/                  (domain ↔ entity)
    │   └── repositories/             (impl de los ports)
    ├── rest/
    │   ├── AuthResource.kt
    │   ├── ProjectResource.kt
    │   ├── TimeEntryResource.kt
    │   ├── CalendarResource.kt
    │   ├── AbsenceResource.kt
    │   └── ReportsResource.kt
    ├── auth/
    │   ├── JwtService.kt
    │   └── BcryptPasswordHasher.kt
    └── config/                       (Quarkus config + StartupObserver)

src/main/resources/
├── application.properties
├── legacy.json                       (datos de los backups normalizados)
└── db/migration/
    ├── V1__init.sql
    ├── V2__seed_member.sql
    └── V3__seed_legacy.sql           (o vía endpoint admin idempotente)

src/test/
├── kotlin/                           (tests unitarios del dominio)
└── resources/features/               (Cucumber .feature files)
    ├── clock_in.feature
    ├── close_interval.feature
    ├── weekly_totals.feature
    ├── vacation.feature
    ├── holiday_calendar.feature
    ├── freelance_invoicing.feature
    ├── year_over_year_comparison.feature
    ├── personal_vs_labor.feature
    ├── orphans.feature
    └── legacy_import.feature
```

### Por qué hexagonal

El dominio (cálculo de totales semanales, normalización de TimeEntryKind por
categoría, hashing de tarifas) queda puro en `domain/`. Permite:
- Tests unitarios rápidos sin Quarkus.
- Cambiar persistencia (Postgres ↔ otro) sin tocar reglas de negocio.
- Native compilation más limpia (menos reflection).

## 6. Estrategia de migraciones (Flyway)

### `V1__init.sql`

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE members (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email TEXT UNIQUE NOT NULL,
  display_name TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  weekly_quota_minutes INT NOT NULL DEFAULT 24000,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE clients (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  member_id UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX clients_member_idx ON clients(member_id);

CREATE TABLE projects (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  member_id UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  client_id UUID REFERENCES clients(id) ON DELETE SET NULL,
  name TEXT NOT NULL,
  category TEXT NOT NULL CHECK (category IN
    ('LABOR_SALARIED','LABOR_FREELANCE','PERSONAL_OWN','PERSONAL_LEARNING')),
  hourly_rate_cents INT,
  currency TEXT NOT NULL DEFAULT 'EUR',
  color TEXT,
  archived_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX projects_member_idx ON projects(member_id);
CREATE INDEX projects_category_idx ON projects(category);

CREATE TABLE time_entries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  member_id UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  project_id UUID REFERENCES projects(id) ON DELETE SET NULL,
  kind TEXT NOT NULL CHECK (kind IN
    ('WORK','VACATION','HOLIDAY','SICK_LEAVE','PERMISO','PERSONAL')),
  start TIMESTAMPTZ NOT NULL,
  end TIMESTAMPTZ,
  description TEXT,
  external_id TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (end IS NULL OR end >= start)
);
CREATE UNIQUE INDEX time_entries_external_id_uq
  ON time_entries(member_id, external_id)
  WHERE external_id IS NOT NULL;
CREATE INDEX time_entries_member_start_idx ON time_entries(member_id, start);
CREATE INDEX time_entries_member_project_idx ON time_entries(member_id, project_id);

CREATE TABLE calendar_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  member_id UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  date DATE NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN
    ('HOLIDAY_NATIONAL','HOLIDAY_REGIONAL','HOLIDAY_LOCAL')),
  scope TEXT,
  description TEXT,
  UNIQUE (member_id, date, kind, scope)
);

CREATE TABLE absence_entries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  member_id UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  date DATE NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN
    ('VACATION','SICK_LEAVE','PERMISO')),
  notes TEXT,
  UNIQUE (member_id, date, kind)
);

CREATE TABLE member_settings (
  member_id UUID PRIMARY KEY REFERENCES members(id) ON DELETE CASCADE,
  salaried_net_monthly_cents INT,
  salaried_expected_monthly_minutes INT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### `V2__seed_member.sql`

```sql
INSERT INTO members (id, email, display_name, password_hash)
VALUES (
  '00000000-0000-0000-0000-000000000001',
  'joaquin@example.com',
  'Joaquín',
  '$2a$12$PLACEHOLDER_BCRYPT_HASH_CAMBIAR_EN_PRIMER_LOGIN'
);
```

### `V3__seed_legacy.sql`

Vacío. La importación real se hace vía el endpoint REST admin `POST /api/v1/admin/import-legacy`, protegido e idempotente, que lee `src/main/resources/legacy.json`. Razones:

- SQL con miles de `INSERT` no escala bien (los backups pueden crecer).
- Permite transformaciones (parseo TZ, saneo de duplicados, drop de anomalías como `end < start`).
- Idempotencia vía `external_id UNIQUE`.
- Re-importable tras un reset sin tocar SQL.

## 7. Specs Gherkin (spec-driven)

Cucumber con `@QuarkusTest` + RestAssured + Testcontainers.

### `clock_in.feature`

```gherkin
Feature: Clock in to start a work interval

  Background:
    Given a member "joaquin" exists with email "joaquin@example.com"
    And "joaquin" is authenticated
    And there is a project "Fisterra" with category LABOR_SALARIED

  Scenario: Start a salaried interval
    Given "joaquin" has no open time entry
    When I POST /api/v1/time-entries/start with body:
      """
      { "projectId": "<fisterra-uuid>", "start": "2026-08-04T09:00:00Z" }
      """
    Then the response is 201
    And the database has an open time entry for "joaquin" on "Fisterra"

  Scenario: Cannot start if one is already open
    Given "joaquin" has an open time entry on "Fisterra"
    When I POST /api/v1/time-entries/start
    Then the response is 409 Conflict
    And the error message is "Ya hay un intervalo abierto"

  Scenario: Cannot start a project of another member
    Given there is a project "OtherCo" owned by another member
    When I POST /api/v1/time-entries/start with project "OtherCo"
    Then the response is 404 Not Found
```

### `close_interval.feature`

```gherkin
Feature: Close an open time entry

  Scenario: Close with valid end
    Given "joaquin" has an open entry starting at "2026-08-04T09:00:00Z"
    When I PATCH /api/v1/time-entries/{id}/end with "2026-08-04T17:30:00Z"
    Then the response is 200
    And the entry is closed with duration 8h30m

  Scenario: End before start is rejected
    Given "joaquin" has an open entry starting at "2026-08-04T09:00:00Z"
    When I PATCH /api/v1/time-entries/{id}/end with "2026-08-04T08:00:00Z"
    Then the response is 400 Bad Request
```

### `weekly_totals.feature`

```gherkin
Feature: Weekly quota and totals

  Scenario: Salaried hours count toward the 40h quota
    Given "joaquin" worked on "Fisterra" 2026-W32:
      | day        | start | end   |
      | 2026-08-03 | 09:00 | 13:00 |
      | 2026-08-03 | 14:00 | 18:00 |
    When I GET /api/v1/reports/weekly?year=2026&week=32
    Then salaried_minutes = 480
    And remaining_minutes = 2160

  Scenario: Freelance hours do NOT count toward salaried quota
    Given "joaquin" worked on freelance project "X" 2026-W32 10h
    When I GET /api/v1/reports/weekly?year=2026&week=32
    Then salaried_minutes = 0
    And freelance_minutes = 600
    And remaining_minutes = 2400

  Scenario: Personal project hours do not appear in any quota
    Given "joaquin" worked on PERSONAL_OWN 2026-W32 5h
    When I GET /api/v1/reports/weekly?year=2026&week=32
    Then salaried_minutes = 0
    And freelance_minutes = 0
    And personal_minutes = 300

  Scenario: Salaried hours on a holiday are excluded from quota
    Given 2026-10-12 is a HOLIDAY_NATIONAL
    And "joaquin" worked on "Fisterra" (LABOR_SALARIED) 8h on 2026-10-12
    When I GET /api/v1/reports/weekly?year=2026&week=41
    Then salaried_minutes = 0
    And the weekly summary shows 1 day with salaried work on a holiday (logged but excluded)

  Scenario: Freelance work on a holiday counts toward freelance bucket
    Given 2026-10-12 is a HOLIDAY_NATIONAL
    And "joaquin" worked on "ClientCo" (LABOR_FREELANCE) 4h on 2026-10-12
    When I GET /api/v1/reports/weekly?year=2026&week=41
    Then salaried_minutes = 0
    And freelance_minutes = 240
```

### `orphans.feature`

```gherkin
Feature: Orphan time entries (TODO backlog)

  Scenario: Orphans appear separately from weekly totals
    Given "joaquin" has an entry on 2024-03-04 with project_id NULL
    When I GET /api/v1/reports/weekly?year=2024&week=10
    Then the response does NOT include the 2024-03-04 entry
    When I GET /api/v1/time-entries/orphans
    Then the response includes the 2024-03-04 entry

  Scenario: Assigning a project removes from TODO and includes in totals
    Given "joaquin" has an orphan entry with id "<uuid>" on 2024-03-04
    When I PATCH /api/v1/time-entries/<uuid>/project with "Fisterra"
    Then a follow-up GET /api/v1/reports/weekly?year=2024&week=10 includes the entry
    And a follow-up GET /api/v1/time-entries/orphans does NOT
```

### `vacation.feature`

```gherkin
Feature: Vacation days

  Scenario: Mark a vacation day
    When I POST /api/v1/absences with { "date": "2026-08-15", "kind": "VACATION" }
    Then a vacation entry exists for 2026-08-15

  Scenario: Vacation does not reduce salaried quota
    Given "joaquin" has salaried_quota_remaining_minutes = 2400 for 2026-W33
    When "joaquin" marks 2026-08-12 as vacation
    Then salaried_quota_remaining_minutes for 2026-W33 is unchanged
    And the weekly summary shows 1 vacation day
```

### `holiday_calendar.feature`

```gherkin
Feature: Holiday calendar (CRUD manual)

  Scenario: Add a national holiday
    When I POST /api/v1/calendar/events with body:
      """
      { "date": "2026-10-12", "kind": "HOLIDAY_NATIONAL",
        "description": "Día de la Hispanidad" }
      """
    Then a calendar event exists for 2026-10-12 HOLIDAY_NATIONAL

  Scenario: Add a regional holiday with scope
    When I POST /api/v1/calendar/events with body:
      """
      { "date": "2026-07-25", "kind": "HOLIDAY_REGIONAL", "scope": "Galicia",
        "description": "Día de Galicia" }
      """
    Then a calendar event exists for 2026-07-25 HOLIDAY_REGIONAL scope=Galicia

  Scenario: Calendar view returns all holidays in a year
    Given multiple holidays exist for 2026
    When I GET /api/v1/calendar?year=2026
    Then the response lists every holiday grouped by date
```

### `freelance_invoicing.feature`

```gherkin
Feature: Freelance invoicing report

  Scenario: Compute gross and estimated net for a freelance project
    Given "joaquin" has freelance project "ClientCo" with hourly_rate 50 EUR/h
    And worked 100h on "ClientCo" in 2026
    When I GET /api/v1/reports/invoicing?project=ClientCo&year=2026
    Then gross_invoiced = 5000.00 EUR
    And hours = 100
    And estimated_net with IRPF 15% = 4250.00 EUR

  Scenario: Invoicing respects kind=WORK only
    Given "joaquin" worked 80h WORK and 10h PERSONAL on "ClientCo" in 2026
    When I GET /api/v1/reports/invoicing?project=ClientCo&year=2026
    Then hours billed = 80
    And hours skipped = 10

  Scenario: Retroactive assignment keeps the entry in its original year
    Given a freelance entry with start = "2025-12-15T09:00:00Z" and no project
    And 30 freelance entries in 2026-Q1
    When I PATCH the entry's project to a freelance project on 2026-04-01
    Then the 2025 invoice includes the retroactively-assigned entry
    And the 2026 invoice is unchanged
```

### `year_over_year_comparison.feature`

```gherkin
Feature: Year-over-year comparison

  Scenario: Compare salaried hours between two years
    Given "joaquin" worked 1800 salaried hours in 2024
    And "joaquin" worked 1950 salaried hours in 2025
    When I GET /api/v1/reports/comparison?metric=SALARIED_HOURS&years=2024,2025
    Then the response shows 2024=1800, 2025=1950, delta=+150 (+8.3%)

  Scenario: Compare by all categories
    When I GET /api/v1/reports/comparison?metric=ALL&years=2024,2025
    Then the response shows breakdown by category for each year
```

### `personal_vs_labor.feature`

```gherkin
Feature: Personal vs Labor totals dashboard

  Scenario: Dashboard groups by category
    Given time entries across categories in 2026
    When I GET /api/v1/reports/yearly?year=2026&groupBy=category
    Then the response groups hours by category
    And LABOR_* hours sum under "total_labor"
    And PERSONAL_* hours sum under "total_personal"
```

### `legacy_import.feature`

```gherkin
Feature: Legacy import endpoint

  Scenario: First-time import populates the DB
    Given the database is empty
    When I POST /api/v1/admin/import-legacy
    Then 443 time entries are inserted
    And the legacy entries have kind = WORK
    And entries without a project_id are imported as huérfanos

  Scenario: Re-import is idempotent
    Given the database already has the legacy entries
    When I POST /api/v1/admin/import-legacy
    Then 0 new time entries are inserted (no duplicates via external_id)
```

## 8. API REST (resumen)

| Method | Path | Auth | Descripción |
|---|---|---|---|
| POST | `/api/v1/auth/login` | no | email+password → JWT |
| GET  | `/api/v1/me` | sí | datos del member |
| GET/POST | `/api/v1/clients` | sí | listar/crear |
| GET/PATCH/DELETE | `/api/v1/clients/{id}` | sí | CRUD |
| GET/POST | `/api/v1/projects` | sí | listar/crear (filtros: `?category=`) |
| GET/PATCH/DELETE | `/api/v1/projects/{id}` | sí | CRUD |
| GET | `/api/v1/time-entries` | sí | filtrar `?from=&to=&projectId=&kind=` |
| POST | `/api/v1/time-entries/start` | sí | clock-in |
| PATCH | `/api/v1/time-entries/{id}/end` | sí | clock-out |
| PATCH | `/api/v1/time-entries/{id}/project` | sí | asignar/reasignar (huérfanos TODO) |
| DELETE | `/api/v1/time-entries/{id}` | sí | borrar |
| GET | `/api/v1/time-entries/orphans` | sí | registros con project_id NULL |
| GET | `/api/v1/calendar?year=2026` | sí | eventos del año |
| POST | `/api/v1/calendar/events` | sí | añadir festivo |
| DELETE | `/api/v1/calendar/events/{id}` | sí | borrar festivo |
| GET | `/api/v1/absences` | sí | filtrar `?year=&kind=` |
| POST | `/api/v1/absences` | sí | marcar |
| DELETE | `/api/v1/absences/{id}` | sí | quitar |
| GET | `/api/v1/reports/weekly?year=&week=` | sí | totales semanales por categoría |
| GET | `/api/v1/reports/yearly?year=&groupBy=` | sí | resumen anual |
| GET | `/api/v1/reports/comparison?metric=&years=` | sí | comparativa |
| GET | `/api/v1/reports/invoicing?project=&year=` | sí | facturación freelance |
| POST | `/api/v1/admin/import-legacy` | admin | idempotente |

## 9. Frontend Vue → API

Migración en paralelo a las fases del backend:

| Capa actual | Reemplazo |
|---|---|
| `firebaseConfig.js` + `firebase/auth` | `apiClient` (fetch envuelto) + JWT en localStorage |
| `stores/user.js` | `stores/user.ts` con login/logout apuntando a `/api/v1/auth/login` |
| `Home.vue` `cargarRegistros/openInterval/closeInterval` (firestore) | llamadas a `/api/v1/time-entries/start`, `/{id}/end`, `DELETE` |
| `Home.vue` (vista actual de fichar) | queda como **Modo Fichar**; cálculos de quota vienen de `/api/v1/reports/weekly` |
| `Home.vue` (cabecera con semana navegable) | **Modo Dashboard**: `/api/v1/reports/yearly?groupBy=category` + comparativa |
| Nueva vista `Dashboard.vue` | gráfica salaried/freelance/personal por mes, comparativa YoY |
| Nueva vista `TareasPendientes.vue` (TODO) | orphans + asignación con week+project picker |
| Nueva vista `Calendar.vue` | `/api/v1/calendar?year=` + botones CRUD festivo/vacación/permiso |
| Nueva vista `Projects.vue` | CRUD proyectos con categoría y cliente opcional |
| `History.vue` (debug) | renombrar a `Registros.vue`, vista de mes/año |

`firebase` se elimina del package.json en la fase de migración.

## 10. Fases de implementación (MVP-first)

1. **Fase 1 — MVP auth + time-entries** (2-3 sprints)
   - Quarkus + Kotlin + Postgres + Flyway.
   - Auth (JWT), Member.
   - Projects + TimeEntry CRUD + Start/Close.
   - Reports/weekly (totales por categoría).
   - Importación legacy (endpoint admin idempotente + `legacy.json`).
   - Smoke test: importar los 443 registros reales y comparar totales con los datos vivos de Firebase.

2. **Fase 2 — Dashboard / Reports**
   - Yearly summary, comparison, invoicing.
   - Hooks en Vue para reemplazar `cargarRegistros`.

3. **Fase 3 — Calendar + Absences**
   - CalendarEvent CRUD, AbsenceEntry CRUD.
   - Vista calendario en Vue con festivo/vacación/permiso/baja.

4. **Fase 4 — Facturación freelance**
   - HourlyRate manager.
   - Reports/invoicing con retenciones IRPF.
   - Generación de facturas PDF (opcional).

5. **Fase 5 — Multi-org/multi-user** (solo si se decide; NO en MVP)

## 11. Riesgos y mitigaciones

- **Datos con `end < start`**: detectarlos en import; rechazar con reporte o normalizar con log.
- **`semana` legacy inconsistente con ISO recalculada**: comparación en V3 + log; conservar el legacy si difiere, normalizar en bulk después.
- **Auth GDPR**: JWT firmado HS256 con secret rotable; bcrypt cost ≥ 12; password nunca loggeada.
- **Native compilation**: entity mappers explícitos en lugar de active record para reducir reflection.
- **Granularidad temporal**: todo `Instant` en UTC; conversión a local en frontend al pintar (dayjs con `isoWeek`).
- **`external_id` uniqueness entre members**: índice `UNIQUE (member_id, external_id)` cubre el caso de IDs repetidos entre miembros distintos.
- **Producción real con `teamId`/`año` no reflejado en backups**: exportar desde Firestore en vivo antes de la migración final.
- **Asignación masiva de huérfanos NO se automatiza en v1**: la vista TODO permite asignación manual; un futuro agente puede explorar heurísticas blandas con el usuario en una sesión específica.

## 12. Cómo se conecta con Solidtime

Solidtime inspiró este diseño. Diferencias intencionales (single-user, sin
Organization, sin Clients separados si el member solo asalariado, sin tags v1,
sin audit log v1, sin reports UI del lado backend, sin invitaciones, sin OAuth
externo).

Mantengo estos principios de Solidtime que aplican aquí:
- **Billable rate** configurable por project (`hourlyRateCents`).
- **Time-entry abierto** (sin end) como estado válido.
- **Tasks** no se modelan en v1; se añaden si hace falta granularidad.
- **Reports** API-first para que el frontend Vue consuma directo.
- **Import endpoint** idempotente con `external_id` (mismo principio que
  `is_imported` en la migración 2024-05-30 de Solidtime).

## 13. Roadmap visual del fichero

```
backend/
├── AGENTS.md                      ← guía para futuros agentes
├── README.md                      ← cómo levantar el entorno
├── pom.xml
├── Dockerfile
├── docker-compose.yml             ← postgres + backend
├── .env.example
├── db/migration/V*.sql
├── src/main/...                   ← dominio, aplicación, infraestructura
├── src/test/...                   ← unit + Cucumber .feature
└── src/main/resources/legacy.json ← seed extraído de Firestore export
```

## 14. Convenciones (a aplicar desde el día 1)

- Ramas: `feature/...`, `bugfix/...`.
- Commits: cortos, lowercase, en español. Los merges a `main` despliegan.
- PR con templates Gherkin cumplidos (al menos 1 scenario nuevo por cambio).
- Tests de dominio obligatorios para cualquier función nueva de `domain/`.
- Tests Cucumber obligatorios para cualquier endpoint nuevo.
- Sin `console.log` (Quarkus tiene logging SLF4J).
- Sin null-checks inseguros en el dominio: usar value objects con invariantes.
- Semana ISO siempre (plugin `isoWeek` en frontend, `WeekId` value object en backend).

## 15. Notas sobre la sesión

- La asignación masiva de los 439 huérfanos legacy queda **explícitamente fuera** del MVP. Se resolverá en una sesión futura con agente, evaluando heurísticas blandas (descripción, hora del día, patrón del usuario).
- El primer registro de `projectId=Fisterra` es 2023-05-04 (mismo día del primer registro histórico). Por fecha no se puede distinguir un huérfano Fisterra de un huérfano MARKETPLACES.
- El corpus legacy es 100% salaried; no hay freelance en los backups.
- El primer commit del repo backend (este) es la versión inicial del documento de diseño. La siguiente fase es generar el proyecto Quarkus base (`pom.xml`, `application.properties`, estructura hexagonal, primer .feature file).