# 10 - Sincronizacion programada

## Tabla de contenidos

- [Que es @Scheduled?](#que-es-scheduled)
- [Habilitacion con @EnableScheduling](#habilitacion-con-enablescheduling)
- [WhoopDataSyncScheduler: el disparador](#whoopdatasyncscheduler-el-disparador)
  - [Cron expression externalizada](#cron-expression-externalizada)
  - [Formato de cron en Spring (6 campos)](#formato-de-cron-en-spring-6-campos)
  - [Manejo de errores en el scheduler](#manejo-de-errores-en-el-scheduler)
- [WhoopSyncService: la logica de sincronizacion](#whoopsyncservice-la-logica-de-sincronizacion)
  - [Sincronizacion incremental](#sincronizacion-incremental)
  - [Independencia entre sincronizaciones](#independencia-entre-sincronizaciones)
  - [Mapeo JSON a entidades](#mapeo-json-a-entidades)
  - [parseInstant(): conversion segura de String a Instant](#parseinstant-conversion-segura-de-string-a-instant)
  - [requireNotNull(): validacion de campos obligatorios](#requirenotnull-validacion-de-campos-obligatorios)
- [Flujo completo de una sincronizacion](#flujo-completo-de-una-sincronizacion)
- [Documentacion oficial](#documentacion-oficial)

---

## Que es @Scheduled?

`@Scheduled` es una anotacion de Spring que permite ejecutar un metodo **automaticamente** en intervalos regulares o en momentos especificos, sin necesidad de que ningun cliente haga una peticion HTTP. Es el equivalente a un **cron job** del sistema operativo, pero gestionado dentro de la aplicacion Spring.

En este proyecto, la sincronizacion con la Whoop API se ejecuta automaticamente cada 30 minutos (configurable), descargando datos nuevos y guardandolos en base de datos.

---

## Habilitacion con @EnableScheduling

Para que `@Scheduled` funcione, es obligatorio habilitar el scheduling en la clase principal de la aplicacion.

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplication.kt`

```kotlin
@SpringBootApplication
@EnableScheduling
class WhoopDavidApiApplication

fun main(args: Array<String>) {
    runApplication<WhoopDavidApiApplication>(*args)
}
```

**`@EnableScheduling`** le dice a Spring: "busca beans con metodos `@Scheduled` y programalos para ejecutarse automaticamente". Sin esta anotacion, `@Scheduled` se ignora silenciosamente (el metodo nunca se ejecutaria y no habria ningun error ni advertencia).

Spring crea internamente un `TaskScheduler` con un pool de hilos dedicado para ejecutar las tareas programadas. Por defecto usa un solo hilo, lo que significa que las tareas programadas se ejecutan secuencialmente (si una tarea tarda mucho, la siguiente espera).

---

## WhoopDataSyncScheduler: el disparador

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/scheduler/WhoopDataSyncScheduler.kt`

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

Desglose:

1. **`@Component`**: Registra la clase como un bean de Spring. Es necesario porque Spring solo busca `@Scheduled` en beans gestionados por el contenedor.

2. **Inyeccion por constructor**: `WhoopDataSyncScheduler` recibe `WhoopSyncService` via constructor. Esto sigue el principio de **separacion de responsabilidades**: el scheduler solo decide **cuando** ejecutar; el servicio decide **que** ejecutar.

3. **`@Scheduled(cron = ...)`**: Programa el metodo para ejecutarse segun la expresion cron.

### Cron expression externalizada

```kotlin
@Scheduled(cron = "\${app.whoop.sync-cron}")
```

La expresion cron **no esta hardcodeada**. Se lee de la propiedad `app.whoop.sync-cron` del YAML. Esto permite cambiar la frecuencia de sincronizacion por perfil:

| Perfil | Archivo | Valor | Significado |
|---|---|---|---|
| Base (todos) | `src/main/resources/application.yaml` | `"0 */30 * * * *"` | Cada 30 minutos |
| demo | `src/main/resources/application-demo.yaml` | `"0 */5 * * * *"` | Cada 5 minutos |

En el perfil `demo`, la sincronizacion es mas frecuente (cada 5 minutos) porque los datos son mock y no hay riesgo de exceder rate limits de la API real.

La sintaxis `"\${...}"` es **SpEL (Spring Expression Language)**. En el codigo Kotlin se escribe `"\${app.whoop.sync-cron}"` (con backslash para escapar el `$` de Kotlin). Spring lo resuelve al valor de la propiedad en tiempo de arranque.

### Formato de cron en Spring (6 campos)

Spring usa expresiones cron de **6 campos** (a diferencia del cron de Linux que usa 5):

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

**Diferencia con Linux**: Linux no tiene el campo de **segundos**. Spring lo anade como primer campo.

Desglose de la expresion `"0 */30 * * * *"`:

| Campo | Valor | Significado |
|---|---|---|
| Segundo | `0` | En el segundo 0 (inicio del minuto) |
| Minuto | `*/30` | Cada 30 minutos (`*/N` = cada N unidades) |
| Hora | `*` | Cualquier hora |
| Dia del mes | `*` | Cualquier dia |
| Mes | `*` | Cualquier mes |
| Dia de la semana | `*` | Cualquier dia de la semana |

Resultado: se ejecuta en el minuto 0 y minuto 30 de cada hora. Es decir, a las 00:00, 00:30, 01:00, 01:30, 02:00, etc.

Otros ejemplos utiles:

| Expresion | Significado |
|---|---|
| `"0 */5 * * * *"` | Cada 5 minutos (usado en perfil demo) |
| `"0 0 6 * * *"` | Todos los dias a las 6:00 AM |
| `"0 0 */2 * * *"` | Cada 2 horas |
| `"0 0 8 * * MON-FRI"` | Lunes a viernes a las 8:00 AM |
| `"0 0 0 1 * *"` | El dia 1 de cada mes a medianoche |

### Manejo de errores en el scheduler

```kotlin
try {
    whoopSyncService.syncAll()
} catch (ex: Exception) {
    log.error("Error en sincronizacion programada: {}", ex.message, ex)
}
```

El `try-catch` es **critico** en un metodo `@Scheduled`. Si una excepcion no capturada escapa del metodo, Spring cancela las ejecuciones futuras de ese metodo programado. Es decir, si una sincronizacion falla sin try-catch, nunca mas se volveria a ejecutar hasta reiniciar la aplicacion.

Con el try-catch, el error se registra en los logs pero el scheduler continua programado para la siguiente ejecucion.

---

## WhoopSyncService: la logica de sincronizacion

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt`

### Sincronizacion incremental

La estrategia de sincronizacion es **incremental**: en vez de descargar todos los datos de Whoop cada vez, solo se piden los datos **posteriores al ultimo registro guardado**.

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

El metodo `syncAll()` llama a los 4 metodos de sincronizacion secuencialmente y mide el tiempo total. Se usa `System.currentTimeMillis()` para calcular la duracion.

El metodo `syncCycles()` muestra el patron completo:

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

Paso a paso:

1. **`cycleRepository.findTopByOrderByUpdatedAtDesc()?.updatedAt`**: Busca el cycle mas reciente en la base de datos y obtiene su campo `updatedAt`. Si la base de datos esta vacia, devuelve `null` (primera sincronizacion).

2. **`whoopApiClient.getAllCycles(start = lastUpdated)`**: Pide a la Whoop API todos los cycles desde la fecha `lastUpdated`. Si es `null` (primera vez), Whoop devuelve todo el historico.

3. **Bucle de guardado con try-catch interno**: Cada registro se mapea y guarda individualmente. Si un registro tiene datos invalidos (por ejemplo, falta el campo `start`), `mapToCycle` lanza `IllegalArgumentException` y ese registro se salta. Los demas se siguen procesando.

4. **`cycleRepository.save(cycle)`**: JPA `save()` hace un **upsert**: si ya existe un registro con el mismo `id`, lo actualiza. Si no existe, lo inserta. Esto permite reprocesar registros que se hayan actualizado en Whoop.

5. **Contadores `saved` y `skipped`**: Se registran en los logs para monitorizar la salud de la sincronizacion.

Los metodos `syncRecoveries()`, `syncSleeps()` y `syncWorkouts()` siguen exactamente el mismo patron. La unica diferencia es el repositorio y el metodo de mapeo que usan.

### Independencia entre sincronizaciones

Cada metodo `syncXxx()` tiene su propio `try-catch` externo:

```kotlin
fun syncAll() {
    syncCycles()       // Si falla, los demas siguen
    syncRecoveries()   // Si falla, los demas siguen
    syncSleeps()       // Si falla, los demas siguen
    syncWorkouts()     // Si falla, los demas siguen
}
```

Si `syncCycles()` falla (por ejemplo, Whoop devuelve error 500 para cycles), la excepcion se captura dentro de `syncCycles()` y las sincronizaciones de recoveries, sleeps y workouts se ejecutan normalmente. Este diseno evita que un fallo parcial pierda toda la sincronizacion.

### Mapeo JSON a entidades

La Whoop API devuelve JSON como `Map<String, Any?>`. El mapeo a entidades JPA se hace manualmente con safe casts de Kotlin.

Ejemplo completo con `mapToCycle()`:

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

**Tecnicas de Kotlin usadas:**

| Expresion | Que hace | Cuando se usa |
|---|---|---|
| `as Number` | Cast **inseguro**. Lanza `ClassCastException` si no es un `Number` | Campos obligatorios como `id` |
| `as? Number` | Cast **seguro** (safe cast). Devuelve `null` si no se puede convertir | Campos opcionales como `strain` |
| `as? Map<*, *>` | Safe cast a un Map generico. Se usa para objetos JSON anidados | El campo `score` es un objeto JSON anidado |
| `?.toFloat()` | Operador de llamada segura. Solo llama `toFloat()` si no es `null` | Conversion encadenada tras safe cast |
| `?: "PENDING_SCORE"` | Operador Elvis. Si el valor es `null`, usa el valor por defecto | Campos con valor por defecto |

**Por que `as Number` y no `as Long` directamente?** Jackson (el deserializador JSON) convierte numeros JSON a diferentes tipos Java segun su tamano: `Integer` para numeros pequenos, `Long` para grandes. Usando `Number` (la clase padre de todos los tipos numericos en Java), se acepta cualquier tipo numerico y luego se convierte con `.toLong()`.

**Campos anidados (score)**: La Whoop API devuelve el score como un objeto JSON anidado. Primero se extrae el mapa del score y luego se acceden sus campos:

```kotlin
val score = record["score"] as? Map<*, *>   // Puede ser null si no hay score
// ...
strain = (score?.get("strain") as? Number)?.toFloat()
// score?         -> si score es null, toda la expresion es null
// .get("strain") -> obtiene el valor de la clave "strain"
// as? Number     -> safe cast a Number
// ?.toFloat()    -> convierte a Float si no es null
```

### parseInstant(): conversion segura de String a Instant

```kotlin
private fun parseInstant(value: Any?): Instant? {
    return when (value) {
        is String -> try { Instant.parse(value) } catch (_: Exception) { null }
        else -> null
    }
}
```

Esta funcion utilitaria convierte un valor JSON (que puede ser `String`, `null`, u otro tipo) a un `Instant` de Java.

- **`when (value)`**: Expresion when de Kotlin (equivalente a switch en otros lenguajes).
- **`is String`**: Verifica que el valor sea un String. Si no lo es, devuelve `null`.
- **`Instant.parse(value)`**: Parsea un String ISO-8601 (como `"2024-12-15T10:30:00.000Z"`) a un `Instant`.
- **`catch (_: Exception) { null }`**: Si el String no tiene formato ISO-8601 valido, devuelve `null` en vez de lanzar excepcion. El `_` indica que la excepcion se descarta (no se necesita su contenido).

### requireNotNull(): validacion de campos obligatorios

```kotlin
start = requireNotNull(parseInstant(record["start"])) {
    "Campo 'start' requerido en cycle (id=${record["id"] ?: "desconocido"})"
},
```

`requireNotNull()` es una funcion estandar de Kotlin que lanza `IllegalArgumentException` si el valor es `null`. Es la forma idiomatica de validar precondiciones en Kotlin.

- Si `parseInstant(record["start"])` devuelve `null` (el campo no existe o tiene formato invalido), se lanza `IllegalArgumentException` con el mensaje proporcionado.
- Ese `IllegalArgumentException` es capturado por el `try-catch` en `syncCycles()`, que salta ese registro y continua con el siguiente.
- El mensaje incluye el `id` del registro para facilitar el debug: `"Campo 'start' requerido en cycle (id=1005)"`.

---

## Flujo completo de una sincronizacion

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

Primera ejecucion (base de datos vacia):
- `findTopByOrderByUpdatedAtDesc()` devuelve `null`
- `getAllCycles(start = null)` pide **todo** el historico
- Puede haber multiples paginas (bucle do-while con `next_token`)

Ejecuciones posteriores:
- Se obtiene la fecha del ultimo registro guardado
- Solo se piden datos mas recientes
- Normalmente son pocos registros (0-2 por cada tipo en 30 minutos)

---

## Documentacion oficial

- [Spring @Scheduled](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) - Documentacion oficial de tareas programadas
- [Spring Cron Expressions](https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-cron-expression) - Formato de cron de Spring (6 campos)
- [Kotlin requireNotNull](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/require-not-null.html) - Documentacion de requireNotNull
- [Kotlin Safe Casts](https://kotlinlang.org/docs/typecasts.html#safe-nullable-cast-operator) - Operador as? de Kotlin
- [Java Instant.parse](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/time/Instant.html#parse(java.lang.CharSequence)) - Parseo de timestamps ISO-8601
