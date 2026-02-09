# 01 - Architecture and BFF Pattern

## What is it?

The **architecture** of an application defines how its components are organized and how data flows between them. In this project we use two key concepts:

1. **Pattern [BFF (Backend For Frontend)](https://learn.microsoft.com/en-us/azure/architecture/patterns/backends-for-frontends)**: An intermediate server that adapts an external API to the format needed by a specific client
2. **Layered architecture**: Separation of responsibilities in Controller, Service, Repository, and Database

### What is the BFF pattern?

BFF stands for **Backend For Frontend**. It is a pattern where you create a backend server dedicated to a specific frontend. In our case:

- **Frontend**: Power BI (the visualization dashboard)
- **External backend**: Whoop API v2 (the official API of the Whoop wristband)
- **BFF**: WhoopDavidAPI (this project)

Without a BFF, Power BI would have to:

- Authenticate directly against the Whoop API (complex OAuth2)
- Handle Whoop pagination (next-page tokens)
- Rely on Whoop being online at each dashboard refresh
- Transform nested JSON formats into the required tabular format

With the BFF, Power BI simply makes HTTP Basic Auth requests to our API, which returns data already stored, paginated, and in a flat format.

---

## Where is it used in this project?

### Entry point

**File**: [`src/main/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplication.kt`](../../src/main/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplication.kt)

```kotlin
@SpringBootApplication
@EnableScheduling
class WhoopDavidApiApplication

fun main(args: Array<String>) {
    runApplication<WhoopDavidApiApplication>(*args)
}
```

### Key components by layer

| Layer | Files | Responsibility |
|---|---|---|
| **Controller** | [`controller/CycleController.kt`](../../src/main/kotlin/com/example/whoopdavidapi/controller/CycleController.kt), etc. | Receive HTTP requests, validate parameters, return responses |
| **Service** | [`service/CycleService.kt`](../../src/main/kotlin/com/example/whoopdavidapi/service/CycleService.kt), etc. | Business logic, data transformation |
| **Repository** | [`repository/CycleRepository.kt`](../../src/main/kotlin/com/example/whoopdavidapi/repository/CycleRepository.kt), etc. | Database access ([JPA](https://docs.spring.io/spring-data/jpa/reference/) queries) |
| **Entity** | [`model/entity/WhoopCycle.kt`](../../src/main/kotlin/com/example/whoopdavidapi/model/entity/WhoopCycle.kt), etc. | Database table representation |
| **DTO** | [`model/dto/CycleDTO.kt`](../../src/main/kotlin/com/example/whoopdavidapi/model/dto/CycleDTO.kt), etc. | Transfer objects for API responses |
| **Mapper** | [`mapper/CycleMapper.kt`](../../src/main/kotlin/com/example/whoopdavidapi/mapper/CycleMapper.kt), etc. | Automatic Entity <-> DTO conversion |
| **Client** | [`client/WhoopApiClient.kt`](../../src/main/kotlin/com/example/whoopdavidapi/client/WhoopApiClient.kt) | HTTP Client for External Whoop API |
| **Sync** | [`service/WhoopSyncService.kt`](../../src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt) | Whoop data synchronization -> local database |
| **Scheduler** | [`scheduler/WhoopDataSyncScheduler.kt`](../../src/main/kotlin/com/example/whoopdavidapi/scheduler/WhoopDataSyncScheduler.kt) | Periodic triggering of synchronization |
| **Security** | [`config/SecurityConfig.kt`](../../src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt) | Authentication and authorization |
| **Exception** | [`exception/GlobalExceptionHandler.kt`](../../src/main/kotlin/com/example/whoopdavidapi/exception/GlobalExceptionHandler.kt) | Centralized error handling |

---

## Data flow diagram

```
                    SINCRONIZACION (cada 30 min via @Scheduled)
                    ==========================================

┌──────────────────┐     ┌──────────────────┐     ┌────────────────────┐     ┌─────────────┐
│   Whoop API v2   │────>│  WhoopApiClient  │────>│  WhoopSyncService  │────>│ PostgreSQL  │
│ (API externa)    │     │  (RestClient +   │     │  (mapea JSON a     │     │ (almacen    │
│                  │     │   Resilience4j)  │     │   entidades JPA)   │     │  local)     │
└──────────────────┘     └──────────────────┘     └────────────────────┘     └─────────────┘
       ^                        ^                                                  │
       │                        │                                                  │
  OAuth2 Bearer           Token auto-refresh                                       │
  token                   via WhoopTokenManager                                    │
                                                                                   │
                    CONSULTA (Power BI via HTTP Basic Auth)                         │
                    ==========================================                     │
                                                                                   v
┌──────────────────┐     ┌──────────────────┐     ┌────────────────────┐     ┌─────────────┐
│    Power BI      │<────│   Controllers    │<────│     Services       │<────│ Repositories│
│   (dashboard)    │     │  (REST /api/v1)  │     │  (paginacion,      │     │ (JPA queries│
│                  │     │                  │     │   filtrado)        │     │  a la BD)   │
└──────────────────┘     └──────────────────┘     └────────────────────┘     └─────────────┘
                              │
                         Basic Auth
                         (usuario: powerbi)
```

### Synchronization flow (write)

1. `WhoopDataSyncScheduler` triggers `syncAll()` every 30 minutes (configurable via cron)
2. `WhoopSyncService` requests the data from `WhoopApiClient` with incremental synchronization (only new data since the last `updatedAt`)
3. `WhoopApiClient` uses [`RestClient`](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html) to call the Whoop API v2 with a Bearer OAuth2 token
4. `WhoopTokenManager` ensures that the token is valid; if it expires in less than 5 minutes, it refreshes it automatically
5. `WhoopSyncService` maps the JSON responses to JPA entities and saves them in the DB

### Query (read) flow

1. Power BI does `GET /api/v1/cycles?page=1&pageSize=100` with HTTP Basic Auth
2. `SecurityConfig` validates the credentials (user `powerbi`)
3. `CycleController` validates the parameters and delegates to `CycleService`
4. `CycleService` query `CycleRepository` with pagination and filters
5. The result is converted from Entity to DTO via `CycleMapper` (MapStruct)
6. A `PaginatedResponse<CycleDTO>` is returned as JSON

---

## Why this decision?

### Why WebMVC and not WebFlux?

Spring offers two web programming models:

| Feature | [WebMVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html) (selected) | [WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux.html) |
|---|---|---|
| Model | Blocking (one thread per request) | Non-blocking (reactive) |
| JPA/Hibernate | Directly compatible | Requires R2DBC (different ORM) |
| `@Scheduled` | Works natively | Requires adaptations |
| Complexity | Simple | More complex (Mono/Flux) |
| Reactive benefit | Not necessary (1 user) | Thousands of concurrent connections |

**Decisions that forced WebMVC:**

1. **JPA is blocking**: Spring Data JPA (Hibernate) uses JDBC, which is blocking by nature. Using WebFlux with JPA would negate the reactive advantages
2. **`@Scheduled` is blocking**: Periodic synchronization uses blocking threads
3. **A single user**: There is no benefit to handling thousands of concurrent connections when only Power BI makes requests

### Why incremental synchronization?

In [`service/WhoopSyncService.kt`](../../src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt), each `sync*()` method looks for the last `updatedAt` in the DB:

```kotlin
private fun syncCycles() {
    try {
        // Sincronizacion incremental: obtener solo datos nuevos
        val lastUpdated = cycleRepository.findTopByOrderByUpdatedAtDesc()?.updatedAt
        val records = whoopApiClient.getAllCycles(start = lastUpdated)
        // ...
    }
}
```

This avoids downloading all the data on each synchronization. On the first run, `lastUpdated` is `null` and all historical data is downloaded. On subsequent runs, only the updated data after the last synchronization is requested.

### Why separate Entity and DTO?

The **entities** (`WhoopCycle`) represent the database structure. The **DTOs** (`CycleDTO`) represent what the API returns to the client. Separating them allows:

- Change the structure of the DB without affecting the API
- Hide internal fields (such as synchronization timestamps)
- Return different formats to different clients in the future

---

## Explained code

### Entry point: `WhoopDavidApiApplication.kt`

**File**: [`src/main/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplication.kt`](../../src/main/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplication.kt)

```kotlin
package com.example.whoopdavidapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication  // (1)
@EnableScheduling        // (2)
class WhoopDavidApiApplication  // (3)

fun main(args: Array<String>) {   // (4)
    runApplication<WhoopDavidApiApplication>(*args)  // (5)
}
```

1. **[`@SpringBootApplication`](https://docs.spring.io/spring-boot/reference/using/using-the-springbootapplication-annotation.html)**: Meta-annotation that combines three annotations:
   - `@Configuration`: This class can declare beans (`@Bean`)
   - `@EnableAutoConfiguration`: Spring Boot automatically configures beans based on the classpath dependencies (if it detects JPA, it configures Hibernate; if it detects H2, it configures an in-memory DataSource, etc.)
   - `@ComponentScan`: Scans all packages under `com.example.whoopdavidapi` looking for classes annotated with `@Component`, `@Service`, `@Controller`, `@Repository`, `@Configuration`

2. **[`@EnableScheduling`](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)**: Enables support for `@Scheduled`. Without this annotation, `WhoopDataSyncScheduler` would not execute its cron task

3. **The class is empty**: In Kotlin, the class only serves as an anchor for the annotations. Spring Boot doesn’t need it to have methods

4. **`fun main`**: Top-level function. In Kotlin it does not need to be inside a class

5. **`runApplication<WhoopDavidApiApplication>(*args)`**: Kotlin extension function equivalent to `SpringApplication.run(WhoopDavidApiApplication::class.java, *args)`. `*args` is Kotlin’s spread operator that converts a `Array` into varargs

### Multi-chain security architecture

**File**: [`src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt`](../../src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt)

Spring Security allows you to define multiple [`SecurityFilterChain`](https://docs.spring.io/spring-security/reference/servlet/architecture.html), each with its own URL pattern and rules. The `@Order` determines the priority:

```kotlin
@Bean @Order(1)  // API: Basic Auth, stateless
fun apiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
    http.securityMatcher("/api/**")
        .httpBasic { }
    // ...
}

@Bean @Order(2)  // OAuth2: flujo de autorizacion con Whoop
fun oauth2SecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
    http.securityMatcher("/login/**", "/oauth2/**")
        .oauth2Login { }
    // ...
}

@Bean @Order(3)  // Publico: actuator, H2 console, Swagger, mock
fun publicSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
    http.securityMatcher("/actuator/**", "/h2-console/**", "/mock/**",
                         "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
        .authorizeHttpRequests { it.anyRequest().permitAll() }
    // ...
}

@Bean @Order(4)  // Catch-all: denegar todo lo demas
fun defaultSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
    http.authorizeHttpRequests { it.anyRequest().denyAll() }
    // ...
}
```

When a request arrives, Spring evaluates the chains in `@Order` order. The first one whose `securityMatcher` matches the URL is the one that is applied. If none match, the catch-all chain (`@Order(4)`) denies access.

### Synchronization flow

**File**: [`src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt`](../../src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt)

```kotlin
@Service
class WhoopSyncService(
    private val whoopApiClient: WhoopApiClient,
    private val cycleRepository: CycleRepository,
    private val recoveryRepository: RecoveryRepository,
    private val sleepRepository: SleepRepository,
    private val workoutRepository: WorkoutRepository
) {
    fun syncAll() {
        log.info("Iniciando sincronizacion completa con Whoop API...")
        val start = System.currentTimeMillis()
        syncCycles()       // 1. Sincronizar ciclos
        syncRecoveries()   // 2. Sincronizar recuperaciones
        syncSleeps()       // 3. Sincronizar sueno
        syncWorkouts()     // 4. Sincronizar entrenamientos
        val elapsed = System.currentTimeMillis() - start
        log.info("Sincronizacion completa en {} ms", elapsed)
    }
}
```

The service injects the `WhoopApiClient` (to talk to Whoop) and the 4 repositories (to save to the DB). Synchronization is sequential because each data type is independent, but we share the same connection to Whoop.

### OAuth2 token flow

**File**: [`src/main/kotlin/com/example/whoopdavidapi/client/WhoopTokenManager.kt`](../../src/main/kotlin/com/example/whoopdavidapi/client/WhoopTokenManager.kt)

```kotlin
override fun getValidAccessToken(): String {
    val token = tokenRepository.findTopByOrderByUpdatedAtDesc()
        ?: throw WhoopApiException("No hay token OAuth2 guardado.")

    // Si el token expira en menos de 5 minutos, refrescarlo
    if (token.expiresAt.isBefore(Instant.now().plusSeconds(300))) {
        log.info("Access token expira pronto, refrescando...")
        return refreshToken(token)
    }

    return token.accessToken
        ?: throw WhoopApiException("Token OAuth2 guardado pero access_token es null.")
}
```

Whoop OAuth2 tokens are stored in the `oauth_tokens` table (encrypted with AES-256-GCM). Every time `WhoopApiClient` needs to make a request, it calls `getValidAccessToken()` which:

1. Find the most recent token in the database
2. If it expires in less than 5 minutes, it refreshes it automatically using `refresh_token`
3. Save the new token in the DB
4. Return the valid access token

Note the `TokenManager` interface in [`client/TokenManager.kt`](../../src/main/kotlin/com/example/whoopdavidapi/client/TokenManager.kt) that allows replacing the implementation:

```kotlin
interface TokenManager {
    fun getValidAccessToken(): String
}
```

In profile `demo`, `DemoWhoopTokenManager` replaces `WhoopTokenManager` by returning a fake token. This is possible thanks to [`@Profile("demo")`](https://docs.spring.io/spring-boot/reference/features/profiles.html) and `@Primary` (see [document 11 - Profiles](11-profiles.md)).

---

## Layer summary

```
┌─────────────────────────────────────────────────────┐
│                   CONTROLLER                        │
│  Recibe HTTP, valida params, devuelve ResponseEntity│
│  Ejemplo: CycleController.kt                       │
├─────────────────────────────────────────────────────┤
│                    SERVICE                          │
│  Logica de negocio, paginacion, transformacion      │
│  Ejemplo: CycleService.kt, WhoopSyncService.kt     │
├─────────────────────────────────────────────────────┤
│                   REPOSITORY                        │
│  Acceso a BD, derived queries, paginacion JPA       │
│  Ejemplo: CycleRepository.kt                       │
├─────────────────────────────────────────────────────┤
│                    DATABASE                         │
│  H2 (dev) / PostgreSQL (prod)                       │
│  Tablas: whoop_cycles, whoop_recoveries, etc.       │
└─────────────────────────────────────────────────────┘
```

**Fundamental rule**: Each layer only knows the layer immediately below. The Controller does not access the Repository directly; it always goes through the Service. This allows:

- Test each layer in isolation (mocks)
- Change the implementation of a layer without affecting the others
- Reuse business logic in different contexts (REST, scheduled, etc.)

---

## Official documentation

- [Spring Boot Application](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)
- [Spring Web MVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html)
- [Spring WebFlux (for comparison)](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [BFF Pattern (Sam Newman)](https://samnewman.io/patterns/architectural/bff/)
- [Spring Security Architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
- [EnableScheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
