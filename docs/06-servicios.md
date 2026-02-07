# 06 - Capa de Servicios (`@Service`)

## Que es la capa de servicios?

La capa de servicios es la **capa intermedia** entre los controladores REST (que reciben peticiones HTTP) y los repositorios (que acceden a la base de datos). Su responsabilidad es contener la **logica de negocio**: transformar datos, aplicar reglas, coordinar operaciones entre distintos repositorios, etc.

En Spring, se marca una clase como servicio con la anotacion `@Service`. Esto le dice a Spring: "esta clase es un componente que contiene logica de negocio, registrala en el contenedor de inyeccion de dependencias".

```
Peticion HTTP
     |
     v
Controller  -->  Service  -->  Repository  -->  Base de datos
     ^              |
     |              v
ResponseEntity   MapStruct (Entity -> DTO)
```

El controlador **no debe** acceder al repositorio directamente. Siempre delega al servicio, que decide como obtener y transformar los datos.

---

## Donde se usa en este proyecto?

Hay **5 servicios** en el proyecto:

| Servicio | Archivo | Responsabilidad |
|---|---|---|
| `CycleService` | `src/main/kotlin/com/example/whoopdavidapi/service/CycleService.kt` | Consulta paginada de ciclos fisiologicos |
| `RecoveryService` | `src/main/kotlin/com/example/whoopdavidapi/service/RecoveryService.kt` | Consulta paginada de recuperaciones |
| `SleepService` | `src/main/kotlin/com/example/whoopdavidapi/service/SleepService.kt` | Consulta paginada de datos de sueno |
| `WorkoutService` | `src/main/kotlin/com/example/whoopdavidapi/service/WorkoutService.kt` | Consulta paginada de entrenamientos |
| `WhoopSyncService` | `src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt` | Orquesta la sincronizacion incremental con la Whoop API |

Los 4 primeros (Cycle, Recovery, Sleep, Workout) siguen un **patron identico**. El quinto (`WhoopSyncService`) tiene una responsabilidad diferente: no sirve datos al usuario, sino que los obtiene de la API externa y los guarda en la base de datos.

---

## Por que una capa de servicios?

1. **Separacion de responsabilidades**: el controlador se limita a validar parametros HTTP y devolver respuestas. El servicio contiene la logica real.
2. **Reutilizacion**: un mismo servicio puede ser invocado desde un controlador, desde un scheduler, desde un test... sin duplicar codigo.
3. **Testabilidad**: se puede testear la logica de negocio del servicio en aislamiento, usando mocks para los repositorios, sin necesidad de levantar un servidor HTTP.
4. **Principio DRY (Don't Repeat Yourself)**: en este proyecto, los 4 servicios de consulta siguen el mismo patron. Si la logica de paginacion cambiara, basta con modificarla en la capa de servicio.

---

## Codigo explicado

### 1. La anotacion `@Service` y component scanning

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

`@Service` es una **especializacion** de `@Component`. Tecnicamente, ambas hacen lo mismo: registrar la clase como un bean en el contenedor de Spring. La diferencia es **semantica**: `@Service` indica que la clase contiene logica de negocio.

Cuando la aplicacion arranca, Spring Boot ejecuta el **component scanning**: escanea todos los paquetes bajo la clase marcada con `@SpringBootApplication` (en este caso, `com.example.whoopdavidapi`) y busca clases anotadas con `@Component`, `@Service`, `@Repository`, `@Controller`, etc. Cada una se registra como un bean singleton que puede ser inyectado en otras clases.

La jerarquia de anotaciones de componentes es:

```
@Component              <-- Generico (base)
  |-- @Service          <-- Logica de negocio
  |-- @Repository        <-- Acceso a datos
  |-- @Controller        <-- Controladores web
```

### 2. Inyeccion por constructor (constructor injection en Kotlin)

```kotlin
@Service
class CycleService(
    private val cycleRepository: CycleRepository,
    private val cycleMapper: CycleMapper
) {
```

En Kotlin, el **constructor primario** se declara directamente en la firma de la clase. Spring detecta automaticamente que `CycleService` necesita un `CycleRepository` y un `CycleMapper`, busca beans de esos tipos en su contenedor, y los inyecta al crear la instancia.

**No se necesita `@Autowired`**. Desde Spring 4.3, si una clase tiene un unico constructor, Spring lo usa automaticamente para inyeccion. Como en Kotlin el constructor primario es el unico constructor (a menos que declares constructores secundarios con `constructor`), la inyeccion funciona sin ninguna anotacion adicional.

Las propiedades son `private val` por dos razones:
- `private`: encapsulacion, ninguna otra clase puede acceder a estas dependencias directamente.
- `val`: inmutabilidad, una vez asignada la dependencia no puede cambiar.

### 3. Logica de paginacion: `PageRequest.of()`

```kotlin
fun getCycles(
    from: Instant?,
    to: Instant?,
    page: Int,
    pageSize: Int
): PaginatedResponse<CycleDTO> {
    val pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "start"))
```

`PageRequest.of()` crea un objeto `Pageable` que Spring Data JPA usa para construir automaticamente las clausulas `LIMIT`, `OFFSET` y `ORDER BY` en la consulta SQL.

La conversion `page - 1` es critica:
- El **usuario** envia paginas **basadas en 1** (pagina 1, 2, 3...) porque es mas natural para un humano.
- Spring Data JPA usa paginas **basadas en 0** internamente (pagina 0, 1, 2...).
- `page - 1` convierte entre ambos sistemas. Si el usuario pide `page=1`, Spring recibe `0` y devuelve los primeros resultados.

`Sort.by(Sort.Direction.DESC, "start")` indica que los resultados se ordenen por el campo `start` de forma descendente (los mas recientes primero). El nombre `"start"` debe coincidir con el nombre del atributo en la entidad JPA (`WhoopCycle.start`), no con el nombre de la columna en la base de datos.

### 4. La expresion `when` para filtrado condicional

```kotlin
val result = when {
    from != null && to != null -> cycleRepository.findByStartBetween(from, to, pageable)
    from != null -> cycleRepository.findByStartGreaterThanEqual(from, pageable)
    to != null -> cycleRepository.findByStartLessThan(to, pageable)
    else -> cycleRepository.findAll(pageable)
}
```

`when` es la version Kotlin de un `switch` en Java, pero mucho mas potente. Aqui se usa como **expresion** (devuelve un valor que se asigna a `result`), no como sentencia.

Se evaluan las condiciones de arriba a abajo:

| Condicion | Metodo del repositorio | SQL generado (simplificado) |
|---|---|---|
| `from` y `to` presentes | `findByStartBetween(from, to, pageable)` | `WHERE start BETWEEN ? AND ?` |
| Solo `from` presente | `findByStartGreaterThanEqual(from, pageable)` | `WHERE start >= ?` |
| Solo `to` presente | `findByStartLessThan(to, pageable)` | `WHERE start < ?` |
| Ninguno presente | `findAll(pageable)` | Sin filtro (todos los registros) |

Los parametros `from` y `to` son `Instant?` (nullable). Esto permite al usuario hacer peticiones como:
- `/api/v1/cycles` -- sin filtro, devuelve todo paginado.
- `/api/v1/cycles?from=2024-01-01T00:00:00Z` -- desde esa fecha en adelante.
- `/api/v1/cycles?from=2024-01-01T00:00:00Z&to=2024-06-01T00:00:00Z` -- rango especifico.

### 5. Mapeo Entity a DTO con MapStruct

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

`result` es un `Page<WhoopCycle>` (un objeto de Spring Data que contiene los resultados paginados y metadatos de paginacion).

- `result.content` devuelve la `List<WhoopCycle>` con las entidades de la pagina actual.
- `.map { cycleMapper.toDto(it) }` transforma cada entidad en su DTO usando MapStruct.
- `result.totalElements` es el numero **total** de registros en la base de datos que coinciden con el filtro (no solo los de esta pagina).
- `result.hasNext()` indica si hay mas paginas despues de la actual.

El mapper (`CycleMapper`) es una interfaz generada por MapStruct:

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/mapper/CycleMapper.kt

@Mapper(componentModel = "spring")
interface CycleMapper {
    fun toDto(entity: WhoopCycle): CycleDTO
    fun toEntity(dto: CycleDTO): WhoopCycle
}
```

`componentModel = "spring"` hace que MapStruct genere una implementacion anotada con `@Component`, que Spring puede inyectar automaticamente en `CycleService`.

La respuesta paginada se envuelve en un DTO generico:

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

El JSON que recibe el cliente (Power BI) tiene esta estructura:

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

### 6. Principio DRY: los 4 servicios de consulta son identicos

Los servicios `CycleService`, `RecoveryService`, `SleepService` y `WorkoutService` siguen **exactamente el mismo patron**. La unica diferencia es:
- El **repositorio** que usan (`CycleRepository`, `RecoveryRepository`, etc.).
- El **mapper** que usan (`CycleMapper`, `RecoveryMapper`, etc.).
- El **campo de ordenacion** (`"start"` para Cycle/Sleep/Workout, `"createdAt"` para Recovery).

Por ejemplo, `RecoveryService` difiere solo en que ordena por `"createdAt"` en vez de `"start"`:

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

Este patron repetido (4 clases con la misma estructura) es un compromiso consciente: se podria abstraer en una clase generica, pero la simplicidad y legibilidad de tener clases explicitas es preferible cuando hay solo 4 casos.

### 7. WhoopSyncService: la orquestacion de sincronizacion

`WhoopSyncService` tiene una responsabilidad distinta: no sirve datos al usuario, sino que **obtiene datos de la Whoop API y los guarda en la base de datos**.

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

Puntos clave:

- **5 dependencias inyectadas**: el cliente HTTP (`WhoopApiClient`) y los 4 repositorios. Este servicio orquesta la comunicacion entre la API externa y la base de datos local.
- **`syncAll()`**: metodo publico que el scheduler (`WhoopDataSyncScheduler`) invoca periodicamente. Sincroniza los 4 tipos de datos en secuencia.
- **Logging con SLF4J**: `LoggerFactory.getLogger(javaClass)` crea un logger con el nombre de la clase. Los `{}` son placeholders que SLF4J reemplaza solo si el nivel de log esta activo (mas eficiente que concatenar strings).
- **Medicion de tiempo**: `System.currentTimeMillis()` antes y despues permite registrar cuanto tarda la sincronizacion completa.

### 8. Sincronizacion incremental

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

La estrategia **incremental** evita re-descargar todo cada vez:

1. `cycleRepository.findTopByOrderByUpdatedAtDesc()` obtiene el registro mas reciente de la BD local (el que tiene `updatedAt` mas alto).
2. `?.updatedAt` extrae el campo `updatedAt` usando safe call de Kotlin. Si no hay registros, devuelve `null`.
3. `whoopApiClient.getAllCycles(start = lastUpdated)` pide a la Whoop API solo los registros modificados despues de esa fecha. Si `lastUpdated` es `null` (primera sincronizacion), trae todo.
4. Cada registro se mapea a entidad y se guarda con `cycleRepository.save()`. Si ya existe (mismo ID), JPA hace un UPDATE en vez de INSERT.

Los metodos `syncRecoveries()`, `syncSleeps()` y `syncWorkouts()` siguen el mismo patron.

### 9. Mapeo de JSON a entidades: safe casts de Kotlin

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

Este metodo convierte un `Map<String, Any?>` (respuesta JSON deserializada) a una entidad JPA. Usa varios operadores de Kotlin:

| Operador | Significado | Ejemplo |
|---|---|---|
| `as?` | **Safe cast**: intenta castear. Si falla, devuelve `null` en vez de lanzar excepcion. | `record["score"] as? Map<*, *>` |
| `?.` | **Safe call**: si el objeto es `null`, devuelve `null` sin ejecutar el metodo. | `score?.get("strain")` |
| `?:` | **Elvis operator**: si la expresion izquierda es `null`, usa el valor de la derecha. | `record["score_state"] as? String ?: "PENDING_SCORE"` |
| `requireNotNull()` | Lanza `IllegalArgumentException` si el valor es `null`. | `requireNotNull(parseInstant(record["start"])) { "mensaje" }` |

El metodo `parseInstant` convierte strings ISO-8601 a `Instant`:

```kotlin
private fun parseInstant(value: Any?): Instant? {
    return when (value) {
        is String -> try { Instant.parse(value) } catch (_: Exception) { null }
        else -> null
    }
}
```

Si el valor no es un `String` o no se puede parsear, devuelve `null` en vez de lanzar una excepcion. Esto hace la sincronizacion mas robusta frente a datos inesperados de la API externa.

### 10. El scheduler que invoca al servicio

El `WhoopSyncService` es invocado periodicamente por el scheduler:

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

El scheduler es un `@Component`, no un `@Service`, porque su unica responsabilidad es **programar** la ejecucion. La logica de sincronizacion real vive en `WhoopSyncService`. Esta separacion permite que la sincronizacion se pueda disparar de otras formas (un endpoint manual, un test) sin depender del scheduler.

---

## Flujo completo de una peticion

Ejemplo: el usuario (Power BI) pide `GET /api/v1/cycles?from=2024-01-01T00:00:00Z&page=2&pageSize=50`.

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

## Documentacion oficial

- **`@Service` y component scanning**: [Spring Framework - Classpath Scanning and Managed Components](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html)
- **Inyeccion por constructor**: [Spring Framework - Constructor-based Dependency Injection](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html#beans-constructor-injection)
- **`Pageable` y `PageRequest`**: [Spring Data JPA - Paging and Sorting](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html#repositories.special-parameters)
- **`Page<T>` result**: [Spring Data Commons - Page Interface](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/domain/Page.html)
- **MapStruct con Spring**: [MapStruct Reference - Using dependency injection](https://mapstruct.org/documentation/stable/reference/html/#using-dependency-injection)
- **SLF4J parameterized logging**: [SLF4J FAQ - What is the fastest way of logging?](https://www.slf4j.org/faq.html#logging_performance)
- **Kotlin safe casts y operadores**: [Kotlin Reference - Type Checks and Casts](https://kotlinlang.org/docs/typecasts.html)
