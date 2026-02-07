# 08 - Spring Security (Configuracion Multi-Cadena)

## Que es Spring Security?

Spring Security es el framework de seguridad de Spring. Se encarga de **autenticacion** (verificar quien eres) y **autorizacion** (verificar que puedes hacer). Funciona como una [cadena de filtros](https://docs.spring.io/spring-security/reference/servlet/architecture.html#servlet-securityfilterchain) que se ejecuta **antes** de que la peticion llegue al controlador.

En este proyecto, Spring Security protege la API con tres mecanismos distintos:

1. **[Basic Auth](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html)** para los endpoints de datos (`/api/**`): Power BI envia credenciales en cada peticion.
2. **[OAuth2](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/index.html)** para el flujo de autorizacion (`/login/**`, `/oauth2/**`): se usa una vez para conectar con la Whoop API.
3. **Acceso publico** para endpoints de monitorizacion y documentacion (`/actuator/**`, `/swagger-ui/**`, `/mock/**`).

---

## Donde se usa en este proyecto?

| Archivo | Responsabilidad |
|---|---|
| `src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt` | 4 cadenas de seguridad + gestion de usuarios |
| `src/main/kotlin/com/example/whoopdavidapi/util/TokenEncryptor.kt` | Cifrado [AES-256-GCM](https://en.wikipedia.org/wiki/Galois/Counter_Mode) de tokens OAuth2 en la BD |
| `src/main/kotlin/com/example/whoopdavidapi/util/EncryptedStringConverter.kt` | JPA converter que cifra/descifra automaticamente |
| `src/main/kotlin/com/example/whoopdavidapi/model/entity/OAuthTokenEntity.kt` | Entidad JPA con tokens cifrados |

---

## Por que esta configuracion?

Este proyecto tiene **tres tipos de consumidores** con necesidades de seguridad distintas:

| Consumidor | Endpoints | Autenticacion | Razon |
|---|---|---|---|
| **Power BI** | `/api/**` | Basic Auth (usuario/password) | Power BI soporta Basic Auth nativamente. Es simple y stateless. |
| **David (una vez)** | `/login/**`, `/oauth2/**` | OAuth2 con Whoop | Para autorizar a esta aplicacion a leer datos de la cuenta Whoop de David. |
| **Monitorizacion/docs** | `/actuator/**`, `/swagger-ui/**` | Ninguna (publico) | Kubernetes health checks y documentacion interactiva deben ser accesibles sin credenciales. |
| **Todo lo demas** | Cualquier otra ruta | Denegado | Principio de minimo privilegio: si una ruta no esta explicitamente permitida, se deniega. |

La arquitectura de **multiples cadenas con [`@Order`](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config.html)** permite aplicar reglas de seguridad distintas a cada grupo de endpoints, en lugar de una unica configuracion monolitica.

---

## Codigo explicado

### 1. `@Configuration` y `@EnableWebSecurity`

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt

@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${app.powerbi.username}") private val powerBiUsername: String,
    @Value("\${app.powerbi.password}") private val powerBiPassword: String
) {
```

- [`@Configuration`](https://docs.spring.io/spring-framework/reference/core/beans/java/configuration-annotation.html): marca la clase como fuente de beans de Spring (metodos [`@Bean`](https://docs.spring.io/spring-framework/reference/core/beans/java/bean-annotation.html) que Spring gestiona).
- `@EnableWebSecurity`: activa la configuracion de seguridad web de Spring Security. Sin esta anotacion, Spring Boot aplica una configuracion de seguridad por defecto (que protege todo con un password generado aleatoriamente).
- `@Value("\${app.powerbi.username}")`: inyecta valores desde `application.yaml`. Las credenciales de Power BI se configuran externamente, no estan hardcodeadas en el codigo.

### 2. `PasswordEncoder` con BCrypt

```kotlin
@Bean
fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
```

[`BCryptPasswordEncoder`](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html) implementa el algoritmo **bcrypt** para hashear passwords. Cuando se guarda un password, bcrypt:

1. Genera un **salt aleatorio** (22 caracteres).
2. Aplica la funcion de hash con un **cost factor** (por defecto 10, lo que significa 2^10 = 1024 iteraciones).
3. Produce un hash de ~60 caracteres que incluye el salt, el cost factor y el hash.

Ejemplo de hash bcrypt:

```
$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
  |  |  |                                                    |
  |  |  salt (22 chars)                                      hash
  |  cost factor (10)
  version (2a)
```

Esto es importante porque:

- El password en texto plano **nunca se almacena**. Solo el hash.
- Incluso si alguien obtiene el hash, no puede revertirlo a texto plano (bcrypt es una funcion unidireccional).
- El salt aleatorio evita ataques con rainbow tables.
- El cost factor hace que el hashing sea intencionalmente lento, dificultando ataques de fuerza bruta.

### 3. `UserDetailsService` con usuario en memoria

```kotlin
@Bean
fun userDetailsService(passwordEncoder: PasswordEncoder): UserDetailsService {
    val user = User.builder()
        .username(powerBiUsername)
        .password(passwordEncoder.encode(powerBiPassword))
        .roles("POWERBI")
        .build()
    return InMemoryUserDetailsManager(user)
}
```

[`UserDetailsService`](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/user-details-service.html) es la interfaz que Spring Security usa para buscar usuarios. Cuando llega una peticion con Basic Auth, Spring:

1. Extrae el `username` y `password` del header `Authorization`.
2. Llama a `userDetailsService.loadUserByUsername(username)` para obtener el usuario almacenado.
3. Compara el password enviado con el hash almacenado usando `passwordEncoder.matches()`.
4. Si coinciden, la peticion esta autenticada.

`InMemoryUserDetailsManager` es una implementacion que almacena los usuarios **en memoria** (no en base de datos). Es apropiada para este proyecto porque solo hay un unico usuario: el de Power BI. No tiene sentido usar una tabla de usuarios en la base de datos para un solo registro.

- `User.builder()`: API fluida para construir un objeto `UserDetails`.
- `.username(powerBiUsername)`: el nombre de usuario viene de la configuracion (`application.yaml`).
- `.password(passwordEncoder.encode(powerBiPassword))`: el password se hashea con bcrypt **antes** de almacenarse.
- `.roles("POWERBI")`: asigna el rol "POWERBI". Internamente Spring lo convierte a la authority `ROLE_POWERBI`. En este proyecto no se usan roles para control de acceso granular, pero es buena practica asignarlo.

### 4. Arquitectura multi-cadena con `@Order`

Spring Security permite definir **multiples [`SecurityFilterChain`](https://docs.spring.io/spring-security/reference/servlet/architecture.html)**, cada uno con sus propias reglas. Cada cadena tiene un `securityMatcher` que define a que URLs aplica. Las cadenas se evaluan en orden de `@Order` (menor numero = mayor prioridad).

```
Peticion HTTP entrante
     |
     v
@Order(1): /api/**         -- Coincide? --> Basic Auth, stateless
     |
    No
     |
     v
@Order(2): /login/**, /oauth2/**  -- Coincide? --> OAuth2 login
     |
    No
     |
     v
@Order(3): /actuator/**, /swagger-ui/**, /mock/**, ...  -- Coincide? --> Permitir todo
     |
    No
     |
     v
@Order(4): todo lo demas   -- Catch-all --> DENEGAR
```

Una peticion solo es procesada por **una cadena**: la primera cuyo `securityMatcher` coincida con la URL.

### 5. Cadena 1 (`@Order(1)`): API endpoints con Basic Auth

```kotlin
// Cadena para endpoints de la API (Basic Auth, stateless)
@Bean
@Order(1)
fun apiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
    http
        .securityMatcher("/api/**")
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { auth ->
            auth.anyRequest().authenticated()
        }
        .httpBasic { }
    return http.build()
}
```

Desglose linea por linea:

**`.securityMatcher("/api/**")`**: esta cadena solo aplica a URLs que empiecen con `/api/`. El `**` es un patron Ant que coincide con cualquier subruta (e.g., `/api/v1/cycles`, `/api/v1/profile`).

**`.csrf { it.disable() }`**: desactiva la proteccion [CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) (Cross-Site Request Forgery). CSRF es relevante para formularios HTML con sesiones basadas en cookies, pero esta API es **stateless** y usa Basic Auth, no cookies. Desactivar CSRF es correcto y necesario para APIs REST.

**`.sessionManagement { it.sessionCreationPolicy(`[`SessionCreationPolicy`](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)`.STATELESS) }`**: indica que Spring **nunca** creara una sesion HTTP para estos endpoints. Cada peticion debe incluir las credenciales de Basic Auth. Esto es importante porque:

- Power BI envia credenciales en cada peticion (no mantiene sesion).
- No se desperdicia memoria del servidor almacenando sesiones.
- Facilita el escalado horizontal (no hay estado compartido entre replicas).

**`.authorizeHttpRequests { auth -> auth.anyRequest().authenticated() }`**: toda peticion a `/api/**` requiere autenticacion. No hay endpoints publicos dentro de `/api/`.

**`.httpBasic { }`**: activa la autenticacion HTTP Basic. El cliente debe enviar el header:

```
Authorization: Basic base64(username:password)
```

### 6. Cadena 2 (`@Order(2)`): OAuth2 para Whoop

```kotlin
// Cadena para OAuth2 (flujo de autorizacion con Whoop)
@Bean
@Order(2)
fun oauth2SecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
    http
        .securityMatcher("/login/**", "/oauth2/**")
        .csrf { it.disable() }
        .oauth2Login { }
    return http.build()
}
```

**`.securityMatcher("/login/**", "/oauth2/**")`**: aplica a las rutas del flujo OAuth2. `/login/oauth2/code/whoop` es donde la Whoop API redirige despues de que David autorice la aplicacion. `/oauth2/**` son las rutas internas de Spring para manejar el handshake.

**`.oauth2Login { }`**: activa el flujo OAuth2 Authorization Code. Spring Security se encarga de:

1. Redirigir al usuario a la pagina de autorizacion de Whoop.
2. Recibir el callback con el codigo de autorizacion.
3. Intercambiar el codigo por tokens de acceso y refresco.
4. Almacenar los tokens (en este proyecto, en la base de datos via `OAuthTokenEntity`).

Este flujo se ejecuta una sola vez: David visita `/login` en un navegador, autoriza la aplicacion, y los tokens se guardan. A partir de ese momento, la sincronizacion automatica usa los tokens almacenados (y los renueva automaticamente cuando expiran).

### 7. Cadena 3 (`@Order(3)`): Endpoints publicos

```kotlin
// Cadena para endpoints publicos (actuator, H2 console)
@Bean
@Order(3)
fun publicSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
    http
        .securityMatcher(
            "/actuator/**", "/h2-console/**", "/mock/**",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
        )
        .csrf { it.disable() }
        .headers { headers -> headers.frameOptions { it.sameOrigin() } }
        .authorizeHttpRequests { auth ->
            auth.anyRequest().permitAll()
        }
    return http.build()
}
```

**Endpoints publicos y su proposito**:

| Ruta | Proposito |
|---|---|
| `/actuator/**` | Health checks de Kubernetes (`/actuator/health`). Deben ser accesibles sin auth para que K8s detecte si el pod esta vivo. |
| `/h2-console/**` | Consola web de H2 para desarrollo. Solo disponible en perfil `dev`. |
| `/mock/**` | Endpoints mock para testing sin API keys reales (perfil `demo`). |
| `/v3/api-docs/**` | Definicion OpenAPI en JSON (usado por Swagger UI). |
| `/swagger-ui/**`, `/swagger-ui.html` | Interfaz web interactiva para probar la API. |

**`.headers { headers -> headers.frameOptions { it.sameOrigin() } }`**: permite que la consola H2 se cargue dentro de un `<iframe>`. Por defecto, Spring Security bloquea iframes como proteccion contra clickjacking. `sameOrigin()` permite iframes solo si provienen del mismo dominio.

**`.authorizeHttpRequests { auth -> auth.anyRequest().permitAll() }`**: todas las peticiones a estos endpoints se permiten sin autenticacion.

### 8. Cadena 4 (`@Order(4)`): Deny-all (catch-all)

```kotlin
// Cadena catch-all para el resto de requests (denegar por defecto)
@Bean
@Order(4)
fun defaultSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
    http
        .csrf { it.disable() }
        .authorizeHttpRequests { auth ->
            auth.anyRequest().denyAll()
        }
    return http.build()
}
```

Esta cadena **no tiene `securityMatcher`**, por lo que coincide con cualquier URL que no haya sido capturada por las cadenas anteriores. `denyAll()` devuelve HTTP 403 Forbidden para cualquier peticion.

Esto implementa el **principio de minimo privilegio** (principle of least privilege): todo esta prohibido por defecto. Solo los endpoints explicitamente configurados en las cadenas 1, 2 y 3 son accesibles.

Sin esta cadena, Spring Boot aplicaria su configuracion de seguridad por defecto, que podria permitir accesos no deseados.

### 9. Cifrado de tokens OAuth2: `TokenEncryptor`

Los tokens OAuth2 (access token y refresh token) se almacenan en la base de datos. Son datos **extremadamente sensibles**: quien tenga un access token puede acceder a los datos de salud de David en la Whoop API. Por eso se cifran en reposo con AES-256-GCM.

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/util/TokenEncryptor.kt

@Component
class TokenEncryptor(
    @Value("\${app.security.encryption-key:#{null}}") private val encryptionKey: String?
) {
    private val algorithm = "AES/GCM/NoPadding"
    private val keySpec: SecretKeySpec
    private val secureRandom = SecureRandom()

    companion object {
        private const val GCM_IV_LENGTH = 12 // 96 bits recommended for GCM
        private const val GCM_TAG_LENGTH = 128 // 128 bits authentication tag
    }
```

**AES-256-GCM** (Advanced Encryption Standard con Galois/Counter Mode):

- **AES-256**: cifrado simetrico con clave de 256 bits. Es el estandar de la industria, aprobado por el NIST y usado por gobiernos y entidades financieras.
- **GCM** (Galois/Counter Mode): modo de operacion que proporciona **confidencialidad** (los datos son ilegibles sin la clave) y **autenticidad** (se detecta si los datos cifrados han sido modificados). GCM es un modo **AEAD** (Authenticated Encryption with Associated Data).
- **NoPadding**: GCM no necesita padding porque opera como un stream cipher.

**Parametros del cifrado**:

- **IV (Initialization Vector)**: 12 bytes (96 bits) aleatorios, generados con `SecureRandom`. Un IV distinto para cada operacion de cifrado garantiza que el mismo texto plano produce ciphertexts diferentes cada vez. Esto es critico: reutilizar un IV con la misma clave rompe completamente la seguridad de GCM.
- **Tag de autenticacion**: 128 bits. Es un "checksum criptografico" que detecta si el ciphertext ha sido modificado (tamper detection).
- **Clave**: 32 bytes (256 bits) codificados en Base64. Se configura en `application.yaml` y se puede generar con `openssl rand -base64 32`.

#### Validacion de la clave en el bloque `init`

```kotlin
init {
    // Fallar si no hay clave configurada
    require(!encryptionKey.isNullOrBlank()) {
        "app.security.encryption-key debe estar configurada. No se permite clave por defecto."
    }

    // Interpretar la clave como Base64 y decodificarla
    val keyBytes = try {
        Base64.getDecoder().decode(encryptionKey)
    } catch (ex: IllegalArgumentException) {
        throw IllegalArgumentException(
            "app.security.encryption-key debe ser una cadena Base64 válida que represente exactamente 32 bytes. " +
            "Ejemplo para generar: openssl rand -base64 32",
            ex
        )
    }

    // Validar que la clave decodificada tenga exactamente 32 bytes (256 bits)
    require(keyBytes.size == 32) {
        "app.security.encryption-key debe representar exactamente 32 bytes (256 bits) tras decodificar Base64. " +
        "Actual: ${keyBytes.size} bytes. Genera una clave segura con: openssl rand -base64 32"
    }

    // Usar directamente los 32 bytes decodificados como clave AES-256
    keySpec = SecretKeySpec(keyBytes, "AES")
}
```

El bloque `init` de Kotlin se ejecuta cuando se crea la instancia del bean. Aqui se valida la clave de cifrado de forma estricta:

1. **No puede estar vacia**: no se permite una clave por defecto (`#{null}` en el `@Value` significa que si la propiedad no existe, sera `null`). Si la clave no esta configurada, la aplicacion **no arranca**. Esto es intencional: es preferible fallar al inicio que ejecutar sin cifrado.
2. **Debe ser Base64 valido**: se decodifica para obtener los bytes reales.
3. **Debe tener exactamente 32 bytes**: AES-256 requiere una clave de exactamente 256 bits (32 bytes). Si la clave tiene otro tamano, la aplicacion falla con un mensaje que explica como generar una clave correcta.

#### Proceso de cifrado

```kotlin
fun encrypt(plainText: String?): String? {
    // Solo retornar null si el valor es null (no si es blank)
    if (plainText == null) return null

    return try {
        val cipher = Cipher.getInstance(algorithm)

        // Generar IV aleatorio de 12 bytes (96 bits) para GCM
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Combinar IV + ciphertext (que incluye el tag de autenticación) y codificar en Base64
        val combined = iv + encryptedBytes
        Base64.getEncoder().encodeToString(combined)
    } catch (ex: Exception) {
        throw IllegalStateException("Error procesando credenciales")
    }
}
```

Flujo de cifrado:

```
Texto plano ("eyJhbGci...")
     |
     v
UTF-8 bytes
     |
     v
AES-256-GCM encrypt (con IV aleatorio de 12 bytes)
     |
     v
IV (12 bytes) + ciphertext + tag (16 bytes)
     |
     v
Base64 encode
     |
     v
String almacenado en BD ("SGVsbG8gV29ybGQ...")
```

El IV se prepone al ciphertext porque es necesario para descifrar. No es secreto (puede estar en texto plano), pero debe ser unico para cada operacion de cifrado.

El mensaje de error generico (`"Error procesando credenciales"`) es intencional: no se revelan detalles criptograficos al exterior.

#### Proceso de descifrado

```kotlin
fun decrypt(encryptedText: String?): String? {
    if (encryptedText == null) return null

    return try {
        val cipher = Cipher.getInstance(algorithm)

        // Decodificar Base64 y separar IV + ciphertext
        val combined = Base64.getDecoder().decode(encryptedText)

        // Validar que existan al menos 12 bytes de IV y > 0 bytes de ciphertext
        if (combined.size <= GCM_IV_LENGTH) {
            throw IllegalStateException("Datos cifrados inválidos")
        }

        val iv = combined.take(GCM_IV_LENGTH).toByteArray()
        val ciphertext = combined.drop(GCM_IV_LENGTH).toByteArray()
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        val decryptedBytes = cipher.doFinal(ciphertext)
        String(decryptedBytes, Charsets.UTF_8)
    } catch (ex: Exception) {
        throw IllegalStateException("Error procesando credenciales")
    }
}
```

Flujo de descifrado (inverso al cifrado):

```
String de BD ("SGVsbG8gV29ybGQ...")
     |
     v
Base64 decode
     |
     v
Separar: IV (primeros 12 bytes) | ciphertext + tag (el resto)
     |
     v
AES-256-GCM decrypt (verifica tag de autenticacion)
     |
     v
UTF-8 string -> Texto plano ("eyJhbGci...")
```

La validacion `combined.size <= GCM_IV_LENGTH` previene un caso borde: si los datos cifrados estan corruptos o truncados, se detecta antes de intentar descifrar.

Si el tag de autenticacion no coincide (porque los datos fueron modificados), `cipher.doFinal()` lanza una `AEADBadTagException`. Esto se captura en el bloque catch y se convierte en un error generico.

### 10. `EncryptedStringConverter`: cifrado automatico con JPA

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/util/EncryptedStringConverter.kt

@Converter
@Component
class EncryptedStringConverter(
    private val encryptor: TokenEncryptor
) : AttributeConverter<String?, String?> {

    override fun convertToDatabaseColumn(attribute: String?): String? {
        return encryptor.encrypt(attribute)
    }

    override fun convertToEntityAttribute(dbData: String?): String? {
        return encryptor.decrypt(dbData)
    }
}
```

[`AttributeConverter`](https://jakarta.ee/specifications/persistence/3.2/)`<String?, String?>` es una interfaz de JPA que permite transformar automaticamente un atributo de entidad antes de guardarlo y despues de leerlo de la base de datos:

- `convertToDatabaseColumn`: se llama cuando JPA va a hacer INSERT o UPDATE. Cifra el token antes de guardarlo.
- `convertToEntityAttribute`: se llama cuando JPA lee de la BD. Descifra el token para que la aplicacion trabaje con texto plano.

El converter se aplica en la entidad con la anotacion `@Convert`:

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/model/entity/OAuthTokenEntity.kt

@Entity
@Table(name = "oauth_tokens")
class OAuthTokenEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "access_token", length = 4096)
    @Convert(converter = EncryptedStringConverter::class)
    var accessToken: String? = null,

    @Column(name = "refresh_token", length = 4096)
    @Convert(converter = EncryptedStringConverter::class)
    var refreshToken: String? = null,

    // ... otros campos
)
```

La columna tiene `length = 4096` porque el cifrado agrega overhead:

- IV: 12 bytes
- Token original: hasta ~2KB
- Tag de autenticacion GCM: 16 bytes
- Base64 encoding: +33% de overhead
- Total: el texto cifrado es significativamente mas largo que el original

Esto significa que el codigo de la aplicacion trabaja con tokens en texto plano (para enviarlos a la Whoop API), pero en la base de datos estan siempre cifrados. El cifrado/descifrado es **transparente**: ni los servicios ni los repositorios necesitan saber que los tokens estan cifrados.

---

## Flujo completo de autenticacion

### Peticion Basic Auth (Power BI pide datos)

```
1. Power BI envia: GET /api/v1/cycles
   Header: Authorization: Basic cG93ZXJiaTpzZWNyZXQ=    (base64 de "powerbi:secret")

2. Spring Security evalua las cadenas:
   - @Order(1): securityMatcher("/api/**") -> COINCIDE

3. Cadena API procesa:
   - CSRF: desactivado (ok para API stateless)
   - Session: STATELESS (no crear sesion)
   - httpBasic: decodifica Base64 -> username="powerbi", password="secret"

4. Spring llama a userDetailsService.loadUserByUsername("powerbi")
   - Encuentra el usuario en InMemoryUserDetailsManager
   - Compara bcrypt hash del password almacenado con "secret"
   - Coincide -> autenticado

5. authorizeHttpRequests: anyRequest().authenticated() -> OK
6. La peticion llega a CycleController.getCycles()
```

### Peticion denegada (ruta no configurada)

```
1. Alguien envia: GET /admin/dashboard

2. Spring Security evalua las cadenas:
   - @Order(1): /api/** -> no coincide
   - @Order(2): /login/**, /oauth2/** -> no coincide
   - @Order(3): /actuator/**, /h2-console/**, /mock/**, /swagger-ui/** -> no coincide
   - @Order(4): catch-all sin securityMatcher -> COINCIDE

3. Cadena default: denyAll() -> HTTP 403 Forbidden
```

---

## Documentacion oficial

- **Spring Security Architecture**: [Spring Security Reference - Architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
- **Multiple SecurityFilterChain**: [Spring Security Reference - Multiple HttpSecurity](https://docs.spring.io/spring-security/reference/servlet/configuration/java.html#_multiple_httpsecurity)
- **`@Order`**: [Spring Framework - @Order](https://docs.spring.io/spring-framework/reference/core/beans/classpath-scanning.html#beans-scanning-autodetection)
- **HTTP Basic Authentication**: [Spring Security Reference - HTTP Basic](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html)
- **OAuth2 Login**: [Spring Security Reference - OAuth2 Login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/core.html)
- **`SessionCreationPolicy`**: [Spring Security Reference - Session Management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- **CSRF Protection**: [Spring Security Reference - CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- **`BCryptPasswordEncoder`**: [Spring Security Reference - Password Encoding](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html#authentication-password-storage-bcrypt)
- **`InMemoryUserDetailsManager`**: [Spring Security Reference - In-Memory Authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/in-memory.html)
- **AES-GCM (NIST SP 800-38D)**: [NIST - Recommendation for Block Cipher Modes of Operation: Galois/Counter Mode](https://csrc.nist.gov/publications/detail/sp/800-38d/final)
- **JPA `AttributeConverter`**: [Jakarta Persistence Specification - AttributeConverter](https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1#a12796)
