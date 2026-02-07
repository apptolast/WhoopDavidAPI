# 09 - Cliente HTTP y Resilience4j

## Tabla de contenidos

- [RestClient: el cliente HTTP moderno de Spring](#restclient-el-cliente-http-moderno-de-spring)
  - [Que es?](#que-es)
  - [Donde se configura?](#donde-se-configura)
  - [API fluida: como se hacen las peticiones](#api-fluida-como-se-hacen-las-peticiones)
  - [URI builder con query parameters](#uri-builder-con-query-parameters)
  - [Timeouts con SimpleClientHttpRequestFactory](#timeouts-con-simpleclienthttprequestfactory)
- [WhoopApiClient: consumo de la API paginada](#whoopapiclient-consumo-de-la-api-paginada)
  - [Que problema resuelve?](#que-problema-resuelve)
  - [WhoopPageResponse: la estructura de pagina](#whooppageresponse-la-estructura-de-pagina)
  - [getAllRecords(): el metodo generico de paginacion](#getallrecords-el-metodo-generico-de-paginacion)
  - [Inyeccion del Bearer token](#inyeccion-del-bearer-token)
- [TokenManager: patron Strategy](#tokenmanager-patron-strategy)
  - [La interfaz](#la-interfaz)
  - [WhoopTokenManager: gestion real de tokens OAuth2](#whooptokenmanager-gestion-real-de-tokens-oauth2)
  - [DemoWhoopTokenManager: token estatico para demos](#demowhooptokenmanager-token-estatico-para-demos)
  - [Como Spring elige cual inyectar](#como-spring-elige-cual-inyectar)
- [Resilience4j: resiliencia ante fallos externos](#resilience4j-resiliencia-ante-fallos-externos)
  - [Que es Resilience4j?](#que-es-resilience4j)
  - [CircuitBreaker](#circuitbreaker)
  - [Retry](#retry)
  - [RateLimiter](#ratelimiter)
  - [Metodos fallback](#metodos-fallback)
  - [Por que se necesita AOP](#por-que-se-necesita-aop)
- [Documentacion oficial](#documentacion-oficial)

---

## RestClient: el cliente HTTP moderno de Spring

### Que es?

[`RestClient`](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html) es el cliente HTTP **sincrono** introducido en **Spring Framework 6.1** (y por tanto disponible desde Spring Boot 3.2 en adelante). Es el reemplazo moderno de `RestTemplate`.

Diferencias clave con `RestTemplate`:

| Aspecto | `RestTemplate` (legacy) | `RestClient` (moderno) |
|---|---|---|
| **Estilo** | Metodos con muchos parametros | API fluida (builder + method chaining) |
| **URI** | Strings con placeholders | Lambda con `UriBuilder` tipado |
| **Mantenimiento** | En modo mantenimiento | Desarrollo activo |
| **Lectura** | Dificil de leer con muchos params | Legible y expresivo |

**Importante**: `RestClient` es sincrono (bloqueante). Para un cliente reactivo no-bloqueante existe `WebClient`, pero en este proyecto usamos WebMVC (bloqueante), asi que `RestClient` es la eleccion correcta.

### Donde se configura?

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/config/WhoopClientConfig.kt`

```kotlin
@Configuration
class WhoopClientConfig(
    @Value("\${app.whoop.base-url}") private val baseUrl: String,
    @Value("\${app.whoop.connect-timeout:10}") private val connectTimeoutSeconds: Long,
    @Value("\${app.whoop.read-timeout:30}") private val readTimeoutSeconds: Long
) {

    @Bean
    fun whoopRestClient(): RestClient {
        // Configurar timeouts para evitar bloqueos indefinidos
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(java.time.Duration.ofSeconds(connectTimeoutSeconds))
            setReadTimeout(java.time.Duration.ofSeconds(readTimeoutSeconds))
        }

        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory)
            .build()
    }
}
```

Desglose paso a paso:

1. **`@Configuration`**: Marca la clase como fuente de beans de Spring. Spring la procesa al arrancar y registra los beans que definen sus metodos `@Bean`.

2. **`@Value("\${app.whoop.base-url}")`**: Inyecta el valor de la propiedad `app.whoop.base-url` desde `application.yaml`. El valor por defecto es `https://api.prod.whoop.com` (definido en el YAML base), pero el perfil `demo` lo sobreescribe a `http://localhost:8080/mock`.

3. **`@Value("\${app.whoop.connect-timeout:10}")`**: El `:10` despues de los dos puntos es un **valor por defecto**. Si la propiedad no existe en ningun YAML, usa `10` (segundos). La propiedad si esta definida en `application.yaml` con valor `10`, pero el default asegura que la aplicacion no falle si se elimina accidentalmente.

4. **`RestClient.builder()`**: Patron Builder. Se configura paso a paso y al final `.build()` crea la instancia inmutable.

5. **`.baseUrl(baseUrl)`**: Todas las llamadas que haga este `RestClient` partiran de esta URL base. Si despues se hace `.uri("/developer/v1/cycle")`, la URL completa sera `https://api.prod.whoop.com/developer/v1/cycle`.

6. **`.defaultHeader("Content-Type", "application/json")`**: Cabecera que se incluira en **todas** las peticiones de este cliente. Asi no hay que repetirla en cada llamada.

7. **`.requestFactory(requestFactory)`**: Conecta la fabrica de conexiones HTTP con los timeouts configurados (explicados mas abajo).

### API fluida: como se hacen las peticiones

Una vez creado el `RestClient`, las peticiones se construyen con method chaining:

```kotlin
val response = whoopRestClient.get()           // (1) Metodo HTTP
    .uri("/developer/v1/cycle")                 // (2) Path (se suma a baseUrl)
    .header("Authorization", "Bearer $token")   // (3) Cabecera por peticion
    .retrieve()                                 // (4) Ejecutar la peticion
    .body(WhoopPageResponse::class.java)        // (5) Deserializar el JSON a este tipo
```

| Paso | Metodo | Que hace |
|---|---|---|
| 1 | `.get()` | Indica que es una peticion HTTP GET. Tambien existen `.post()`, `.put()`, `.delete()`, `.patch()` |
| 2 | `.uri(...)` | Especifica el path. Se concatena con `baseUrl` del builder |
| 3 | `.header(...)` | Anade una cabecera a esta peticion concreta (no a todas) |
| 4 | `.retrieve()` | Ejecuta la peticion HTTP y obtiene la respuesta. Si el servidor devuelve un error (4xx, 5xx), lanza una excepcion |
| 5 | `.body(...)` | Deserializa el cuerpo de la respuesta JSON al tipo indicado. Jackson (el serializador JSON de Spring) se encarga automaticamente |

### URI builder con query parameters

Cuando la URL necesita parametros de consulta opcionales (como `?limit=25&start=2024-01-01`), se usa una **lambda con `UriBuilder`**:

```kotlin
.uri { uriBuilder ->
    uriBuilder.path(path)
        .queryParam("limit", 25)
    start?.let { uriBuilder.queryParam("start", it.toString()) }
    end?.let { uriBuilder.queryParam("end", it.toString()) }
    nextToken?.let { uriBuilder.queryParam("nextToken", it) }
    uriBuilder.build()
}
```

Por que una lambda y no un String?

- **Parametros opcionales**: Con `?.let { ... }` solo se anade el parametro si no es `null`. Con un String habria que construir la query manualmente concatenando.
- **Codificacion automatica**: `UriBuilder` codifica automaticamente caracteres especiales (como `+` en timestamps). Asi se evitan bugs sutiles de encoding.
- **Type-safe**: No hay riesgo de errores por concatenacion de strings.

Ejemplo de URL resultante:

```
https://api.prod.whoop.com/developer/v1/cycle?limit=25&start=2024-12-01T00:00:00Z&nextToken=abc123
```

### Timeouts con SimpleClientHttpRequestFactory

```kotlin
val requestFactory = SimpleClientHttpRequestFactory().apply {
    setConnectTimeout(java.time.Duration.ofSeconds(connectTimeoutSeconds))
    setReadTimeout(java.time.Duration.ofSeconds(readTimeoutSeconds))
}
```

Dos timeouts diferentes:

| Timeout | Valor por defecto | Que protege |
|---|---|---|
| **Connect timeout** | 10 segundos | Tiempo maximo para **establecer** la conexion TCP con el servidor. Si Whoop esta caido, no esperamos eternamente |
| **Read timeout** | 30 segundos | Tiempo maximo para **recibir** la respuesta una vez conectados. Si Whoop esta lento, cortamos despues de 30s |

Sin timeouts, una peticion HTTP a un servidor que no responde bloquearia el hilo **indefinidamente**. Esto es critico porque:

1. Spring WebMVC usa un pool de hilos limitado (normalmente 200 hilos por defecto en Tomcat).
2. Si todas las peticiones se quedan esperando, el pool se agota y la aplicacion deja de responder.
3. El `@Scheduled` de sincronizacion tambien se bloquearia, impidiendo futuras sincronizaciones.

Los valores se configuran en `application.yaml`:

```yaml
app:
  whoop:
    connect-timeout: 10  # Tiempo max para establecer conexion
    read-timeout: 30     # Tiempo max para leer respuesta
```

---

## WhoopApiClient: consumo de la API paginada

### Que problema resuelve?

La Whoop API devuelve datos **paginados**: cada peticion devuelve maximo 25 registros y un `next_token` para pedir la pagina siguiente. `WhoopApiClient` encapsula esta logica de paginacion para que el resto de la aplicacion simplemente llame `getAllCycles()` y reciba **todos** los registros.

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/client/WhoopApiClient.kt`

### WhoopPageResponse: la estructura de pagina

```kotlin
data class WhoopPageResponse(
    val records: List<Map<String, Any?>> = emptyList(),
    val next_token: String? = null
)
```

Esta `data class` interna modela la estructura JSON que devuelve la Whoop API:

```json
{
  "records": [ { "id": 1000, "strain": 14.5, ... }, ... ],
  "next_token": "abc123"
}
```

- **`records`**: Lista de registros de la pagina actual. Se usa `Map<String, Any?>` en vez de un DTO tipado porque la estructura varia entre endpoints (cycles, recovery, sleep, workout). El mapeo a entidades se hace despues en `WhoopSyncService`.
- **`next_token`**: Si es `null`, no hay mas paginas. Si tiene valor, hay que hacer otra peticion incluyendo este token.

### getAllRecords(): el metodo generico de paginacion

```kotlin
private fun getAllRecords(path: String, start: Instant?, end: Instant?): List<Map<String, Any?>> {
    val allRecords = mutableListOf<Map<String, Any?>>()
    var nextToken: String? = null

    do {
        val token = tokenManager.getValidAccessToken()
        val response = whoopRestClient.get()
            .uri { uriBuilder ->
                uriBuilder.path(path)
                    .queryParam("limit", 25)
                start?.let { uriBuilder.queryParam("start", it.toString()) }
                end?.let { uriBuilder.queryParam("end", it.toString()) }
                nextToken?.let { uriBuilder.queryParam("nextToken", it) }
                uriBuilder.build()
            }
            .header("Authorization", "Bearer $token")
            .retrieve()
            .body(WhoopPageResponse::class.java)
            ?: throw WhoopApiException("Respuesta vacía de Whoop API al obtener registros de '$path' (nextToken=$nextToken)")

        allRecords.addAll(response.records)
        nextToken = response.next_token
        log.debug("Obtenidos {} registros de {}, nextToken={}", response.records.size, path, nextToken)
    } while (nextToken != null)

    log.info("Total {} registros obtenidos de {}", allRecords.size, path)
    return allRecords
}
```

Flujo paso a paso:

1. **`val allRecords = mutableListOf<>()`**: Lista acumuladora donde se van agregando los registros de cada pagina.
2. **`var nextToken: String? = null`**: En la primera iteracion no hay token de paginacion.
3. **`do { ... } while (nextToken != null)`**: Bucle que sigue pidiendo paginas mientras exista `next_token`.
4. **`tokenManager.getValidAccessToken()`**: Se pide un token valido **en cada iteracion**. Esto es importante porque si hay muchas paginas, el token podria expirar durante la paginacion. El `TokenManager` se encarga de refrescarlo si esta proximo a expirar.
5. **`.queryParam("limit", 25)`**: Pide el maximo de registros por pagina (25 es el limite de Whoop API).
6. **`allRecords.addAll(response.records)`**: Acumula los registros de esta pagina.
7. **`nextToken = response.next_token`**: Actualiza el token de paginacion. Si es `null`, el bucle termina.

Los metodos publicos son wrappers que llaman a `getAllRecords` con el path correcto:

```kotlin
@CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
@Retry(name = "whoopApi")
@RateLimiter(name = "whoopApi")
fun getAllCycles(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
    return getAllRecords("/developer/v1/cycle", start, end)
}

// ... getAllRecoveries -> "/developer/v1/recovery"
// ... getAllSleeps -> "/developer/v1/activity/sleep"
// ... getAllWorkouts -> "/developer/v1/activity/workout"
```

Cada metodo publico tiene las mismas tres anotaciones de Resilience4j. Las anotaciones deben estar en el metodo **publico** porque Resilience4j usa proxies AOP que solo interceptan llamadas externas (mas sobre esto en la seccion de AOP).

### Inyeccion del Bearer token

```kotlin
.header("Authorization", "Bearer $token")
```

El patron de autenticacion con la Whoop API es **OAuth2 Bearer Token**: cada peticion incluye la cabecera `Authorization` con el prefijo `Bearer` seguido del access token. El token se obtiene de `TokenManager`, que es una interfaz (no una clase concreta). Esto es el **[patron Strategy](https://refactoring.guru/design-patterns/strategy)**.

---

## TokenManager: patron Strategy

### La interfaz

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/client/TokenManager.kt`

```kotlin
interface TokenManager {
    fun getValidAccessToken(): String
}
```

Una interfaz con un unico metodo. Define **que** se necesita (un access token valido) sin especificar **como** se obtiene. Esto permite tener multiples implementaciones y que Spring inyecte la correcta segun el perfil activo.

### WhoopTokenManager: gestion real de tokens OAuth2

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/client/WhoopTokenManager.kt`

```kotlin
@Component
@org.springframework.context.annotation.Profile("!demo")
class WhoopTokenManager(
    private val tokenRepository: OAuthTokenRepository,
    @Value("\${spring.security.oauth2.client.registration.whoop.client-id}") private val clientId: String,
    @Value("\${spring.security.oauth2.client.registration.whoop.client-secret}") private val clientSecret: String,
    @Value("\${spring.security.oauth2.client.provider.whoop.token-uri}") private val tokenUri: String
) : TokenManager {

    private val log = LoggerFactory.getLogger(javaClass)
    private val refreshClient = RestClient.builder().build()

    override fun getValidAccessToken(): String {
        val token = tokenRepository.findTopByOrderByUpdatedAtDesc()
            ?: throw WhoopApiException("No hay token OAuth2 guardado. Realiza el flujo de autorizacion primero.")

        // Si el token expira en menos de 5 minutos, refrescarlo
        if (token.expiresAt.isBefore(Instant.now().plusSeconds(300))) {
            log.info("Access token expira pronto, refrescando...")
            return refreshToken(token)
        }

        return token.accessToken
            ?: throw WhoopApiException("Token OAuth2 guardado pero access_token es null. Realiza el flujo de autorizacion de nuevo.")
    }
```

Puntos importantes:

1. **[`@Profile`](https://docs.spring.io/spring-boot/reference/features/profiles.html)`("!demo")`**: El `!` es una negacion. Esta clase esta activa en **todos** los perfiles **excepto** `demo`. Cuando se ejecuta con `--spring.profiles.active=dev` o `prod`, Spring crea este bean. Cuando se ejecuta con `demo`, no lo crea.

2. **`tokenRepository.findTopByOrderByUpdatedAtDesc()`**: Busca en la tabla `oauth_tokens` el token mas reciente (ordenado por `updated_at` descendente). Es un **derived query** de Spring Data JPA.

3. **Logica de [refresco de token](https://datatracker.ietf.org/doc/html/rfc6749#section-6) (5 minutos)**: `token.expiresAt.isBefore(Instant.now().plusSeconds(300))` verifica si el token expira dentro de los proximos 300 segundos (5 minutos). Si es asi, lo refresca proactivamente. Esto previene que expire justo en medio de una sincronizacion paginada.

4. **Metodo `refreshToken()`**: Hace una peticion POST al endpoint de tokens de Whoop con `grant_type=refresh_token`:

```kotlin
private fun refreshToken(token: OAuthTokenEntity): String {
    val refreshToken = token.refreshToken
        ?: throw WhoopApiException("No hay refresh token disponible. Realiza el flujo de autorizacion de nuevo.")

    val formData = LinkedMultiValueMap<String, String>()
    formData.add("grant_type", "refresh_token")
    formData.add("refresh_token", refreshToken)
    formData.add("client_id", clientId)
    formData.add("client_secret", clientSecret)

    val response = refreshClient.post()
        .uri(tokenUri)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(formData)
        .retrieve()
        .body(Map::class.java)
        ?: throw WhoopApiException("Respuesta vacia al refrescar token")

    val newAccessToken = response["access_token"] as? String
        ?: throw WhoopApiException("No se recibio access_token al refrescar")
    val newRefreshToken = response["refresh_token"] as? String
    val expiresIn = (response["expires_in"] as? Number)?.toLong() ?: 3600L
    val scope = response["scope"] as? String

    saveToken(newAccessToken, newRefreshToken, expiresIn, scope)
    log.info("Token refrescado exitosamente")
    return newAccessToken
}
```

- **`LinkedMultiValueMap`**: Es la estructura que Spring usa para codificar datos `application/x-www-form-urlencoded` (el formato que OAuth2 espera para refresh de tokens). Es como un `Map<String, List<String>>`.
- **`refreshClient`**: Se crea un `RestClient` separado (sin `baseUrl`) porque el endpoint de tokens de Whoop (`https://api.prod.whoop.com/oauth/oauth2/token`) ya se configura como URI completa via `@Value`.
- **`saveToken(...)`**: Guarda el nuevo access token y refresh token en base de datos para la proxima vez.

### DemoWhoopTokenManager: token estatico para demos

**Archivo**: `src/main/kotlin/com/example/whoopdavidapi/mock/DemoWhoopTokenManager.kt`

```kotlin
@Component
@Profile("demo")
@Primary
class DemoWhoopTokenManager : TokenManager {

    override fun getValidAccessToken(): String = "demo-access-token"
}
```

Solo 4 lineas de logica. Siempre devuelve el string `"demo-access-token"`. No hace ninguna llamada HTTP ni consulta base de datos.

### Como Spring elige cual inyectar

Cuando `WhoopApiClient` pide un `TokenManager`:

```kotlin
class WhoopApiClient(
    private val whoopRestClient: RestClient,
    private val tokenManager: TokenManager  // <-- cual implementacion?
)
```

Spring decide asi:

| Perfil activo | Beans disponibles | Bean inyectado |
|---|---|---|
| `dev` o `prod` | Solo `WhoopTokenManager` (porque `@Profile("!demo")`) | `WhoopTokenManager` |
| `demo` | `WhoopTokenManager` NO se crea. `DemoWhoopTokenManager` SI se crea | `DemoWhoopTokenManager` |
| `dev,demo` | Ambos existen (dev activa `!demo` = false? No: `!demo` se evalua y demo esta activo, asi que `WhoopTokenManager` NO se crea). Solo `DemoWhoopTokenManager` | `DemoWhoopTokenManager` |

La anotacion **[`@Primary`](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)** en `DemoWhoopTokenManager` es una precaucion adicional: si por alguna razon ambos beans existieran a la vez, Spring usaria el marcado como `@Primary`. Es un patron defensivo habitual.

---

## Resilience4j: resiliencia ante fallos externos

### Que es Resilience4j?

Resilience4j es una libreria de resiliencia para Java/Kotlin que implementa patrones para manejar **fallos de servicios externos**. Cuando tu aplicacion depende de una API externa (como Whoop), esa API puede:

- Caerse temporalmente
- Responder muy lento
- Tener limites de peticiones (rate limits)

Sin Resilience4j, un fallo en Whoop se propagaria directamente a tu aplicacion (excepciones, timeouts, bloqueos). Resilience4j anade capas de proteccion.

En este proyecto, las tres anotaciones se aplican a cada metodo publico de `WhoopApiClient`:

```kotlin
@CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
@Retry(name = "whoopApi")
@RateLimiter(name = "whoopApi")
fun getAllCycles(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
```

### CircuitBreaker

El patron **Circuit Breaker** (interruptor de circuito) funciona como un interruptor electrico: si detecta demasiados fallos, "abre" el circuito y deja de hacer peticiones durante un tiempo.

**Tres estados:**

```
                 fallo > umbral
    ┌──────────┐ ─────────────> ┌──────────┐
    │  CLOSED  │                │   OPEN   │
    │ (normal) │ <───────────── │ (parado) │
    └──────────┘   tras espera  └──────────┘
         ^                           │
         │    prueba peticiones       │
         │       ┌───────────┐       │
         └────── │ HALF_OPEN │ <─────┘
          exito  │ (probando)│  wait-duration
                 └───────────┘
```

| Estado | Comportamiento |
|---|---|
| **CLOSED** (cerrado) | Estado normal. Todas las peticiones pasan. Se monitorizan los fallos |
| **OPEN** (abierto) | Las peticiones **no se ejecutan**. Se devuelve el [fallback](https://resilience4j.readme.io/docs/circuitbreaker#fallback-methods) inmediatamente. Protege tanto a tu app como al servidor caido |
| **HALF_OPEN** (semi-abierto) | Se permiten unas pocas peticiones de prueba para ver si el servicio se recupero. Si tienen exito, vuelve a CLOSED. Si fallan, vuelve a OPEN |

**Configuracion en `application.yaml`:**

```yaml
resilience4j:
  circuitbreaker:
    instances:
      whoopApi:
        register-health-indicator: true
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
```

| Propiedad | Valor | Significado |
|---|---|---|
| `register-health-indicator` | `true` | Registra el estado del circuit breaker en el endpoint `/actuator/health` |
| [`sliding-window-size`](https://resilience4j.readme.io/docs/circuitbreaker#create-and-configure-a-circuitbreaker) | `10` | Se analizan las **ultimas 10** llamadas para calcular la tasa de fallo |
| `minimum-number-of-calls` | `5` | No se abre el circuito hasta que se hayan hecho al menos 5 llamadas (evita abrir con poca evidencia) |
| `failure-rate-threshold` | `50` | Si el 50% o mas de las ultimas 10 llamadas fallan, se abre el circuito |
| `wait-duration-in-open-state` | `30s` | Tras abrir, espera 30 segundos antes de probar de nuevo (HALF_OPEN) |
| `permitted-number-of-calls-in-half-open-state` | `3` | En estado HALF_OPEN, permite 3 peticiones de prueba |

**Ejemplo practico**: Si de las ultimas 10 llamadas a Whoop, 5 devuelven error 500, el circuit breaker se abre. Durante 30 segundos, todas las llamadas devuelven el fallback sin intentar contactar a Whoop. Despues de 30s, permite 3 peticiones de prueba. Si esas 3 funcionan, vuelve a CLOSED.

### Retry

El patron **Retry** reintenta peticiones que fallan. Es util para errores **transitorios** (un timeout puntual, un error 503 temporal).

```yaml
resilience4j:
  retry:
    instances:
      whoopApi:
        max-attempts: 3
        wait-duration: 2s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
```

| Propiedad | Valor | Significado |
|---|---|---|
| `max-attempts` | `3` | Intenta la peticion hasta 3 veces (1 original + 2 reintentos) |
| `wait-duration` | `2s` | Espera base entre reintentos |
| `enable-exponential-backoff` | `true` | Cada reintento espera mas que el anterior |
| `exponential-backoff-multiplier` | `2` | Factor de multiplicacion exponencial |

Con exponential backoff, las esperas son:

- 1er intento: falla -> espera **2s**
- 2do intento: falla -> espera **4s** (2s x 2)
- 3er intento: falla -> se propaga la excepcion al circuit breaker

**Por que exponential backoff?** Si el servidor esta sobrecargado, reintentar inmediatamente solo empeora la situacion. Esperando cada vez mas, se le da tiempo al servidor para recuperarse.

### RateLimiter

El **Rate Limiter** limita cuantas peticiones se pueden hacer en un periodo de tiempo. Protege contra exceder los limites de la API externa.

```yaml
resilience4j:
  ratelimiter:
    instances:
      whoopApi:
        limit-for-period: 90
        limit-refresh-period: 60s
        timeout-duration: 10s
```

| Propiedad | Valor | Significado |
|---|---|---|
| `limit-for-period` | `90` | Maximo 90 peticiones por periodo |
| `limit-refresh-period` | `60s` | El periodo se reinicia cada 60 segundos |
| `timeout-duration` | `10s` | Si se excede el limite, espera hasta 10s a que se libere un permiso. Si no se libera, lanza excepcion |

Esto significa: maximo **90 peticiones por minuto** a la Whoop API. Si la sincronizacion intenta hacer la peticion numero 91 dentro del mismo minuto, se bloquea hasta 10 segundos esperando que se reinicie el periodo. Si no lo consigue, falla.

### Metodos fallback

Cuando el circuit breaker esta abierto (o se agotan los reintentos), se ejecuta el **metodo fallback** en vez de lanzar una excepcion:

```kotlin
@CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
fun getAllCycles(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
    return getAllRecords("/developer/v1/cycle", start, end)
}

@Suppress("UNUSED_PARAMETER")
private fun fallbackGetAllRecords(start: Instant?, end: Instant?, ex: Throwable): List<Map<String, Any?>> {
    log.warn("Circuit breaker abierto para Whoop API: {}", ex.message)
    return emptyList()
}
```

Reglas de los metodos fallback:

1. **Mismos parametros** que el metodo original, **mas** un parametro `Throwable` al final (la excepcion que causo el fallo).
2. **Mismo tipo de retorno** que el metodo original (`List<Map<String, Any?>>`).
3. El nombre del fallback se especifica en la anotacion: `fallbackMethod = "fallbackGetAllRecords"`.

En este proyecto, los fallbacks devuelven listas vacias. El resultado es que si Whoop esta caido, la sincronizacion simplemente no importa datos nuevos (no falla con excepcion). Cuando Whoop vuelva, la siguiente sincronizacion funcionara normalmente.

Hay tambien un fallback especifico para el metodo `getUserProfile()`:

```kotlin
@Suppress("UNUSED_PARAMETER")
private fun fallbackGetProfile(ex: Throwable): Map<String, Any?> {
    log.warn("Circuit breaker abierto para perfil Whoop: {}", ex.message)
    return mapOf("error" to "Whoop API no disponible temporalmente")
}
```

### Por que se necesita AOP

Las anotaciones [`@CircuitBreaker`](https://resilience4j.readme.io/docs/circuitbreaker), [`@Retry`](https://resilience4j.readme.io/docs/retry) y [`@RateLimiter`](https://resilience4j.readme.io/docs/ratelimiter) funcionan mediante **[AOP (Aspect-Oriented Programming)](https://docs.spring.io/spring-framework/reference/core/aop.html)**. Esto requiere la dependencia:

**En `build.gradle.kts`:**

```kotlin
implementation("org.springframework.boot:spring-boot-starter-aspectj")
```

**Nota importante (Spring Boot 4)**: En Spring Boot 3.x era `spring-boot-starter-aop`. En **Spring Boot 4** se renombro a `spring-boot-starter-aspectj`.

Como funciona AOP con estas anotaciones:

1. Spring crea un **proxy** alrededor de `WhoopApiClient`.
2. Cuando algo llama a `getAllCycles()`, no llama al metodo directamente. Llama al proxy.
3. El proxy ejecuta la logica de Resilience4j **antes** y **despues** del metodo real:
   - **RateLimiter**: Verifica si hay permisos disponibles. Si no, espera o falla.
   - **Retry**: Ejecuta el metodo. Si falla, espera y reintenta.
   - **CircuitBreaker**: Verifica el estado del circuito. Si esta abierto, ejecuta el fallback sin llamar al metodo real.

```
Llamada a getAllCycles()
  └─> Proxy AOP
       └─> RateLimiter (controla velocidad)
            └─> Retry (reintenta si falla)
                 └─> CircuitBreaker (corta si muchos fallos)
                      └─> getAllRecords() (metodo real)
```

**Importante**: Por esta razon, las anotaciones solo funcionan en metodos **publicos** llamados desde **fuera** de la clase. Si un metodo privado llama a otro metodo de la misma clase, el proxy no intercepta la llamada. Es por eso que `getAllRecords()` es privado y las anotaciones estan en los metodos publicos `getAllCycles()`, `getAllRecoveries()`, etc.

---

## Documentacion oficial

- [Spring RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-restclient) - Documentacion oficial de RestClient
- [Resilience4j Documentation](https://resilience4j.readme.io/docs) - Documentacion oficial de Resilience4j
- [Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker) - Circuit breaker en detalle
- [Resilience4j Retry](https://resilience4j.readme.io/docs/retry) - Retry en detalle
- [Resilience4j RateLimiter](https://resilience4j.readme.io/docs/ratelimiter) - Rate limiter en detalle
- [Spring AOP](https://docs.spring.io/spring-framework/reference/core/aop.html) - Programacion orientada a aspectos en Spring
- [OAuth 2.0 Token Refresh (RFC 6749)](https://datatracker.ietf.org/doc/html/rfc6749#section-6) - Especificacion del flujo de refresh token
