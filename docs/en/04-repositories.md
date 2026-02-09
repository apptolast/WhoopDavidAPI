# 04 - Spring Data JPA Repositories

> How Spring automatically generates SQL from Kotlin interfaces, without writing a single query.

---

## 1. What is the Repository pattern?

The **Repository pattern** is an abstraction that separates business logic from data access. Instead of your service writing SQL directly, it asks the repository "give me the cycles between these dates," and the repository takes care of the how.

```
Controller  -->  Service  -->  Repository  -->  Base de datos
                  (logica)      (abstraccion)    (SQL real)
```

**[Spring Data JPA](https://docs.spring.io/spring-data/jpa/reference/)** takes this pattern to the extreme: you only define an **interface** with method signatures, and Spring **generates the complete implementation** at runtime. You don’t write SQL, you don’t write implementation classes, you don’t write anything other than the interface.

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

## 2. Where is it used in this project?

The repositories are in the `repository` package:

| File | Entity | ID Type |
|---------|---------|------------|
| [`src/main/kotlin/.../repository/CycleRepository.kt`](../../src/main/kotlin/com/example/whoopdavidapi/repository/CycleRepository.kt) | `WhoopCycle` | `Long` |
| [`src/main/kotlin/.../repository/RecoveryRepository.kt`](../../src/main/kotlin/com/example/whoopdavidapi/repository/RecoveryRepository.kt) | `WhoopRecovery` | `Long` |
| [`src/main/kotlin/.../repository/SleepRepository.kt`](../../src/main/kotlin/com/example/whoopdavidapi/repository/SleepRepository.kt) | `WhoopSleep` | `String` |
| [`src/main/kotlin/.../repository/WorkoutRepository.kt`](../../src/main/kotlin/com/example/whoopdavidapi/repository/WorkoutRepository.kt) | `WhoopWorkout` | `String` |
| [`src/main/kotlin/.../repository/OAuthTokenRepository.kt`](../../src/main/kotlin/com/example/whoopdavidapi/repository/OAuthTokenRepository.kt) | `OAuthTokenEntity` | `Long` |

Repositories are consumed in two layers:

- **Reading services** (e.g.: [`CycleService.kt`](../../src/main/kotlin/com/example/whoopdavidapi/service/CycleService.kt)): They use filtering and pagination methods to serve data to the REST API.
- **Synchronization service** ([`WhoopSyncService.kt`](../../src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt)): Uses `findTopByOrderByUpdatedAtDesc()` for incremental synchronization and `save()` to persist data.

---

## 3. Why this decision?

### Why Spring Data JPA and not write manual queries?

1. **Zero boilerplate**: You don’t need `EntityManager`, `CriteriaBuilder`, or SQL strings. You define an interface and Spring does the rest.
2. **Type safety**: If you change the name of a field in the entity (e.g., `start` to `startTime`), the `findByStartBetween` method would no longer compile. Spring validates method names against the entity’s fields when the application starts up.
3. **Integrated pagination**: The parameter [`Pageable`](https://docs.spring.io/spring-data/commons/reference/repositories/query-methods-details.html) and the return value [`Page<T>`](https://docs.spring.io/spring-data/commons/reference/repositories/query-methods-details.html) give you complete pagination (total elements, total pages, has next page) effortlessly.
4. **Queries in this project are simple**: Date filters and pagination. There are no complex joins or subqueries that would justify manual SQL.

### Why `JpaRepository` and not `CrudRepository`?

Spring Data offers a hierarchy of interfaces:

```
Repository                        (vacio, marcador)
  └── CrudRepository              (save, findById, findAll, delete, count)
       └── ListCrudRepository     (versiones con List en vez de Iterable)
            └── JpaRepository     (+ flush, saveAllAndFlush, paginacion con findAll(Pageable))
```

We use [`JpaRepository`](https://docs.spring.io/spring-data/jpa/reference/jpa/getting-started.html) because we need **pagination** (`findAll(Pageable)`) and the batch methods (`saveAll`), which [`CrudRepository`](https://docs.spring.io/spring-data/commons/reference/repositories/core-concepts.html) does not offer directly.

---

## 4. Explained code

### 4a. `JpaRepository<Entity, IdType>`: that it gives you for free

By extending `JpaRepository<WhoopCycle, Long>`, you get **without writing anything** these methods (among others):

| Method | Generated SQL (simplified) | Use in the project |
|--------|-------|-----|
| `save(entity)` | `INSERT INTO ... / UPDATE ...` (decide automatically) | `WhoopSyncService.syncCycles()` |
| `saveAll(entities)` | Batch of `INSERT`/`UPDATE` | Tests |
| `findById(id)` | `SELECT * FROM whoop_cycles WHERE id = ?` | Tests |
| `findAll()` | `SELECT * FROM whoop_cycles` | - |
| `findAll(pageable)` | `SELECT * FROM ... ORDER BY ... LIMIT ? OFFSET ?` + `SELECT COUNT(*)...` | `CycleService.getCycles()` |
| `count()` | `SELECT COUNT(*) FROM whoop_cycles` | - |
| `deleteById(id)` | `DELETE FROM whoop_cycles WHERE id = ?` | - |
| `existsById(id)` | `SELECT COUNT(*) > 0 FROM ... WHERE id = ?` | - |

**Note about `save()`**: This method is smart. If the entity has an `@Id` with a value (not null/0), Hibernate checks whether it already exists in the DB:

- If **exists**: execute `UPDATE` (merge).
- If **does not exist**: execute `INSERT` (persist).

This is fundamental for Whoop’s incremental synchronization: when re-synchronizing data, existing records are updated instead of being duplicated.

### 4b. `CycleRepository`: complete anatomy of a repository

Let’s analyze [`CycleRepository.kt`](../../src/main/kotlin/com/example/whoopdavidapi/repository/CycleRepository.kt):

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

**Name breakdown `findByStartBetween`**:

| Part | Meaning |
|-------|-------------|
| (a) `find` | Search operation (`SELECT`) |
| (b) `By` | Separator: what follows is the filter criterion (`WHERE`) |
| (c) `StartBetween` | Field `start` with operator `BETWEEN` |
| (d) `pageable: Pageable` | Special parameter: Spring applies `ORDER BY`, `LIMIT`, and `OFFSET` automatically |
| (e) `Page<WhoopCycle>` | Special return: includes the data + pagination metadata |

### 4c. Derived Query Methods: how Spring generates SQL from names

Spring Data JPA analyzes the method name and "parses" it into an SQL query. Here are the exact translations for our project:

#### `findByStartBetween(from, to, pageable)`

```sql
SELECT w FROM WhoopCycle w
WHERE w.start BETWEEN :from AND :to
ORDER BY ...    -- segun el Pageable
LIMIT ...       -- segun el Pageable
OFFSET ...      -- segun el Pageable
```

In native SQL (what Hibernate actually executes against PostgreSQL):

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

**Breakdown of each keyword**:

| Keyword | SQL equivalent | Example |
|---------------|----------------|---------|
| `findBy` | `SELECT ... WHERE` | `findByStartBetween` |
| `Between` | `BETWEEN ? AND ?` | `findByStartBetween(from, to)` |
| `GreaterThanEqual` | `>= ?` | `findByStartGreaterThanEqual(from)` |
| `LessThan` | `< ?` | `findByStartLessThan(to)` |
| `Top` (without number) | `LIMIT 1` | `findTopByOrderBy...` |
| `OrderBy` | `ORDER BY` | `findTopByOrderByUpdatedAtDesc` |
| `Desc` | `DESC` | `OrderByUpdatedAtDesc` |

### 4d. Variations between repositories: same pattern, different fields

The five repositories follow the same pattern. The differences are:

**[`RecoveryRepository.kt`](../../src/main/kotlin/com/example/whoopdavidapi/repository/RecoveryRepository.kt)**:

```kotlin
interface RecoveryRepository : JpaRepository<WhoopRecovery, Long> {
    fun findByCreatedAtBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopRecovery>
    //         ^^^^^^^^^ filtra por createdAt en vez de start (Recovery no tiene campo start)
    fun findByCreatedAtGreaterThanEqual(from: Instant, pageable: Pageable): Page<WhoopRecovery>
    fun findByCreatedAtLessThan(to: Instant, pageable: Pageable): Page<WhoopRecovery>
    fun findTopByOrderByUpdatedAtDesc(): WhoopRecovery?
}
```

Note: `WhoopRecovery` does not have a `start` field, so the date filters use `createdAt`.

**[`SleepRepository.kt`](../../src/main/kotlin/com/example/whoopdavidapi/repository/SleepRepository.kt)**:

```kotlin
interface SleepRepository : JpaRepository<WhoopSleep, String> {
    //                                                ^^^^^^ ID es String (UUID de Whoop)
    fun findByStartBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopSleep>
    fun findByStartGreaterThanEqual(from: Instant, pageable: Pageable): Page<WhoopSleep>
    fun findByStartLessThan(to: Instant, pageable: Pageable): Page<WhoopSleep>
    fun findTopByOrderByUpdatedAtDesc(): WhoopSleep?
}
```

**[`WorkoutRepository.kt`](../../src/main/kotlin/com/example/whoopdavidapi/repository/WorkoutRepository.kt)**:

```kotlin
interface WorkoutRepository : JpaRepository<WhoopWorkout, String> {
    //                                                    ^^^^^^ ID es String
    fun findByStartBetween(from: Instant, to: Instant, pageable: Pageable): Page<WhoopWorkout>
    fun findByStartGreaterThanEqual(from: Instant, pageable: Pageable): Page<WhoopWorkout>
    fun findByStartLessThan(to: Instant, pageable: Pageable): Page<WhoopWorkout>
    fun findTopByOrderByUpdatedAtDesc(): WhoopWorkout?
}
```

**[`OAuthTokenRepository.kt`](../../src/main/kotlin/com/example/whoopdavidapi/repository/OAuthTokenRepository.kt)** -- the simplest:

```kotlin
interface OAuthTokenRepository : JpaRepository<OAuthTokenEntity, Long> {
    fun findTopByOrderByUpdatedAtDesc(): OAuthTokenEntity?
}
```

It only has one custom method: get the most recent token. It doesn’t need date filters or pagination because there’s only one user and few historical tokens.

### 4e. `Page<T>` and `Pageable`: complete pagination

**`Pageable`** is an object that encapsulates three things: page number, page size, and sorting.

In [`CycleService.kt`](../../src/main/kotlin/com/example/whoopdavidapi/service/CycleService.kt) it is created like this:

```kotlin
val pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "start"))
//                         ^^^^^^^^^                ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                         page - 1: Spring usa      Ordenar por campo "start"
//                         paginas base-0, pero      de la entidad, descendente
//                         nuestra API usa base-1
```

**`Page<T>`** is the result that includes the data AND metadata:

```kotlin
val result: Page<WhoopCycle> = cycleRepository.findByStartBetween(from, to, pageable)

result.content         // List<WhoopCycle> - los datos de esta pagina
result.totalElements   // Long - total de registros que coinciden (ej: 150)
result.totalPages      // Int - total de paginas (ej: 2 si pageSize=100)
result.hasNext()       // Boolean - hay mas paginas despues de esta?
result.number          // Int - numero de pagina actual (base-0)
result.size            // Int - tamano de pagina solicitado
```

Spring automatically executes **two queries** to build the `Page`:

1. `SELECT * FROM whoop_cycles WHERE ... ORDER BY ... LIMIT ? OFFSET ?` (the data)
2. `SELECT COUNT(*) FROM whoop_cycles WHERE ...` (the total, to know how many pages there are)

These metadata are transformed into the API response within the service:

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

### 4f. `findTopByOrderByUpdatedAtDesc()`: the key to incremental synchronization

This method appears in **all** repositories and is fundamental to the synchronization strategy.

In [`WhoopSyncService.kt`](../../src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt):

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

**Why is it important?**

Without incremental synchronization, each scheduler run would download **all** of Whoop’s historical data (potentially years of data). With `findTopByOrderByUpdatedAtDesc()`:

1. **First run**: There is no data in the DB -> `lastUpdated = null` -> downloads the entire history.
2. **Subsequent runs**: There is data -> `lastUpdated = 2024-06-15T10:30:00Z` -> only downloads updated data after that date.

The return is `WhoopCycle?` (nullable) to handle the case of the first execution when the table is empty.

---

## 5. Official documentation

- [Spring Data JPA Reference - Query Methods](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html)
- [Spring Data JPA - Derived Query Methods (complete table of keywords)](https://docs.spring.io/spring-data/jpa/reference/repositories/query-keywords-reference.html)
- [Spring Data JPA - Paging and Sorting](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html#repositories.special-parameters)
- [JpaRepository JavaDoc](https://docs.spring.io/spring-data/jpa/docs/current/api/org/springframework/data/jpa/repository/JpaRepository.html)
- [Page Interface JavaDoc](https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/domain/Page.html)

---

> **Previous**: [03 - JPA Entities](./03-entidades-jpa.md)
>
> **Next**: [05 - DTOs and MapStruct](./05-dtos-mapstruct.md) -- how to separate what you expose in the API from what you store in the DB.
