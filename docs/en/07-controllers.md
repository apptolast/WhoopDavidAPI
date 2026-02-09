# 07 - REST Controllers (`@RestController`)

## What is a REST controller?

A REST controller is the **entry point** to the application. It receives HTTP requests from the client (in this case, Power BI), validates them, delegates the logic to the corresponding service, and returns the response in JSON format.

In Spring, a REST controller is created with the annotation [`@RestController`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html), which is the combination of two annotations:

```
@RestController = @Controller + @ResponseBody
```

- `@Controller`: registers the class as a web component that handles HTTP requests.
- `@ResponseBody`: indicates that the value returned by each method is serialized directly into the body of the HTTP response (by default as JSON), instead of looking for an HTML view/template.

---

## Where is it used in this project?

| Controller | File | Endpoint | Pattern |
|---|---|---|---|
| `CycleController` | `src/main/kotlin/com/example/whoopdavidapi/controller/CycleController.kt` | `GET /api/v1/cycles` | Local data (DB) |
| `RecoveryController` | `src/main/kotlin/com/example/whoopdavidapi/controller/RecoveryController.kt` | `GET /api/v1/recovery` | Local data (DB) |
| `SleepController` | `src/main/kotlin/com/example/whoopdavidapi/controller/SleepController.kt` | `GET /api/v1/sleep` | Local data (DB) |
| `WorkoutController` | `src/main/kotlin/com/example/whoopdavidapi/controller/WorkoutController.kt` | `GET /api/v1/workouts` | Local data (DB) |
| `ProfileController` | `src/main/kotlin/com/example/whoopdavidapi/controller/ProfileController.kt` | `GET /api/v1/profile` | Direct call to the Whoop API |

Additionally, the project has a global exception handler:

| Class | File |
|---|---|
| `GlobalExceptionHandler` | `src/main/kotlin/com/example/whoopdavidapi/exception/GlobalExceptionHandler.kt` |
| `WhoopApiException` | `src/main/kotlin/com/example/whoopdavidapi/exception/WhoopApiException.kt` |
| `ErrorResponse` | `src/main/kotlin/com/example/whoopdavidapi/exception/GlobalExceptionHandler.kt` (declared in the same file) |

---

## Why REST controllers?

1. **Standard HTTP interface**: Power BI consumes data via GET requests with query parameters. REST controllers expose exactly that.
2. **API versioning**: the `/api/v1` prefix makes it possible to create new versions (`/api/v2`) in the future without breaking existing clients.
3. **Separation of responsibilities**: the controller is only responsible for the HTTP layer (validating parameters, returning status codes). Business logic lives in the services.
4. **Centralized error handling**: [`@RestControllerAdvice`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html) allows capturing all exceptions in a single place, returning consistent JSON responses.

---

## Explained code

### 1. `@RestController` and `@RequestMapping`

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/controller/CycleController.kt

@RestController
@RequestMapping("/api/v1")
class CycleController(private val cycleService: CycleService) {
```

- `@RestController`: marks the class as a REST controller. All methods automatically return JSON.
- [`@RequestMapping("/api/v1")`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html): sets the **base path** for all endpoints in this class. Each `@GetMapping` within the class adds its route to this prefix.
- Constructor injection: `CycleService` is injected automatically (just like in services, it doesn’t need `@Autowired`).

### 2. `@GetMapping` and `@RequestParam`

```kotlin
@GetMapping("/cycles")
fun getCycles(
    @RequestParam(required = false) from: Instant?,
    @RequestParam(required = false) to: Instant?,
    @RequestParam(defaultValue = "1") page: Int,
    @RequestParam(defaultValue = "100") pageSize: Int
): ResponseEntity<PaginatedResponse<CycleDTO>> {
```

The final route for this endpoint is `/api/v1/cycles` (the sum of `@RequestMapping("/api/v1")` + [`@GetMapping("/cycles")`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html)).

[`@RequestParam`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestparam.html) maps URL parameters (query string) to method parameters:

| Parameter | Configuration | Example in URL | Behavior |
|---|---|---|---|
| `from` | `required = false`, type `Instant?` | `?from=2024-01-01T00:00:00Z` | Optional. If it is not sent, it is `null`. |
| `to` | `required = false`, type `Instant?` | `?to=2024-12-31T23:59:59Z` | Optional. If it is not sent, it is `null`. |
| `page` | `defaultValue = "1"` | `?page=3` | It has a default value. If it is not sent, it is `1`. |
| `pageSize` | `defaultValue = "100"` | `?pageSize=50` | It has a default value. If it is not sent, it is `100`. |

**Automatic conversion of `Instant`**: Spring Boot automatically converts ISO-8601 strings (such as `2024-01-01T00:00:00Z`) to `java.time.Instant` objects. No additional annotation or custom formatter is needed. Spring uses the converter registered by default for `java.time` types.

### 3. Validation with `require()` (Kotlin preconditions)

```kotlin
require(page >= 1) { "page debe ser >= 1" }
require(pageSize in 1..1000) { "pageSize debe estar entre 1 y 1000" }
```

`require()` is a function from Kotlin’s standard library for validating **preconditions**. If the condition is `false`, it throws a `IllegalArgumentException` with the message provided in the lambda block.

| Kotlin function | Launch | Typical use |
|---|---|---|
| `require()` | `IllegalArgumentException` | Validate input arguments |
| `requireNotNull()` | `IllegalArgumentException` | Validate that a value is not null |
| `check()` | `IllegalStateException` | Validate the object's status |
| `error()` | `IllegalStateException` | Fail unconditionally with message |

In this case:

- `page >= 1`: the page must be at least 1 (Spring Data uses 0-based indexing internally, but the user sends 1-based).
- `pageSize in 1..1000`: the page size must be between 1 and 1000. This prevents requests that could return too many results and overload the server or the database.

The `IllegalArgumentException` thrown by `require()` is caught by `GlobalExceptionHandler` (see section 7) and converted into an HTTP 400 response with a descriptive message.

### 4. `ResponseEntity<T>`: HTTP response control

```kotlin
return ResponseEntity.ok(cycleService.getCycles(from, to, page, pageSize))
```

[`ResponseEntity<T>`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/responseentity.html) is a Spring wrapper that allows you to control the complete HTTP response: the **body** (body), the **[codigo de estado](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Status)** (status code), and the **headers**.

`ResponseEntity.ok(body)` is a shortcut for `ResponseEntity.status(200).body(body)`. Common static methods:

| Metodo | Status Code | Use |
|---|---|---|
| `ResponseEntity.ok(body)` | 200 OK | Successful response with body |
| `ResponseEntity.badRequest().body(body)` | 400 Bad Request | Validation error |
| `ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body)` | 429 Too Many Requests | Rate limiting |
| `ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)` | 500 Internal Server Error | Unexpected error |

**Alternative without `ResponseEntity`**: you could return `PaginatedResponse<CycleDTO>` directly (without wrapping it in `ResponseEntity`). Spring will automatically assume HTTP 200. But `ResponseEntity` is preferable because it allows returning status codes other than 200 in other methods.

### 5. JSON Serialization: Spring Boot 4 + Jackson 3

Spring Boot 4 includes **Jackson 3** as the JSON serializer. An important difference compared to previous versions:

- **Jackson 3 serializes dates (`Instant`, `LocalDate`, etc.) as ISO-8601 by default**. It is no longer necessary to configure `spring.jackson.serialization.write-dates-as-timestamps=false` as was done in Spring Boot 2/3.
- The `Instant` are serialized as `"2024-01-15T08:30:00Z"` automatically.

Example JSON response for `GET /api/v1/cycles?page=1&pageSize=2`:

```json
{
  "data": [
    {
      "id": 12345,
      "start": "2024-06-15T04:00:00Z",
      "end": "2024-06-16T04:00:00Z",
      "strain": 14.7,
      "averageHeartRate": 72,
      "maxHeartRate": 185
    },
    {
      "id": 12344,
      "start": "2024-06-14T04:00:00Z",
      "end": "2024-06-15T04:00:00Z",
      "strain": 8.3,
      "averageHeartRate": 65,
      "maxHeartRate": 142
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 2,
    "totalCount": 542,
    "hasMore": true
  }
}
```

### 6. ProfileController: a different pattern

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/controller/ProfileController.kt

@RestController
@RequestMapping("/api/v1")
class ProfileController(private val whoopApiClient: WhoopApiClient) {

    @GetMapping("/profile")
    fun getProfile(): ResponseEntity<Map<String, Any?>> {
        val profile = whoopApiClient.getUserProfile()
        return ResponseEntity.ok(profile)
    }
}
```

This controller is different from the other 4 because:

1. **It has no intermediate service**: injects `WhoopApiClient` directly instead of a service.
2. **Does not query the database**: the user profile is not stored locally; it is obtained in real time from the Whoop API.
3. **It has no pagination or filters**: the profile is a single object, not a collection.
4. **The return type is `Map<String, Any?>`**: there is no typed DTO. The Whoop API response is forwarded as-is.

This pattern is suitable because the profile rarely changes and is a simple piece of data that does not require local persistence or transformations.

### 7. `@RestControllerAdvice` and global exception handling

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/exception/GlobalExceptionHandler.kt

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)
```

`@RestControllerAdvice` is the combination of `@ControllerAdvice` + `@ResponseBody`. It allows defining exception handlers that apply to **all** the application's controllers. Without this mechanism, each controller would have to handle its own exceptions with try-catch blocks, duplicating code.

#### WhoopApiException Handler

```kotlin
@ExceptionHandler(WhoopApiException::class)
fun handleWhoopApiException(ex: WhoopApiException): ResponseEntity<ErrorResponse> {
    log.error("Whoop API error: {}", ex.message, ex)
    val status = when (ex.statusCode) {
        429 -> HttpStatus.TOO_MANY_REQUESTS
        401, 403 -> HttpStatus.BAD_GATEWAY
        else -> HttpStatus.BAD_GATEWAY
    }
    return ResponseEntity.status(status).body(
        ErrorResponse(
            error = status.reasonPhrase,
            message = ex.message ?: "Error comunicandose con Whoop API",
            status = status.value()
        )
    )
}
```

[`@ExceptionHandler(WhoopApiException::class)`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html) indicates: "when any controller throws a `WhoopApiException`, execute this method".

The status code mapping logic is:

- **429 from Whoop** is returned as **429 to the client**: the external API’s rate limiting is propagated to the client. This way Power BI knows it must wait before retrying.
- **Whoop 401/403** is returned as **502 Bad Gateway**: an authentication error with the external API is a server problem (this BFF), not the client. 502 indicates that the server received an invalid response from an upstream.
- **Any other error** is also mapped to **502**: any communication failure with the Whoop API is treated as a gateway error.

#### IllegalArgumentException Handler

```kotlin
@ExceptionHandler(IllegalArgumentException::class)
fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
    log.warn("Bad request: {}", ex.message)
    return ResponseEntity.badRequest().body(
        ErrorResponse(
            error = "Bad Request",
            message = ex.message ?: "Parametro invalido",
            status = 400
        )
    )
}
```

This handler catches the exceptions thrown by `require()` in the controllers (for example, `page < 1` or `pageSize > 1000`). It returns them as HTTP 400 Bad Request with a descriptive message.

It logs as `warn` (not `error`) because a 400 is the client's fault, not a server failure.

#### Generic handler (catch-all)

```kotlin
@ExceptionHandler(Exception::class)
fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
    log.error("Unexpected error", ex)
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
        ErrorResponse(
            error = "Internal Server Error",
            message = "Error interno del servidor",
            status = 500
        )
    )
}
```

This is the **last resort**: any exception that is neither `WhoopApiException` nor `IllegalArgumentException` is caught here. Important points:

- It logs in as `error` with the full exception (stack trace) for debugging.
- The message to the client is **generic** (`"Error interno del servidor"`), it does not expose internal details. This is a security practice: never reveal stack traces, class names, or internal paths to the client.

### 8. The exception hierarchy

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/exception/WhoopApiException.kt

class WhoopApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)
```

`WhoopApiException` extends `RuntimeException` (unchecked exception). It is a **domain exception** that represents any communication failure with the Whoop API.

Properties:

- `message`: error description (inherited from `RuntimeException`).
- `statusCode`: the HTTP code returned by the Whoop API (429, 401, 500...). It is nullable because some errors (timeout, network failure) do not have an HTTP code.
- `cause`: the original exception that caused the error (for chaining exceptions).

The error response DTO:

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/exception/GlobalExceptionHandler.kt

data class ErrorResponse(
    val error: String,
    val message: String,
    val status: Int
)
```

All error responses have the same JSON structure:

```json
{
  "error": "Bad Request",
  "message": "page debe ser >= 1",
  "status": 400
}
```

### 9. Evaluation order of `@ExceptionHandler`

When a controller throws an exception, Spring looks for the most **specific** handler first:

```
Excepcion lanzada
     |
     v
Es WhoopApiException? ----Si----> handleWhoopApiException() -> 429/502
     |
    No
     |
     v
Es IllegalArgumentException? ----Si----> handleIllegalArgument() -> 400
     |
    No
     |
     v
Es Exception? ----Si----> handleGenericException() -> 500
```

This works because `WhoopApiException` and `IllegalArgumentException` are subclasses of `Exception`. Spring evaluates the handlers from most specific to most generic.

### 10. The 4 data controllers follow the same pattern

`CycleController`, `RecoveryController`, `SleepController`, and `WorkoutController` are structurally identical. The only difference is the service they invoke and the endpoint they expose:

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/controller/RecoveryController.kt

@RestController
@RequestMapping("/api/v1")
class RecoveryController(private val recoveryService: RecoveryService) {

    @GetMapping("/recovery")
    fun getRecoveries(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "100") pageSize: Int
    ): ResponseEntity<PaginatedResponse<RecoveryDTO>> {
        require(page >= 1) { "page debe ser >= 1" }
        require(pageSize in 1..1000) { "pageSize debe estar entre 1 y 1000" }
        return ResponseEntity.ok(recoveryService.getRecoveries(from, to, page, pageSize))
    }
}
```

Summary of the 5 API endpoints:

| Endpoint | HTTP method | Parameters | Authentication |
|---|---|---|---|
| `/api/v1/cycles` | GET | `from`, `to`, `page`, `pageSize` | Basic Auth |
| `/api/v1/recovery` | GET | `from`, `to`, `page`, `pageSize` | Basic Auth |
| `/api/v1/sleep` | GET | `from`, `to`, `page`, `pageSize` | Basic Auth |
| `/api/v1/workouts` | GET | `from`, `to`, `page`, `pageSize` | Basic Auth |
| `/api/v1/profile` | GET | none | Basic Auth |

All of them are under `/api/**`, so the `SecurityConfig` security chain `@Order(1)` applies Basic Auth to them (see `docs/08-seguridad.md`).

---

## Complete error flow

Example: the client sends `GET /api/v1/cycles?page=0`.

```
1. CycleController.getCycles() recibe page=0
2. require(page >= 1) falla -> lanza IllegalArgumentException("page debe ser >= 1")
3. Spring intercepta la excepcion y busca un @ExceptionHandler compatible
4. GlobalExceptionHandler.handleIllegalArgument() captura la excepcion
5. Devuelve ResponseEntity con status 400 y cuerpo:
   {
     "error": "Bad Request",
     "message": "page debe ser >= 1",
     "status": 400
   }
```

---

## Official documentation

- **`@RestController`**: [Spring Framework - @RestController](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html)
- **`@RequestMapping`**: [Spring Framework - Request Mapping](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html)
- **`@RequestParam`**: [Spring Framework - @RequestParam](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestparam.html)
- **`ResponseEntity`**: [Spring Framework - ResponseEntity](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/responseentity.html)
- **`@RestControllerAdvice`**: [Spring Framework - Controller Advice](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html)
- **`@ExceptionHandler`**: [Spring Framework - Exceptions](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html)
- **Kotlin `require()` and preconditions**: [Kotlin Standard Library - Preconditions](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/require.html)
- **Jackson 3 and ISO-8601 dates**: [Spring Boot Reference - JSON](https://docs.spring.io/spring-boot/reference/features/json.html)
