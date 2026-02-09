# 10 - Scheduled synchronization

## Table of contents

- [What is @Scheduled?](#what-is-scheduled)
- [Enabling with @EnableScheduling](#enablement-with-enablescheduling)
- [WhoopDataSyncScheduler: the trigger](#whoopdatasyncscheduler-the-trigger)
  - [Externalized cron expression](#externalized-cron-expression)
  - [Cron format in Spring (6 fields)](#cron-format-in-spring-6-fields)
  - [Error handling in the scheduler](#error-handling-in-the-scheduler)
- [WhoopSyncService: the synchronization logic](#whoopsyncservice-the-synchronization-logic)
  - [Incremental synchronization](#incremental-synchronization)
  - [Independence between synchronizations](#independence-between-synchronizations)
  - [JSON Mapping to Entities](#json-mapping-to-entities)
  - [parseInstant(): safe conversion from String to Instant](#parseinstant-safe-conversion-from-string-to-instant)
  - [requireNotNull(): validation of required fields](#requirenotnull-validation-of-required-fields)
- [Complete flow of a synchronization](#complete-synchronization-flow)
- [Official documentation](#official-documentation)

---

## What is @Scheduled?

[`@Scheduled`](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) is a Spring annotation that allows you to execute a method **automatically** at regular intervals or at specific times, without the need for any client to make an HTTP request. It is the equivalent of an operating system **[cron](https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-cron-expression) job**, but managed within the Spring application.

In this project, synchronization with the Whoop API runs automatically every 30 minutes (configurable), downloading new data and saving it to the database.

---

## Enablement with @EnableScheduling

For `@Scheduled` to work, it is mandatory to enable scheduling with [`@EnableScheduling`](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) in the application's main class.

**File**: `src/main/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplication.kt`

```kotlin
@SpringBootApplication
@EnableScheduling
class WhoopDavidApiApplication

fun main(args: Array<String>) {
    runApplication<WhoopDavidApiApplication>(*args)
}
```

**`@EnableScheduling`** tells Spring: "look for beans with `@Scheduled` methods and schedule them to run automatically". Without this annotation, `@Scheduled` is silently ignored (the method would never run and there would be no error or warning).

Spring internally creates a `TaskScheduler` with a dedicated thread pool to execute scheduled tasks. By default it uses a single thread, which means scheduled tasks run sequentially (if one task takes a long time, the next one waits).

---

## WhoopDataSyncScheduler: the trigger

**File**: `src/main/kotlin/com/example/whoopdavidapi/scheduler/WhoopDataSyncScheduler.kt`

```kotlin
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

Breakdown:

1. **`@Component`**: Registers the class as a Spring bean. It is necessary because Spring only looks for `@Scheduled` in beans managed by the container.

2. **Constructor injection**: `WhoopDataSyncScheduler` receives `WhoopSyncService` via constructor. This follows the principle of **separation of responsibilities**: the scheduler only decides **when** to execute; the service decides **what** to execute.

3. **`@Scheduled(cron = ...)`**: Schedules the method to run according to the cron expression.

### Externalized cron expression

```kotlin
@Scheduled(cron = "\${app.whoop.sync-cron}")
```

The cron expression  **is not hardcoded**. It is read from the `app.whoop.sync-cron` property in the YAML. This allows changing the synchronization frequency per profile:

| Profile | File | Value | Meaning |
|---|---|---|---|
| Base (all) | `src/main/resources/application.yaml` | `"0 */30 * * * *"` | Every 30 minutes |
| demo | `src/main/resources/application-demo.yaml` | `"0 */5 * * * *"` | Every 5 minutes |

In profile `demo`, synchronization is more frequent (every 5 minutes) because the data is mock and there is no risk of exceeding the real API’s rate limits.

The syntax `"\${...}"` is **SpEL (Spring Expression Language)**. In Kotlin code it is written as `"\${app.whoop.sync-cron}"` (with a backslash to escape Kotlin’s `$`). Spring resolves it to the property value at startup time.

### Cron format in Spring (6 fields)

Spring uses cron expressions with **6 fields** (unlike Linux cron, which uses 5):

```
┌──────────── segundo (0-59)
│ ┌────────── minuto (0-59)
│ │ ┌──────── hora (0-23)
│ │ │ ┌────── dia del mes (1-31)
│ │ │ │ ┌──── mes (1-12)
│ │ │ │ │ ┌── dia de la semana (0-7, donde 0 y 7 = domingo)
│ │ │ │ │ │
* * * * * *
```

**Difference with Linux**: Linux does not have the **seconds** field. Spring adds it as the first field.

Breakdown of the expression `"0 */30 * * * *"`:

| Field | Value | Meaning |
|---|---|---|
| Second | `0` | At second 0 (start of the minute) |
| Minute | `*/30` | Every 30 minutes (`*/N` = every N units) |
| Time | `*` | Any time |
| Day of the month | `*` | Any day |
| Month | `*` | Any month |
| Day of the week | `*` | Any day of the week |

Result: it runs at minute 0 and minute 30 of every hour. That is, at 00:00, 00:30, 01:00, 01:30, 02:00, etc.

Other useful examples:

| Expression | Meaning |
|---|---|
| `"0 */5 * * * *"` | Every 5 minutes (used in demo profile) |
| `"0 0 6 * * *"` | Every day at 6:00 AM |
| `"0 0 */2 * * *"` | Every 2 hours |
| `"0 0 8 * * MON-FRI"` | Monday to Friday at 8:00 AM |
| `"0 0 0 1 * *"` | On the 1st day of each month at midnight |

### Error handling in the scheduler

```kotlin
try {
    whoopSyncService.syncAll()
} catch (ex: Exception) {
    log.error("Error en sincronizacion programada: {}", ex.message, ex)
}
```

The `try-catch` is **critical** in a `@Scheduled` method. If an uncaught exception escapes from the method, Spring cancels future executions of that scheduled method. That is, if a synchronization fails without try-catch, it would never run again until the application is restarted.

With the try-catch, the error is logged, but the scheduler remains scheduled for the next execution.

---

## WhoopSyncService: the synchronization logic

**File**: `src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt`

### Incremental synchronization

The synchronization strategy is **incremental**: instead of downloading all Whoop data every time, only the data **after the last saved record** is requested.

```kotlin
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

The method `syncAll()` calls the 4 synchronization methods sequentially and measures the total time. `System.currentTimeMillis()` is used to calculate the duration.

The method `syncCycles()` shows the complete pattern:

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

Step by step:

1. **`cycleRepository.findTopByOrderByUpdatedAtDesc()?.updatedAt`**: Looks up the most recent cycle in the database and retrieves its `updatedAt` field. If the database is empty, it returns `null` (first synchronization).

2. **`whoopApiClient.getAllCycles(start = lastUpdated)`**: Requests from the Whoop API all cycles since date `lastUpdated`. If it is `null` (first time), Whoop returns the entire history.

3. **Save loop with internal try-catch**: Each record is mapped and saved individually. If a record has invalid data (for example, the `start` field is missing), `mapToCycle` throws `IllegalArgumentException` and that record is skipped. The others continue to be processed.

4. **`cycleRepository.save(cycle)`**: JPA `save()` performs an **upsert**: if a record with the same `id` already exists, it updates it. If it doesn’t exist, it inserts it. This allows reprocessing records that have been updated in Whoop.

5. **Counters `saved` and `skipped`**: They are recorded in the logs to monitor the health of synchronization.

The methods `syncRecoveries()`, `syncSleeps()`, and `syncWorkouts()` follow exactly the same pattern. The only difference is the repository and the mapping method they use.

### Independence between synchronizations

Each `syncXxx()` method has its own external `try-catch`:

```kotlin
fun syncAll() {
    syncCycles()       // Si falla, los demas siguen
    syncRecoveries()   // Si falla, los demas siguen
    syncSleeps()       // Si falla, los demas siguen
    syncWorkouts()     // Si falla, los demas siguen
}
```

If `syncCycles()` fails (for example, Whoop returns a 500 error for cycles), the exception is caught inside `syncCycles()` and the recoveries, sleeps, and workouts syncs run normally. This design prevents a partial failure from losing the entire sync.

### JSON Mapping to Entities

The Whoop API returns JSON like `Map<String, Any?>`. The mapping to JPA entities is done manually with Kotlin safe casts.

Complete example with `mapToCycle()`:

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

**Kotlin techniques used:**

| Expression | What does it do? | When it is used |
|---|---|---|
| `as Number` | Unsafe cast **insecure**. Throws `ClassCastException` if it is not a `Number` | Required fields such as `id` |
| `as? Number` | Safe cast **safe** (safe cast). Returns `null` if it cannot be converted | Optional fields such as `strain` |
| `as? Map<*, *>` | Safe cast to a generic Map. Used for nested JSON objects | The `score` field is a nested JSON object |
| `?.toFloat()` | Safe call operator. Only calls `toFloat()` if it is not `null` | Chained conversion after safe cast |
| `?: "PENDING_SCORE"` | Elvis operator. If the value is `null`, use the default value | Fields with default value |

**Why `as Number` and not `as Long` directly?** Jackson (the JSON deserializer) converts JSON numbers to different Java types depending on their size: `Integer` for small numbers, `Long` for large ones. Using `Number` (the parent class of all numeric types in Java), any numeric type is accepted and then converted with `.toLong()`.

**Nested fields (score)**: The Whoop API returns the score as a nested JSON object. First, the score map is extracted and then its fields are accessed:

```kotlin
val score = record["score"] as? Map<*, *>   // Puede ser null si no hay score
// ...
strain = (score?.get("strain") as? Number)?.toFloat()
// score?         -> si score es null, toda la expresion es null
// .get("strain") -> obtiene el valor de la clave "strain"
// as? Number     -> safe cast a Number
// ?.toFloat()    -> convierte a Float si no es null
```

### parseInstant(): safe conversion from String to Instant

```kotlin
private fun parseInstant(value: Any?): Instant? {
    return when (value) {
        is String -> try { Instant.parse(value) } catch (_: Exception) { null }
        else -> null
    }
}
```

This utility function converts a JSON value (which may be `String`, `null`, or another type) to a Java [`Instant`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Instant.html).

- **`when (value)`**: Kotlin when expression (equivalent to switch in other languages).
- **`is String`**: Verify that the value is a String. If it is not, return `null`.
- **`Instant.parse(value)`**: Parses an ISO-8601 String (such as `"2024-12-15T10:30:00.000Z"`) into a `Instant`.
- **`catch (_: Exception) { null }`**: If the String does not have a valid ISO-8601 format, it returns `null` instead of throwing an exception. The `_` indicates that the exception is discarded (its contents are not needed).

### requireNotNull(): validation of required fields

```kotlin
start = requireNotNull(parseInstant(record["start"])) {
    "Campo 'start' requerido en cycle (id=${record["id"] ?: "desconocido"})"
},
```

[`requireNotNull()`](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/require-not-null.html) is a standard Kotlin function that throws `IllegalArgumentException` if the value is `null`. It is the idiomatic way to validate preconditions in Kotlin.

- If `parseInstant(record["start"])` returns `null` (the field does not exist or has an invalid format), `IllegalArgumentException` is thrown with the provided message.
- That `IllegalArgumentException` is caught by the `try-catch` in `syncCycles()`, which skips that record and continues with the next one.
- The message includes the log `id` to facilitate debugging: `"Campo 'start' requerido en cycle (id=1005)"`.

---

## Complete synchronization flow

```
[Cada 30 min]
    │
    ▼
WhoopDataSyncScheduler.scheduledSync()
    │
    ▼
WhoopSyncService.syncAll()
    │
    ├─> syncCycles()
    │     ├─ cycleRepository.findTopByOrderByUpdatedAtDesc()?.updatedAt → "2024-12-15T10:00:00Z"
    │     ├─ whoopApiClient.getAllCycles(start = "2024-12-15T10:00:00Z")
    │     │    ├─ GET /developer/v1/cycle?limit=25&start=2024-12-15T10:00:00Z
    │     │    │   → { records: [...15 registros...], next_token: null }
    │     │    └─ return 15 registros
    │     ├─ Para cada registro: mapToCycle() → cycleRepository.save()
    │     └─ Log: "Cycles sincronizados: 15 nuevos/actualizados, 0 saltados"
    │
    ├─> syncRecoveries()
    │     └─ (mismo patron)
    │
    ├─> syncSleeps()
    │     └─ (mismo patron)
    │
    └─> syncWorkouts()
          └─ (mismo patron)
    │
    ▼
Log: "Sincronizacion completa en 2345 ms"
```

First run (empty database):

- `findTopByOrderByUpdatedAtDesc()` returns `null`
- `getAllCycles(start = null)` requests **everything** the history
- There may be multiple pages (do-while loop with `next_token`)

Subsequent runs:

- The date of the last saved record is obtained
- Only the most recent data is requested
- Normally there are few records (0–2 for each type in 30 minutes)

---

## Official documentation

- [Spring @Scheduled](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) - Official documentation for scheduled tasks
- [Spring Cron Expressions](https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-cron-expression) - Spring cron format (6 fields)
- [Kotlin requireNotNull](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/require-not-null.html) - requireNotNull Documentation
- [Kotlin Safe Casts](https://kotlinlang.org/docs/typecasts.html#safe-nullable-cast-operator) - Kotlin’s as? Operator
- [Java Instant.parse](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/time/Instant.html#parse(java.lang.CharSequence)) - Parsing ISO-8601 timestamps
