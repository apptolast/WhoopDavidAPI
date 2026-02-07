# 04 - Repositorios Spring Data JPA

> Como Spring genera SQL automaticamente a partir de interfaces Kotlin, sin escribir una sola query.

---

## 1. Que es el patron Repository?

El **patron Repository** es una abstraccion que separa la logica de negocio del acceso a datos. En vez de que tu servicio escriba SQL directamente, le pide al repositorio "dame los ciclos entre estas fechas" y el repositorio se encarga del como.

```
Controller  -->  Service  -->  Repository  -->  Base de datos
                  (logica)      (abstraccion)    (SQL real)
```

**Spring Data JPA** lleva este patron al extremo: tu solo defines una **interfaz** con firmas de metodos, y Spring **genera la implementacion completa** en tiempo de ejecucion. No escribes SQL, no escribes clases de implementacion, no escribes nada mas que la interfaz.

```kotlin
// Tu solo escribes esto:
interface CycleRepository : JpaRepository<WhoopCycle, Long> {
    fun findByStartBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopCycle>
}

// Spring genera automaticamente esto (simplificado):
class CycleRepositoryImpl : CycleRepository {
    override fun findByStartBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopCycle> {
        val sql = "SELECT * FROM whoop_cycles WHERE start_time BETWEEN ? AND ? ORDER BY ... LIMIT ? OFFSET ?"
        // ejecutar query, mapear resultados, construir Page...
    }
}
```

---

## 2. Donde se usa en este proyecto?

Los repositorios estan en el paquete `repository`:

| Archivo | Entidad | Tipo de ID |
|---------|---------|------------|
| [`src/main/kotlin/.../repository/CycleRepository.kt`](../src/main/kotlin/com/example/whoopdavidapi/repository/CycleRepository.kt) | `WhoopCycle` | `Long` |
| [`src/main/kotlin/.../repository/RecoveryRepository.kt`](../src/main/kotlin/com/example/whoopdavidapi/repository/RecoveryRepository.kt) | `WhoopRecovery` | `Long` |
| [`src/main/kotlin/.../repository/SleepRepository.kt`](../src/main/kotlin/com/example/whoopdavidapi/repository/SleepRepository.kt) | `WhoopSleep` | `String` |
| [`src/main/kotlin/.../repository/WorkoutRepository.kt`](../src/main/kotlin/com/example/whoopdavidapi/repository/WorkoutRepository.kt) | `WhoopWorkout` | `String` |
| [`src/main/kotlin/.../repository/OAuthTokenRepository.kt`](../src/main/kotlin/com/example/whoopdavidapi/repository/OAuthTokenRepository.kt) | `OAuthTokenEntity` | `Long` |

Los repositorios se consumen en dos capas:

- **Servicios de lectura** (ej: [`CycleService.kt`](../src/main/kotlin/com/example/whoopdavidapi/service/CycleService.kt)): Usan los metodos de filtrado y paginacion para servir datos al API REST.
- **Servicio de sincronizacion** ([`WhoopSyncService.kt`](../src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt)): Usa `findTopByOrderByUpdatedAtDesc()` para sincronizacion incremental y `save()` para persistir datos.

---

## 3. Por que esta decision?

### Por que Spring Data JPA y no escribir queries manuales?

1. **Cero boilerplate**: No necesitas `EntityManager`, `CriteriaBuilder`, ni SQL strings. Defines una interfaz y Spring hace el resto.
2. **Seguridad de tipos**: Si cambias el nombre de un campo en la entidad (ej: `start` a `startTime`), el metodo `findByStartBetween` ya no compilaria. Spring valida los nombres de metodo contra los campos de la entidad al arrancar la aplicacion.
3. **Paginacion integrada**: El parametro `Pageable` y el retorno `Page<T>` te dan paginacion completa (total de elementos, total de paginas, tiene siguiente pagina) sin esfuerzo.
4. **Queries de este proyecto son simples**: Filtros por fecha y paginacion. No hay joins complejos ni subqueries que justifiquen SQL manual.

### Por que `JpaRepository` y no `CrudRepository`?

Spring Data ofrece una jerarquia de interfaces:

```
Repository                        (vacio, marcador)
  └── CrudRepository              (save, findById, findAll, delete, count)
       └── ListCrudRepository     (versiones con List en vez de Iterable)
            └── JpaRepository     (+ flush, saveAllAndFlush, paginacion con findAll(Pageable))
```

Usamos `JpaRepository` porque necesitamos **paginacion** (`findAll(Pageable)`) y los metodos batch (`saveAll`), que `CrudRepository` no ofrece directamente.

---

## 4. Codigo explicado

### 4a. `JpaRepository<Entity, IdType>`: que te da gratis

Al extender `JpaRepository<WhoopCycle, Long>`, obtienes **sin escribir nada** estos metodos (entre otros):

| Metodo | SQL generado (simplificado) | Uso en el proyecto |
|--------|-------|-----|
| `save(entity)` | `INSERT INTO ... / UPDATE ...` (decide automaticamente) | `WhoopSyncService.syncCycles()` |
| `saveAll(entities)` | Batch de `INSERT`/`UPDATE` | Tests |
| `findById(id)` | `SELECT * FROM whoop_cycles WHERE id = ?` | Tests |
| `findAll()` | `SELECT * FROM whoop_cycles` | - |
| `findAll(pageable)` | `SELECT * FROM ... ORDER BY ... LIMIT ? OFFSET ?` + `SELECT COUNT(*)...` | `CycleService.getCycles()` |
| `count()` | `SELECT COUNT(*) FROM whoop_cycles` | - |
| `deleteById(id)` | `DELETE FROM whoop_cycles WHERE id = ?` | - |
| `existsById(id)` | `SELECT COUNT(*) > 0 FROM ... WHERE id = ?` | - |

**Nota sobre `save()`**: Este metodo es inteligente. Si la entidad tiene un `@Id` con valor (no null/0), Hibernate verifica si ya existe en la BD:
- Si **existe**: ejecuta `UPDATE` (merge).
- Si **no existe**: ejecuta `INSERT` (persist).

Esto es fundamental para la sincronizacion incremental de Whoop: al re-sincronizar datos, los registros existentes se actualizan en vez de duplicarse.

### 4b. `CycleRepository`: anatomia completa de un repositorio

Analicemos [`CycleRepository.kt`](../src/main/kotlin/com/example/whoopdavidapi/repository/CycleRepository.kt):

```kotlin
package com.example.whoopdavidapi.repository

import com.example.whoopdavidapi.model.entity.WhoopCycle
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface CycleRepository : JpaRepository<WhoopCycle, Long> {
//                                          ^            ^
//                                     Entidad       Tipo del @Id

    fun findByStartBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopCycle>
    //  |    |     |                                    |                    |
    //  (a)  (b)   (c)                                  (d)                  (e)

    fun findByStartGreaterThanEqual(from: Instant, pageable: Pageable): Page<WhoopCycle>

    fun findByStartLessThan(to: Instant, pageable: Pageable): Page<WhoopCycle>

    fun findTopByOrderByUpdatedAtDesc(): WhoopCycle?
    //       |           |          |               |
    //       (f)         (g)        (h)             (i)
}
```

**Desglose del nombre `findByStartBetween`**:

| Parte | Significado |
|-------|-------------|
| (a) `find` | Operacion de busqueda (`SELECT`) |
| (b) `By` | Separador: lo que sigue es el criterio de filtro (`WHERE`) |
| (c) `StartBetween` | Campo `start` con operador `BETWEEN` |
| (d) `pageable: Pageable` | Parametro especial: Spring aplica `ORDER BY`, `LIMIT` y `OFFSET` automaticamente |
| (e) `Page<WhoopCycle>` | Retorno especial: incluye los datos + metadatos de paginacion |

### 4c. Derived Query Methods: como Spring genera SQL a partir de nombres

Spring Data JPA analiza el nombre del metodo y lo "parsea" en una query SQL. Aqui estan las traducciones exactas para nuestro proyecto:

#### `findByStartBetween(from, to, pageable)`

```sql
SELECT w FROM WhoopCycle w
WHERE w.start BETWEEN :from AND :to
ORDER BY ...    -- segun el Pageable
LIMIT ...       -- segun el Pageable
OFFSET ...      -- segun el Pageable
```

En SQL nativo (lo que realmente ejecuta Hibernate contra PostgreSQL):

```sql
SELECT * FROM whoop_cycles
WHERE start_time BETWEEN ? AND ?
ORDER BY start_time DESC
LIMIT 100 OFFSET 0
```

#### `findByStartGreaterThanEqual(from, pageable)`

```sql
SELECT * FROM whoop_cycles
WHERE start_time >= ?
ORDER BY start_time DESC
LIMIT 100 OFFSET 0
```

#### `findByStartLessThan(to, pageable)`

```sql
SELECT * FROM whoop_cycles
WHERE start_time < ?
ORDER BY start_time DESC
LIMIT 100 OFFSET 0
```

#### `findTopByOrderByUpdatedAtDesc()`

```sql
SELECT * FROM whoop_cycles
ORDER BY updated_at DESC
LIMIT 1
```

**Desglose de cada palabra clave**:

| Palabra clave | SQL equivalente | Ejemplo |
|---------------|----------------|---------|
| `findBy` | `SELECT ... WHERE` | `findByStartBetween` |
| `Between` | `BETWEEN ? AND ?` | `findByStartBetween(from, to)` |
| `GreaterThanEqual` | `>= ?` | `findByStartGreaterThanEqual(from)` |
| `LessThan` | `< ?` | `findByStartLessThan(to)` |
| `Top` (sin numero) | `LIMIT 1` | `findTopByOrderBy...` |
| `OrderBy` | `ORDER BY` | `findTopByOrderByUpdatedAtDesc` |
| `Desc` | `DESC` | `OrderByUpdatedAtDesc` |

### 4d. Variaciones entre repositorios: mismo patron, distintos campos

Los cinco repositorios siguen el mismo patron. Las diferencias son:

**[`RecoveryRepository.kt`](../src/main/kotlin/com/example/whoopdavidapi/repository/RecoveryRepository.kt)**:

```kotlin
interface RecoveryRepository : JpaRepository<WhoopRecovery, Long> {
    fun findByCreatedAtBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopRecovery>
    //         ^^^^^^^^^ filtra por createdAt en vez de start (Recovery no tiene campo start)
    fun findByCreatedAtGreaterThanEqual(from: Instant, pageable: Pageable): Page<WhoopRecovery>
    fun findByCreatedAtLessThan(to: Instant, pageable: Pageable): Page<WhoopRecovery>
    fun findTopByOrderByUpdatedAtDesc(): WhoopRecovery?
}
```

Nota: `WhoopRecovery` no tiene campo `start`, asi que los filtros de fecha usan `createdAt`.

**[`SleepRepository.kt`](../src/main/kotlin/com/example/whoopdavidapi/repository/SleepRepository.kt)**:

```kotlin
interface SleepRepository : JpaRepository<WhoopSleep, String> {
    //                                                ^^^^^^ ID es String (UUID de Whoop)
    fun findByStartBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopSleep>
    fun findByStartGreaterThanEqual(from: Instant, pageable: Pageable): Page<WhoopSleep>
    fun findByStartLessThan(to: Instant, pageable: Pageable): Page<WhoopSleep>
    fun findTopByOrderByUpdatedAtDesc(): WhoopSleep?
}
```

**[`WorkoutRepository.kt`](../src/main/kotlin/com/example/whoopdavidapi/repository/WorkoutRepository.kt)**:

```kotlin
interface WorkoutRepository : JpaRepository<WhoopWorkout, String> {
    //                                                    ^^^^^^ ID es String
    fun findByStartBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopWorkout>
    fun findByStartGreaterThanEqual(from: Instant, pageable: Pageable): Page<WhoopWorkout>
    fun findByStartLessThan(to: Instant, pageable: Pageable): Page<WhoopWorkout>
    fun findTopByOrderByUpdatedAtDesc(): WhoopWorkout?
}
```

**[`OAuthTokenRepository.kt`](../src/main/kotlin/com/example/whoopdavidapi/repository/OAuthTokenRepository.kt)** -- el mas simple:

```kotlin
interface OAuthTokenRepository : JpaRepository<OAuthTokenEntity, Long> {
    fun findTopByOrderByUpdatedAtDesc(): OAuthTokenEntity?
}
```

Solo tiene un metodo custom: obtener el token mas reciente. No necesita filtros por fecha ni paginacion porque solo hay un usuario y pocos tokens historicos.

### 4e. `Page<T>` y `Pageable`: paginacion completa

**`Pageable`** es un objeto que encapsula tres cosas: numero de pagina, tamano de pagina, y ordenamiento.

En [`CycleService.kt`](../src/main/kotlin/com/example/whoopdavidapi/service/CycleService.kt) se crea asi:

```kotlin
val pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "start"))
//                         ^^^^^^^^^                ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                         page - 1: Spring usa      Ordenar por campo "start"
//                         paginas base-0, pero      de la entidad, descendente
//                         nuestra API usa base-1
```

**`Page<T>`** es el resultado que incluye los datos Y metadatos:

```kotlin
val result: Page<WhoopCycle> = cycleRepository.findByStartBetween(from, to, pageable)

result.content         // List<WhoopCycle> - los datos de esta pagina
result.totalElements   // Long - total de registros que coinciden (ej: 150)
result.totalPages      // Int - total de paginas (ej: 2 si pageSize=100)
result.hasNext()       // Boolean - hay mas paginas despues de esta?
result.number          // Int - numero de pagina actual (base-0)
result.size            // Int - tamano de pagina solicitado
```

Spring ejecuta **dos queries** automaticamente para construir el `Page`:
1. `SELECT * FROM whoop_cycles WHERE ... ORDER BY ... LIMIT ? OFFSET ?` (los datos)
2. `SELECT COUNT(*) FROM whoop_cycles WHERE ...` (el total, para saber cuantas paginas hay)

Estos metadatos se transforman en la respuesta API dentro del servicio:

```kotlin
// En CycleService.kt
return PaginatedResponse(
    data = result.content.map { cycleMapper.toDto(it) },
    pagination = PaginationInfo(
        page = page,
        pageSize = pageSize,
        totalCount = result.totalElements,  // <-- viene del Page
        hasMore = result.hasNext()          // <-- viene del Page
    )
)
```

### 4f. `findTopByOrderByUpdatedAtDesc()`: la clave de la sincronizacion incremental

Este metodo aparece en **todos** los repositorios y es fundamental para la estrategia de sincronizacion.

En [`WhoopSyncService.kt`](../src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt):

```kotlin
private fun syncCycles() {
    // (1) Obtener la fecha del ultimo registro sincronizado
    val lastUpdated = cycleRepository.findTopByOrderByUpdatedAtDesc()?.updatedAt
    //                                ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //                                SELECT * FROM whoop_cycles
    //                                ORDER BY updated_at DESC
    //                                LIMIT 1
    //                                                              ^^^^^^^^^^^
    //                                                              ?.updatedAt: si no hay
    //                                                              registros, devuelve null

    // (2) Pedir a Whoop API solo datos posteriores a esa fecha
    val records = whoopApiClient.getAllCycles(start = lastUpdated)
    //                                              ^^^^^^^^^^^
    //                                              Si null: trae todo (primera sincronizacion)
    //                                              Si tiene valor: trae solo datos nuevos

    // (3) Guardar cada registro (INSERT si nuevo, UPDATE si ya existe)
    for (record in records) {
        val cycle = mapToCycle(record)
        cycleRepository.save(cycle)
    }
}
```

**Por que es importante?**

Sin sincronizacion incremental, cada ejecucion del scheduler descargaria **todos** los datos historicos de Whoop (potencialmente anos de datos). Con `findTopByOrderByUpdatedAtDesc()`:

1. **Primera ejecucion**: No hay datos en BD -> `lastUpdated = null` -> descarga todo el historico.
2. **Ejecuciones siguientes**: Hay datos -> `lastUpdated = 2024-06-15T10:30:00Z` -> solo descarga datos actualizados despues de esa fecha.

El retorno es `WhoopCycle?` (nullable) para manejar el caso de la primera ejecucion cuando la tabla esta vacia.

---

## 5. Documentacion oficial

- [Spring Data JPA Reference - Query Methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
- [Spring Data JPA - Derived Query Methods (tabla completa de keywords)](https://docs.spring.io/spring-data/jpa/reference/repositories/query-keywords-reference.html)
- [Spring Data JPA - Paging and Sorting](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html#repositories.special-parameters)
- [JpaRepository JavaDoc](https://docs.spring.io/spring-data/jpa/docs/current/api/org/springframework/data/jpa/repository/JpaRepository.html)
- [Page Interface JavaDoc](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/domain/Page.html)

---

> **Anterior**: [03 - Entidades JPA](./03-entidades-jpa.md)
>
> **Siguiente**: [05 - DTOs y MapStruct](./05-dtos-mapstruct.md) -- como separar lo que expones en el API de lo que almacenas en la BD.
