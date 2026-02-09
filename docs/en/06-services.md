# 06 - Services Layer (`@Service`)

## What is the service layer?

The service layer is the **middle layer** between the REST controllers (which receive HTTP requests) and the repositories (which access the database). Its responsibility is to contain the **business logic**: transform data, apply rules, coordinate operations between different repositories, etc.

In Spring, a class is marked as a service with the annotation [`@Service`](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html). This tells Spring: "this class is a component that contains business logic; register it in the [dependency injection](https://docs.spring.io/spring-framework/reference/core/beans/introduction.html) container."

```
Peticion HTTP
     |
     v
Controller  -->  Service  -->  Repository  -->  Base de datos
     ^              |
     |              v
ResponseEntity   MapStruct (Entity -> DTO)
```

The controller **must not** access the repository directly. Always delegate to the service, which decides how to obtain and transform the data.

---

## Where is it used in this project?

There are **5 services** in the project:

| Service | File | Responsibility |
|---|---|---|
| `CycleService` | `src/main/kotlin/com/example/whoopdavidapi/service/CycleService.kt` | Paginated query of physiological cycles |
| `RecoveryService` | `src/main/kotlin/com/example/whoopdavidapi/service/RecoveryService.kt` | Paginated recovery query |
| `SleepService` | `src/main/kotlin/com/example/whoopdavidapi/service/SleepService.kt` | Paginated sleep data query |
| `WorkoutService` | `src/main/kotlin/com/example/whoopdavidapi/service/WorkoutService.kt` | Paginated training query |
| `WhoopSyncService` | `src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt` | Orchestrate incremental synchronization with the Whoop API |

The first 4 (Cycle, Recovery, Sleep, Workout) follow an **identical pattern**. The fifth (`WhoopSyncService`) has a different responsibility: it doesn’t serve data to the user, but rather obtains it from the external API and saves it in the database.

---

## Why a service layer?

1. **Separation of responsibilities**: the controller is limited to validating HTTP parameters and returning responses. The service contains the real logic.
2. **Reuse**: the same service can be invoked from a controller, from a scheduler, from a test... without duplicating code.
3. **Testability**: you can test the service’s business logic in isolation, using mocks for the repositories, without needing to spin up an HTTP server.
4. **DRY Principle (Don't Repeat Yourself)**: in this project, the 4 query services follow the same pattern. If the pagination logic changed, it would be enough to modify it in the service layer.

---

## Explained code

### 1. The `@Service` annotation and component scanning

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/service/CycleService.kt

@Service
class CycleService(
    private val cycleRepository: CycleRepository,
    private val cycleMapper: CycleMapper
) {
    // ...
}
```

`@Service` is a **specialization** of `@Component`. Technically, both do the same thing: register the class as a [bean](https://docs.spring.io/spring-framework/reference/core/beans/definition.html) in the Spring container. The difference is **semantic**: `@Service` indicates that the class contains business logic.

When the application starts, Spring Boot runs **component scanning**: it scans all packages under the class marked with `@SpringBootApplication` (in this case, `com.example.whoopdavidapi`) and looks for classes annotated with `@Component`, `@Service`, `@Repository`, `@Controller`, etc. Each one is registered as a singleton bean that can be injected into other classes.

The hierarchy of component annotations is:

```
@Component              <-- Generico (base)
  |-- @Service          <-- Logica de negocio
  |-- @Repository        <-- Acceso a datos
  |-- @Controller        <-- Controladores web
```

### 2. Constructor injection (constructor injection in Kotlin)

```kotlin
@Service
class CycleService(
    private val cycleRepository: CycleRepository,
    private val cycleMapper: CycleMapper
) {
```

In Kotlin, **[constructor primario](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)** is declared directly in the class signature. Spring automatically detects that `CycleService` needs a `CycleRepository` and a `CycleMapper`, looks for beans of those types in its container, and injects them when creating the instance.

**No `@Autowired`** is needed. Since Spring 4.3, if a class has a single constructor, Spring automatically uses it for injection. Since in Kotlin the primary constructor is the only constructor (unless you declare secondary constructors with `constructor`), injection works without any additional annotation.

The properties are `private val` for two reasons:

- `private`: encapsulation, no other class can access these dependencies directly.
- `val`: immutability; once the dependency is assigned, it cannot change.

### 3. Pagination logic: `PageRequest.of()`

```kotlin
fun getCycles(
    from: Instant?,
    to: Instant?,
    page: Int,
    pageSize: Int
): PaginatedResponse<CycleDTO> {
    val pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "start"))
```

[`PageRequest.of()`](https://docs.spring.io/spring-data/commons/reference/repositories/query-methods-details.html) creates an `Pageable` object that Spring Data JPA uses to automatically build the `LIMIT`, `OFFSET`, and `ORDER BY` clauses in the SQL query.

The conversion `page - 1` is critical:

- The **user** sends pages **based on 1** (page 1, 2, 3...) because it is more natural for a human.
- Spring Data JPA uses **0-based** pages internally (page 0, 1, 2...).
- `page - 1` converts between both systems. If the user requests `page=1`, Spring receives `0` and returns the first results.

`Sort.by(Sort.Direction.DESC, "start")` indicates that the results should be sorted by the `start` field in descending order (most recent first). The name `"start"` must match the attribute name in the JPA entity (`WhoopCycle.start`), not the column name in the database.

### 4. The expression `when` for conditional filtering

```kotlin
val result = when {
    from != null && to != null -> cycleRepository.findByStartBetween(from, to, pageable)
    from != null -> cycleRepository.findByStartGreaterThanEqual(from, pageable)
    to != null -> cycleRepository.findByStartLessThan(to, pageable)
    else -> cycleRepository.findAll(pageable)
}
```

[`when`](https://kotlinlang.org/docs/control-flow.html#when-expression) is the Kotlin version of a `switch` in Java, but much more powerful. Here it is used as a **expression** (it returns a value that is assigned to `result`), not as a statement.

The conditions are evaluated from top to bottom:

| Condition | Repository method | Generated SQL (simplified) |
|---|---|---|
| `from` and `to` present | `findByStartBetween(from, to, pageable)` | `WHERE start BETWEEN ? AND ?` |
| Only `from` present | `findByStartGreaterThanEqual(from, pageable)` | `WHERE start >= ?` |
| Only `to` present | `findByStartLessThan(to, pageable)` | `WHERE start < ?` |
| None present | `findAll(pageable)` | Unfiltered (all records) |

The parameters `from` and `to` are `Instant?` (nullable). This allows the user to make requests such as:

- `/api/v1/cycles` -- without a filter, returns everything paginated.
- `/api/v1/cycles?from=2024-01-01T00:00:00Z` -- from that date onward.
- `/api/v1/cycles?from=2024-01-01T00:00:00Z&to=2024-06-01T00:00:00Z` -- specific range.

### 5. Entity-to-DTO Mapping with MapStruct

```kotlin
return PaginatedResponse(
    data = result.content.map { cycleMapper.toDto(it) },
    pagination = PaginationInfo(
        page = page,
        pageSize = pageSize,
        totalCount = result.totalElements,
        hasMore = result.hasNext()
    )
)
```

`result` is a `Page<WhoopCycle>` (a Spring Data object that contains paginated results and pagination metadata).

- `result.content` returns the `List<WhoopCycle>` with the entities from the current page.
- `.map { cycleMapper.toDto(it) }` transforms each entity into its DTO using MapStruct.
- `result.totalElements` is the **total** number of records in the database that match the filter (not just those on this page).
- `result.hasNext()` indicates whether there are more pages after the current one.

The mapper (`CycleMapper`) is an interface generated by MapStruct:

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/mapper/CycleMapper.kt

@Mapper(componentModel = "spring")
interface CycleMapper {
    fun toDto(entity: WhoopCycle): CycleDTO
    fun toEntity(dto: CycleDTO): WhoopCycle
}
```

`componentModel = "spring"` makes MapStruct generate an implementation annotated with `@Component`, which Spring can automatically inject into `CycleService`.

The paginated response is wrapped in a generic DTO:

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/model/dto/PaginatedResponse.kt

data class PaginatedResponse<T>(
    val data: List<T>,
    val pagination: PaginationInfo
)

data class PaginationInfo(
    val page: Int,
    val pageSize: Int,
    val totalCount: Long,
    val hasMore: Boolean
)
```

The JSON that the client (Power BI) receives has this structure:

```json
{
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "pageSize": 100,
    "totalCount": 542,
    "hasMore": true
  }
}
```

### 6. DRY principle: the 4 query services are identical

Services `CycleService`, `RecoveryService`, `SleepService`, and `WorkoutService` follow **exactly the same pattern**. The only difference is:

- The **repository** they use (`CycleRepository`, `RecoveryRepository`, etc.).
- The **mapper** they use (`CycleMapper`, `RecoveryMapper`, etc.).
- The **sorting field** (`"start"` for Cycle/Sleep/Workout, `"createdAt"` for Recovery).

For example, `RecoveryService` differs only in that it sorts by `"createdAt"` instead of `"start"`:

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/service/RecoveryService.kt

@Service
class RecoveryService(
    private val recoveryRepository: RecoveryRepository,
    private val recoveryMapper: RecoveryMapper
) {

    fun getRecoveries(
        from: Instant?,
        to: Instant?,
        page: Int,
        pageSize: Int
    ): PaginatedResponse<RecoveryDTO> {
        val pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"))

        val result = when {
            from != null && to != null -> recoveryRepository.findByCreatedAtBetween(from, to, pageable)
            from != null -> recoveryRepository.findByCreatedAtGreaterThanEqual(from, pageable)
            to != null -> recoveryRepository.findByCreatedAtLessThan(to, pageable)
            else -> recoveryRepository.findAll(pageable)
        }

        return PaginatedResponse(
            data = result.content.map { recoveryMapper.toDto(it) },
            pagination = PaginationInfo(
                page = page,
                pageSize = pageSize,
                totalCount = result.totalElements,
                hasMore = result.hasNext()
            )
        )
    }
}
```

This repeated pattern (4 classes with the same structure) is a conscious trade-off: it could be abstracted into a generic class, but the simplicity and readability of having explicit classes is preferable when there are only 4 cases.

### 7. WhoopSyncService: the synchronization orchestration

`WhoopSyncService` has a different responsibility: it does not serve data to the user; instead, **fetches data from the Whoop API and saves it to the database**.

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt

@Service
class WhoopSyncService(
    private val whoopApiClient: WhoopApiClient,
    private val cycleRepository: CycleRepository,
    private val recoveryRepository: RecoveryRepository,
    private val sleepRepository: SleepRepository,
    private val workoutRepository: WorkoutRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun syncAll() {
        log.info("Iniciando sincronizacion completa con Whoop API...")
        val start = System.currentTimeMillis()

        syncCycles()
        syncRecoveries()
        syncSleeps()
        syncWorkouts()

        val elapsed = System.currentTimeMillis() - start
        log.info("Sincronizacion completa en {} ms", elapsed)
    }
```

Key points:

- **5 injected dependencies**: the HTTP client (`WhoopApiClient`) and the 4 repositories. This service orchestrates communication between the external API and the local database.
- **`syncAll()`**: public method that the scheduler (`WhoopDataSyncScheduler`) invokes periodically. Synchronizes the 4 data types in sequence.
- **Logging with SLF4J**: `LoggerFactory.getLogger(javaClass)` creates a logger with the class name. `{}` are placeholders that SLF4J replaces only if the log level is active (more efficient than concatenating strings).
- **Time measurement**: `System.currentTimeMillis()` before and after makes it possible to record how long the full synchronization takes.

### 8. Incremental synchronization

```kotlin
private fun syncCycles() {
    try {
        // Sincronizacion incremental: obtener solo datos nuevos
        val lastUpdated = cycleRepository.findTopByOrderByUpdatedAtDesc()?.updatedAt
        val records = whoopApiClient.getAllCycles(start = lastUpdated)
        var saved = 0
        var skipped = 0

        for (record in records) {
            try {
                val cycle = mapToCycle(record)
                cycleRepository.save(cycle)
                saved++
            } catch (ex: IllegalArgumentException) {
                log.warn("Saltando cycle con datos inválidos: {}", ex.message)
                skipped++
            }
        }

        log.info("Cycles sincronizados: {} nuevos/actualizados, {} saltados", saved, skipped)
    } catch (ex: Exception) {
        log.error("Error sincronizando cycles: {}", ex.message, ex)
    }
}
```

The **incremental** strategy avoids re-downloading everything every time:

1. `cycleRepository.findTopByOrderByUpdatedAtDesc()` retrieves the most recent record from the local DB (the one with the highest `updatedAt`).
2. `?.updatedAt` extracts the `updatedAt` field using Kotlin’s safe call. If there are no records, it returns `null`.
3. `whoopApiClient.getAllCycles(start = lastUpdated)` requests from the Whoop API only the records modified after that date. If `lastUpdated` is `null` (first synchronization), it fetches everything.
4. Each record is mapped to an entity and saved with `cycleRepository.save()`. If it already exists (same ID), JPA performs an UPDATE instead of an INSERT.

The methods `syncRecoveries()`, `syncSleeps()`, and `syncWorkouts()` follow the same pattern.

### 9. Mapping JSON to entities: Kotlin safe casts

```kotlin
private fun mapToCycle(record: Map<String, Any?>): WhoopCycle {
    val score = record["score"] as? Map<*, *>
    return WhoopCycle(
        id = (record["id"] as Number).toLong(),
        userId = (record["user_id"] as Number).toLong(),
        createdAt = parseInstant(record["created_at"]),
        updatedAt = parseInstant(record["updated_at"]),
        start = requireNotNull(parseInstant(record["start"])) {
            "Campo 'start' requerido en cycle (id=${record["id"] ?: "desconocido"})"
        },
        end = parseInstant(record["end"]),
        timezoneOffset = record["timezone_offset"] as? String,
        scoreState = record["score_state"] as? String ?: "PENDING_SCORE",
        strain = (score?.get("strain") as? Number)?.toFloat(),
        kilojoule = (score?.get("kilojoule") as? Number)?.toFloat(),
        averageHeartRate = (score?.get("average_heart_rate") as? Number)?.toInt(),
        maxHeartRate = (score?.get("max_heart_rate") as? Number)?.toInt()
    )
}
```

This method converts a `Map<String, Any?>` (deserialized JSON response) into a JPA entity. It uses several Kotlin operators:

| Operator | Meaning | Example |
|---|---|---|
| `as?` | **Safe cast**: tries to cast. If it fails, it returns `null` instead of throwing an exception. | `record["score"] as? Map<*, *>` |
| `?.` | **Safe call**: if the object is `null`, it returns `null` without executing the method. | `score?.get("strain")` |
| `?:` | **Elvis operator**: if the left expression is `null`, use the value on the right. | `record["score_state"] as? String ?: "PENDING_SCORE"` |
| `requireNotNull()` | Throws `IllegalArgumentException` if the value is `null`. | `requireNotNull(parseInstant(record["start"])) { "mensaje" }` |

The method `parseInstant` converts ISO-8601 strings to `Instant`:

```kotlin
private fun parseInstant(value: Any?): Instant? {
    return when (value) {
        is String -> try { Instant.parse(value) } catch (_: Exception) { null }
        else -> null
    }
}
```

If the value is not a `String` or cannot be parsed, return `null` instead of throwing an exception. This makes synchronization more robust against unexpected data from the external API.

### 10. The scheduler that invokes the service

The `WhoopSyncService` is invoked periodically by the scheduler:

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/scheduler/WhoopDataSyncScheduler.kt

@Component
class WhoopDataSyncScheduler(
    private val whoopSyncService: WhoopSyncService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${app.whoop.sync-cron}")
    fun scheduledSync() {
        log.info("Ejecutando sincronizacion programada...")
        try {
            whoopSyncService.syncAll()
        } catch (ex: Exception) {
            log.error("Error en sincronizacion programada: {}", ex.message, ex)
        }
    }
}
```

The scheduler is a `@Component`, not a `@Service`, because its only responsibility is to **schedule** execution. The actual synchronization logic lives in `WhoopSyncService`. This separation allows synchronization to be triggered in other ways (a manual endpoint, a test) without depending on the scheduler.

---

## Complete flow of a request

Example: the user (Power BI) requests `GET /api/v1/cycles?from=2024-01-01T00:00:00Z&page=2&pageSize=50`.

```
1. CycleController recibe la peticion
   - Valida: page >= 1, pageSize entre 1 y 1000
   - Delega a cycleService.getCycles(from, null, 2, 50)

2. CycleService.getCycles()
   - Crea PageRequest.of(1, 50, Sort.by(DESC, "start"))  // page 2 del usuario = page 1 de Spring
   - Evalua el `when`: from != null, to == null -> findByStartGreaterThanEqual()
   - El repositorio ejecuta: SELECT * FROM whoop_cycle WHERE start >= ? ORDER BY start DESC LIMIT 50 OFFSET 50

3. CycleService transforma el resultado
   - Mapea cada WhoopCycle a CycleDTO usando CycleMapper
   - Envuelve en PaginatedResponse con metadatos de paginacion

4. CycleController devuelve ResponseEntity.ok(resultado)
   - Spring serializa a JSON y envia con HTTP 200
```

---

## Official documentation

- **`@Service` and component scanning**: [Spring Framework - Classpath Scanning and Managed Components](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html)
- **Constructor injection**: [Spring Framework - Constructor-based Dependency Injection](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html#beans-constructor-injection)
- **`Pageable` and `PageRequest`**: [Spring Data JPA - Paging and Sorting](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html#repositories.special-parameters)
- **`Page<T>` result**: [Spring Data Commons - Page Interface](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/domain/Page.html)
- **MapStruct with Spring**: [MapStruct Reference - Using dependency injection](https://mapstruct.org/documentation/stable/reference/html/#using-dependency-injection)
- **SLF4J parameterized logging**: [SLF4J Manual](https://www.slf4j.org/manual.html)
- **Kotlin safe casts and operators**: [Kotlin Reference - Type Checks and Casts](https://kotlinlang.org/docs/typecasts.html)
