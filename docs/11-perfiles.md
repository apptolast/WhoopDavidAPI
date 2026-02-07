# 11 - Perfiles de Spring

## Tabla de contenidos

- [Que son los perfiles de Spring?](#que-son-los-perfiles-de-spring)
- [Como se activan los perfiles](#como-se-activan-los-perfiles)
- [Carga de configuracion: base + perfil](#carga-de-configuracion-base--perfil)
- [Anotaciones de perfil en beans](#anotaciones-de-perfil-en-beans)
- [Los 3 perfiles del proyecto](#los-3-perfiles-del-proyecto)
  - [Perfil dev (desarrollo)](#perfil-dev-desarrollo)
  - [Perfil prod (produccion)](#perfil-prod-produccion)
  - [Perfil demo (testing sin API keys reales)](#perfil-demo-testing-sin-api-keys-reales)
- [Beans exclusivos del perfil demo](#beans-exclusivos-del-perfil-demo)
  - [MockWhoopApiController](#mockwhoopapicontroller)
  - [MockWhoopDataGenerator](#mockwhoopdatagenerator)
  - [DemoTokenSeeder y CommandLineRunner](#demotokenseeder-y-commandlinerunner)
  - [DemoWhoopTokenManager y @Primary](#demowhooptokenmanager-y-primary)
- [Tabla resumen de diferencias entre perfiles](#tabla-resumen-de-diferencias-entre-perfiles)
- [Documentacion oficial](#documentacion-oficial)

---

## Que son los perfiles de Spring?

Los [perfiles de Spring](https://docs.spring.io/spring-boot/reference/features/profiles.html) permiten tener **diferentes configuraciones** para diferentes entornos (desarrollo, produccion, testing) dentro de la misma aplicacion. En vez de mantener multiples versiones del codigo o multiples archivos de configuracion sueltos, Spring permite activar/desactivar componentes y propiedades segun el perfil activo.

Un perfil afecta dos cosas:

1. **Propiedades de configuracion**: Que archivo YAML se carga (base de datos, URLs, credenciales, etc.)
2. **Beans de Spring**: Que clases se registran en el contenedor (se pueden crear beans solo para ciertos perfiles)

---

## Como se activan los perfiles

Hay varias formas de activar un perfil. En orden de prioridad (de mayor a menor):

| Metodo | Ejemplo | Donde se usa |
|---|---|---|
| **Variable de entorno** | `SPRING_PROFILES_ACTIVE=prod` | Produccion (Kubernetes, Docker) |
| **Argumento de JVM** | `-Dspring.profiles.active=dev` | IDE (IntelliJ, Eclipse) |
| **Argumento de linea de comandos** | `--spring.profiles.active=dev,demo` | Terminal, scripts |
| **En application.yaml** | `spring.profiles.active: dev` | No recomendado (hardcodea el perfil) |

En este proyecto:

- **Desarrollo local**: Se ejecuta con [`--spring.profiles.active=dev`](https://docs.spring.io/spring-boot/reference/features/profiles.html) o `SPRING_PROFILES_ACTIVE=dev`
- **Produccion (Kubernetes)**: La variable de entorno `SPRING_PROFILES_ACTIVE=prod` se define en el manifiesto de Kubernetes
- **Demo**: Se ejecuta con `--spring.profiles.active=dev,demo` (demo se combina con dev para tener H2)

Se pueden activar **multiples perfiles** separandolos por coma: `dev,demo` activa ambos perfiles simultaneamente.

---

## Carga de configuracion: base + perfil

Spring carga los [archivos YAML](https://docs.spring.io/spring-boot/reference/features/external-config.html) en un orden especifico y los **fusiona** (merge):

```
1. application.yaml           ← Se carga SIEMPRE (configuracion base)
2. application-{perfil}.yaml  ← Se carga SOLO si el perfil esta activo
```

**Las propiedades del perfil sobreescriben las del base.** Las que no se redefinen en el perfil mantienen el valor base.

Ejemplo concreto con `app.whoop.sync-cron`:

**`src/main/resources/application.yaml`** (base):

```yaml
app:
  whoop:
    sync-cron: "0 */30 * * * *"    # Cada 30 minutos
```

**`src/main/resources/application-demo.yaml`** (perfil demo):

```yaml
app:
  whoop:
    sync-cron: "0 */5 * * * *"     # Cada 5 minutos (sobreescribe el base)
```

| Perfil activo | Valor de `app.whoop.sync-cron` | Fuente |
|---|---|---|
| `dev` | `"0 */30 * * * *"` | application.yaml (base, no sobreescrito) |
| `prod` | `"0 */30 * * * *"` | application.yaml (base, no sobreescrito) |
| `demo` | `"0 */5 * * * *"` | application-demo.yaml (sobreescribe base) |

Lo mismo ocurre con `app.whoop.base-url`:

| Perfil activo | Valor | Fuente |
|---|---|---|
| `dev` o `prod` | `https://api.prod.whoop.com` | application.yaml (base) |
| `demo` | `http://localhost:8080/mock` | application-demo.yaml (sobreescribe) |

---

## Anotaciones de perfil en beans

La anotacion [`@Profile`](https://docs.spring.io/spring-framework/reference/core/beans/environment.html) controla en que perfiles se registra un bean:

| Anotacion | Significado | Ejemplo |
|---|---|---|
| `@Profile("demo")` | Solo se crea cuando el perfil `demo` esta activo | `DemoWhoopTokenManager` |
| `@Profile("!demo")` | Se crea en todos los perfiles **excepto** `demo` | `WhoopTokenManager` |
| `@Profile("dev")` | Solo se crea cuando el perfil `dev` esta activo | (no usado en este proyecto) |
| Sin `@Profile` | Se crea siempre, independientemente del perfil | `WhoopApiClient`, `WhoopSyncService`, etc. |

El operador `!` (negacion) es clave para el patron Strategy que usa este proyecto con `TokenManager`:

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

## Los 3 perfiles del proyecto

### Perfil dev (desarrollo)

**Archivo**: `src/main/resources/application-dev.yaml`

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

Puntos clave del perfil dev:

**Base de datos [H2](https://www.h2database.com/) in-memory**:

- `jdbc:h2:mem:whoop_dev` crea una base de datos **en memoria**. Se crea al arrancar y se destruye al parar. No necesita instalar nada.
- `DB_CLOSE_DELAY=-1` evita que H2 cierre la base de datos cuando no hay conexiones activas.
- `DB_CLOSE_ON_EXIT=FALSE` evita que H2 cierre la base de datos al salir de la JVM (necesario para tests).
- `driver-class-name: org.h2.Driver` indica el driver JDBC para H2.

**`ddl-auto: update`**:
Hibernate **crea y modifica** las tablas automaticamente basandose en las entidades `@Entity`. Si se anade un campo a `WhoopCycle`, Hibernate anade la columna a la tabla. Ideal para desarrollo rapido, pero **peligroso en produccion** (podria hacer cambios no deseados).

**Consola H2**:

- `h2.console.enabled: true` habilita una interfaz web para consultar la base de datos H2.
- Accesible en `http://localhost:8080/h2-console`.
- Util para verificar que los datos se estan guardando correctamente durante desarrollo.

**`show-sql: true` y `format_sql: true`**:
Muestra las queries SQL que Hibernate genera en la consola, formateadas para legibilidad. Ejemplo de output:

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

**Clave de cifrado con default**:
`${ENCRYPTION_KEY:YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU=}` proporciona una clave por defecto para desarrollo. En produccion, la variable `ENCRYPTION_KEY` debe estar definida sin default (ver perfil prod).

**OAuth2 client-id/secret con defaults**:
`${WHOOP_CLIENT_ID:dev-client-id}` permite arrancar sin las variables de entorno reales de Whoop. Esto es util en desarrollo cuando no se necesita conectar a la API real.

### Perfil prod (produccion)

**Archivo**: `src/main/resources/application-prod.yaml`

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

Puntos clave del perfil prod:

**PostgreSQL con HikariCP**:

- `${DATABASE_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`: Sin valores por defecto. La aplicacion **no arranca** si estas variables no estan definidas. Esto es intencional: obliga a configurar las credenciales correctamente.
- `driver-class-name: org.postgresql.Driver`: Driver JDBC para PostgreSQL.
- **HikariCP** es el pool de conexiones de base de datos por defecto de Spring Boot. Reutiliza conexiones en vez de abrir/cerrar una nueva para cada query:

| Propiedad | Valor | Significado |
|---|---|---|
| `maximum-pool-size` | `10` | Maximo 10 conexiones simultaneas a PostgreSQL |
| `minimum-idle` | `2` | Mantiene al menos 2 conexiones abiertas siempre (para respuesta rapida) |
| `connection-timeout` | `30000` | 30 segundos max para obtener una conexion del pool |
| `idle-timeout` | `600000` | 10 minutos: una conexion inactiva se cierra |
| `max-lifetime` | `1800000` | 30 minutos: vida maxima de una conexion (se recicla para evitar leaks) |

**`ddl-auto: validate`**:
A diferencia de `update` en dev, `validate` **no modifica** la base de datos. Solo verifica que las tablas existentes coincidan con las entidades JPA. Si no coinciden, la aplicacion **no arranca**. Esto protege contra cambios accidentales en produccion. Los cambios de esquema deben hacerse con herramientas de migracion (como Flyway o Liquibase).

**Logging reducido**:

- `com.example.whoopdavidapi: INFO` (en vez de DEBUG)
- `org.hibernate.SQL: WARN` (no muestra queries SQL)
- Menos logs = mejor rendimiento y menos almacenamiento en produccion.

### Perfil demo (testing sin API keys reales)

**Archivo**: `src/main/resources/application-demo.yaml`

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

El perfil `demo` esta disenado para ejecutar la aplicacion **completa** sin necesitar credenciales reales de la Whoop API. Es ideal para:

- Probar la aplicacion por primera vez
- Hacer demostraciones
- Desarrollo de frontend (Power BI) sin depender de la API real

**`base-url: http://localhost:8080/mock`**:
Redirige todas las peticiones HTTP del `WhoopApiClient` al controlador mock que corre dentro de la propia aplicacion (en `/mock/developer/v1/...`). Es decir, la aplicacion se llama a si misma.

**`sync-cron: "0 */5 * * * *"`**:
Sincronizacion cada 5 minutos (en vez de 30). Como los datos son mock, no hay riesgo de saturar ninguna API.

**Credenciales demo**: `demo-client-id` y `demo-client-secret` son valores falsos. El controlador mock no valida credenciales.

El perfil `demo` se usa normalmente **combinado con dev**: `--spring.profiles.active=dev,demo`. Asi se tiene H2 in-memory (de dev) + API mock (de demo).

---

## Beans exclusivos del perfil demo

El perfil demo activa 4 clases anotadas con `@Profile("demo")`, ubicadas en el paquete `mock/`.

### MockWhoopApiController

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/mock/MockWhoopApiController.kt`

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

Este controlador **simula la Whoop API** dentro de la propia aplicacion. Los endpoints replican la misma estructura que la API real:

- Mismos paths: `/developer/v1/cycle`, `/developer/v1/recovery`, `/developer/v1/activity/sleep`, `/developer/v1/activity/workout`, `/developer/v1/user/profile/basic`
- Mismos parametros: `limit`, `nextToken`, `start`, `end`
- Misma estructura de respuesta: `{ "records": [...], "next_token": "..." }`

El `@RequestMapping("/mock/developer/v1")` anade el prefijo `/mock` para no colisionar con los endpoints reales de la aplicacion (que estan en `/api/v1/...`).

**Logica de paginacion mock**: La funcion `paginate()` simula la paginacion real de Whoop. Usa `nextToken` como un offset numerico (indice en la lista de registros), devuelve `limit` registros desde ese offset, y genera un `next_token` si quedan mas registros.

El `WhoopApiClient` no sabe que esta llamando a un mock. Para el, es la misma URL base (configurada como `http://localhost:8080/mock` via YAML) con los mismos endpoints. Esto es posible porque el `base-url` se inyecta desde la configuracion.

### MockWhoopDataGenerator

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/mock/MockWhoopDataGenerator.kt`

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

Caracteristicas importantes:

- **`Random(42)`**: Se usa una seed determinista (`42`). Esto significa que los datos generados son **siempre los mismos** en cada ejecucion. Esto facilita el testing reproducible y las demostraciones consistentes.
- **`by lazy { ... }`**: Delegacion `lazy` de Kotlin. Los datos no se generan al crear el bean, sino la **primera vez** que se accede a la propiedad. Despues se cachean (no se vuelven a generar).
- **30 dias de datos**: Se generan cycles, recoveries, sleeps y workouts para los ultimos 30 dias, con valores realistas (strain entre 4-21, recovery score entre 20-99, etc.).
- **Workouts con probabilidad del 70%**: Para simular que no todos los dias tienen entrenamiento, se usa `if (random.nextFloat() > 0.70f) continue` (aproximadamente 4-5 workouts por semana).

### DemoTokenSeeder y CommandLineRunner

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/mock/DemoTokenSeeder.kt`

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

**[`CommandLineRunner`](https://docs.spring.io/spring-boot/reference/features/spring-application.html)** es una interfaz de Spring Boot que permite ejecutar codigo **justo despues de que la aplicacion arranca**. El metodo `run()` se invoca automaticamente una sola vez al inicio.

En este caso, inserta un token OAuth2 falso en la base de datos para que la aplicacion funcione sin haber pasado por el flujo real de autorizacion de Whoop. La condicion `if (tokenRepository.count() == 0L)` evita insertar duplicados si se reinicia la aplicacion sin limpiar la base de datos.

El `accessToken` es `"demo-access-token"`, el mismo valor que devuelve `DemoWhoopTokenManager.getValidAccessToken()`. El `expiresAt` se configura a 24 horas en el futuro (`86400` segundos) para que no expire durante una sesion de desarrollo normal.

### DemoWhoopTokenManager y @Primary

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/mock/DemoWhoopTokenManager.kt`

```kotlin
@Component
@Profile("demo")
@Primary
class DemoWhoopTokenManager : TokenManager {

    override fun getValidAccessToken(): String = "demo-access-token"
}
```

Dos anotaciones trabajan juntas aqui:

**`@Profile("demo")`**: Este bean solo existe cuando el perfil `demo` esta activo.

**[`@Primary`](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)**: Si Spring encuentra **mas de un bean** del mismo tipo (`TokenManager`), usa el marcado como `@Primary`. Es una medida de seguridad: aunque `@Profile("!demo")` en `WhoopTokenManager` deberia impedir que ambos coexistan, `@Primary` garantiza que en caso de conflicto, el mock gana.

La contraparte es `WhoopTokenManager`:

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/client/WhoopTokenManager.kt`

```kotlin
@Component
@org.springframework.context.annotation.Profile("!demo")
class WhoopTokenManager(...) : TokenManager {
```

**`@Profile("!demo")`** significa "activo en cualquier perfil que NO sea demo". Es la negacion logica. Si el perfil activo es `dev`, `prod`, o cualquier otro que no sea `demo`, este bean se crea. Si el perfil activo incluye `demo`, este bean no se crea.

---

## Tabla resumen de diferencias entre perfiles

| Aspecto | dev | prod | demo (con dev) |
|---|---|---|---|
| **Base de datos** | H2 in-memory | PostgreSQL | H2 in-memory |
| **DDL** | `update` (auto-crear) | `validate` (solo verificar) | `update` (auto-crear) |
| **Consola H2** | Habilitada | Deshabilitada | Habilitada |
| **SQL en logs** | Si (DEBUG) | No (WARN) | Si (DEBUG) |
| **URL Whoop** | `https://api.prod.whoop.com` | `https://api.prod.whoop.com` | `http://localhost:8080/mock` |
| **Sync cron** | Cada 30 min | Cada 30 min | Cada 5 min |
| **TokenManager** | `WhoopTokenManager` (real) | `WhoopTokenManager` (real) | `DemoWhoopTokenManager` (mock) |
| **API keys** | Defaults (`dev-client-id`) | Requeridas (sin default) | Valores demo |
| **Encryption key** | Default hardcodeado | Requerida (sin default) | Default hardcodeado |
| **MockWhoopApiController** | No existe | No existe | Activo en `/mock/...` |
| **DemoTokenSeeder** | No existe | No existe | Inserta token al arrancar |
| **Log level** | DEBUG | INFO | DEBUG + mock: DEBUG |

---

## Documentacion oficial

- [Spring Boot Profiles](https://docs.spring.io/spring-boot/reference/features/profiles.html) - Documentacion oficial de perfiles
- [Spring @Profile](https://docs.spring.io/spring-framework/reference/core/beans/environment.html#beans-definition-profiles) - Anotacion @Profile
- [Spring Boot External Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html) - Orden de precedencia de propiedades
- [Spring Boot CommandLineRunner](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/CommandLineRunner.html) - Ejecucion de codigo al arrancar
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby) - Configuracion del pool de conexiones
