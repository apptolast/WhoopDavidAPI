# 05 - DTOs y MapStruct

> Como separar lo que expones en el API de lo que almacenas en la BD, y como MapStruct genera el codigo de conversion automaticamente en tiempo de compilacion.

---

## 1. Que son los DTOs?

**DTO (Data Transfer Object)** es un objeto cuyo unico proposito es transportar datos entre capas de la aplicacion. En nuestro caso, los DTOs son lo que el API REST devuelve al cliente (Power BI u otro consumidor).

```
Base de datos  -->  Entidad JPA  -->  Mapper  -->  DTO  -->  JSON  -->  Cliente
                    (WhoopCycle)      (toDto)     (CycleDTO)           (Power BI)
```

**Por que no devolver la entidad directamente?**

1. **Seguridad**: La entidad `OAuthTokenEntity` tiene campos `accessToken` y `refreshToken`. Si la devuelves como JSON, expones tokens OAuth2. Con un DTO, solo expones los campos que decides.
2. **Desacoplamiento**: Si cambias la estructura de la BD (renombrar columna, anadir campo interno), no rompes el contrato del API. El DTO sigue igual.
3. **Formato del API vs formato de la BD**: La BD puede tener campos internos (`scoreState = "PENDING_SCORE"`) que quieres representar de forma diferente en el API, o campos que no necesitas exponer.
4. **Inmutabilidad**: Las entidades JPA usan `var` (mutable, requerido por Hibernate). Los DTOs usan `val` (inmutable), que es mas seguro para transportar datos.

---

## 2. Donde se usa en este proyecto?

### DTOs

| Archivo | Entidad origen | Campos |
|---------|---------------|--------|
| [`src/main/kotlin/.../model/dto/CycleDTO.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/dto/CycleDTO.kt) | `WhoopCycle` | 12 campos |
| [`src/main/kotlin/.../model/dto/RecoveryDTO.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/dto/RecoveryDTO.kt) | `WhoopRecovery` | 12 campos |
| [`src/main/kotlin/.../model/dto/SleepDTO.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/dto/SleepDTO.kt) | `WhoopSleep` | 26 campos |
| [`src/main/kotlin/.../model/dto/WorkoutDTO.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/dto/WorkoutDTO.kt) | `WhoopWorkout` | 24 campos |
| [`src/main/kotlin/.../model/dto/PaginatedResponse.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/dto/PaginatedResponse.kt) | (generico) | Wrapper de paginacion |

### Mappers (MapStruct)

| Archivo | Convierte |
|---------|-----------|
| [`src/main/kotlin/.../mapper/CycleMapper.kt`](../src/main/kotlin/com/example/whoopdavidapi/mapper/CycleMapper.kt) | `WhoopCycle` <-> `CycleDTO` |
| [`src/main/kotlin/.../mapper/RecoveryMapper.kt`](../src/main/kotlin/com/example/whoopdavidapi/mapper/RecoveryMapper.kt) | `WhoopRecovery` <-> `RecoveryDTO` |
| [`src/main/kotlin/.../mapper/SleepMapper.kt`](../src/main/kotlin/com/example/whoopdavidapi/mapper/SleepMapper.kt) | `WhoopSleep` <-> `SleepDTO` |
| [`src/main/kotlin/.../mapper/WorkoutMapper.kt`](../src/main/kotlin/com/example/whoopdavidapi/mapper/WorkoutMapper.kt) | `WhoopWorkout` <-> `WorkoutDTO` |

### Donde se consumen

Los mappers se usan en los servicios de lectura. Ejemplo en [`CycleService.kt`](../src/main/kotlin/com/example/whoopdavidapi/service/CycleService.kt):

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

## 3. Por que esta decision?

### 3a. Por que `data class` para DTOs?

En Kotlin, `data class` es el tipo ideal para DTOs porque el compilador genera automaticamente:

| Metodo generado | Para que sirve |
|----------------|----------------|
| `equals()` | Comparar dos DTOs por contenido (no por referencia) |
| `hashCode()` | Usar DTOs como claves en `HashMap`/`HashSet` |
| `toString()` | Debug: `CycleDTO(id=12345, userId=67890, strain=15.5, ...)` |
| `copy()` | Crear una copia modificando solo algunos campos |
| `componentN()` | Destructuring: `val (id, userId) = cycleDto` |

Ademas, todos los campos son `val` (inmutables), lo que garantiza que un DTO no cambie despues de crearse.

**Recordatorio**: Las entidades JPA usan `class` normal con `var`, no `data class`. Ver [03-entidades-jpa.md](./03-entidades-jpa.md) para la explicacion.

### 3b. Por que MapStruct y no mapeo manual?

**Alternativa 1: Mapeo manual**

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

Problemas del mapeo manual:
- **Tedioso**: `SleepDTO` tiene 26 campos. Escribir el mapeo a mano son 52+ lineas de codigo repetitivo.
- **Fragil**: Si anades un campo a la entidad y olvidas anadirlo al mapeo manual, no hay error de compilacion. El campo simplemente se pierde silenciosamente.

**Alternativa 2: MapStruct (lo que usamos)**

```kotlin
@Mapper(componentModel = "spring")
interface CycleMapper {
    fun toDto(entity: WhoopCycle): CycleDTO
    fun toEntity(dto: CycleDTO): WhoopCycle
}
// Fin. MapStruct genera TODO el codigo de mapeo en compilacion.
```

Ventajas de MapStruct:
- **Seguridad en compilacion**: Si la entidad tiene un campo `strain` pero el DTO no, MapStruct lanza un **error de compilacion** (no un error en runtime).
- **Cero reflexion**: El codigo generado es Kotlin/Java puro con getters y setters. No usa reflexion, asi que es tan rapido como el mapeo manual.
- **Mantenible**: Si anades un campo, MapStruct te avisa si falta el mapeo.

**Alternativa 3: ModelMapper / Dozer (librerias basadas en reflexion)**

Estas librerias mapean por convencion (nombre de campo igual), pero usan reflexion en runtime, son mas lentas, y los errores aparecen en runtime en vez de en compilacion. MapStruct es superior en todos los aspectos.

### 3c. Por que `PaginatedResponse<T>` como wrapper generico?

En vez de devolver `Page<CycleDTO>` directamente de Spring, creamos nuestro propio wrapper:

```kotlin
data class PaginatedResponse<T>(
    val data: List<T>,
    val pagination: PaginationInfo
)
```

Razones:
1. **Control del formato JSON**: `Page<T>` de Spring serializa con muchos campos internos (`pageable`, `sort`, `first`, `last`, `numberOfElements`...). Nuestro wrapper es mas limpio y predecible.
2. **Consistencia**: Todos los endpoints de la API devuelven el mismo formato, independientemente de la entidad.
3. **Power BI**: Un formato simple y consistente es mas facil de consumir en Power BI.

---

## 4. Codigo explicado

### 4a. Un DTO completo: `CycleDTO`

[`CycleDTO.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/dto/CycleDTO.kt):

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

| # | Concepto | Explicacion |
|---|---------|-------------|
| (1) | `data class` | El compilador Kotlin genera `equals()`, `hashCode()`, `toString()`, `copy()` y funciones `componentN()` basados en TODOS los campos del constructor primario. |
| (2) | `val` | Inmutable. Una vez construido el DTO, ningun campo puede cambiar. Esto es seguro para pasar entre capas. |
| (3) | `Instant?` | Nullable. `createdAt` puede no existir si el dato aun no fue procesado completamente por Whoop. |
| (4) | `Instant` (no nullable) | `start` siempre existe. Un ciclo Whoop siempre tiene hora de inicio. |
| (5) | `Float?` | Los scores son null cuando `scoreState = "PENDING_SCORE"`. En el JSON resultante, estos campos aparecen como `null`. |

**Comparacion entidad vs DTO para Cycle:**

| Campo | Entidad (`class`) | DTO (`data class`) | Diferencia |
|-------|-------------------|-------------------|------------|
| `id` | `var id: Long = 0` | `val id: Long` | `var` -> `val`, valor por defecto eliminado |
| `start` | `var start: Instant = Instant.now()` | `val start: Instant` | Mismo patron |
| Todos | Mutables (`var`) con defaults | Inmutables (`val`) sin defaults | DTOs son mas estrictos |

### 4b. `PaginatedResponse<T>` y `PaginationInfo`

[`PaginatedResponse.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/dto/PaginatedResponse.kt):

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

**`<T>` (generics)**: La `T` permite reutilizar la misma clase para cualquier tipo de DTO:

```kotlin
PaginatedResponse<CycleDTO>       // Para /api/v1/cycles
PaginatedResponse<RecoveryDTO>    // Para /api/v1/recoveries
PaginatedResponse<SleepDTO>       // Para /api/v1/sleeps
PaginatedResponse<WorkoutDTO>     // Para /api/v1/workouts
```

**Ejemplo de JSON resultante** cuando un cliente llama a `GET /api/v1/cycles?page=1&pageSize=2`:

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

### 4c. MapStruct: el mapper como interfaz

[`CycleMapper.kt`](../src/main/kotlin/com/example/whoopdavidapi/mapper/CycleMapper.kt):

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

| # | Concepto | Explicacion |
|---|---------|-------------|
| (1) | `componentModel = "spring"` | La clase generada por MapStruct tendra `@Component`, lo que permite inyectarla con `@Autowired` o inyeccion por constructor en Spring. |
| (2) | `interface` | Tu solo declaras QUE quieres mapear. MapStruct genera la implementacion en tiempo de compilacion. |
| (3) | `toDto` | Convierte una entidad JPA (mutable, con `var`) a un DTO (inmutable, con `val`). Mapea por convencion de nombres: `entity.id` -> `dto.id`, `entity.strain` -> `dto.strain`, etc. |
| (4) | `toEntity` | Conversion inversa. Se usa cuando necesitas crear una entidad a partir de datos del API (aunque en este proyecto la sincronizacion usa mapeo manual desde JSON). |

**Que genera MapStruct en compilacion?** Algo equivalente a esto (simplificado):

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

**Mapeo por convencion**: MapStruct mapea campos automaticamente cuando el **nombre y tipo** coinciden. Como `WhoopCycle.id` (Long) y `CycleDTO.id` (Long) tienen el mismo nombre y tipo, no necesitas anotaciones adicionales. Esto funciona para los 12 campos de CycleDTO sin escribir una sola linea de configuracion.

Los otros mappers siguen exactamente el mismo patron:

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

### 4d. kapt: como MapStruct funciona con Kotlin

**kapt (Kotlin Annotation Processing Tool)** es el puente entre el procesador de anotaciones de Java (que MapStruct usa) y el compilador de Kotlin. Cuando compilas el proyecto:

1. **kapt** analiza tus interfaces Kotlin anotadas con `@Mapper`
2. Genera stubs Java a partir de ellas (para que el procesador de anotaciones de Java las entienda)
3. **MapStruct** procesa esos stubs y genera implementaciones Java
4. El compilador de Kotlin compila todo junto

La configuracion esta en [`build.gradle.kts`](../build.gradle.kts):

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

| # | Concepto | Explicacion |
|---|---------|-------------|
| (1) | `kotlin("kapt")` | Plugin de Gradle que habilita el procesamiento de anotaciones para Kotlin. Sin esto, MapStruct no puede generar codigo. |
| (2) | `implementation("org.mapstruct:mapstruct:1.6.3")` | Las anotaciones que usas en tu codigo (`@Mapper`). Se incluyen en el classpath de compilacion y runtime. |
| (3) | `kapt("org.mapstruct:mapstruct-processor:1.6.3")` | El procesador que lee las anotaciones y genera las clases de implementacion. Solo se ejecuta en compilacion, no se incluye en el JAR final. |
| (4) | `correctErrorTypes = true` | Cuando kapt encuentra tipos que no puede resolver completamente, intenta corregirlos en vez de fallar. Util para proyectos mixtos Kotlin/Java. |
| (5) | `includeCompileClasspath = false` | Optimizacion recomendada: evita que kapt procese anotaciones que no le corresponden. |
| (6) | `mapstruct.defaultComponentModel = "spring"` | Configuracion global: todas las implementaciones generadas por MapStruct tendran `@Component`, integrando con Spring sin necesidad de especificar `componentModel = "spring"` en cada `@Mapper`. (Lo especificamos en ambos lados por claridad explicita.) |

### 4e. Gotcha de Spring Boot 4: desactivar kapt para test sources

En [`build.gradle.kts`](../build.gradle.kts) hay una configuracion critica:

```kotlin
// Desactivar kapt para test sources (no hay annotation processors en tests)
tasks.matching { it.name == "kaptTestKotlin" || it.name == "kaptGenerateStubsTestKotlin" }.configureEach {
    enabled = false
}
```

**Por que es necesario?** En Spring Boot 4, las anotaciones de test (`@WebMvcTest`, `@DataJpaTest`) cambiaron de paquete. Por ejemplo:

```kotlin
// Spring Boot 3:
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

// Spring Boot 4:
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
```

kapt intenta generar stubs para las clases de test, y cuando encuentra estas anotaciones de Spring Boot 4, no puede resolverlas correctamente porque los nuevos paquetes no estan en el classpath de kapt. Esto causa errores de compilacion.

La solucion es simple: como no tenemos procesadores de anotaciones en tests (no hay `@Mapper` ni nada que necesite kapt en `src/test/`), simplemente desactivamos kapt para los sources de test.

### 4f. Flujo completo: del HTTP request al JSON response

Para consolidar todos los conceptos, veamos el flujo completo cuando Power BI llama `GET /api/v1/cycles?from=2024-01-01T00:00:00Z&page=1&pageSize=100`:

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

## 5. Documentacion oficial

- [MapStruct Reference Guide](https://mapstruct.org/documentation/stable/reference/html/)
- [MapStruct with Kotlin (kapt)](https://mapstruct.org/documentation/installation/#kotlin)
- [Kotlin data classes](https://kotlinlang.org/docs/data-classes.html)
- [Kotlin kapt compiler plugin](https://kotlinlang.org/docs/kapt.html)
- [Spring Data JPA - Projections (DTOs)](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)
- [Jackson Kotlin Module](https://github.com/FasterXML/jackson-module-kotlin)

---

> **Anterior**: [04 - Repositorios](./04-repositorios.md)
