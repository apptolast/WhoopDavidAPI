# 01 - Arquitectura y Patron BFF

## Que es?

La **arquitectura** de una aplicacion define como se organizan sus componentes y como fluyen los datos entre ellos. En este proyecto usamos dos conceptos clave:

1. **Patron [BFF (Backend For Frontend)](https://learn.microsoft.com/en-us/azure/architecture/patterns/backends-for-frontends)**: Un servidor intermedio que adapta una API externa al formato que necesita un cliente especifico
2. **Arquitectura por capas**: Separacion de responsabilidades en Controller, Service, Repository y Database

### Que es el patron BFF?

BFF significa **Backend For Frontend**. Es un patron donde creas un servidor backend dedicado a un frontend especifico. En nuestro caso:

- **Frontend**: Power BI (el dashboard de visualizacion)
- **Backend externo**: Whoop API v2 (la API oficial de la pulsera Whoop)
- **BFF**: WhoopDavidAPI (este proyecto)

Sin un BFF, Power BI tendria que:

- Autenticarse directamente contra Whoop API (OAuth2 complejo)
- Manejar paginacion de Whoop (tokens de siguiente pagina)
- Depender de que Whoop este online en cada refresco del dashboard
- Transformar formatos JSON anidados al formato tabular que necesita

Con el BFF, Power BI simplemente hace peticiones HTTP Basic Auth a nuestra API, que devuelve datos ya almacenados, paginados y en formato plano.

---

## Donde se usa en este proyecto?

### Punto de entrada

**Archivo**: [`src/main/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplication.kt`](../src/main/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplication.kt)

```kotlin
@SpringBootApplication
@EnableScheduling
class WhoopDavidApiApplication

fun main(args: Array<String>) {
    runApplication<WhoopDavidApiApplication>(*args)
}
```

### Componentes clave por capa

| Capa | Archivos | Responsabilidad |
|---|---|---|
| **Controller** | [`controller/CycleController.kt`](../src/main/kotlin/com/example/whoopdavidapi/controller/CycleController.kt), etc. | Recibir peticiones HTTP, validar parametros, devolver respuestas |
| **Service** | [`service/CycleService.kt`](../src/main/kotlin/com/example/whoopdavidapi/service/CycleService.kt), etc. | Logica de negocio, transformacion de datos |
| **Repository** | [`repository/CycleRepository.kt`](../src/main/kotlin/com/example/whoopdavidapi/repository/CycleRepository.kt), etc. | Acceso a base de datos (queries [JPA](https://docs.spring.io/spring-data/jpa/reference/)) |
| **Entity** | [`model/entity/WhoopCycle.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/entity/WhoopCycle.kt), etc. | Representacion de tablas de BD |
| **DTO** | [`model/dto/CycleDTO.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/dto/CycleDTO.kt), etc. | Objetos de transferencia para respuestas API |
| **Mapper** | [`mapper/CycleMapper.kt`](../src/main/kotlin/com/example/whoopdavidapi/mapper/CycleMapper.kt), etc. | Conversion automatica Entity <-> DTO |
| **Client** | [`client/WhoopApiClient.kt`](../src/main/kotlin/com/example/whoopdavidapi/client/WhoopApiClient.kt) | Cliente HTTP para Whoop API externa |
| **Sync** | [`service/WhoopSyncService.kt`](../src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt) | Sincronizacion de datos Whoop -> BD local |
| **Scheduler** | [`scheduler/WhoopDataSyncScheduler.kt`](../src/main/kotlin/com/example/whoopdavidapi/scheduler/WhoopDataSyncScheduler.kt) | Disparo periodico de la sincronizacion |
| **Security** | [`config/SecurityConfig.kt`](../src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt) | Autenticacion y autorizacion |
| **Exception** | [`exception/GlobalExceptionHandler.kt`](../src/main/kotlin/com/example/whoopdavidapi/exception/GlobalExceptionHandler.kt) | Manejo centralizado de errores |

---

## Diagrama de flujo de datos

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

### Flujo de sincronizacion (escritura)

1. `WhoopDataSyncScheduler` dispara `syncAll()` cada 30 minutos (configurable via cron)
2. `WhoopSyncService` pide los datos a `WhoopApiClient` con sincronizacion incremental (solo datos nuevos desde el ultimo `updatedAt`)
3. `WhoopApiClient` usa [`RestClient`](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html) para llamar a Whoop API v2 con token Bearer OAuth2
4. `WhoopTokenManager` se asegura de que el token sea valido; si expira en menos de 5 minutos, lo refresca automaticamente
5. `WhoopSyncService` mapea las respuestas JSON a entidades JPA y las guarda en la BD

### Flujo de consulta (lectura)

1. Power BI hace `GET /api/v1/cycles?page=1&pageSize=100` con HTTP Basic Auth
2. `SecurityConfig` valida las credenciales (usuario `powerbi`)
3. `CycleController` valida los parametros y delega a `CycleService`
4. `CycleService` consulta `CycleRepository` con paginacion y filtros
5. El resultado se convierte de Entity a DTO via `CycleMapper` (MapStruct)
6. Se devuelve un `PaginatedResponse<CycleDTO>` como JSON

---

## Por que esta decision?

### Por que WebMVC y no WebFlux?

Spring ofrece dos modelos de programacion web:

| Caracteristica | [WebMVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html) (elegido) | [WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux.html) |
|---|---|---|
| Modelo | Bloqueante (un hilo por request) | No bloqueante (reactivo) |
| JPA/Hibernate | Compatible directamente | Requiere R2DBC (diferente ORM) |
| `@Scheduled` | Funciona nativo | Requiere adaptaciones |
| Complejidad | Simple | Mas complejo (Mono/Flux) |
| Beneficio reactivo | No necesario (1 usuario) | Miles de conexiones concurrentes |

**Decisiones que forzaron WebMVC:**

1. **JPA es bloqueante**: Spring Data JPA (Hibernate) usa JDBC, que es bloqueante por naturaleza. Usar WebFlux con JPA anularia las ventajas reactivas
2. **`@Scheduled` es bloqueante**: La sincronizacion periodica usa hilos bloqueantes
3. **Un solo usuario**: No hay beneficio en manejar miles de conexiones concurrentes cuando solo Power BI hace peticiones

### Por que sincronizacion incremental?

En [`service/WhoopSyncService.kt`](../src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt), cada metodo `sync*()` busca el ultimo `updatedAt` en la BD:

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

Esto evita descargar todos los datos en cada sincronizacion. En la primera ejecucion, `lastUpdated` es `null` y se descargan todos los datos historicos. En ejecuciones siguientes, solo se piden los datos actualizados despues de la ultima sincronizacion.

### Por que separar Entity y DTO?

Las **entidades** (`WhoopCycle`) representan la estructura de la base de datos. Los **DTOs** (`CycleDTO`) representan lo que la API devuelve al cliente. Separarlos permite:

- Cambiar la estructura de la BD sin afectar la API
- Ocultar campos internos (como timestamps de sincronizacion)
- Devolver formatos diferentes a distintos clientes en el futuro

---

## Codigo explicado

### Punto de entrada: `WhoopDavidApiApplication.kt`

**Archivo**: [`src/main/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplication.kt`](../src/main/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplication.kt)

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

1. **[`@SpringBootApplication`](https://docs.spring.io/spring-boot/reference/using/using-the-springbootapplication-annotation.html)**: Meta-anotacion que combina tres anotaciones:
   - `@Configuration`: Esta clase puede declarar beans (`@Bean`)
   - `@EnableAutoConfiguration`: Spring Boot configura automaticamente beans basandose en las dependencias del classpath (si detecta JPA, configura Hibernate; si detecta H2, configura un DataSource in-memory, etc.)
   - `@ComponentScan`: Escanea todos los paquetes bajo `com.example.whoopdavidapi` buscando clases anotadas con `@Component`, `@Service`, `@Controller`, `@Repository`, `@Configuration`

2. **[`@EnableScheduling`](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)**: Activa el soporte para `@Scheduled`. Sin esta anotacion, `WhoopDataSyncScheduler` no ejecutaria su tarea cron

3. **La clase esta vacia**: En Kotlin, la clase solo sirve como ancla para las anotaciones. Spring Boot no necesita que tenga metodos

4. **`fun main`**: Funcion de nivel superior (top-level function). En Kotlin no necesita estar dentro de una clase

5. **`runApplication<WhoopDavidApiApplication>(*args)`**: Funcion de extension de Kotlin que equivale a `SpringApplication.run(WhoopDavidApiApplication::class.java, *args)`. El `*args` es el operador spread de Kotlin que convierte un `Array` en varargs

### Arquitectura de seguridad multi-cadena

**Archivo**: [`src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt`](../src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt)

Spring Security permite definir multiples [`SecurityFilterChain`](https://docs.spring.io/spring-security/reference/servlet/architecture.html), cada una con su propio patron de URLs y reglas. El `@Order` determina la prioridad:

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

Cuando llega una peticion, Spring evalua las cadenas en orden de `@Order`. La primera cuyo `securityMatcher` coincida con la URL es la que se aplica. Si ninguna coincide, la cadena catch-all (`@Order(4)`) deniega el acceso.

### Flujo de sincronizacion

**Archivo**: [`src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt`](../src/main/kotlin/com/example/whoopdavidapi/service/WhoopSyncService.kt)

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

El servicio inyecta el `WhoopApiClient` (para hablar con Whoop) y los 4 repositorios (para guardar en BD). La sincronizacion es secuencial porque cada tipo de dato es independiente pero compartimos la misma conexion a Whoop.

### Flujo de tokens OAuth2

**Archivo**: [`src/main/kotlin/com/example/whoopdavidapi/client/WhoopTokenManager.kt`](../src/main/kotlin/com/example/whoopdavidapi/client/WhoopTokenManager.kt)

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

Los tokens OAuth2 de Whoop se almacenan en la tabla `oauth_tokens` (cifrados con AES-256-GCM). Cada vez que el `WhoopApiClient` necesita hacer una peticion, llama a `getValidAccessToken()` que:

1. Busca el token mas reciente en la BD
2. Si expira en menos de 5 minutos, lo refresca automaticamente usando el `refresh_token`
3. Guarda el nuevo token en la BD
4. Devuelve el access token valido

Nota la interfaz `TokenManager` en [`client/TokenManager.kt`](../src/main/kotlin/com/example/whoopdavidapi/client/TokenManager.kt) que permite sustituir la implementacion:

```kotlin
interface TokenManager {
    fun getValidAccessToken(): String
}
```

En el perfil `demo`, `DemoWhoopTokenManager` reemplaza a `WhoopTokenManager` devolviendo un token falso. Esto es posible gracias a [`@Profile("demo")`](https://docs.spring.io/spring-boot/reference/features/profiles.html) y `@Primary` (ver [documento 11 - Perfiles](11-perfiles.md)).

---

## Resumen de capas

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

**Regla fundamental**: Cada capa solo conoce a la capa inmediatamente inferior. El Controller no accede directamente al Repository; siempre pasa por el Service. Esto permite:

- Testear cada capa de forma aislada (mocks)
- Cambiar la implementacion de una capa sin afectar las demas
- Reutilizar logica de negocio en distintos contextos (REST, scheduled, etc.)

---

## Documentacion oficial

- [Spring Boot Application](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)
- [Spring Web MVC](https://docs.spring.io/spring-framework/reference/web/webmvc.html)
- [Spring WebFlux (para comparacion)](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [Patron BFF (Sam Newman)](https://samnewman.io/patterns/architectural/bff/)
- [Spring Security Architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
- [EnableScheduling](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
