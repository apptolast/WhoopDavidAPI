# 05 - DTOs and MapStruct

> How to separate what you expose in the API from what you store in the DB, and how [MapStruct](https://mapstruct.org/documentation/stable/reference/html/) automatically generates the conversion code at compile time.

---

## 1. What are DTOs?

**[DTO (Data Transfer Object)](https://martinfowler.com/eaaCatalog/dataTransferObject.html)** is an object whose sole purpose is to transport data between the layers of the application. In our case, DTOs are what the REST API returns to the client (Power BI or another consumer).

```
Base de datos  -->  Entidad JPA  -->  Mapper  -->  DTO  -->  JSON  -->  Cliente
                    (WhoopCycle)      (toDto)     (CycleDTO)           (Power BI)
```

**Why not return the entity directly?**

1. **Security**: The `OAuthTokenEntity` entity has `accessToken` and `refreshToken` fields. If you return it as JSON, you expose OAuth2 tokens. With a DTO, you only expose the fields you choose.
2. **Decoupling**: If you change the DB structure (rename a column, add an internal field), you don’t break the API contract. The DTO stays the same.
3. **API format vs DB format**: The DB may have internal fields (`scoreState = "PENDING_SCORE"`) that you want to represent differently in the API, or fields that you don’t need to expose.
4. **Immutability**: JPA entities use `var` (mutable, required by Hibernate). DTOs use `val` (immutable), which is safer for transporting data.

---

## 2. Where is it used in this project?

### DTOs

| File | Source entity | Fields |
|---------|---------------|--------|
| [`src/main/kotlin/.../model/dto/CycleDTO.kt`](../../src/main/kotlin/com/example/whoopdavidapi/model/dto/CycleDTO.kt) | `WhoopCycle` | 12 fields |
| [`src/main/kotlin/.../model/dto/RecoveryDTO.kt`](../../src/main/kotlin/com/example/whoopdavidapi/model/dto/RecoveryDTO.kt) | `WhoopRecovery` | 12 fields |
| [`src/main/kotlin/.../model/dto/SleepDTO.kt`](../../src/main/kotlin/com/example/whoopdavidapi/model/dto/SleepDTO.kt) | `WhoopSleep` | 26 fields |
| [`src/main/kotlin/.../model/dto/WorkoutDTO.kt`](../../src/main/kotlin/com/example/whoopdavidapi/model/dto/WorkoutDTO.kt) | `WhoopWorkout` | 24 fields |
| [`src/main/kotlin/.../model/dto/PaginatedResponse.kt`](../../src/main/kotlin/com/example/whoopdavidapi/model/dto/PaginatedResponse.kt) | (generic) | Pagination wrapper |

### Mappers (MapStruct)

| File | Convert |
|---------|-----------|
| [`src/main/kotlin/.../mapper/CycleMapper.kt`](../../src/main/kotlin/com/example/whoopdavidapi/mapper/CycleMapper.kt) | `WhoopCycle` <-> `CycleDTO` |
| [`src/main/kotlin/.../mapper/RecoveryMapper.kt`](../../src/main/kotlin/com/example/whoopdavidapi/mapper/RecoveryMapper.kt) | `WhoopRecovery` <-> `RecoveryDTO` |
| [`src/main/kotlin/.../mapper/SleepMapper.kt`](../../src/main/kotlin/com/example/whoopdavidapi/mapper/SleepMapper.kt) | `WhoopSleep` <-> `SleepDTO` |
| [`src/main/kotlin/.../mapper/WorkoutMapper.kt`](../../src/main/kotlin/com/example/whoopdavidapi/mapper/WorkoutMapper.kt) | `WhoopWorkout` <-> `WorkoutDTO` |

### Where are they consumed?

Mappers are used in read services. Example in [`CycleService.kt`](../../src/main/kotlin/com/example/whoopdavidapi/service/CycleService.kt):

```kotlin
@Service
class CycleService(
    private val cycleRepository: CycleRepository,
    private val cycleMapper: CycleMapper             // <-- inyeccion del mapper
) {
    fun getCycles(...): PaginatedResponse<CycleDTO> {
        val result = cycleRepository.findByStartBetween(from, to, pageable)
        return PaginatedResponse(
            data = result.content.map { cycleMapper.toDto(it) },  // <-- entidad -> DTO
            pagination = PaginationInfo(...)
        )
    }
}
```

---

## 3. Why this decision?

### 3a. Why `data class` for DTOs?

In Kotlin, [`data class`](https://kotlinlang.org/docs/data-classes.html) is the ideal type for DTOs because the compiler automatically generates:

| Generated method | What is it for? |
|----------------|----------------|
| `equals()` | Compare two DTOs by content (not by reference) |
| `hashCode()` | Using DTOs as keys in `HashMap`/`HashSet` |
| `toString()` | Debug: `CycleDTO(id=12345, userId=67890, strain=15.5, ...)` |
| `copy()` | Create a copy by modifying only some fields |
| `componentN()` | Destructuring: `val (id, userId) = cycleDto` |

Additionally, all fields are `val` (immutable), which guarantees that a DTO does not change after being created.

**Reminder**: JPA entities use `class` normal with `var`, not `data class`. See [03-jpa-entities.md](./03-entidades-jpa.md) for the explanation.

### 3b. Why MapStruct and not manual mapping?

**Alternative 1: Manual mapping**

```kotlin
// Tendrias que escribir esto para CADA entidad:
fun WhoopCycle.toDto() = CycleDTO(
    id = this.id,
    userId = this.userId,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
    start = this.start,
    end = this.end,
    timezoneOffset = this.timezoneOffset,
    scoreState = this.scoreState,
    strain = this.strain,
    kilojoule = this.kilojoule,
    averageHeartRate = this.averageHeartRate,
    maxHeartRate = this.maxHeartRate
)
// Y tambien la funcion inversa toEntity()...
// Y repetir para Recovery (12 campos), Sleep (26 campos), Workout (24 campos)...
```

Problems with manual mapping:

- **Tedious**: `SleepDTO` has 26 fields. Writing the mapping by hand is 52+ lines of repetitive code.
- **Fragile**: If you add a field to the entity and forget to add it to the manual mapping, there is no compilation error. The field is simply silently lost.

**Alternative 2: MapStruct (what we use)**

```kotlin
@Mapper(componentModel = "spring")
interface CycleMapper {
    fun toDto(entity: WhoopCycle): CycleDTO
    fun toEntity(dto: CycleDTO): WhoopCycle
}
// Fin. MapStruct genera TODO el codigo de mapeo en compilacion.
```

Advantages of MapStruct:

- **Compile-time safety**: If the entity has a `strain` field but the DTO does not, MapStruct throws a **compile-time error** (not a runtime error).
- **Zero reflection**: The generated code is pure Kotlin/Java with getters and setters. It does not use reflection, so it is as fast as manual mapping.
- **Maintainable**: If you add a field, MapStruct warns you if the mapping is missing.

**Alternative 3: ModelMapper / Dozer (reflection-based libraries)**

These libraries map by convention (same field name), but they use reflection at runtime, are slower, and errors appear at runtime instead of at compile time. MapStruct is superior in every respect.

### 3c. Why `PaginatedResponse<T>` as a generic wrapper?

Instead of returning `Page<CycleDTO>` directly from Spring, we create our own wrapper:

```kotlin
data class PaginatedResponse<T>(
    val data: List<T>,
    val pagination: PaginationInfo
)
```

Reasons:

1. **JSON format control**: Spring’s `Page<T>` serializes with many internal fields (`pageable`, `sort`, `first`, `last`, `numberOfElements`...). Our wrapper is cleaner and more predictable.
2. **Consistency**: All API endpoints return the same format, regardless of the entity.
3. **Power BI**: A simple and consistent format is easier to consume in Power BI.

---

## 4. Explained code

### 4a. A complete DTO: `CycleDTO`

[`CycleDTO.kt`](../../src/main/kotlin/com/example/whoopdavidapi/model/dto/CycleDTO.kt):

```kotlin
package com.example.whoopdavidapi.model.dto

import java.time.Instant

data class CycleDTO(               // (1) data class: genera equals, hashCode, toString, copy
    val id: Long,                   // (2) val: inmutable una vez creado
    val userId: Long,
    val createdAt: Instant?,        // (3) Instant?: puede ser null
    val updatedAt: Instant?,
    val start: Instant,             // (4) Instant (no nullable): siempre tiene valor
    val end: Instant?,
    val timezoneOffset: String?,
    val scoreState: String,
    val strain: Float?,             // (5) Campos de score: null si PENDING_SCORE
    val kilojoule: Float?,
    val averageHeartRate: Int?,
    val maxHeartRate: Int?
)
```

| # | Concept | Explanation |
|---|---------|-------------|
| (1) | `data class` | The Kotlin compiler generates `equals()`, `hashCode()`, `toString()`, `copy()`, and `componentN()` functions based on ALL the fields of the primary constructor. |
| (2) | `val` | Immutable. Once the DTO is constructed, no field can change. This is safe to pass between layers. |
| (3) | `Instant?` | Nullable. `createdAt` may not exist if the data has not yet been fully processed by Whoop. |
| (4) | `Instant` (no nullable) | `start` always exists. A Whoop cycle always has a start time. |
| (5) | `Float?` | The scores are null when `scoreState = "PENDING_SCORE"`. In the resulting JSON, these fields appear as `null`. |

**Entity vs DTO comparison for Cycle:**

| Field | Entity (`class`) | DTO (`data class`) | Difference |
|-------|-------------------|-------------------|------------|
| `id` | `var id: Long = 0` | `val id: Long` | `var` -> `val`, default value removed |
| `start` | `var start: Instant = Instant.now()` | `val start: Instant` | Same pattern |
| All | Mutables (`var`) with defaults | Immutables (`val`) without defaults | DTOs are stricter |

### 4b. `PaginatedResponse<T>` and `PaginationInfo`

[`PaginatedResponse.kt`](../../src/main/kotlin/com/example/whoopdavidapi/model/dto/PaginatedResponse.kt):

```kotlin
data class PaginatedResponse<T>(     // (1) Generico: funciona con cualquier DTO
    val data: List<T>,                // (2) Los datos de esta pagina
    val pagination: PaginationInfo    // (3) Metadatos de paginacion
)

data class PaginationInfo(
    val page: Int,                    // (4) Pagina actual (base-1 para el usuario)
    val pageSize: Int,                // (5) Tamano de pagina solicitado
    val totalCount: Long,             // (6) Total de registros que coinciden
    val hasMore: Boolean              // (7) Hay mas paginas despues?
)
```

**`<T>` (generics)**: The `T` allows reusing the same class for any type of DTO:

```kotlin
PaginatedResponse<CycleDTO>       // Para /api/v1/cycles
PaginatedResponse<RecoveryDTO>    // Para /api/v1/recoveries
PaginatedResponse<SleepDTO>       // Para /api/v1/sleeps
PaginatedResponse<WorkoutDTO>     // Para /api/v1/workouts
```

**Example of the resulting JSON** when a client calls `GET /api/v1/cycles?page=1&pageSize=2`:

```json
{
  "data": [
    {
      "id": 12345,
      "userId": 67890,
      "start": "2024-01-15T08:00:00Z",
      "end": "2024-01-16T08:00:00Z",
      "scoreState": "SCORED",
      "strain": 15.5,
      "kilojoule": 2500.0,
      "averageHeartRate": 72,
      "maxHeartRate": 185
    },
    {
      "id": 12344,
      "userId": 67890,
      "start": "2024-01-14T08:00:00Z",
      "scoreState": "PENDING_SCORE",
      "strain": null,
      "kilojoule": null
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 2,
    "totalCount": 150,
    "hasMore": true
  }
}
```

### 4c. MapStruct: the mapper as an interface

[`CycleMapper.kt`](../../src/main/kotlin/com/example/whoopdavidapi/mapper/CycleMapper.kt):

```kotlin
package com.example.whoopdavidapi.mapper

import com.example.whoopdavidapi.model.dto.CycleDTO
import com.example.whoopdavidapi.model.entity.WhoopCycle
import org.mapstruct.Mapper

@Mapper(componentModel = "spring")   // (1) Genera un @Component de Spring
interface CycleMapper {               // (2) Solo una interfaz, sin implementacion
    fun toDto(entity: WhoopCycle): CycleDTO    // (3) Entidad -> DTO
    fun toEntity(dto: CycleDTO): WhoopCycle    // (4) DTO -> Entidad
}
```

| # | Concept | Explanation |
|---|---------|-------------|
| (1) | [`componentModel = "spring"`](https://mapstruct.org/documentation/stable/reference/html/#configuration-options) | The class generated by MapStruct will have `@Component`, which allows it to be injected with `@Autowired` or constructor injection in Spring. |
| (2) | `interface` | You only declare WHAT you want to map. MapStruct generates the implementation at compile time. |
| (3) | `toDto` | Convert a JPA entity (mutable, with `var`) into a DTO (immutable, with `val`). Map by naming convention: `entity.id` -> `dto.id`, `entity.strain` -> `dto.strain`, etc. |
| (4) | `toEntity` | Reverse conversion. It is used when you need to create an entity from API data (although in this project synchronization uses manual mapping from JSON). |

**What does MapStruct generate at compile time?** Something equivalent to this (simplified):

```kotlin
// Generado automaticamente en build/generated/source/kapt/main/
@Component
class CycleMapperImpl : CycleMapper {
    override fun toDto(entity: WhoopCycle): CycleDTO {
        return CycleDTO(
            id = entity.id,
            userId = entity.userId,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            start = entity.start,
            end = entity.end,
            timezoneOffset = entity.timezoneOffset,
            scoreState = entity.scoreState,
            strain = entity.strain,
            kilojoule = entity.kilojoule,
            averageHeartRate = entity.averageHeartRate,
            maxHeartRate = entity.maxHeartRate
        )
    }

    override fun toEntity(dto: CycleDTO): WhoopCycle {
        return WhoopCycle(
            id = dto.id,
            userId = dto.userId,
            createdAt = dto.createdAt,
            updatedAt = dto.updatedAt,
            start = dto.start,
            end = dto.end,
            timezoneOffset = dto.timezoneOffset,
            scoreState = dto.scoreState,
            strain = dto.strain,
            kilojoule = dto.kilojoule,
            averageHeartRate = dto.averageHeartRate,
            maxHeartRate = dto.maxHeartRate
        )
    }
}
```

**Convention-based mapping**: MapStruct maps fields automatically when the **name and type** match. Since `WhoopCycle.id` (Long) and `CycleDTO.id` (Long) have the same name and type, you don’t need additional annotations. This works for the 12 fields of CycleDTO without writing a single line of configuration.

The other mappers follow exactly the same pattern:

```kotlin
// RecoveryMapper.kt
@Mapper(componentModel = "spring")
interface RecoveryMapper {
    fun toDto(entity: WhoopRecovery): RecoveryDTO   // 12 campos mapeados automaticamente
    fun toEntity(dto: RecoveryDTO): WhoopRecovery
}

// SleepMapper.kt
@Mapper(componentModel = "spring")
interface SleepMapper {
    fun toDto(entity: WhoopSleep): SleepDTO         // 26 campos mapeados automaticamente
    fun toEntity(dto: SleepDTO): WhoopSleep
}

// WorkoutMapper.kt
@Mapper(componentModel = "spring")
interface WorkoutMapper {
    fun toDto(entity: WhoopWorkout): WorkoutDTO     // 24 campos mapeados automaticamente
    fun toEntity(dto: WorkoutDTO): WhoopWorkout
}
```

### 4d. kapt: how MapStruct works with Kotlin

**kapt (Kotlin Annotation Processing Tool)** is the bridge between the Java annotation processor (which MapStruct uses) and the Kotlin compiler. When you compile the project:

1. **[kapt](https://kotlinlang.org/docs/kapt.html)** analyzes your Kotlin interfaces annotated with [`@Mapper`](https://mapstruct.org/documentation/stable/reference/html/#defining-mapper)
2. Generate Java stubs from them (so that the Java annotation processor can understand them)
3. **MapStruct** processes those stubs and generates Java implementations
4. The Kotlin compiler compiles everything together

The configuration is in [`build.gradle.kts`](../../build.gradle.kts):

```kotlin
plugins {
    kotlin("kapt") version "2.2.21"       // (1) Habilita kapt en el proyecto
}

dependencies {
    implementation("org.mapstruct:mapstruct:1.6.3")          // (2) API de MapStruct (anotaciones)
    kapt("org.mapstruct:mapstruct-processor:1.6.3")          // (3) Procesador que genera codigo
}

kapt {
    correctErrorTypes = true               // (4) Mejor manejo de errores de tipo
    includeCompileClasspath = false        // (5) No incluir classpath de compilacion (best practice)
    arguments {
        arg("mapstruct.defaultComponentModel", "spring")  // (6) Todas las implementaciones seran @Component
    }
}
```

| # | Concept | Explanation |
|---|---------|-------------|
| (1) | `kotlin("kapt")` | Gradle plugin that enables annotation processing for Kotlin. Without this, MapStruct cannot generate code. |
| (2) | `implementation("org.mapstruct:mapstruct:1.6.3")` | The annotations you use in your code (`@Mapper`). They are included in the compilation and runtime classpath. |
| (3) | `kapt("org.mapstruct:mapstruct-processor:1.6.3")` | The processor that reads the annotations and generates the implementation classes. It only runs at compile time; it is not included in the final JAR. |
| (4) | `correctErrorTypes = true` | When kapt encounters types that it cannot fully resolve, it tries to correct them instead of failing. Useful for mixed Kotlin/Java projects. |
| (5) | `includeCompileClasspath = false` | Recommended optimization: prevent kapt from processing annotations that don’t apply to it. |
| (6) | `mapstruct.defaultComponentModel = "spring"` | Global configuration: all implementations generated by MapStruct will have `@Component`, integrating with Spring without needing to specify `componentModel = "spring"` in each `@Mapper`. (We specify it on both sides for explicit clarity.) |

### 4e. Spring Boot Gotcha 4: disable kapt for test sources

In [`build.gradle.kts`](../../build.gradle.kts) there is a critical configuration:

```kotlin
// Desactivar kapt para test sources (no hay annotation processors en tests)
tasks.matching { it.name == "kaptTestKotlin" || it.name == "kaptGenerateStubsTestKotlin" }.configureEach {
    enabled = false
}
```

**Why is it necessary?** In Spring Boot 4, the test annotations (`@WebMvcTest`, `@DataJpaTest`) changed packages. For example:

```kotlin
// Spring Boot 3:
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

// Spring Boot 4:
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
```

kapt tries to generate stubs for the test classes, and when it encounters these Spring Boot 4 annotations, it cannot resolve them correctly because the new packages are not on kapt’s classpath. This causes compilation errors.

The solution is simple: since we don’t have annotation processors in tests (there’s no `@Mapper` or anything that needs kapt in `src/test/`), we simply disable kapt for the test sources.

### 4f. Complete flow: from the HTTP request to the JSON response

To consolidate all the concepts, let’s look at the complete flow when Power BI calls `GET /api/v1/cycles?from=2024-01-01T00:00:00Z&page=1&pageSize=100`:

```
1. CycleController.getCycles(from, to, page, pageSize)
   |
   v
2. CycleService.getCycles(from, to, page, pageSize)
   |
   |  Crea: PageRequest.of(0, 100, Sort.by(DESC, "start"))
   |
   v
3. CycleRepository.findByStartGreaterThanEqual(from, pageable)
   |
   |  Spring genera SQL:
   |  SELECT * FROM whoop_cycles WHERE start_time >= '2024-01-01T00:00:00Z'
   |  ORDER BY start_time DESC LIMIT 100 OFFSET 0
   |
   |  Hibernate ejecuta SQL contra PostgreSQL
   |
   v
4. Page<WhoopCycle>  (entidades JPA, clases mutables con var)
   |
   v
5. result.content.map { cycleMapper.toDto(it) }
   |
   |  MapStruct (CycleMapperImpl) copia campo por campo:
   |  entity.id -> dto.id
   |  entity.strain -> dto.strain
   |  ... (12 campos)
   |
   v
6. List<CycleDTO>  (data classes inmutables con val)
   |
   v
7. PaginatedResponse(data = ..., pagination = PaginationInfo(...))
   |
   v
8. Jackson serializa a JSON
   |
   v
9. HTTP 200 con el JSON de respuesta
```

---

## 5. Official documentation

- [MapStruct Reference Guide](https://mapstruct.org/documentation/stable/reference/html/)
- [MapStruct with Kotlin (kapt)](https://mapstruct.org/documentation/installation/#kotlin)
- [Kotlin data classes](https://kotlinlang.org/docs/data-classes.html)
- [Kotlin kapt compiler plugin](https://kotlinlang.org/docs/kapt.html)
- [Spring Data JPA - Projections (DTOs)](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)
- [Jackson Kotlin Module](https://github.com/FasterXML/jackson-module-kotlin)

---

> **Previous**: [04 - Repositories](./04-repositorios.md)
