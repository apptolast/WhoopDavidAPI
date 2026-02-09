# 09 - HTTP Client and Resilience4j

## Table of contents

- [RestClient: Spring’s modern HTTP client](#restclient-springs-modern-http-client)
  - [What is it?](#what-is-it)
  - [Where is it configured?](#where-is-it-configured)
  - [Fluent API: how requests are made](#fluent-api-how-requests-are-made)
  - [URI builder with query parameters](#uri-builder-with-query-parameters)
  - [Timeouts with SimpleClientHttpRequestFactory](#timeouts-with-simpleclienthttprequestfactory)
- [WhoopApiClient: consumption of the paginated API](#whoopapiclient-consumption-of-the-paginated-api)
  - [What problem does it solve?](#what-problem-does-it-solve)
  - [WhoopPageResponse: the page structure](#whooppageresponse-the-page-structure)
  - [getAllRecords(): the generic pagination method](#getallrecords-the-generic-pagination-method)
  - [Bearer token injection](#bearer-token-injection)
- [TokenManager: Strategy pattern](#tokenmanager-strategy-pattern)
  - [The interface](#the-interface)
  - [WhoopTokenManager: real OAuth2 token management](#whooptokenmanager-real-oauth2-token-management)
  - [DemoWhoopTokenManager: static token for demos](#demowhooptokenmanager-static-token-for-demos)
  - [How Spring chooses which one to inject](#how-does-spring-choose-which-one-to-inject)
- [Resilience4j: resilience against external failures](#resilience4j-resilience-in-the-face-of-external-failures)
  - [What is Resilience4j?](#what-is-resilience4j)
  - [CircuitBreaker](#circuitbreaker)
  - [Retry](#retry)
  - [RateLimiter](#ratelimiter)
  - [Fallback methods](#fallback-methods)
  - [Why is AOP needed?](#why-is-aop-needed)
- [Official documentation](#official-documentation)

---

## RestClient: Spring’s modern HTTP client

### What is it?

[`RestClient`](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html) is the **synchronous** HTTP client introduced in **Spring Framework 6.1** (and therefore available from Spring Boot 3.2 onwards). It is the modern replacement for `RestTemplate`.

Key differences with `RestTemplate`:

| Aspect | `RestTemplate` (legacy) | `RestClient` (modern) |
|---|---|---|
| **Style** | Methods with many parameters | Fluent API (builder + method chaining) |
| **URI** | Strings with placeholders | Lambda with typed `UriBuilder` |
| **Maintenance** | In maintenance mode | Active development |
| **Reading** | Hard to read with many params | Legible and expressive |

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

1. **`@Configuration`**: Marks the class as a source of Spring beans. Spring processes it at startup and registers the beans defined by its methods `@Bean`.

2. **`@Value("\${app.whoop.base-url}")`**: Injects the value of the `app.whoop.base-url` property from `application.yaml`. The default value is `https://api.prod.whoop.com` (defined in the base YAML), but the `demo` profile overrides it to `http://localhost:8080/mock`.

3. **`@Value("\${app.whoop.connect-timeout:10}")`**: The `:10` after the colon is a **default value**. If the property does not exist in any YAML, it uses `10` (seconds). The property is defined in `application.yaml` with value `10`, but the default ensures that the application does not fail if it is accidentally removed.

4. **`RestClient.builder()`**: Builder Pattern. It is configured step by step and at the end `.build()` creates the immutable instance.

5. **`.baseUrl(baseUrl)`**: All calls made by this `RestClient` will start from this base URL. If `.uri("/developer/v1/cycle")` is done afterwards, the full URL will be `https://api.prod.whoop.com/developer/v1/cycle`.

6. **`.defaultHeader("Content-Type", "application/json")`**: Header that will be included in **all** requests from this client. This way it doesn’t have to be repeated in each call.

7. **`.requestFactory(requestFactory)`**: Connect the HTTP connection factory with the configured timeouts (explained below).

### Fluent API: how requests are made

Once the `RestClient` has been created, requests are built using method chaining:

```kotlin
val response = whoopRestClient.get()           // (1) Metodo HTTP
    .uri("/developer/v1/cycle")                 // (2) Path (se suma a baseUrl)
    .header("Authorization", "Bearer $token")   // (3) Cabecera por peticion
    .retrieve()                                 // (4) Ejecutar la peticion
    .body(WhoopPageResponse::class.java)        // (5) Deserializar el JSON a este tipo
```

| Step | Method | What does it do? |
|---|---|---|
| 1 | `.get()` | Indicates that it is an HTTP GET request. There are also `.post()`, `.put()`, `.delete()`, `.patch()` |
| 2 | `.uri(...)` | Specify the path. It is concatenated with `baseUrl` from the builder |
| 3 | `.header(...)` | Add a header to this specific request (not to all of them) |
| 4 | `.retrieve()` | Executes the HTTP request and obtains the response. If the server returns an error (4xx, 5xx), it throws an exception |
| 5 | `.body(...)` | Deserializes the body of the JSON response into the specified type. Jackson (Spring’s JSON serializer) takes care of it automatically |

### URI builder with query parameters

When the URL needs optional query parameters (such as `?limit=25&start=2024-01-01`), a **lambda is used with `UriBuilder`**:

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
- **Automatic encoding**: `UriBuilder` automatically encodes special characters (such as `+` in timestamps). This avoids subtle encoding bugs.
- **Type-safe**: There is no risk of errors due to string concatenation.

Example of the resulting URL:

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

| Timeout | Default value | What does it protect? |
|---|---|---|
| **Connect timeout** | 10 seconds | Maximum time to **establish** the TCP connection with the server. If Whoop is down, we don’t wait forever |
| **Read timeout** | 30 seconds | Maximum time to **receive** the response once connected. If Whoop is slow, we cut off after 30s |

Without timeouts, an HTTP request to a server that does not respond would block the thread **indefinitely**. This is critical because:

1. Spring WebMVC uses a limited thread pool (normally 200 threads by default in Tomcat).
2. If all requests remain waiting, the pool gets exhausted and the application stops responding.
3. The `@Scheduled` synchronization would also be blocked, preventing future synchronizations.

The values are configured in `application.yaml`:

```yaml
app:
  whoop:
    connect-timeout: 10  # Tiempo max para establecer conexion
    read-timeout: 30     # Tiempo max para leer respuesta
```

---

## WhoopApiClient: consumption of the paginated API

### What problem does it solve?

The Whoop API returns **paginated** data: each request returns a maximum of 25 records and a `next_token` to request the next page. `WhoopApiClient` encapsulates this pagination logic so that the rest of the application simply calls `getAllCycles()` and receives **all** the records.

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

- **`records`**: List of records from the current page. `Map<String, Any?>` is used instead of a typed DTO because the structure varies between endpoints (cycles, recovery, sleep, workout). The mapping to entities is done later in `WhoopSyncService`.
- **`next_token`**: If it is `null`, there are no more pages. If it has a value, you must make another request including this token.

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

Step-by-step flow:

1. **`val allRecords = mutableListOf<>()`**: Accumulator list where the records from each page are added.
2. **`var nextToken: String? = null`**: In the first iteration there is no pagination token.
3. **`do { ... } while (nextToken != null)`**: Loop that keeps requesting pages as long as `next_token` exists.
4. **`tokenManager.getValidAccessToken()`**: A valid token is requested **in each iteration**. This is important because if there are many pages, the token could expire during pagination. The `TokenManager` is responsible for refreshing it if it is close to expiring.
5. **`.queryParam("limit", 25)`**: Requests the maximum number of records per page (25 is the limit of the Whoop API).
6. **`allRecords.addAll(response.records)`**: Accumulates the records on this page.
7. **`nextToken = response.next_token`**: Updates the pagination token. If it is `null`, the loop ends.

The public methods are wrappers that call `getAllRecords` with the correct path:

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

Each public method has the same three Resilience4j annotations. The annotations must be on the **public** method because Resilience4j uses AOP proxies that only intercept external calls (more about this in the AOP section).

### Bearer token injection

```kotlin
.header("Authorization", "Bearer $token")
```

The authentication pattern with the Whoop API is **OAuth2 Bearer Token**: each request includes the `Authorization` header with the `Bearer` prefix followed by the access token. The token is obtained from `TokenManager`, which is an interface (not a concrete class). This is the **[patron Strategy](https://refactoring.guru/design-patterns/strategy)**.

---

## TokenManager: Strategy pattern

### The interface

**File**: `src/main/kotlin/com/example/whoopdavidapi/client/TokenManager.kt`

```kotlin
interface TokenManager {
    fun getValidAccessToken(): String
}
```

An interface with a single method. It defines **what** is needed (a valid access token) without specifying **how** it is obtained. This allows having multiple implementations and for Spring to inject the correct one according to the active profile.

### WhoopTokenManager: real OAuth2 token management

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

1. **[`@Profile`](https://docs.spring.io/spring-boot/reference/features/profiles.html)`("!demo")`**: `!` is a negation. This class is active in **all** profiles **except** `demo`. When run with `--spring.profiles.active=dev` or `prod`, Spring creates this bean. When run with `demo`, it does not create it.

2. **`tokenRepository.findTopByOrderByUpdatedAtDesc()`**: Search in the `oauth_tokens` table for the most recent token (ordered by `updated_at` descending). It is a Spring Data JPA **derived query**.

3. **Logic for [token refresh](https://datatracker.ietf.org/doc/html/rfc6749#section-6) (5 minutes)**: `token.expiresAt.isBefore(Instant.now().plusSeconds(300))` checks whether the token expires within the next 300 seconds (5 minutes). If so, it proactively refreshes it. This prevents it from expiring right in the middle of a paginated sync.

4. **Method `refreshToken()`**: Makes a POST request to Whoop’s token endpoint with `grant_type=refresh_token`:

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

- **`LinkedMultiValueMap`**: It is the structure that Spring uses to encode `application/x-www-form-urlencoded` data (the format that OAuth2 expects for token refresh). It is like a `Map<String, List<String>>`.
- **`refreshClient`**: A separate `RestClient` is created (without `baseUrl`) because Whoop’s token endpoint (`https://api.prod.whoop.com/oauth/oauth2/token`) is already configured as a full URI via `@Value`.
- **`saveToken(...)`**: Save the new access token and refresh token in the database for next time.

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

Only 4 lines of logic. It always returns the string `"demo-access-token"`. It doesn’t make any HTTP calls or query the database.

### How does Spring choose which one to inject?

When `WhoopApiClient` asks for a `TokenManager`:

```kotlin
class WhoopApiClient(
    private val whoopRestClient: RestClient,
    private val tokenManager: TokenManager  // <-- cual implementacion?
)
```

Spring decides like this:

| Active profile | Available beans | Injected bean |
|---|---|---|
| `dev` or `prod` | Only `WhoopTokenManager` (because `@Profile("!demo")`) | `WhoopTokenManager` |
| `demo` | `WhoopTokenManager` is NOT created. `DemoWhoopTokenManager` IS created | `DemoWhoopTokenManager` |
| `dev,demo` | Both exist (dev active `!demo` = false? No: `!demo` is evaluated and demo is active, so `WhoopTokenManager` is NOT created). Only `DemoWhoopTokenManager` | `DemoWhoopTokenManager` |

The **[`@Primary`](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)** annotation in `DemoWhoopTokenManager` is an additional precaution: if for some reason both beans existed at the same time, Spring would use the one marked as `@Primary`. It’s a common defensive pattern.

---

## Resilience4j: resilience in the face of external failures

### What is Resilience4j?

Resilience4j is a resilience library for Java/Kotlin that implements patterns to handle **failures of external services**. When your application depends on an external API (like Whoop), that API can:

- Fall temporarily
- Respond very slowly
- Have request limits (rate limits)

Without Resilience4j, a failure in Whoop would propagate directly to your application (exceptions, timeouts, blocks). Resilience4j adds layers of protection.

In this project, the three annotations are applied to each public method of `WhoopApiClient`:

```kotlin
@CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
@Retry(name = "whoopApi")
@RateLimiter(name = "whoopApi")
fun getAllCycles(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
```

### CircuitBreaker

The **Circuit Breaker** pattern (circuit breaker) works like an electrical switch: if it detects too many failures, it "opens" the circuit and stops making requests for a while.

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

| Status | Behavior |
|---|---|
| **CLOSED** (closed) | Normal status. All requests pass. Failures are monitored |
| **OPEN** (open) | Requests **are not executed**. The [fallback](https://resilience4j.readme.io/docs/circuitbreaker#fallback-methods) is returned immediately. It protects both your app and the downed server |
| **HALF_OPEN** (half-open) | A few test requests are allowed to see if the service has recovered. If they succeed, it returns to CLOSED. If they fail, it returns to OPEN |

**Configuration in `application.yaml`:**

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

| Property | Value | Meaning |
|---|---|---|
| `register-health-indicator` | `true` | Record the circuit breaker status at the endpoint `/actuator/health` |
| [`sliding-window-size`](https://resilience4j.readme.io/docs/circuitbreaker#create-and-configure-a-circuitbreaker) | `10` | The **last 10** calls are analyzed to calculate the failure rate |
| `minimum-number-of-calls` | `5` | The circuit does not open until at least 5 calls have been made (prevents opening with little evidence) |
| `failure-rate-threshold` | `50` | If 50% or more of the last 10 calls fail, the circuit opens |
| `wait-duration-in-open-state` | `30s` | After opening, wait 30 seconds before trying again (HALF_OPEN) |
| `permitted-number-of-calls-in-half-open-state` | `3` | In the HALF_OPEN state, it allows 3 test requests |

**Practical example**: If, out of the last 10 calls to Whoop, 5 return a 500 error, the circuit breaker opens. For 30 seconds, all calls return the fallback without attempting to contact Whoop. After 30s, it allows 3 trial requests. If those 3 work, it goes back to CLOSED.

### Retry

The **Retry** pattern retries failed requests. It is useful for **transient** errors (an occasional timeout, a temporary 503 error).

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

| Property | Value | Meaning |
|---|---|---|
| `max-attempts` | `3` | Try the request up to 3 times (1 original + 2 retries) |
| `wait-duration` | `2s` | Base wait between retries |
| `enable-exponential-backoff` | `true` | Each retry waits longer than the previous one |
| `exponential-backoff-multiplier` | `2` | Exponential multiplication factor |

With exponential backoff, the waits are:

- 1st attempt: fails -> wait **2s**
- 2nd attempt: fails -> waits **4s** (2s x 2)
- 3rd attempt: fails -> the exception is propagated to the circuit breaker

**Why exponential backoff?** If the server is overloaded, retrying immediately only makes the situation worse. By waiting longer each time, you give the server time to recover.

### RateLimiter

The **Rate Limiter** limits how many requests can be made in a period of time. It protects against exceeding the limits of the external API.

```yaml
resilience4j:
  ratelimiter:
    instances:
      whoopApi:
        limit-for-period: 90
        limit-refresh-period: 60s
        timeout-duration: 10s
```

| Property | Value | Meaning |
|---|---|---|
| `limit-for-period` | `90` | Maximum 90 requests per period |
| `limit-refresh-period` | `60s` | The period resets every 60 seconds |
| `timeout-duration` | `10s` | If the limit is exceeded, wait up to 10s for a permit to be released. If it isn’t released, throw an exception. |

This means: a maximum of **90 requests per minute** to the Whoop API. If the sync tries to make request number 91 within the same minute, it is blocked for up to 10 seconds waiting for the period to reset. If it doesn’t manage to, it fails.

### Fallback methods

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

Rules for fallback methods:

1. **Same parameters** as the original method, **plus** one `Throwable` parameter at the end (the exception that caused the failure).
2. **Same return type** as the original method (`List<Map<String, Any?>>`).
3. The name of the fallback is specified in the annotation: `fallbackMethod = "fallbackGetAllRecords"`.

In this project, the fallbacks return empty lists. The result is that if Whoop is down, the synchronization simply doesn’t import new data (it doesn’t fail with an exception). When Whoop comes back, the next synchronization will work normally.

There is also a specific fallback for the method `getUserProfile()`:

```kotlin
@Suppress("UNUSED_PARAMETER")
private fun fallbackGetProfile(ex: Throwable): Map<String, Any?> {
    log.warn("Circuit breaker abierto para perfil Whoop: {}", ex.message)
    return mapOf("error" to "Whoop API no disponible temporalmente")
}
```

### Why is AOP needed?

Annotations [`@CircuitBreaker`](https://resilience4j.readme.io/docs/circuitbreaker), [`@Retry`](https://resilience4j.readme.io/docs/retry), and [`@RateLimiter`](https://resilience4j.readme.io/docs/ratelimiter) work via **[AOP (Aspect-Oriented Programming)](https://docs.spring.io/spring-framework/reference/core/aop.html)**. This requires the dependency:

**In `build.gradle.kts`:**

```kotlin
implementation("org.springframework.boot:spring-boot-starter-aspectj")
```

**Important note (Spring Boot 4)**: In Spring Boot 3.x it was `spring-boot-starter-aop`. In **Spring Boot 4** it was renamed to `spring-boot-starter-aspectj`.

How does AOP work with these annotations:

1. Spring creates a **proxy** around `WhoopApiClient`.
2. When something calls `getAllCycles()`, it doesn’t call the method directly. It calls the proxy.
3. The proxy executes the Resilience4j logic **before** and **after** the real method:
   - **RateLimiter**: Checks whether there are permits available. If not, waits or fails.
   - **Retry**: Executes the method. If it fails, wait and retry.
   - **CircuitBreaker**: Checks the circuit state. If it is open, it executes the fallback without calling the real method.

```
Llamada a getAllCycles()
  └─> Proxy AOP
       └─> RateLimiter (controla velocidad)
            └─> Retry (reintenta si falla)
                 └─> CircuitBreaker (corta si muchos fallos)
                      └─> getAllRecords() (metodo real)
```

**Important**: For this reason, annotations only work on **public** methods called from **outside** the class. If a private method calls another method in the same class, the proxy does not intercept the call. That is why `getAllRecords()` is private and the annotations are on the public methods `getAllCycles()`, `getAllRecoveries()`, etc.

---

## Official documentation

- [Spring RestClient](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html#rest-restclient) - Official RestClient documentation
- [Resilience4j Documentation](https://resilience4j.readme.io/docs) - Official Resilience4j documentation
- [Resilience4j CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker) - Circuit breaker in detail
- [Resilience4j Retry](https://resilience4j.readme.io/docs/retry) - Retry in detail
- [Resilience4j RateLimiter](https://resilience4j.readme.io/docs/ratelimiter) - Rate limiter in detail
- [Spring AOP](https://docs.spring.io/spring-framework/reference/core/aop.html) - Aspect-oriented programming in Spring
- [OAuth 2.0 Token Refresh (RFC 6749)](https://datatracker.ietf.org/doc/html/rfc6749#section-6) - Specification of the refresh token flow
