# 11 - Spring Profiles

## Table of contents

- [What are Spring profiles?](#what-are-spring-profiles)
- [How are profiles activated](#how-do-you-activate-profiles)
- [Configuration loading: base + profile](#configuration-loading-base--profile)
- [Profile annotations in beans](#profile-annotations-in-beans)
- [The 3 project profiles](#the-3-project-profiles)
  - [Dev profile (development)](#dev-profile-development)
  - [Prod profile (production)](#prod-profile-production)
  - [Demo profile (testing without real API keys)](#demo-profile-testing-without-real-api-keys)
- [Beans exclusive to the demo profile](#exclusive-beans-from-the-demo-profile)
  - [MockWhoopApiController](#mockwhoopapicontroller)
  - [MockWhoopDataGenerator](#mockwhoopdatagenerator)
  - [DemoTokenSeeder and CommandLineRunner](#demotokenseeder-and-commandlinerunner)
  - [DemoWhoopTokenManager and @Primary](#demowhooptokenmanager-and-primary)
- [Summary table of differences between profiles](#summary-table-of-differences-between-profiles)
- [Official documentation](#official-documentation)

---

## What are Spring profiles?

Spring [profiles](https://docs.spring.io/spring-boot/reference/features/profiles.html) allow you to have **different configurations** for different environments (development, production, testing) within the same application. Instead of maintaining multiple versions of the code or multiple separate configuration files, Spring allows you to enable/disable components and properties depending on the active profile.

A profile affects two things:

1. **Configuration properties**: Which YAML file is loaded (database, URLs, credentials, etc.)
2. **Spring Beans**: Which classes are registered in the container (beans can be created only for certain profiles)

---

## How do you activate profiles?

There are several ways to activate a profile. In order of priority (from highest to lowest):

| Metodo | Example | Where is it used? |
|---|---|---|
| **Environment variable** | `SPRING_PROFILES_ACTIVE=prod` | Production (Kubernetes, Docker) |
| **JVM argument** | `-Dspring.profiles.active=dev` | IDE (IntelliJ, Eclipse) |
| **Command-line argument** | `--spring.profiles.active=dev,demo` | Terminal, scripts |
| **In application.yaml** | `spring.profiles.active: dev` | Not recommended (hardcodes the profile) |

In this project:

- **Local development**: It runs with [`--spring.profiles.active=dev`](https://docs.spring.io/spring-boot/reference/features/profiles.html) or `SPRING_PROFILES_ACTIVE=dev`
- **Production (Kubernetes)**: The environment variable `SPRING_PROFILES_ACTIVE=prod` is defined in the Kubernetes manifest
- **Demo**: It runs with `--spring.profiles.active=dev,demo` (demo is combined with dev to have H2)

You can activate **multiple profiles** by separating them with a comma: `dev,demo` activates both profiles simultaneously.

---

## Configuration loading: base + profile

Spring loads the [YAML files](https://docs.spring.io/spring-boot/reference/features/external-config.html) in a specific order and **merges** them (merge):

```
1. application.yaml           ← Se carga SIEMPRE (configuracion base)
2. application-{perfil}.yaml  ← Se carga SOLO si el perfil esta activo
```

**The profile properties override the base properties.** Those that are not redefined in the profile keep the base value.

Concrete example with `app.whoop.sync-cron`:

**`src/main/resources/application.yaml`** (base):

```yaml
app:
  whoop:
    sync-cron: "0 */30 * * * *"    # Cada 30 minutos
```

**`src/main/resources/application-demo.yaml`** (demo profile):

```yaml
app:
  whoop:
    sync-cron: "0 */5 * * * *"     # Cada 5 minutos (sobreescribe el base)
```

| Active profile | Value of `app.whoop.sync-cron` | Source |
|---|---|---|
| `dev` | `"0 */30 * * * *"` | application.yaml (base, not overridden) |
| `prod` | `"0 */30 * * * *"` | application.yaml (base, not overridden) |
| `demo` | `"0 */5 * * * *"` | application-demo.yaml (overwrites base) |

The same happens with `app.whoop.base-url`:

| Active profile | Value | Source |
|---|---|---|
| `dev` or `prod` | `https://api.prod.whoop.com` | application.yaml (base) |
| `demo` | `http://localhost:8080/mock` | application-demo.yaml (overwrites) |

---

## Profile annotations in beans

The annotation [`@Profile`](https://docs.spring.io/spring-framework/reference/core/beans/environment.html) controls in which profiles a bean is registered:

| Annotation | Meaning | Example |
|---|---|---|
| `@Profile("demo")` | It is only created when the `demo` profile is active | `DemoWhoopTokenManager` |
| `@Profile("!demo")` | It is created in all profiles **except** `demo` | `WhoopTokenManager` |
| `@Profile("dev")` | It is only created when the `dev` profile is active | (not used in this project) |
| Without `@Profile` | It is always created, regardless of the profile | `WhoopApiClient`, `WhoopSyncService`, etc. |

The `!` operator (negation) is key to the Strategy pattern used by this project with `TokenManager`:

```
Perfil activo  │  @Profile("!demo")         │  @Profile("demo")
               │  WhoopTokenManager          │  DemoWhoopTokenManager
───────────────┼─────────────────────────────┼──────────────────────
dev            │  SE CREA                    │  NO se crea
prod           │  SE CREA                    │  NO se crea
demo           │  NO se crea                │  SE CREA
dev,demo       │  NO se crea (demo activo)  │  SE CREA
```

---

## The 3 project profiles

### Dev profile (development)

**File**: `src/main/resources/application-dev.yaml`

```yaml
spring:
  # H2 in-memory database
  datasource:
    url: jdbc:h2:mem:whoop_dev;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
    username: sa
    password:

  # H2 Console
  h2:
    console:
      enabled: true
      path: /h2-console

  # JPA dev settings
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        format_sql: true

  # Disable OAuth2 auto-config in dev (tokens managed manually)
  security:
    oauth2:
      client:
        registration:
          whoop:
            client-id: ${WHOOP_CLIENT_ID:dev-client-id}
            client-secret: ${WHOOP_CLIENT_SECRET:dev-client-secret}

# App-specific dev config
app:
  security:
    # Clave de cifrado para desarrollo/testing (NO usar en produccion)
    encryption-key: ${ENCRYPTION_KEY:YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU=}

logging:
  level:
    com.example.whoopdavidapi: DEBUG
    org.springframework.security: DEBUG
    org.hibernate.SQL: DEBUG
```

Key points of the dev profile:

**Database [H2](https://www.h2database.com/) in-memory**:

- `jdbc:h2:mem:whoop_dev` creates an **in-memory** database. It is created at startup and destroyed at shutdown. You don’t need to install anything.
- `DB_CLOSE_DELAY=-1` prevents H2 from closing the database when there are no active connections.
- `DB_CLOSE_ON_EXIT=FALSE` prevents H2 from closing the database when exiting the JVM (necessary for tests).
- `driver-class-name: org.h2.Driver` indicates the JDBC driver for H2.

**`ddl-auto: update`**:
Hibernate **creates and modifies** tables automatically based on the entities `@Entity`. If a field is added to `WhoopCycle`, Hibernate adds the column to the table. Ideal for rapid development, but **dangerous in production** (it could make unwanted changes).

**H2 Console**:

- `h2.console.enabled: true` enables a web interface to query the H2 database.
- Accessible at `http://localhost:8080/h2-console`.
- Useful for verifying that data is being saved correctly during development.

**`show-sql: true` and `format_sql: true`**:
Show the SQL queries that Hibernate generates in the console, formatted for readability. Example output:

```sql
select
    wc.id,
    wc.user_id,
    wc.start_time,
    ...
from
    whoop_cycles wc
order by
    wc.updated_at desc
limit 1
```

**Encryption key with default**:
`${ENCRYPTION_KEY:YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU=}` provides a default key for development. In production, the `ENCRYPTION_KEY` variable must be defined without a default (see the prod profile).

**OAuth2 client-id/secret with defaults**:
`${WHOOP_CLIENT_ID:dev-client-id}` allows starting up without Whoop’s real environment variables. This is useful in development when there’s no need to connect to the real API.

### Prod profile (production)

**File**: `src/main/resources/application-prod.yaml`

```yaml
spring:
  # PostgreSQL
  datasource:
    url: ${DATABASE_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      maximum-pool-size: 10
      minimum-idle: 2
      pool-name: WhoopHikariPool

  # H2 Console disabled in prod
  h2:
    console:
      enabled: false

  # JPA prod settings
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

logging:
  level:
    root: INFO
    com.example.whoopdavidapi: INFO
    org.hibernate.SQL: WARN
```

Key points of the prod profile:

**PostgreSQL with HikariCP**:

- `${DATABASE_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`: No default values. The **application does not start** if these variables are not defined. This is intentional: it forces you to configure the credentials correctly.
- `driver-class-name: org.postgresql.Driver`: JDBC driver for PostgreSQL.
- **HikariCP** is Spring Boot’s default database connection pool. It reuses connections instead of opening/closing a new one for each query:

| Property | Value | Meaning |
|---|---|---|
| `maximum-pool-size` | `10` | Maximum of 10 simultaneous connections to PostgreSQL |
| `minimum-idle` | `2` | Keeps at least 2 connections open at all times (for a quick response) |
| `connection-timeout` | `30000` | 30 seconds max to obtain a connection from the pool |
| `idle-timeout` | `600000` | 10 minutes: an inactive connection is closed |
| `max-lifetime` | `1800000` | 30 minutes: maximum lifetime of a connection (it is recycled to avoid leaks) |

**`ddl-auto: validate`**:
Unlike `update` in dev, `validate` **does not modify** the database. It only checks that the existing tables match the JPA entities. If they don’t match, the application **does not start**. This protects against accidental changes in production. Schema changes must be made with migration tools (such as Flyway or Liquibase).

**Reduced logging**:

- `com.example.whoopdavidapi: INFO` (instead of DEBUG)
- `org.hibernate.SQL: WARN` (does not show SQL queries)
- Fewer logs = better performance and less storage in production.

### Demo profile (testing without real API keys)

**File**: `src/main/resources/application-demo.yaml`

```yaml
# Perfil demo: mock Whoop API en localhost, sin API keys reales
app:
  whoop:
    base-url: http://localhost:8080/mock
    sync-cron: "0 */5 * * * *"

spring:
  security:
    oauth2:
      client:
        registration:
          whoop:
            client-id: demo-client-id
            client-secret: demo-client-secret

logging:
  level:
    com.example.whoopdavidapi.mock: DEBUG
```

The `demo` profile is designed to run the full **application** without needing real Whoop API credentials. It is ideal for:

- Test the application for the first time
- Give demonstrations
- Frontend development (Power BI) without relying on the real API

**`base-url: http://localhost:8080/mock`**:
Redirect all HTTP requests from `WhoopApiClient` to the mock controller running within the application itself (at `/mock/developer/v1/...`). That is, the application calls itself.

**`sync-cron: "0 */5 * * * *"`**:
Synchronization every 5 minutes (instead of 30). Since the data is mock, there is no risk of saturating any API.

**Demo credentials**: `demo-client-id` and `demo-client-secret` are fake values. The mock controller does not validate credentials.

The `demo` profile is normally used **combined with dev**: `--spring.profiles.active=dev,demo`. This way you have H2 in-memory (from dev) + mock API (from demo).

---

## Exclusive beans from the demo profile

The demo profile activates 4 classes annotated with `@Profile("demo")`, located in the `mock/` package.

### MockWhoopApiController

**File**: `src/main/kotlin/com/example/whoopdavidapi/mock/MockWhoopApiController.kt`

```kotlin
@RestController
@Profile("demo")
@RequestMapping("/mock/developer/v1")
class MockWhoopApiController(
    private val dataGenerator: MockWhoopDataGenerator
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/cycle")
    fun getCycles(
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) nextToken: String?,
        @RequestParam(required = false) start: String?,
        @RequestParam(required = false) end: String?
    ): Map<String, Any?> {
        log.debug("Mock GET /cycle limit={} nextToken={}", limit, nextToken)
        return paginate(dataGenerator.cycles, limit, nextToken)
    }

    @GetMapping("/recovery")
    fun getRecoveries(
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) nextToken: String?,
        @RequestParam(required = false) start: String?,
        @RequestParam(required = false) end: String?
    ): Map<String, Any?> {
        log.debug("Mock GET /recovery limit={} nextToken={}", limit, nextToken)
        return paginate(dataGenerator.recoveries, limit, nextToken)
    }

    @GetMapping("/activity/sleep")
    fun getSleeps(
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) nextToken: String?,
        @RequestParam(required = false) start: String?,
        @RequestParam(required = false) end: String?
    ): Map<String, Any?> {
        log.debug("Mock GET /activity/sleep limit={} nextToken={}", limit, nextToken)
        return paginate(dataGenerator.sleeps, limit, nextToken)
    }

    @GetMapping("/activity/workout")
    fun getWorkouts(
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(required = false) nextToken: String?,
        @RequestParam(required = false) start: String?,
        @RequestParam(required = false) end: String?
    ): Map<String, Any?> {
        log.debug("Mock GET /activity/workout limit={} nextToken={}", limit, nextToken)
        return paginate(dataGenerator.workouts, limit, nextToken)
    }

    @GetMapping("/user/profile/basic")
    fun getProfile(): Map<String, Any?> {
        log.debug("Mock GET /user/profile/basic")
        return dataGenerator.profile
    }

    private fun paginate(
        allRecords: List<Map<String, Any?>>,
        limit: Int,
        nextToken: String?
    ): Map<String, Any?> {
        val offset = nextToken?.toIntOrNull() ?: 0
        val effectiveLimit = limit.coerceIn(1, 25)
        val page = allRecords.drop(offset).take(effectiveLimit)
        val nextOffset = offset + page.size

        return mapOf(
            "records" to page,
            "next_token" to if (nextOffset < allRecords.size) nextOffset.toString() else null
        )
    }
}
```

This controller **simulates the Whoop API** within the application itself. The endpoints replicate the same structure as the real API:

- Same paths: `/developer/v1/cycle`, `/developer/v1/recovery`, `/developer/v1/activity/sleep`, `/developer/v1/activity/workout`, `/developer/v1/user/profile/basic`
- Same parameters: `limit`, `nextToken`, `start`, `end`
- Same response structure: `{ "records": [...], "next_token": "..." }`

The `@RequestMapping("/mock/developer/v1")` adds the `/mock` prefix to avoid colliding with the application's real endpoints (which are in `/api/v1/...`).

**Mock pagination logic**: The function `paginate()` simulates Whoop’s real pagination. It uses `nextToken` as a numeric offset (index in the list of records), returns `limit` records from that offset, and generates a `next_token` if there are more records remaining.

The `WhoopApiClient` does not know that it is calling a mock. For it, it is the same base URL (configured as `http://localhost:8080/mock` via YAML) with the same endpoints. This is possible because the `base-url` is injected from the configuration.

### MockWhoopDataGenerator

**File**: `src/main/kotlin/com/example/whoopdavidapi/mock/MockWhoopDataGenerator.kt`

```kotlin
@Component
@Profile("demo")
class MockWhoopDataGenerator {

    private val random = Random(42)
    private val userId = 123456L
    private val days = 30

    val cycles: List<Map<String, Any?>> by lazy { generateCycles() }
    val recoveries: List<Map<String, Any?>> by lazy { generateRecoveries() }
    val sleeps: List<Map<String, Any?>> by lazy { generateSleeps() }
    val workouts: List<Map<String, Any?>> by lazy { generateWorkouts() }

    val profile: Map<String, Any?> = mapOf(
        "user_id" to userId,
        "email" to "david.demo@whoop.com",
        "first_name" to "David",
        "last_name" to "Demo"
    )
    // ... metodos generateCycles(), generateRecoveries(), etc.
}
```

Important features:

- **`Random(42)`**: A deterministic seed (`42`) is used. This means that the generated data is **always the same** in each run. This facilitates reproducible testing and consistent demonstrations.
- **`by lazy { ... }`**: Kotlin `lazy` delegation. The data is not generated when creating the bean, but rather the **first time** the property is accessed. Afterwards it is cached (it is not generated again).
- **30 days of data**: cycles, recoveries, sleeps, and workouts are generated for the last 30 days, with realistic values (strain between 4-21, recovery score between 20-99, etc.).
- **Workouts with a 70% probability**: To simulate that not every day has training, `if (random.nextFloat() > 0.70f) continue` is used (approximately 4–5 workouts per week).

### DemoTokenSeeder and CommandLineRunner

**File**: `src/main/kotlin/com/example/whoopdavidapi/mock/DemoTokenSeeder.kt`

```kotlin
@Component
@Profile("demo")
class DemoTokenSeeder(
    private val tokenRepository: OAuthTokenRepository
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(vararg args: String) {
        if (tokenRepository.count() == 0L) {
            val token = OAuthTokenEntity(
                accessToken = "demo-access-token",
                refreshToken = "demo-refresh-token",
                tokenType = "Bearer",
                expiresAt = Instant.now().plusSeconds(86400),
                scope = "offline,read:profile,read:cycles,read:recovery,read:sleep,read:workout",
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
            tokenRepository.save(token)
            log.info("Token OAuth2 demo insertado en BD")
        }
    }
}
```

**[`CommandLineRunner`](https://docs.spring.io/spring-boot/reference/features/spring-application.html)** is a Spring Boot interface that allows you to execute code **right after the application starts**. The `run()` method is automatically invoked only once at startup.

In this case, insert a fake OAuth2 token into the database so that the application works without having gone through Whoop’s real authorization flow. The `if (tokenRepository.count() == 0L)` condition prevents inserting duplicates if the application is restarted without clearing the database.

The `accessToken` is `"demo-access-token"`, the same value returned by `DemoWhoopTokenManager.getValidAccessToken()`. The `expiresAt` is set to 24 hours in the future (`86400` seconds) so that it does not expire during a normal development session.

### DemoWhoopTokenManager and @Primary

**File**: `src/main/kotlin/com/example/whoopdavidapi/mock/DemoWhoopTokenManager.kt`

```kotlin
@Component
@Profile("demo")
@Primary
class DemoWhoopTokenManager : TokenManager {

    override fun getValidAccessToken(): String = "demo-access-token"
}
```

Two annotations work together here:

**`@Profile("demo")`**: This bean only exists when the `demo` profile is active.

**[`@Primary`](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)**: If Spring finds **more than one bean** of the same type (`TokenManager`), it uses the one marked as `@Primary`. It’s a safety measure: even though `@Profile("!demo")` in `WhoopTokenManager` should prevent both from coexisting, `@Primary` ensures that in case of conflict, the mock wins.

The counterpart is `WhoopTokenManager`:

**File**: `src/main/kotlin/com/example/whoopdavidapi/client/WhoopTokenManager.kt`

```kotlin
@Component
@org.springframework.context.annotation.Profile("!demo")
class WhoopTokenManager(...) : TokenManager {
```

**`@Profile("!demo")`** means "active in any profile that is NOT demo". It is the logical negation. If the active profile is `dev`, `prod`, or any other that is not `demo`, this bean is created. If the active profile includes `demo`, this bean is not created.

---

## Summary table of differences between profiles

| Aspect | dev | prod | demo (with dev) |
|---|---|---|---|
| **Database** | H2 in-memory | PostgreSQL | H2 in-memory |
| **DDL** | `update` (auto-create) | `validate` (just verify) | `update` (auto-create) |
| **H2 Console** | Enabled | Disabled | Enabled |
| **SQL in logs** | Yes (DEBUG) | No (WARN) | Yes (DEBUG) |
| **Whoop URL** | `https://api.prod.whoop.com` | `https://api.prod.whoop.com` | `http://localhost:8080/mock` |
| **Sync cron** | Every 30 min | Every 30 min | Every 5 min |
| **TokenManager** | `WhoopTokenManager` (real) | `WhoopTokenManager` (real) | `DemoWhoopTokenManager` (mock) |
| **API keys** | Defaults (`dev-client-id`) | Required (no default) | Demo values |
| **Encryption key** | Hardcoded default | Required (no default) | Hardcoded default |
| **MockWhoopApiController** | It does not exist | It does not exist | Active in `/mock/...` |
| **DemoTokenSeeder** | It does not exist | It does not exist | Insert token at startup |
| **Log level** | DEBUG | INFO | DEBUG + mock: DEBUG |

---

## Official documentation

- [Spring Boot Profiles](https://docs.spring.io/spring-boot/reference/features/profiles.html) - Official documentation on profiles
- [Spring @Profile](https://docs.spring.io/spring-framework/reference/core/beans/environment.html#beans-definition-profiles) - @Profile Annotation
- [Spring Boot External Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html) - Property precedence order
- [Spring Boot CommandLineRunner](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/CommandLineRunner.html) - Code execution on startup
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby) - Connection pool configuration
