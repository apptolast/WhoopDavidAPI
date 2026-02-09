# 09 - HTTP Client and Resilience4j

## Table of contents

- [RestClient - Spring's Modern HTTP Client](#restclient---springs-modern-http-client)
  - [What is it?](#what-is-it)
  - [Where is it configured?](#where-is-it-configured)
  - [Fluid API: how requests are made](#fluent-api-how-requests-are-made)
  - [URI builder with query parameters](#uri-builder-with-query-parameters)
  - [Timeouts with SimpleClientHttpRequestFactory](#timeouts-with-simpleclienthttprequestfactory)
- [WhoopApiClient: Paginated API consumption](#whoopapiclient---paginated-api-consumption)
  - [What problem does it solve?](#what-problem-does-it-solve)
  - [WhoopPageResponse: the page structure](#whooppageresponse-the-page-structure)
  - [getAllRecords(): the generic pagination method](#getallrecords-the-generic-pagination-method)
  - [Bearer token injection](#bearer-token-injection)
- [TokenManager: pattern Strategy](#tokenmanager-strategy-pattern)
  - [Interface](#the-interface)
  - [WhoopTokenManager: actual OAuth2 token management](#whooptokenmanager-actual-oauth2-token-management)
  - [DemoWhoopTokenManager: static token for demos](#demowhooptokenmanager-static-token-for-demos)
  - [How Spring chooses which one to inject](#how-spring-chooses-which-to-inject)
- [Resilience4j: resilience to external failures](#resilience4j-resilience-to-external-failures)
  - [What is Resilience4j?](#what-is-resilience4j)
  - [CircuitBreaker](#circuit-breaker)
  - [Retry](#retry)
  - [RateLimiter](#ratelimiter)
  - [Fallback methods](#fallback-methods)
  - [Why is AOP needed](#why-aop-is-needed)
- [Official documentation](#official-documentation)

---

## RestClient - Spring's modern HTTP client

### What is it?

[`RestClient`](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html) is the **synchronous** HTTP client introduced in **Spring Framework 6.1** (and therefore available from Spring Boot 3.2 onwards). It is the modern replacement for `RestTemplate`.

Key differences with `RestTemplate`:

| Aspect | `RestTemplate` (legacy) | `RestClient` (modern) |
|---|---|---|
| **Style** | Methods with many parameters | Fluent API (builder + method chaining) |
| **URI** | Strings with placeholders | Lambda with `UriBuilder` typed |
| **Maintenance** | In maintenance mode | Active development |
| **Reading** | Difficult to read with many params | Readable and expressive |

**Important**: `RestClient` is synchronous (blocking). For a non-blocking reactive client there is `WebClient`, but in this project we use WebMVC (blocking), so `RestClient` is the correct choice.

### Where is it configured?

**File**: `src/main/kotlin/com/example/whoopdavidapi/config/WhoopClientConfig.kt`

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

Step-by-step breakdown:

1. **`@Configuration`**: Marks the class as a source of Spring beans. Spring processes it at startup and registers the beans that define its methods `@Bean`.

2. **`@Value("\${app.whoop.base-url}")`**: Injects the value of property `app.whoop.base-url` from `application.yaml`. The default value is `https://api.prod.whoop.com` (defined in the base YAML), but profile `demo` overrides it to `http://localhost:8080/mock`.

3. **`@Value("\${app.whoop.connect-timeout:10}")`**: The `:10` after the colon is a **default**. If the property does not exist in any YAML, use `10` (seconds). The property is set to `application.yaml` with a value of `10`, but the default ensures that the application does not crash if it is accidentally deleted.

4. **`RestClient.builder()`**: Pattern Builder. It is configured step by step and at the end `.build()` creates the immutable instance.

5. **`.baseUrl(baseUrl)`**: All calls made by this `RestClient` will start from this base URL. If you then do `.uri("/developer/v1/cycle")`, the full URL will be `https://api.prod.whoop.com/developer/v1/cycle`.

6. **`.defaultHeader("Content-Type", "application/json")`**: Header that will be included in **all** requests from this client. This way you don't have to repeat it on every call.

7. **`.requestFactory(requestFactory)`**: Connects the HTTP connection factory with the configured timeouts (explained below).

### Fluent API: how requests are made

Once the `RestClient` is created, the requests are built with method chaining:

```kotlin
val response = whoopRestClient.get()           // (1) Metodo HTTP
    .uri("/developer/v1/cycle")                 // (2) Path (se suma a baseUrl)
    .header("Authorization", "Bearer $token")   // (3) Cabecera por peticion
    .retrieve()                                 // (4) Ejecutar la peticion
    .body(WhoopPageResponse::class.java)        // (5) Deserializar el JSON a este tipo
```

| Passed | Method | What are you doing |
|---|---|---|
| 1 | `.get()` | Indicates that it is an HTTP GET request. There are also `.post()`, `.put()`, `.delete()`, `.patch()` |
| 2 | `.uri(...)` | Specifies the path. It is concatenated with `baseUrl` of the builder |
| 3 | `.header(...)` | Add a header to this specific request (not all of them) |
| 4 | `.retrieve()` | Execute the HTTP request and get the response. If the server returns an error (4xx, 5xx), throw an exception |
| 5 | `.body(...)` | Deserializes the JSON response body to the indicated type. Jackson (Spring's JSON serializer) takes care of it automatically. |

### URI builder with query parameters

When the URL needs optional query parameters (such as `?limit=25&start=2024-01-01`), a **lambda with `UriBuilder`** is used:

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

Why a lambda and not a String?

- **Optional parameters**: With `?.let { ... }` the parameter is only added if it is not `null`. With a String you would have to build the query manually by concatenating.
- **Automatic encoding**: `UriBuilder` automatically encodes special characters (such as `+` in timestamps). This avoids subtle coding bugs.
- **Type-safe**: There is no risk of errors due to string concatenation.

Example of resulting URL:

```
https://api.prod.whoop.com/developer/v1/cycle?limit=25&start=2024-12-01T00:00:00Z&nextToken=abc123
```

### Timeouts with SimpleClientHttpRequestFactory

```kotlin
val requestFactory = SimpleClientHttpRequestFactory().apply {
    setConnectTimeout(java.time.Duration.ofSeconds(connectTimeoutSeconds))
    setReadTimeout(java.time.Duration.ofSeconds(readTimeoutSeconds))
}
```

Two different timeouts:

| Timeout | Default value | that protects |
|---|---|---|
| **Connect timeout** | 10 seconds | Maximum time to **establish** the TCP connection with the server. If Whoop is down, we don't wait forever |
| **Read timeout** | 30 seconds | Maximum time to **receive** the response once connected. If Whoop is slow, we cut off after 30s |

Without timeouts, an HTTP request to an unresponsive server would block thread **indefinitely**. This is critical because:

1. Spring WebMVC uses a limited thread pool (typically 200 threads by default in Tomcat).
2. If all requests are waiting, the pool is exhausted and the application stops responding.
3. The sync `@Scheduled` would also be locked, preventing future syncs.

The values ​​are set to `application.yaml`:

```yaml
app:
  whoop:
    connect-timeout: 10  # Tiempo max para establecer conexion
    read-timeout: 30     # Tiempo max para leer respuesta
```

---

## WhoopApiClient - Paginated API consumption

### What problem does it solve?

The Whoop API returns **paged** data: each request returns a maximum of 25 records and a `next_token` to request the next page. `WhoopApiClient` encapsulates this paging logic so that the rest of the application simply calls `getAllCycles()` and receives **all** records.

**File**: `src/main/kotlin/com/example/whoopdavidapi/client/WhoopApiClient.kt`

### WhoopPageResponse: the page structure

```kotlin
data class WhoopPageResponse(
    val records: List<Map<String, Any?>> = emptyList(),
    val next_token: String? = null
)
```

This internal `data class` models the JSON structure returned by the Whoop API:

```json
{
  "records": [ { "id": 1000, "strain": 14.5, ... }, ... ],
  "next_token": "abc123"
}
```

- **`records`**: List of records of the current page. `Map<String, Any?>` is used instead of a typed DTO because the structure varies between endpoints (cycles, recovery, sleep, workout). The mapping to entities is done later in `WhoopSyncService`.
- **`next_token`**: If it is `null`, there are no more pages. If it has value, another request must be made including this token.

### getAllRecords(): the generic pagination method

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

Step by step flow:

1. **`val allRecords = mutableListOf<>()`**: Accumulator list where the records of each page are added.
2. **`var nextToken: String? = null`**: In the first iteration there is no pagination token.
3. **`do { ... } while (nextToken != null)`**: Loop that continues requesting pages as long as `next_token` exists.
4. **`tokenManager.getValidAccessToken()`**: A valid token  **is requested in each iteration** . This is important because if there are many pages, the token could expire during pagination. The `TokenManager` is responsible for refreshing it if it is close to expiring.
5. **`.queryParam("limit", 25)`**: Requests the maximum number of records per page (25 is the Whoop API limit).
6. **`allRecords.addAll(response.records)`**: Accumulate the records of this page.
7. **`nextToken = response.next_token`**: Updates the paging token. If it is `null`, the loop ends.

Public methods are wrappers that call `getAllRecords` with the correct path:

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

Each public method has the same three Resilience4j annotations. The annotations must be in the **public** method because Resilience4j uses AOP proxies that only intercept external calls (more on this in the AOP section).

### Bearer token injection

```kotlin
.header("Authorization", "Bearer $token")
```

The authentication pattern with the Whoop API is **OAuth2 Bearer Token**: each request includes the header `Authorization` with the prefix `Bearer` followed by the access token. The token is obtained from `TokenManager`, which is an interface (not a concrete class). This is the **[patron Strategy](https://refactoring.guru/design-patterns/strategy)**.

---

## TokenManager: Strategy pattern

### The interface

**File**: `src/main/kotlin/com/example/whoopdavidapi/client/TokenManager.kt`

```kotlin
interface TokenManager {
    fun getValidAccessToken(): String
}
```

An interface with a single method. Defines **that** is needed (a valid access token) without specifying **how** is obtained. This allows you to have multiple implementations and for Spring to inject the correct one according to the active profile.

### WhoopTokenManager: actual OAuth2 token management

**File**: `src/main/kotlin/com/example/whoopdavidapi/client/WhoopTokenManager.kt`

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

Important points:

1. **[`@Profile`](https://docs.spring.io/spring-boot/reference/features/profiles.html)`("!demo")`**: The `!` is a negation. This class is active in **all** profiles **except** `demo`. When run with `--spring.profiles.active=dev` or `prod`, Spring creates this bean. When executed with `demo`, don't believe it.

2. **`tokenRepository.findTopByOrderByUpdatedAtDesc()`**: Search table `oauth_tokens` for the most recent token (sorted by descending `updated_at`). It is a **derived query** from Spring Data JPA.

3. **Logic of [token refresh](https://datatracker.ietf.org/doc/html/rfc6749#section-6) (5 minutes)**: `token.expiresAt.isBefore(Instant.now().plusSeconds(300))` checks if the token expires within the next 300 seconds (5 minutes). If so, refresh it proactively. This prevents it from expiring right in the middle of a paged sync.

4. **Method `refreshToken()`**: Makes a POST request to the Whoop token endpoint with `grant_type=refresh_token`:

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

- **`LinkedMultiValueMap`**: This is the structure that Spring uses to encode `application/x-www-form-urlencoded` data (the format that OAuth2 expects to refresh tokens). It's like a `Map<String, List<String>>`.
- **`refreshClient`**: A separate `RestClient` is created (without `baseUrl`) because the Whoop token endpoint (`https://api.prod.whoop.com/oauth/oauth2/token`) is already configured as a full URI via `@Value`.
- **`saveToken(...)`**: Save the new access token and refresh token in the database for the next time.

### DemoWhoopTokenManager: static token for demos

**File**: `src/main/kotlin/com/example/whoopdavidapi/mock/DemoWhoopTokenManager.kt`

```kotlin
@Component
@Profile("demo")
@Primary
class DemoWhoopTokenManager : TokenManager {

    override fun getValidAccessToken(): String = "demo-access-token"
}
```

Only 4 lines of logic. Always returns the string `"demo-access-token"`. It does not make any HTTP calls or database queries.

### How Spring chooses which to inject

When `WhoopApiClient` asks for a `TokenManager`:

```kotlin
class WhoopApiClient(
    private val whoopRestClient: RestClient,
    private val tokenManager: TokenManager  // <-- cual implementacion?
)
```

Spring decides like this:

| Active profile | Beans available | injected bean |
|---|---|---|
| `dev` or `prod` | Only `WhoopTokenManager` (because `@Profile("!demo")`) | `WhoopTokenManager` |
| `demo` | `WhoopTokenManager` is NOT created. `DemoWhoopTokenManager` IF created | `DemoWhoopTokenManager` |
| `dev,demo` | Both exist (dev active `!demo` = false? No: `!demo` is evaluated and demo is active, so `WhoopTokenManager` is NOT created). Only `DemoWhoopTokenManager` | `DemoWhoopTokenManager` |

The notation **[`@Primary`](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)** in `DemoWhoopTokenManager` is an additional precaution: if for some reason both beans existed at the same time, Spring would use the one marked as `@Primary`. It is a common defensive pattern.

---

## Resilience4j: resilience to external failures

### What is Resilience4j?

Resilience4j is a resilience library for Java/Kotlin that implements patterns to handle **external service failures**. When your application depends on an external API (like Whoop), that API can:

- Temporarily fall
- Respond very slow
- Have request limits (rate limits)

Without Resilience4j, a Whoop failure would propagate directly to your application (exceptions, timeouts, crashes). Resilience4j adds layers of protection.

In this project, all three annotations are applied to each public method of `WhoopApiClient`:

```kotlin
@CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
@Retry(name = "whoopApi")
@RateLimiter(name = "whoopApi")
fun getAllCycles(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
```

### Circuit Breaker

The **Circuit Breaker** pattern works like an electrical switch: if it detects too many faults, it "opens" the circuit and stops making requests for a while.

**Three states:**

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

| State | Behavior |
|---|---|
| **CLOSED** | Normal state. All requests pass. Failures are monitored |
| **OPEN** (open) | Requests  **are not executed**. The [fallback](https://resilience4j.readme.io/docs/circuitbreaker#fallback-methods) is returned immediately. Protect both your app and the downed server |
| **HALF_OPEN** (semi-open) | A few test requests are allowed to see if service has recovered. If they are successful, return to CLOSED. If they fail, go back to OPEN |

**Settings in `application.yaml`:**

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

| Property | Worth | Meaning |
|---|---|---|
| `register-health-indicator` | `true` | Records the status of the circuit breaker at endpoint `/actuator/health` |
| [`sliding-window-size`](https://resilience4j.readme.io/docs/circuitbreaker#create-and-configure-a-circuitbreaker) | `10` | The **last 10** calls are analyzed to calculate the failure rate |
| `minimum-number-of-calls` | `5` | The circuit is not opened until at least 5 calls have been made (avoid opening with little evidence) |
| `failure-rate-threshold` | `50` | If 50% or more of the last 10 calls fail, the circuit is opened |
| `wait-duration-in-open-state` | `30s` | After opening, wait 30 seconds before trying again (HALF_OPEN) |
| `permitted-number-of-calls-in-half-open-state` | `3` | In HALF_OPEN state, allows 3 test requests |

**Practical example**: If of the last 10 calls to Whoop, 5 return error 500, the circuit breaker opens. For 30 seconds, all calls fallback without attempting to contact Whoop. After 30s, allow 3 test requests. If those 3 work, go back to CLOSED.

### Retry

The **Retry** pattern retries requests that fail. It is useful for **transient** errors (a specific timeout, a temporary 503 error).

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

| Property | Worth | Meaning |
|---|---|---|
| `max-attempts` | `3` | Try the request up to 3 times (1 original + 2 retries) |
| `wait-duration` | `2s` | Base wait between retries |
| `enable-exponential-backoff` | `true` | Each retry waits longer than the previous one |
| `exponential-backoff-multiplier` | `2` | Exponential multiplication factor |

With exponential backoff, the waits are:

- 1st attempt: fail -> wait **2s**
- 2nd attempt: fail -> wait **4s** (2s x 2)
- 3rd attempt: failure -> the exception is propagated to the circuit breaker

**Why exponential backoff?** If the server is overloaded, retrying immediately only makes the situation worse. By waiting longer and longer, you give the server time to recover.

### RateLimiter

The **Rate Limiter** limits how many requests can be made in a period of time. Protects against exceeding external API limits.

```yaml
resilience4j:
  ratelimiter:
    instances:
      whoopApi:
        limit-for-period: 90
        limit-refresh-period: 60s
        timeout-duration: 10s
```

| Property | Worth | Meaning |
|---|---|---|
| `limit-for-period` | `90` | Maximum 90 requests per period |
| `limit-refresh-period` | `60s` | The period resets every 60 seconds |
| `timeout-duration` | `10s` | If the limit is exceeded, wait up to 10s for a permit to be released. If not released, throw exception |

This means: maximum **90 requests per minute** to the Whoop API. If the sync tries to make the 91st request within the same minute, it blocks for up to 10 seconds waiting for the period to restart. If you don't succeed, you fail.

### fallback methods

When the circuit breaker is open (or retries are exhausted), the **fallback method** is executed instead of throwing an exception:

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

Rules of fallback methods:

1. **Same parameters** as the original method, **plus** a `Throwable` parameter at the end (the exception that caused the failure).
2. **Same return type** as the original method (`List<Map<String, Any?>>`).
3. The name of the fallback is specified in the annotation: `fallbackMethod = "fallbackGetAllRecords"`.

In this project, fallbacks return empty lists. The result is that if Whoop is down, the sync simply does not import new data (it does not fail with exceptions). When Whoop comes back, the next sync will work normally.

There is also a specific fallback for the `getUserProfile()` method:

```kotlin
@Suppress("UNUSED_PARAMETER")
private fun fallbackGetProfile(ex: Throwable): Map<String, Any?> {
    log.warn("Circuit breaker abierto para perfil Whoop: {}", ex.message)
    return mapOf("error" to "Whoop API no disponible temporalmente")
}
```

### Why AOP is needed

The notations [`@CircuitBreaker`](https://resilience4j.readme.io/docs/circuitbreaker), [`@Retry`](https://resilience4j.readme.io/docs/retry), and [`@RateLimiter`](https://resilience4j.readme.io/docs/ratelimiter) operate via **[AOP (Aspect-Oriented Programming)](https://docs.spring.io/spring-framework/reference/core/aop.html)**. This requires the dependency:

**In `build.gradle.kts`:**

```kotlin
implementation("org.springframework.boot:spring-boot-starter-aspectj")
```

**Important note (Spring Boot 4)**: In Spring Boot 3.x it was `spring-boot-starter-aop`. In **Spring Boot 4** it was renamed to `spring-boot-starter-aspectj`.

How AOP works with these annotations:

1. Spring creates a **proxy** around `WhoopApiClient`.
2. When something calls `getAllCycles()`, it does not call the method directly. Call the proxy.
3. The proxy executes the Resilience4j logic **before** and **after** of the actual method:
   - **RateLimiter**: Check if permissions are available. If not, wait or fail.
   - **Retry**: Execute the method. If it fails, wait and retry.
   - **CircuitBreaker**: Check the circuit status. If it is open, it executes the fallback without calling the real method.

```
Llamada a getAllCycles()
  └─> Proxy AOP
       └─> RateLimiter (controla velocidad)
            └─> Retry (reintenta si falla)
                 └─> CircuitBreaker (corta si muchos fallos)
                      └─> getAllRecords() (metodo real)
```

**Important**: For this reason, annotations only work in **public** methods called from **outside** of the class. If a private method calls another method of the same class, the proxy does not intercept the call. That's why `getAllRecords()` is private and the annotations are in the public methods `getAllCycles()`, `getAllRecoveries()`, etc.

---

## Official documentation

- [Spring RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-restclient) - Official RestClient Documentation
- [Resilience4j Documentation](https://resilience4j.readme.io/docs) - Official Resilience4j Documentation
- [Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker) - Circuit breaker in detail
- [Resilience4j Retry](https://resilience4j.readme.io/docs/retry) – Retry in detail
- [Resilience4j RateLimiter](https://resilience4j.readme.io/docs/ratelimiter) - Rate limiter in detail
- [Spring AOP](https://docs.spring.io/spring-framework/reference/core/aop.html) - Aspect-oriented programming in Spring
- [OAuth 2.0 Token Refresh (RFC 6749)](https://datatracker.ietf.org/doc/html/rfc6749#section-6) - Refresh token flow specification
