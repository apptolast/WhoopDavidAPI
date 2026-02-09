# 07 - Controladores REST (`@RestController`)

## Que es un controlador REST?

Un controlador REST es la **puerta de entrada** a la aplicacion. Recibe peticiones HTTP del cliente (en este caso, Power BI), las valida, delega la logica al servicio correspondiente y devuelve la respuesta en formato JSON.

En Spring, un controlador REST se crea con la anotacion [`@RestController`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html), que es la combinacion de dos anotaciones:

```
@RestController = @Controller + @ResponseBody
```

- `@Controller`: registra la clase como un componente web que maneja peticiones HTTP.
- `@ResponseBody`: indica que el valor devuelto por cada metodo se serializa directamente al cuerpo de la respuesta HTTP (por defecto como JSON), en lugar de buscar una vista/template HTML.

---

## Donde se usa en este proyecto?

| Controlador | Archivo | Endpoint | Patron |
|---|---|---|---|
| `CycleController` | `src/main/kotlin/com/example/whoopdavidapi/controller/CycleController.kt` | `GET /api/v1/cycles` | Datos locales (BD) |
| `RecoveryController` | `src/main/kotlin/com/example/whoopdavidapi/controller/RecoveryController.kt` | `GET /api/v1/recovery` | Datos locales (BD) |
| `SleepController` | `src/main/kotlin/com/example/whoopdavidapi/controller/SleepController.kt` | `GET /api/v1/sleep` | Datos locales (BD) |
| `WorkoutController` | `src/main/kotlin/com/example/whoopdavidapi/controller/WorkoutController.kt` | `GET /api/v1/workouts` | Datos locales (BD) |
| `ProfileController` | `src/main/kotlin/com/example/whoopdavidapi/controller/ProfileController.kt` | `GET /api/v1/profile` | Llamada directa a Whoop API |

Ademas, el proyecto tiene un manejador global de excepciones:

| Clase | Archivo |
|---|---|
| `GlobalExceptionHandler` | `src/main/kotlin/com/example/whoopdavidapi/exception/GlobalExceptionHandler.kt` |
| `WhoopApiException` | `src/main/kotlin/com/example/whoopdavidapi/exception/WhoopApiException.kt` |
| `ErrorResponse` | `src/main/kotlin/com/example/whoopdavidapi/exception/GlobalExceptionHandler.kt` (declarado en el mismo archivo) |

---

## Por que controladores REST?

1. **Interfaz HTTP estandar**: Power BI consume datos via peticiones GET con parametros de query. Los controladores REST exponen exactamente eso.
2. **Versionado de API**: el prefijo `/api/v1` permite crear nuevas versiones (`/api/v2`) en el futuro sin romper clientes existentes.
3. **Separacion de responsabilidades**: el controlador solo se encarga de la capa HTTP (validar parametros, devolver codigos de estado). La logica de negocio vive en los servicios.
4. **Manejo centralizado de errores**: [`@RestControllerAdvice`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html) permite capturar todas las excepciones en un unico lugar, devolviendo respuestas JSON consistentes.

---

## Codigo explicado

### 1. `@RestController` y `@RequestMapping`

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/controller/CycleController.kt

@RestController
@RequestMapping("/api/v1")
class CycleController(private val cycleService: CycleService) {
```

- `@RestController`: marca la clase como controlador REST. Todos los metodos devuelven JSON automaticamente.
- [`@RequestMapping("/api/v1")`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html): establece el **path base** para todos los endpoints de esta clase. Cada `@GetMapping` dentro de la clase aniade su ruta a este prefijo.
- Constructor injection: `CycleService` se inyecta automaticamente (igual que en los servicios, no necesita `@Autowired`).

### 2. `@GetMapping` y `@RequestParam`

```kotlin
@GetMapping("/cycles")
fun getCycles(
    @RequestParam(required = false) from: Instant?,
    @RequestParam(required = false) to: Instant?,
    @RequestParam(defaultValue = "1") page: Int,
    @RequestParam(defaultValue = "100") pageSize: Int
): ResponseEntity<PaginatedResponse<CycleDTO>> {
```

La ruta final de este endpoint es `/api/v1/cycles` (la suma de `@RequestMapping("/api/v1")` + [`@GetMapping("/cycles")`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html)).

[`@RequestParam`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestparam.html) mapea parametros de la URL (query string) a parametros del metodo:

| Parametro | Configuracion | Ejemplo en URL | Comportamiento |
|---|---|---|---|
| `from` | `required = false`, tipo `Instant?` | `?from=2024-01-01T00:00:00Z` | Opcional. Si no se envia, es `null`. |
| `to` | `required = false`, tipo `Instant?` | `?to=2024-12-31T23:59:59Z` | Opcional. Si no se envia, es `null`. |
| `page` | `defaultValue = "1"` | `?page=3` | Tiene valor por defecto. Si no se envia, es `1`. |
| `pageSize` | `defaultValue = "100"` | `?pageSize=50` | Tiene valor por defecto. Si no se envia, es `100`. |

**Conversion automatica de `Instant`**: Spring Boot convierte automaticamente strings ISO-8601 (como `2024-01-01T00:00:00Z`) a objetos `java.time.Instant`. No se necesita ninguna anotacion adicional ni un formatter custom. Spring usa el converter registrado por defecto para tipos `java.time`.

### 3. Validacion con `require()` (precondiciones de Kotlin)

```kotlin
require(page >= 1) { "page debe ser >= 1" }
require(pageSize in 1..1000) { "pageSize debe estar entre 1 y 1000" }
```

`require()` es una funcion de la libreria estandar de Kotlin para validar **precondiciones**. Si la condicion es `false`, lanza una `IllegalArgumentException` con el mensaje proporcionado en el bloque lambda.

| Funcion Kotlin | Lanza | Uso tipico |
|---|---|---|
| `require()` | `IllegalArgumentException` | Validar argumentos de entrada |
| `requireNotNull()` | `IllegalArgumentException` | Validar que un valor no sea null |
| `check()` | `IllegalStateException` | Validar estado del objeto |
| `error()` | `IllegalStateException` | Fallar incondicionalmente con mensaje |

En este caso:

- `page >= 1`: la pagina debe ser al menos 1 (Spring Data usa base 0 internamente, pero el usuario envia base 1).
- `pageSize in 1..1000`: el tamano de pagina debe estar entre 1 y 1000. Esto previene peticiones que podrian devolver demasiados resultados y sobrecargar el servidor o la base de datos.

La `IllegalArgumentException` lanzada por `require()` es capturada por el `GlobalExceptionHandler` (ver seccion 7) y convertida en una respuesta HTTP 400 con un mensaje descriptivo.

### 4. `ResponseEntity<T>`: control del HTTP response

```kotlin
return ResponseEntity.ok(cycleService.getCycles(from, to, page, pageSize))
```

[`ResponseEntity<T>`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/responseentity.html) es un wrapper de Spring que permite controlar la respuesta HTTP completa: el **cuerpo** (body), el **[codigo de estado](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Status)** (status code) y los **headers**.

`ResponseEntity.ok(body)` es un atajo para `ResponseEntity.status(200).body(body)`. Metodos estaticos comunes:

| Metodo | Status Code | Uso |
|---|---|---|
| `ResponseEntity.ok(body)` | 200 OK | Respuesta exitosa con cuerpo |
| `ResponseEntity.badRequest().body(body)` | 400 Bad Request | Error de validacion |
| `ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body)` | 429 Too Many Requests | Rate limiting |
| `ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)` | 500 Internal Server Error | Error inesperado |

**Alternativa sin `ResponseEntity`**: se podria devolver directamente `PaginatedResponse<CycleDTO>` (sin wrappear en `ResponseEntity`). Spring asumira HTTP 200 automaticamente. Pero `ResponseEntity` es preferible porque permite devolver codigos de estado distintos a 200 en otros metodos.

### 5. Serializacion JSON: Spring Boot 4 + Jackson 3

Spring Boot 4 incluye **Jackson 3** como serializador JSON. Una diferencia importante respecto a versiones anteriores:

- **Jackson 3 serializa fechas (`Instant`, `LocalDate`, etc.) como ISO-8601 por defecto**. Ya no es necesario configurar `spring.jackson.serialization.write-dates-as-timestamps=false` como se hacia en Spring Boot 2/3.
- Los `Instant` se serializan como `"2024-01-15T08:30:00Z"` automaticamente.

Ejemplo de respuesta JSON para `GET /api/v1/cycles?page=1&pageSize=2`:

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

### 6. ProfileController: un patron diferente

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

Este controlador es diferente a los otros 4 porque:

1. **No tiene servicio intermedio**: inyecta directamente `WhoopApiClient` en vez de un servicio.
2. **No consulta la base de datos**: el perfil del usuario no se almacena localmente, se obtiene en tiempo real de la Whoop API.
3. **No tiene paginacion ni filtros**: el perfil es un unico objeto, no una coleccion.
4. **El tipo de retorno es `Map<String, Any?>`**: no hay DTO tipado. La respuesta de la Whoop API se reenvía tal cual.

Este patron es adecuado porque el perfil cambia raramente y es un dato simple que no necesita persistencia local ni transformaciones.

### 7. `@RestControllerAdvice` y manejo global de excepciones

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/exception/GlobalExceptionHandler.kt

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)
```

`@RestControllerAdvice` es la combinacion de `@ControllerAdvice` + `@ResponseBody`. Permite definir manejadores de excepciones que aplican a **todos** los controladores de la aplicacion. Sin este mecanismo, cada controlador tendria que manejar sus propias excepciones con bloques try-catch, duplicando codigo.

#### Manejador de WhoopApiException

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

[`@ExceptionHandler(WhoopApiException::class)`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html) indica: "cuando cualquier controlador lance una `WhoopApiException`, ejecuta este metodo".

La logica de mapeo de codigos de estado es:

- **429 de Whoop** se devuelve como **429 al cliente**: el rate limiting de la API externa se propaga al cliente. Asi Power BI sabe que debe esperar antes de reintentar.
- **401/403 de Whoop** se devuelve como **502 Bad Gateway**: un error de autenticacion con la API externa es un problema del servidor (este BFF), no del cliente. El 502 indica que el servidor recibio una respuesta invalida de un upstream.
- **Cualquier otro error** tambien se mapea a **502**: cualquier fallo de comunicacion con la Whoop API se trata como un error del gateway.

#### Manejador de IllegalArgumentException

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

Este manejador captura las excepciones lanzadas por `require()` en los controladores (por ejemplo, `page < 1` o `pageSize > 1000`). Las devuelve como HTTP 400 Bad Request con un mensaje descriptivo.

Se loguea como `warn` (no `error`) porque un 400 es culpa del cliente, no un fallo del servidor.

#### Manejador generico (catch-all)

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

Este es el **ultimo recurso**: cualquier excepcion que no sea `WhoopApiException` ni `IllegalArgumentException` se captura aqui. Puntos importantes:

- Se loguea como `error` con la excepcion completa (stacktrace) para debugging.
- El mensaje al cliente es **generico** (`"Error interno del servidor"`), no expone detalles internos. Esto es una practica de seguridad: nunca revelar stacktraces, nombres de clases o rutas internas al cliente.

### 8. La jerarquia de excepciones

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/exception/WhoopApiException.kt

class WhoopApiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)
```

`WhoopApiException` extiende `RuntimeException` (excepcion no checked). Es una **excepcion de dominio** que representa cualquier fallo de comunicacion con la Whoop API.

Propiedades:

- `message`: descripcion del error (heredada de `RuntimeException`).
- `statusCode`: el codigo HTTP que devolvio la Whoop API (429, 401, 500...). Es nullable porque algunos errores (timeout, fallo de red) no tienen codigo HTTP.
- `cause`: la excepcion original que causo el error (para encadenar excepciones).

El DTO de respuesta de error:

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/exception/GlobalExceptionHandler.kt

data class ErrorResponse(
    val error: String,
    val message: String,
    val status: Int
)
```

Todas las respuestas de error tienen la misma estructura JSON:

```json
{
  "error": "Bad Request",
  "message": "page debe ser >= 1",
  "status": 400
}
```

### 9. Orden de evaluacion de `@ExceptionHandler`

Cuando un controlador lanza una excepcion, Spring busca el manejador mas **especifico** primero:

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

Esto funciona porque `WhoopApiException` y `IllegalArgumentException` son subclases de `Exception`. Spring evalua los handlers de mas especifico a mas generico.

### 10. Los 4 controladores de datos siguen el mismo patron

`CycleController`, `RecoveryController`, `SleepController` y `WorkoutController` son estructuralmente identicos. La unica diferencia es el servicio que invocan y el endpoint que exponen:

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

Resumen de los 5 endpoints de la API:

| Endpoint | Metodo HTTP | Parametros | Autenticacion |
|---|---|---|---|
| `/api/v1/cycles` | GET | `from`, `to`, `page`, `pageSize` | Basic Auth |
| `/api/v1/recovery` | GET | `from`, `to`, `page`, `pageSize` | Basic Auth |
| `/api/v1/sleep` | GET | `from`, `to`, `page`, `pageSize` | Basic Auth |
| `/api/v1/workouts` | GET | `from`, `to`, `page`, `pageSize` | Basic Auth |
| `/api/v1/profile` | GET | ninguno | Basic Auth |

Todos estan bajo `/api/**`, por lo que la cadena de seguridad `@Order(1)` de `SecurityConfig` les aplica Basic Auth (ver `docs/08-seguridad.md`).

---

## Flujo completo de un error

Ejemplo: el cliente envia `GET /api/v1/cycles?page=0`.

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

## Documentacion oficial

- **`@RestController`**: [Spring Framework - @RestController](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html)
- **`@RequestMapping`**: [Spring Framework - Request Mapping](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html)
- **`@RequestParam`**: [Spring Framework - @RequestParam](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestparam.html)
- **`ResponseEntity`**: [Spring Framework - ResponseEntity](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/responseentity.html)
- **`@RestControllerAdvice`**: [Spring Framework - Controller Advice](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html)
- **`@ExceptionHandler`**: [Spring Framework - Exceptions](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html)
- **Kotlin `require()` y precondiciones**: [Kotlin Standard Library - Preconditions](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin/require.html)
- **Jackson 3 y fechas ISO-8601**: [Spring Boot Reference - JSON](https://docs.spring.io/spring-boot/reference/features/json.html)
