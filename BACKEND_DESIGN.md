# Backend Design — WorkMeter

Diseño del backend propio de WorkMeter/Registro Horario. Este documento sustituye
el diseño inicial que trataba `Project` como el centro del modelo. El proyecto
seguirá existiendo para clasificar trabajo, pero la relación laboral será un
concepto de dominio independiente.

Estado: propuesta de diseño, octubre de 2026.

Las aclaraciones del 07/10/2026 amplían las fases funcionales sin cambiar la
prioridad: login, CRUD horario y migración primero. Las tablas y entidades de
fases posteriores no son requisitos del primer despliegue.

## 1. Objetivos y decisiones base

- Sustituir Firebase Auth y Firestore por una API propia y PostgreSQL.
- Mantener el producto inicialmente personal y de un único usuario, sin cerrar
  la puerta a más usuarios en el futuro.
- Mantener el frontend Vue actual operativo durante la migración por fases.
- Registrar tanto el tiempo real trabajado como el tiempo esperado según el
  contrato y su calendario laboral.
- Conservar el histórico sin guardar campos derivados como semana o año.
- Aplicar Hexagonal Architecture estricta y orientar el dominio a DDD:
  dominio y casos de uso sin dependencias de Quarkus, Hibernate, REST ni SQL.
- Evitar que una empresa, un cliente, un proyecto o una nómina sean conceptos
  intercambiables.

### Decisiones de plataforma

| Concern | Decisión |
|---|---|
| Lenguaje | Kotlin 2.4.20 |
| Framework HTTP/runtime | Quarkus 3.40 LTS, con adaptadores REST |
| JDK | Java 21 LTS como baseline de compatibilidad |
| Build | Maven inicialmente; no condiciona el dominio |
| Base de datos | PostgreSQL 18 |
| Persistencia | Hibernate ORM/Panache únicamente en infraestructura, con mappers explícitos |
| Migraciones | Flyway, una migración SQL versionada por cambio de esquema |
| Autenticación | Email + contraseña, tokens JWT; hash aislado detrás de un puerto |
| Tests | JUnit 5, tests de dominio, integración con PostgreSQL y RestAssured |
| Entorno de integración | Testcontainers con PostgreSQL 18 |
| Zona horaria inicial | `Europe/Madrid` |

Kotlin 2.4.20 es la versión estable publicada actualmente según la
documentación oficial de Kotlin. PostgreSQL 18 fue publicado el 25 de septiembre
de 2025 y es la versión mayor objetivo del proyecto. Las versiones de Quarkus y
el JDK se mantienen como decisiones de implementación revisables al crear el
proyecto, porque su compatibilidad con el último compilador Kotlin debe validarse
en ese momento.

## 2. Límites del dominio

El sistema tendrá estos bounded contexts. No se deben compartir entidades entre
ellos; se compartirán identificadores y value objects pequeños.

### Identity & Access

Cuenta, credenciales, sesión y perfil del usuario. No conoce horas, contratos ni
clientes.

### Time Tracking

Intervalos de tiempo registrados manualmente o mediante reloj de entrada/salida.
Puede enlazar un intervalo con una relación laboral, una asignación a cliente,
un encargo freelance o un proyecto personal.

### Employment

Histórico de relaciones laborales, empresas empleadoras, clientes de una
consultora, jornadas, vacaciones, festivos, ausencias, revisiones salariales,
evaluaciones y notas.

### Freelance

Presupuestos, oportunidades aceptadas o rechazadas, encargos, facturación y
cobros. Un presupuesto rechazado debe conservarse y no convertirse en trabajo
facturable.

### Reporting

Consultas derivadas que combinan datos de los contextos anteriores. No es una
fuente adicional de verdad en la primera versión.

### Career & Portfolio

Experiencias profesionales y proyectos personales orientados a la trayectoria y
a la generación futura de CV. Puede reutilizar organizaciones, relaciones
laborales y encargos, pero también existir sin registros horarios.

## 3. Glosario y modelo de negocio

### Organization

Una empresa u organización externa. Puede desempeñar varios papeles:

- `EMPLOYER`: empresa con la que existe una relación laboral.
- `CLIENT`: cliente para el que se trabaja dentro de una relación laboral o en
  freelance.
- Ambos a la vez, si el caso de negocio lo necesita.

No se utilizará una entidad `Client` separada de `Organization`: Capitole puede
ser empleador y Fisterra puede ser cliente, pero ambos son organizaciones.

### EmploymentRelationship

La relación de la persona con una empresa empleadora durante un periodo. Por
ejemplo, el contrato con Capitole. Es la fuente de verdad para:

- fechas de alta y baja;
- tipo de relación y puesto;
- zona horaria y calendario laboral aplicable;
- versiones de jornada y horas contratadas;
- vacaciones y festivos de esa relación;
- revisiones salariales;
- evaluaciones, reuniones 1-to-1 y notas.

El hecho de trabajar para un cliente de la consultora no crea una relación
laboral nueva.

Situación confirmada: Capitole es la consultora empleadora y hasta ahora ha
habido un único cliente, con varios proyectos dentro de ese cliente. Los
proyectos de los registros y backups deben conservar esa distinción; sus nombres
no bastan para inferir la identidad del cliente.

### ClientAssignment

Asignación temporal dentro de una relación laboral. Permite modelar que durante
un periodo se trabajó para Fisterra y después para otro cliente de Capitole.
Tiene fechas de vigencia, rol/proyecto del cliente y notas propias de la
asignación.

### WorkScheduleVersion

Versión efectiva de la jornada. Define las horas contratadas y su distribución
por día de la semana. Los cambios crean versiones con fecha de efecto sin
sobrescribir el histórico; se admiten correcciones explícitas con trazabilidad.

Ejemplo:

```text
Lunes a jueves: 08:30 cada día
Viernes:        06:00
Total semanal:  40:00
Vigente desde: 2026-01-01
```

### Official time y real time

Para un periodo se mostrarán como métricas distintas:

- `raw_scheduled_minutes`: minutos que dicta la jornada para los días incluidos.
- `expected_minutes`: minutos esperados después de aplicar festivos, vacaciones,
  ausencias y reglas del calendario laboral.
- `worked_minutes`: suma de intervalos `WORK` cerrados y válidos.
- `balance_minutes`: `worked_minutes - expected_minutes`.

Los valores se calculan al consultar. No se guardará `annual_total` ni un
`weeklyQuotaMinutes` fijo en el usuario.

## 4. Agregados y entidades principales

Los agregados se identifican por UUID. Las relaciones entre agregados se hacen
por ID, nunca mediante referencias ORM navegables en el dominio.

### Identity

#### Member (aggregate root)

- `id: MemberId`
- `email: Email` único, normalizado
- `displayName: String`
- `passwordHash: PasswordHash` (solo accesible desde Identity)
- `createdAt: Instant`

#### Session (aggregate root opcional en v1)

Si se necesitan revocación y refresh tokens, se persistirá una sesión separada.
El JWT no será la fuente de verdad del perfil.

### Employment

#### Organization (aggregate root)

- `id: OrganizationId`
- `name: String`
- `legalName: String?`
- `taxId: String?`
- `roles: Set<OrganizationRole>`
- `notes: String?`

#### EmploymentRelationship (aggregate root)

- `id: EmploymentRelationshipId`
- `memberId: MemberId`
- `employerOrganizationId: OrganizationId`
- `relationshipType: EMPLOYEE | OTHER`
- `startDate: LocalDate`
- `endDate: LocalDate?`
- `status: ACTIVE | ENDED`
- `timezone: ZoneId`
- `holidayCalendarId: HolidayCalendarId?`

Invariantes:

- `endDate` no puede ser anterior a `startDate`.
- Una relación no puede tener dos versiones de jornada o salario activas para la
  misma fecha.
- Dos relaciones del mismo usuario pueden solaparse solo si se marca
  explícitamente que la simultaneidad está permitida.

#### WorkScheduleVersion (aggregate root)

- `id: WorkScheduleVersionId`
- `employmentRelationshipId: EmploymentRelationshipId`
- `effectiveFrom: LocalDate`
- `effectiveTo: LocalDate?`
- `contractedWeeklyMinutes: Int`
- `dailyMinutes: Map<DayOfWeek, Int>`
- `basis: CONTRACTUAL | APPLIED`
- `deviationReason: String?`

La suma diaria debe coincidir con `contractedWeeklyMinutes`, salvo una
distribución aplicada diferente documentada con su motivo. El cálculo inicial
utiliza una única versión vigente y muestra su base. La comparación automática
entre varios calendarios simultáneos queda para más adelante.

#### ClientAssignment (aggregate root)

- `id: ClientAssignmentId`
- `employmentRelationshipId: EmploymentRelationshipId`
- `clientOrganizationId: OrganizationId`
- `startDate: LocalDate`
- `endDate: LocalDate?`
- `role: String?`
- `description: String?`

Las horas de una relación se pueden consultar por empleador, cliente y periodo
sin inferir nada a partir del nombre de un proyecto.

#### EmploymentCondition (condiciones con vigencia)

Condición vinculada a una relación laboral, con tipo, descripción, fechas de
vigencia y origen opcional (contrato, anexo o acuerdo). Una condición puede
referenciar una asignación cuando dependa del cliente.

- Modalidad pactada: `REMOTE | HYBRID | ONSITE`.
- Jornada contractual: actualmente 40 horas semanales.
- Jornada intensiva u otras condiciones: descripción y periodo aplicable.
- Observaciones de aplicación: por ejemplo, intensiva contemplada pero no
  disfrutada por condiciones del cliente, con fechas y motivo.

Se conserva lo pactado aunque su aplicación real sea distinta. Inicialmente
estas discrepancias se documentan; no requieren un motor de cumplimiento ni
generan compensaciones. `WorkScheduleVersion` contiene la distribución usada
para los cálculos, identificando si corresponde al horario contractual o al
aplicado. Una diferencia respecto a las horas contractuales debe quedar
justificada, sin modificar el pacto original de 40 horas.

La posible modalidad híbrida desde enero de 2027 es una previsión, no un cambio
confirmado: no se crea una versión efectiva hasta conocer el acuerdo.

#### WorkLocationDay (lugar de trabajo observado)

Registro por relación laboral y fecha, con `HOME | ONSITE | MIXED`, asignación
opcional y notas. Permite contar las fechas trabajadas en casa y presencialmente,
independientemente de la modalidad contractual y de los intervalos del día.
Un día mixto se informa por separado para evitar dobles conteos. Sin registro,
el lugar se considera desconocido; no se deduce automáticamente del contrato.

#### CompensationRevision (aggregate root)

- `id: CompensationRevisionId`
- `employmentRelationshipId: EmploymentRelationshipId`
- `effectiveFrom: LocalDate`
- `effectiveTo: LocalDate?`
- `grossAnnualAmount: Money?`
- `netAnnualReferenceAmount: Money?`
- `notes: String?`

Se registra el bruto anual y el neto anual de referencia conocidos, sin obligar
a inventar datos históricos faltantes. Al menos uno debe estar informado.
El neto anual de referencia es una anotación del usuario, independiente de la
suma del neto efectivamente cobrado. No se calcula ni valida la nómina.

#### NetSalaryObservation (aggregate root)

- `id: NetSalaryObservationId`
- `employmentRelationshipId: EmploymentRelationshipId`
- `period: YearMonth`
- `netAmount: Money`
- `paidOn: LocalDate?`
- `notes: String?`

El neto se registra por nómina/mes porque puede variar por retenciones, variables
o circunstancias personales aunque no cambie la revisión salarial contractual.
Se anota lo realmente recibido. No se desglosan gastos de tarjeta restaurante ni
se intenta reconstruir el salario a partir de sus deducciones. Un periodo admite
varios cobros y el historial puede estar incompleto, sin alertas por diferencias
respecto al salario anual de referencia.

#### EmploymentNote (aggregate root)

- `id: EmploymentNoteId`
- `employmentRelationshipId: EmploymentRelationshipId`
- `clientAssignmentId: ClientAssignmentId?`
- `type: GENERAL | ONE_TO_ONE | PERFORMANCE_REVIEW | ADMINISTRATIVE`
- `occurredOn: LocalDate`
- `title: String?`
- `body: String`
- `createdAt: Instant`

Una revisión salarial se modela como `CompensationRevision`; una evaluación o
una conversación 1-to-1 se modela como nota tipada. Así se conserva el histórico
sin forzar todos los apuntes a tener estructura de nómina.

#### HolidayCalendar y HolidayDay

`HolidayCalendar` identifica el calendario aplicable a una relación laboral
(por ejemplo, España + comunidad autónoma + municipio). `HolidayDay` contiene:

- fecha;
- nombre;
- ámbito (`NATIONAL`, `REGIONAL`, `LOCAL`, `COMPANY`);
- si es laborable por defecto;
- notas y fuente.

Para responder a “festivos disfrutados y no disfrutados” se guardará una
observación explícita por relación solo cuando el usuario lo recuerde o quiera
documentarlo. No será obligatorio completar todos los festivos:

```text
HolidayObservation = ENJOYED | NOT_ENJOYED | WORKED
```

Un festivo que cae en fin de semana y no se devuelve se podrá registrar como
`NOT_ENJOYED`, con una nota o motivo. La ausencia de observación significa
simplemente “sin documentar”, no “disfrutado”. El sistema no hará una
interpretación jurídica automática.

Una compensación posterior se enlaza al festivo mediante el día compensatorio
correspondiente. No sustituye la observación original: un festivo puede haberse
trabajado y haberse compensado después.

#### VacationEntitlement y AbsenceRecord

`VacationEntitlement` registra por relación y año los días asignados, ajustados,
arrastrados y disfrutados. La unidad inicial queda fijada a `WORKING_DAYS`; se
mantendrá el diseño extensible por si más adelante se necesitan horas o días
naturales.

`AbsenceRecord` registra una ausencia con rango de fechas:

- `VACATION`
- `SICK_LEAVE`
- `PERMISSION`
- `COMPENSATORY_LEAVE`
- `OTHER`

Cada ausencia tiene estado `REQUESTED | CONFIRMED | TAKEN | CANCELLED | REJECTED`
y fechas de sus cambios. Es un seguimiento personal de lo que ha comunicado la
empresa, sin flujo multiusuario de aprobaciones. El disfrute se confirma
explícitamente; el paso de la fecha no basta para convertirlo en `TAKEN`.

Las solicitadas se muestran como pendientes; las confirmadas reservan saldo y
las disfrutadas lo consumen. Se muestran por separado asignado, solicitado,
reservado, disfrutado, caducado y disponible. Una ausencia no se descuenta dos
veces al pasar de confirmada a disfrutada. Para días futuros, los informes de
previsión consideran las confirmadas; para el histórico, las disfrutadas. Los
datos pendientes de confirmar se señalan como incompletos.

El arrastre mantiene el año de origen, cantidad y fecha límite de disfrute
configurable. El usuario recuerda que llega hasta enero del año siguiente, pero
la fecha exacta está pendiente de confirmar y no se fija automáticamente al
31 de enero. Las reservas deben corresponder a fechas válidas del saldo del que
se descuentan. No se borra el histórico al caducar un saldo.

Los días compensatorios tienen saldo separado, concesión manual, motivo y
caducidad opcional. Pueden enlazarse a un festivo y no consumen vacaciones ni
nacen automáticamente de un exceso horario. Los medios días quedan para una
ampliación; inicialmente se gestionan días laborables completos.

### Time Tracking

#### Project (aggregate root)

Se conserva como agrupador operativo para el frontend, pero no representa por sí
solo una relación laboral.

- `id: ProjectId`
- `memberId: MemberId`
- `name: String`
- `context: EMPLOYMENT | FREELANCE | PERSONAL`
- `employmentRelationshipId: EmploymentRelationshipId?`
- `clientAssignmentId: ClientAssignmentId?`
- `freelanceEngagementId: FreelanceEngagementId?`
- `archivedAt: Instant?`

En v1 seguirá siendo posible tener un proyecto sin todos los enlaces durante la
migración. Los nuevos casos de uso validarán que el contexto sea coherente.

#### TimeEntry (aggregate root)

- `id: TimeEntryId`
- `memberId: MemberId`
- `projectId: ProjectId?`
- `employmentRelationshipId: EmploymentRelationshipId?`
- `clientAssignmentId: ClientAssignmentId?`
- `freelanceEngagementId: FreelanceEngagementId?`
- `kind: WORK | PERSONAL`
- `interval: TimeInterval` (`start: Instant`, `end: Instant?`)
- `description: String?`
- `source: MANUAL | CLOCK | LEGACY_IMPORT`
- `externalId: String?`
- `createdAt: Instant`
- `updatedAt: Instant`

Invariantes:

- `end` es nulo o no anterior a `start`.
- Solo existe un intervalo abierto por usuario.
- Los intervalos no se solapan, salvo que una regla explícita permita registrar
  simultáneamente contextos distintos.
- `kind != WORK` no puede enlazar un encargo freelance ni un cliente laboral.
- Un registro huérfano se conserva, aparece en pendientes y en el total registrado
  sin clasificar. Solo se atribuye a una empresa o cliente cuando se conoce ese
  vínculo; los informes indican lo que queda pendiente de clasificación.

Los límites de unicidad y solapamiento se reforzarán también en PostgreSQL para
evitar carreras entre peticiones concurrentes.

Las ausencias se registran exclusivamente en `AbsenceRecord`. Los festivos viven
en el calendario y sus observaciones; ninguno genera un intervalo ficticio.

En la fase de seguimiento de actividad se añaden categoría opcional
(`DEVELOPMENT`, `MEETING`, `SUPPORT`, `LEARNING`, `PROPOSAL`, `OTHER`), etiquetas y
referencia opcional a tarea/ticket. No serán obligatorias para fichar ni exigidas
a los registros migrados. El tiempo de preparar propuestas puede enlazarse a un
presupuesto, aunque sea rechazado y no llegue a existir un encargo.

Los vínculos de proyecto y registro deben ser coherentes. Al crear una entrada,
el caso de uso resuelve su contexto; cambiar posteriormente el proyecto no
reasigna el histórico automáticamente. Las correcciones de clasificación son
explícitas.

### Freelance

#### FreelanceQuote (aggregate root)

- `id: FreelanceQuoteId`
- `memberId: MemberId`
- `clientOrganizationId: OrganizationId`
- `title: String`
- `description: String?`
- `status: DRAFT | SENT | ACCEPTED | REJECTED | EXPIRED | CANCELLED`
- `issuedOn: LocalDate?`
- `validUntil: LocalDate?`
- `estimatedMinutes: Long?`
- `hourlyRate: Money?`
- `subtotal: Money?`
- `taxAmount: Money?`
- `withholdingAmount: Money?`
- `total: Money?`
- `rejectionReason: String?`
- `billingMode: NOT_FORMALIZED | FORMAL_INVOICE`
- `taxProfileId: TaxProfileId?`

Un presupuesto aceptado puede crear un `FreelanceEngagement`, pero no se debe
crear automáticamente una factura ni marcarlo como cobrado.

`billingMode` permite conservar presupuestos y trabajos que todavía no se
facturan formalmente, sin impedir que un encargo futuro incluya IVA, IRPF y una
factura completa. Los porcentajes y reglas fiscales se configurarán por perfil y
por encargo; no se fijarán como una regla global del usuario.

#### FreelanceEngagement (aggregate root)

Representa el trabajo que nace de un presupuesto aceptado o de un encargo
directo. Contiene fechas, condiciones y enlace opcional al presupuesto.

La primera versión freelance prioriza precio cerrado y pagos por entregas:

- `DeliveryMilestone`: entrega de un encargo, descripción, importe pactado,
  vencimiento opcional y estado de entrega independiente del estado de cobro.
- `PaymentReceipt`: cobro real con importe, moneda y fecha, vinculado al encargo
  y distribuido entre sus entregas cuando corresponda. Permite anticipos y pagos
  parciales; no requiere una factura para existir.
- `ProjectExpense`: gasto real de hosting, dominio u otro concepto, con importe,
  moneda, fecha y proyecto. Puede pertenecer a un encargo o proyecto personal.

El total distribuido de un cobro no puede superar el importe recibido. Lo no
distribuido queda como anticipo del encargo y solo se cuenta una vez en su total.
Los saldos de cobro se derivan de los importes pactados y recibidos. Preparar o
entregar trabajo no lo marca automáticamente como cobrado.

Una ampliación se registra como presupuesto adicional enlazado al encargo;
cuando se acepta añade entregas e importe sin sobrescribir el acuerdo anterior.
También se puede abrir otro encargo si el usuario considera que es un trabajo
independiente. Los presupuestos rechazados permanecen en el histórico.

Los gastos se anotan manualmente aunque se repitan cada año. Se informa de
cobrado, gastos registrados y diferencia entre ambos como balance de caja del
proyecto, sin presentarlo como cálculo fiscal de beneficio. Automatizar ingresos
recurrentes, renovaciones y facturación periódica queda para una fase posterior.

#### FreelanceInvoice (aggregate root, fase posterior)

Factura emitida, vencimiento e importes, con enlaces a los cobros registrados.
El estado de cobro se deriva de esos enlaces y admite pagos parciales; no se
duplica el ingreso al asociar un cobro existente con una factura. Las horas se
informan por fecha de trabajo, los cobros por fecha de recepción y las facturas
por fecha de emisión. En un encargo a precio cerrado, las horas no determinan
el importe pactado.

#### TaxProfile (aggregate root, fase posterior)

- `id: TaxProfileId`
- `memberId: MemberId`
- `name: String`
- `vatRate: BigDecimal?`
- `withholdingRate: BigDecimal?`
- `currency: String`
- `active: Boolean`

Un `FreelanceQuote` o `FreelanceInvoice` puede referenciar el perfil aplicable,
pero siempre conservará una copia de los importes y porcentajes utilizados en
ese documento para que los cambios futuros no modifiquen el histórico.

### Career & Portfolio

#### CareerExperience (aggregate root)

Permite registrar una experiencia profesional o un proyecto personal aunque no
existan `TimeEntry` asociados:

- `id: CareerExperienceId`
- `memberId: MemberId`
- `type: EMPLOYMENT | FREELANCE | PERSONAL_PROJECT`
- `organizationId: OrganizationId?`
- `employmentRelationshipId: EmploymentRelationshipId?`
- `freelanceEngagementId: FreelanceEngagementId?`
- `clientAssignmentId: ClientAssignmentId?`
- `projectId: ProjectId?`
- `title: String`
- `startDate: LocalDate`
- `endDate: LocalDate?`
- `summary: String?`
- `achievements: List<String>`
- `technologies: List<TechnologyUse>`

`TechnologyUse` conservará el nombre de la tecnología, categoría, periodo de uso
y notas opcionales. Esto permitirá generar posteriormente CVs por periodo,
experiencia, cliente o stack sin intentar deducir tecnologías a partir de los
registros horarios.

Una relación laboral histórica sin fichajes se registra en Employment y puede
tener igualmente una `CareerExperience` para describir puesto, salario,
responsabilidades y tecnologías utilizadas.

Los datos compartidos se consultan desde su relación/proyecto de origen, evitando
copias divergentes. Los salarios se mantienen en Employment y se excluyen por
defecto de los datos para CV. Se conserva la jerarquía consultora → cliente →
proyectos para describir distintas experiencias dentro del mismo cliente.

## 5. Reglas de cálculo

### Horas oficiales frente a horas reales

Para cada día del periodo se localiza la versión de jornada efectiva y se suman
los minutos del día. Luego se aplica la política de calendario de la relación:

1. límites de inicio y fin de la relación laboral;
2. fines de semana y días con jornada cero;
3. festivos del calendario;
4. vacaciones y ausencias aprobadas;
5. excepciones de jornada que se incorporen en fases posteriores.

El resultado mantiene siempre el desglose de cada ajuste. El usuario podrá ver
por qué el esperado anual o semanal no coincide con una simple multiplicación de
40 horas.

La política exacta de si un festivo o unas vacaciones reducen
`expected_minutes`
queda parametrizada por relación hasta confirmar el criterio que se desea usar.
El backend no impondrá una interpretación legal universal.

El balance es informativo. En la relación actual no se realizan ni reconocen
horas extra; no se crea una bolsa de horas ni una deuda económica a partir del
balance. Los días compensatorios se anotan mediante concesiones independientes.

### Tiempo real

`worked_minutes` suma intervalos `WORK` cerrados y válidos del contexto consultado.
Los pendientes de clasificación se muestran por separado dentro del total
registrado. El tiempo personal también dispone de su total independiente.

Los intervalos se agrupan por fecha usando `Europe/Madrid` en la primera versión,
no la zona horaria del servidor.

Cuando un intervalo cruza medianoche, el cálculo de informes lo divide por día
sin modificar el intervalo almacenado.

La antigüedad por empleador o cliente se calcula desde los periodos de relación
o asignación, independientemente de las horas fichadas. Se muestra separada de
las horas registradas; al sumar periodos no se cuentan dos veces sus solapamientos.
Los trabajos históricos sin fichajes aparecen como «sin datos horarios», no como
un déficit. Los informes distinguen periodos completos, parciales y sin datos;
la cobertura no se infiere únicamente de que exista algún intervalo.

### Informes laborales

Consultas iniciales:

- resumen semanal: raw, esperado, real, balance, festivos, vacaciones y
  pendientes;
- resumen anual: los mismos valores por mes y total;
- comparación interanual;
- total de tiempo para una relación laboral;
- total de tiempo para cada cliente de una consultora;
- evolución de salario por relación;
- días de vacaciones asignados, disfrutados y pendientes;
- festivos disfrutados, trabajados, cambiados y sin confirmar.
- modalidad pactada y fechas observadas de trabajo en casa, presencial y mixto;
- condiciones y discrepancias documentadas, sin evaluación legal automática.

### Informes freelance

- presupuestos por estado y periodo;
- horas estimadas frente a reales;
- ingresos presupuestados, aceptados, facturados y cobrados;
- horas facturables por mes, presupuesto o cliente;
- solicitudes rechazadas o caducadas, que permanecen en el histórico.
- pactado y cobrado por entrega, anticipos y saldo pendiente;
- gastos registrados de hosting, dominios y otros conceptos por proyecto.

## 6. Persistencia PostgreSQL 18

Las tablas se organizarán por contexto lógico, aunque inicialmente estén en el
schema `public`:

```text
members
organizations
employment_relationships
employment_conditions
work_location_days
work_schedule_versions
client_assignments
compensation_revisions
net_salary_observations
employment_notes
holiday_calendars
holiday_days
holiday_observations
vacation_entitlements
compensatory_entitlements
absence_records
projects
time_entries
freelance_quotes
freelance_engagements
delivery_milestones
payment_receipts
payment_allocations
project_expenses
freelance_invoices
tax_profiles
career_experiences
technology_uses
```

Convenciones:

- UUID como claves técnicas.
- `TIMESTAMPTZ` para instantes y `DATE` para fechas laborales.
- `NUMERIC(19, 4)` para importes o, si se decide, céntimos enteros con moneda
  separada; no usar `double` para dinero.
- `TEXT` + `CHECK` o tablas de referencia para estados; las decisiones que
  cambien a menudo no deben quedar bloqueadas en enums Java/Kotlin.
- `created_at` y `updated_at` en entidades editables.
- Foreign keys explícitas y `ON DELETE` elegido por agregado; no cascadas
  accidentales desde un usuario hacia histórico financiero.
- Intervalos de vigencia con validación de no solapamiento.
- Índices por `member_id`, fecha efectiva, relación y cliente.

La tabla `time_entries` deberá tener como mínimo:

```sql
CHECK ("end" IS NULL OR "end" >= start)
CREATE UNIQUE INDEX time_entries_external_id_uq
  ON time_entries (member_id, external_id)
  WHERE external_id IS NOT NULL;
```

La unicidad de intervalo abierto se implementará con un índice parcial. La
protección contra solapamientos se evaluará con un `EXCLUDE USING gist` sobre
rangos de tiempo cuando el primer adaptador de persistencia esté implementado.

No se persistirán `week`, `year`, `semana` ni `año`; se calculan con la zona
horaria del usuario o de la relación laboral.

## 7. Hexagonal Architecture y DDD

Estructura propuesta:

```text
src/main/kotlin/com/workmeter/
├── domain/
│   ├── identity/
│   │   ├── model/
│   │   └── ports/
│   ├── employment/
│   │   ├── model/
│   │   └── ports/
│   ├── timetracking/
│   │   ├── model/
│   │   └── ports/
│   ├── freelance/
│   │   ├── model/
│   │   └── ports/
│   ├── career/
│   │   ├── model/
│   │   └── ports/
│   └── shared/
│       └── valueobjects/
├── application/
│   ├── identity/
│   ├── employment/
│   ├── timetracking/
│   ├── freelance/
│   ├── career/
│   └── reporting/
└── infrastructure/
    ├── rest/
    ├── persistence/
    │   ├── entities/
    │   ├── mappers/
    │   └── repositories/
    ├── auth/
    ├── migration/
    └── config/
```

Reglas no negociables:

- `domain` no importa paquetes de Quarkus, Jakarta, Hibernate, REST ni SQL.
- Las entidades ORM son clases de infraestructura distintas de las entidades de
  dominio.
- Los casos de uso reciben comandos/queries y devuelven resultados de aplicación
  o errores de dominio.
- Los puertos de entrada son casos de uso; los puertos de salida son interfaces
  como `TimeEntryRepository`, `Clock`, `PasswordHasher` y
  `EmploymentReportReader`.
- REST, JWT, transacciones y serialización son adaptadores.
- La lógica de cálculo de jornadas, intervalos, dinero y vigencias tiene tests
  unitarios sin arrancar Quarkus ni PostgreSQL.
- No se añadirá CQRS/event sourcing por obligación. Reporting empezará con
  consultas SQL/read models y solo se separará más si el volumen lo justifica.

## 8. API inicial

La API será versionada con `/api/v1`.

### Identity

```text
POST /api/v1/auth/login
POST /api/v1/auth/refresh       (si se habilitan refresh tokens)
POST /api/v1/auth/logout
GET  /api/v1/me
```

### Time Tracking

```text
POST   /api/v1/time-entries
GET    /api/v1/time-entries?from=&to=
GET    /api/v1/time-entries/{id}
PATCH  /api/v1/time-entries/{id}
DELETE /api/v1/time-entries/{id}
POST   /api/v1/time-entries/start
POST   /api/v1/time-entries/{id}/close
GET    /api/v1/time-entries/orphans
PATCH  /api/v1/time-entries/{id}/classification
```

### Employment y reporting

```text
GET/POST/PATCH /api/v1/organizations
GET/POST/PATCH /api/v1/employment-relationships
GET/POST/PATCH /api/v1/employment-relationships/{id}/schedules
GET/POST/PATCH /api/v1/employment-relationships/{id}/assignments
GET/POST/PATCH /api/v1/employment-relationships/{id}/conditions
GET/POST/PATCH /api/v1/employment-relationships/{id}/work-locations
GET/POST/PATCH /api/v1/employment-relationships/{id}/absences
GET/POST/PATCH /api/v1/employment-relationships/{id}/leave-entitlements
GET/POST/PATCH /api/v1/employment-relationships/{id}/compensation
GET/POST/PATCH /api/v1/employment-relationships/{id}/payroll-observations
GET/POST/PATCH /api/v1/employment-relationships/{id}/notes
GET /api/v1/reports/weekly
GET /api/v1/reports/yearly
GET /api/v1/reports/employment/{id}
```

### Career & Portfolio

```text
GET/POST/PATCH /api/v1/career/experiences
GET/POST/PATCH /api/v1/career/technologies
GET /api/v1/career/cv-data
```

### Freelance

```text
GET/POST/PATCH /api/v1/freelance/quotes
POST /api/v1/freelance/quotes/{id}/accept
POST /api/v1/freelance/quotes/{id}/reject
GET/POST/PATCH /api/v1/freelance/engagements
GET/POST/PATCH /api/v1/freelance/engagements/{id}/milestones
GET/POST/PATCH /api/v1/freelance/engagements/{id}/receipts
GET/POST/PATCH /api/v1/projects/{id}/expenses
GET/POST/PATCH /api/v1/freelance/invoices
```

Los endpoints de gestión conservarán las versiones de condiciones, jornada y
salario. Se distingue un cambio efectivo de una corrección de datos: ambos
conservan fecha de registro, fecha de efecto y motivo, también cuando se anota
hoy un cambio ocurrido en el pasado. Las rutas anteriores son un inventario
orientativo por fase, no una API completa exigida al primer despliegue.

## 9. Roadmap por slices verticales

Cada slice incluye dominio, caso de uso, migración, adaptador HTTP, test de
integración y el cambio mínimo del frontend necesario para usarlo.

El primer hito utilizable termina con los slices 0–3. El resto se incorpora
gradualmente, sin crear sus tablas por adelantado. Se mantiene el orden existente.
La autenticación y los datos del nuevo backend se prueban juntos antes del corte
a producción; no se deja el frontend dependiendo de una sesión propia para
acceder todavía a Firestore.

### Slice 0 — Bootstrap técnico

- crear el módulo backend Quarkus + Kotlin;
- configurar PostgreSQL 18, Flyway y Testcontainers;
- establecer estructura hexagonal y reglas de dependencias;
- crear `Member` y endpoint de salud;
- añadir una primera migración vacía/reproducible.

### Slice 1 — Login y sesión

- login email/contraseña equivalente al actual Firebase Auth;
- hash detrás de `PasswordHasher`;
- JWT y usuario actual;
- sustituir `stores/user.js` por un cliente HTTP sin romper el guard del router;
- tests de credenciales válidas, inválidas y sesión expirada.

### Slice 2 — CRUD de registros horarios

- crear, listar, consultar, editar y borrar `TimeEntry`;
- catálogo mínimo de proyectos para preservar la clasificación actual;
- iniciar y cerrar intervalo;
- validación de abierto único y solapamientos;
- semana actual y navegación histórica;
- sustituir el acceso directo a Firestore en `Home.vue` y `History.vue`.

### Slice 3 — Migración compatible con Firebase

- ensayar con los 443 registros del backup del 10/05/2024 (04/05/2023–10/05/2024)
  y migrar finalmente desde una exportación actual completa;
- conservar el Firestore document id en `external_id`;
- importar todos inicialmente como `WORK`;
- crear registros huérfanos cuando no exista proyecto;
- endpoint idempotente protegido para importación o comando administrativo;
- exportar una copia final de Firestore antes de cerrar la migración.

### Slice 4 — Organizaciones, proyectos y relaciones laborales

- `Organization`, `EmploymentRelationship`, `Project` y `ClientAssignment`;
- registrar Capitole como empleador y clientes históricos/actuales;
- conservar el único cliente actual y sus varios proyectos sin confundirlos;
- condiciones con vigencia, modalidad pactada y lugar trabajado por fecha;
- anotar discrepancias como la intensiva no disfrutada, sin automatizar su cálculo;
- asignar cada registro histórico al contexto correspondiente;
- consultar tiempo total por consultora, cliente y periodo.

### Slice 5 — Jornada, calendario, vacaciones y métricas

- versiones de jornada por día de la semana;
- calendarios y festivos con observación disfrutado/trabajado;
- vacaciones solicitadas, confirmadas y disfrutadas en días laborables completos;
- arrastre con origen y fecha límite configurable, pendiente de confirmar;
- saldo manual de días compensatorios separado de vacaciones;
- informe de raw/esperado/real/balance semanal y anual;
- tests de cambios de jornada a mitad de año y años bisiestos.

### Slice 6 — Salario y notas laborales

- revisiones salariales efectivas por fecha;
- bruto y neto anual de referencia más neto realmente recibido por periodo;
- sin conciliación de nóminas ni desglose de tarjeta restaurante;
- evaluaciones, 1-to-1 y notas vinculadas a relación o asignación;
- evolución temporal sin sobrescribir registros antiguos.

### Slice 7 — Career & Portfolio

- experiencias laborales históricas aunque no tengan fichajes;
- proyectos personales con resumen, logros y tecnologías;
- catálogo de tecnologías reutilizable;
- categorías de actividad, etiquetas y referencias opcionales a tareas/tickets;
- logros y entregables vinculados a experiencias/proyectos;
- consulta estructurada para futuros generadores de CV.

### Slice 8 — Freelance

- organizaciones cliente independientes de la relación laboral;
- presupuestos y estados aceptado/rechazado/caducado;
- encargos aceptados y enlace con horas;
- primera entrega: precio cerrado por entregas, anticipos, cobros parciales y
  saldo pendiente, independientemente de que exista factura;
- ampliaciones mediante presupuestos adicionales o encargos independientes;
- tiempo dedicado a propuestas y gastos manuales de hosting/dominios;
- segunda entrega dentro de la fase: facturas con IVA/IRPF configurable y enlace
  a cobros existentes; no bloquea la primera entrega freelance.

### Mejoras posteriores sin compromiso en el alcance inicial

- Motor de cumplimiento y comparación automática de calendarios contractuales,
  de empresa y de cliente.
- Bonus, formación como beneficio contractual, guardias y bolsa de horas extra.
- Vacaciones fraccionadas y reglas automáticas de devengo/prorrateo; los saldos
  iniciales se introducen manualmente.
- Ingresos recurrentes, suscripciones, facturación periódica y renovaciones de gastos.
- Gestión avanzada de documentos adjuntos y generación de CV con plantillas;
  inicialmente se guardan notas/referencias y datos estructurados para exportar.

Casos de aceptación para las fases que los incorporen:

- Una modalidad remota puede pasar a híbrida desde una fecha confirmada sin
  cambiar el histórico de días trabajados en casa o presencialmente.
- Anotar la intensiva no disfrutada conserva la condición pactada y su motivo.
- Confirmar unas vacaciones reserva días; disfrutarlas mueve esa cantidad a
  consumo sin duplicar el descuento. Los compensatorios no gastan vacaciones.
- Anotar bruto/neto anual y cobros mensuales no genera alertas por diferencias.
- Dos cobros parciales completan una entrega; enlazarlos después a una factura
  no duplica el ingreso. Una ampliación conserva el presupuesto original.
- Una relación histórica sin fichajes conserva antigüedad y salario sin mostrar
  un déficit horario ficticio.

## 10. Migración desde Firebase

El frontend actual usa `registros` y `projects`. Los campos duplicados de los
backups se normalizarán así:

| Firebase | Backend | Regla |
|---|---|---|
| `inicio` / `start` | `time_entries.start` | normalizar si coinciden o existe uno; si discrepan, conflicto para revisión |
| `fin` / `end` | `time_entries.end` | conservar abiertos; valores discrepantes, incluido null frente a fecha, se revisan |
| `id` de Firestore | `time_entries.external_id` | único por miembro |
| `projectId` | `time_entries.project_id` | nulo significa pendiente de clasificar |
| `semana` / `week` | no persistir | recalcular ISO |
| `year` / `año` | no persistir | recalcular desde el instante |
| `teamId` | no modelar en v1 | no aparece de forma fiable en los backups |
| ausencia de `kind` | `WORK` | todos los históricos son registros de trabajo |

Antes de la importación final se debe exportar Firestore en vivo y revisar:

- registros nuevos posteriores a mayo de 2024;
- intervalos con `end < start`;
- intervalos abiertos;
- proyectos y clientes actuales;
- posibles registros duplicados por las claves bilingües.

La importación no debe borrar datos ni corregir silenciosamente anomalías:
deberá producir un informe de descartados, conflictos y huérfanos para revisión.

## 11. Decisiones confirmadas y detalles pendientes

Con las decisiones actuales quedan cerradas estas bases:

- zona horaria inicial: `Europe/Madrid`;
- vacaciones expresadas en días laborables;
- seguimiento solicitado/confirmado/disfrutado, arrastre y días compensatorios;
- condiciones laborales y modalidades remota/híbrida/presencial con vigencia;
- lugar de trabajo real documentado por fecha;
- jornada contractual actual de 40 horas y discrepancias de intensiva anotadas;
- balance horario informativo, sin bolsa de horas extra;
- festivos documentados mediante observaciones explícitas y parciales;
- salario bruto y neto anual de referencia más neto realmente cobrado;
- freelance preparado para modalidad no formalizada y para facturación formal
  con IVA/IRPF configurable;
- relaciones laborales históricas sin fichajes y proyectos personales con
  tecnologías reutilizables para CV.
- precio cerrado por entregas, ampliaciones, cobros parciales y gastos de proyecto;
- categorías, etiquetas y tareas opcionales para describir el trabajo.

Quedan para concretar al implementar cada fase, sin bloquear el primer hito:

1. Fecha exacta límite del arrastre de vacaciones: enero del año siguiente es
   un recuerdo pendiente de confirmar, no una regla cerrada.
2. Fecha y condiciones de una eventual modalidad híbrida desde enero de 2027.
3. Horario aplicado, calendario y tratamiento de ausencias para el primer informe
   laboral; solo se calcula un balance concluyente con datos y reglas completos.
4. Qué campos fiscales concretos deben aparecer en la primera factura formal:
   número, serie, fecha de vencimiento y tipos de IVA/IRPF.
5. Qué información mínima debe tener la primera plantilla de CV: puesto,
   resumen, logros, tecnologías, cliente y modalidad de trabajo.

La implementación puede comenzar ya con Identity, Time Tracking, CRUD y
migración, sin bloquearse por estas decisiones.
