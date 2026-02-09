# 08 - Spring Security (Multi-Chain Configuration)

## What is Spring Security?

Spring Security is Spring’s security framework. It is responsible for **authentication** (verifying who you are) and **authorization** (verifying what you can do). It works as a [filter chain](https://docs.spring.io/spring-security/reference/servlet/architecture.html#servlet-securityfilterchain) that runs **before** the request reaches the controller.

In this project, Spring Security protects the API with three different mechanisms:

1. **[Basic Auth](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/basic.html)** for the data endpoints (`/api/**`): Power BI sends credentials in each request.
2. **[OAuth2](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/index.html)** for the authorization flow (`/login/**`, `/oauth2/**`): it is used once to connect to the Whoop API.
3. **Public access** for monitoring and documentation endpoints (`/actuator/**`, `/swagger-ui/**`, `/mock/**`).

---

## Where is it used in this project?

| File | Responsibility |
|---|---|
| `src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt` | 4 security chains + user management |
| `src/main/kotlin/com/example/whoopdavidapi/util/TokenEncryptor.kt` | Encryption [AES-256-GCM](https://en.wikipedia.org/wiki/Galois/Counter_Mode) of OAuth2 tokens in the DB |
| `src/main/kotlin/com/example/whoopdavidapi/util/EncryptedStringConverter.kt` | JPA converter that automatically encrypts/decrypts |
| `src/main/kotlin/com/example/whoopdavidapi/model/entity/OAuthTokenEntity.kt` | JPA entity with encrypted tokens |

---

## Why this configuration?

This project has **three types of consumers** with different security needs:

| Consumer | Endpoints | Authentication | Reason |
|---|---|---|---|
| **Power BI** | `/api/**` | Basic Auth (username/password) | Power BI supports Basic Auth natively. It is simple and stateless. |
| **David (once)** | `/login/**`, `/oauth2/**` | OAuth2 with Whoop | To authorize this application to read data from David's Whoop account. |
| **Monitoring/docs** | `/actuator/**`, `/swagger-ui/**` | None (public) | Kubernetes health checks and interactive documentation must be accessible without credentials. |
| **Everything else** | Any other route | Denied | Principle of least privilege: if a route is not explicitly allowed, it is denied. |

The **multiple-chain architecture with [`@Order`](https://docs.spring.io/spring-framework/reference/core/beans/annotation-config.html)** makes it possible to apply different security rules to each group of endpoints, instead of a single monolithic configuration.

---

## Explained code

### 1. `@Configuration` and `@EnableWebSecurity`

```kotlin
// src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt

@Configuration
@EnableWebSecurity
class SecurityConfig(
    @Value("\${app.powerbi.username}") private val powerBiUsername: String,
    @Value("\${app.powerbi.password}") private val powerBiPassword: String
) {
```

- [`@Configuration`](https://docs.spring.io/spring-framework/reference/core/beans/java/configuration-annotation.html): marks the class as a source of Spring beans (methods [`@Bean`](https://docs.spring.io/spring-framework/reference/core/beans/java/bean-annotation.html) that Spring manages).
- `@EnableWebSecurity`: enables Spring Security’s web security configuration. Without this annotation, Spring Boot applies a default security configuration (which protects everything with a randomly generated password).
- `@Value("\${app.powerbi.username}")`: injects values from `application.yaml`. Power BI credentials are configured externally; they are not hardcoded in the code.

### 2. `PasswordEncoder` with BCrypt

```kotlin
@Bean
fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
```

[`BCryptPasswordEncoder`](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html) implements the **bcrypt** algorithm to hash passwords. When a password is saved, bcrypt:

1. Generate a random **salt** (22 characters).
2. Apply the hash function with a **cost factor** (default 10, which means 2^10 = 1024 iterations).
3. It produces a ~60-character hash that includes the salt, the cost factor, and the hash.

Example of a bcrypt hash:

```
$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
  |  |  |                                                    |
  |  |  salt (22 chars)                                      hash
  |  cost factor (10)
  version (2a)
```

This is important because:

- The plaintext password **is never stored**. Only the hash.
- Even if someone obtains the hash, they cannot reverse it to plaintext (bcrypt is a one-way function).
- The random salt prevents rainbow table attacks.
- The cost factor makes hashing intentionally slow, making brute-force attacks more difficult.

### 3. `UserDetailsService` with in-memory user

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

[`UserDetailsService`](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/user-details-service.html) is the interface that Spring Security uses to look up users. When a request with Basic Auth arrives, Spring:

1. Extract `username` and `password` from the `Authorization` header.
2. Call `userDetailsService.loadUserByUsername(username)` to retrieve the stored user.
3. Compare the sent password with the stored hash using `passwordEncoder.matches()`.
4. If they match, the request is authenticated.

`InMemoryUserDetailsManager` is an implementation that stores users **in memory** (not in a database). It is appropriate for this project because there is only a single user: the Power BI user. It makes no sense to use a users table in the database for a single record.

- `User.builder()`: Fluent API for building a `UserDetails` object.
- `.username(powerBiUsername)`: the username comes from the configuration (`application.yaml`).
- `.password(passwordEncoder.encode(powerBiPassword))`: the password is hashed with bcrypt **before** being stored.
- `.roles("POWERBI")`: assigns the "POWERBI" role. Internally, Spring converts it to the authority `ROLE_POWERBI`. In this project, roles are not used for granular access control, but it is good practice to assign it.

### 4. Multi-chain architecture with `@Order`

Spring Security allows you to define **multiple [`SecurityFilterChain`](https://docs.spring.io/spring-security/reference/servlet/architecture.html)**, each with its own rules. Each chain has a `securityMatcher` that defines which URLs it applies to. The chains are evaluated in `@Order` order (lower number = higher priority).

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

A request is only processed by **a chain**: the first one whose `securityMatcher` matches the URL.

### 5. String 1 (`@Order(1)`): API endpoints with Basic Auth

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

Line-by-line breakdown:

**`.securityMatcher("/api/**")`**: this string only applies to URLs that start with `/api/`. `**` is an Ant pattern that matches any subpath (e.g., `/api/v1/cycles`, `/api/v1/profile`).

**`.csrf { it.disable() }`**: disables [CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) (Cross-Site Request Forgery) protection. CSRF is relevant for HTML forms with cookie-based sessions, but this API is **stateless** and uses Basic Auth, not cookies. Disabling CSRF is correct and necessary for REST APIs.

**`.sessionManagement { it.sessionCreationPolicy(`[`SessionCreationPolicy`](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)`.STATELESS) }`**: indicates that Spring **never** will create an HTTP session for these endpoints. Each request must include the Basic Auth credentials. This is important because:

- Power BI sends credentials with each request (it does not maintain a session).
- Server memory is not wasted storing sessions.
- It facilitates horizontal scaling (there is no shared state between replicas).

**`.authorizeHttpRequests { auth -> auth.anyRequest().authenticated() }`**: every request to `/api/**` requires authentication. There are no public endpoints within `/api/`.

**`.httpBasic { }`**: enables HTTP Basic authentication. The client must send the header:

```
Authorization: Basic base64(username:password)
```

### 6. String 2 (`@Order(2)`): OAuth2 for Whoop

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

**`.securityMatcher("/login/**", "/oauth2/**")`**: applies to the OAuth2 flow routes. `/login/oauth2/code/whoop` is where the Whoop API redirects after David authorizes the application. `/oauth2/**` are Spring’s internal routes to handle the handshake.

**`.oauth2Login { }`**: enables the OAuth2 Authorization Code flow. Spring Security takes care of:

1. Redirect the user to the Whoop authorization page.
2. Receive the callback with the authorization code.
3. Exchange the code for access and refresh tokens.
4. Store the tokens (in this project, in the database via `OAuthTokenEntity`).

This flow runs only once: David visits `/login` in a browser, authorizes the application, and the tokens are saved. From that point on, automatic synchronization uses the stored tokens (and renews them automatically when they expire).

### 7. String 3 (`@Order(3)`): Public endpoints

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

**Public endpoints and their purpose**:

| Route | Purpose |
|---|---|
| `/actuator/**` | Kubernetes health checks (`/actuator/health`). They must be accessible without auth so that K8s can detect whether the pod is alive. |
| `/h2-console/**` | H2 web console for development. Only available in profile `dev`. |
| `/mock/**` | Mock endpoints for testing without real API keys (profile `demo`). |
| `/v3/api-docs/**` | OpenAPI definition in JSON (used by Swagger UI). |
| `/swagger-ui/**`, `/swagger-ui.html` | Interactive web interface for testing the API. |

**`.headers { headers -> headers.frameOptions { it.sameOrigin() } }`**: allows the H2 console to load inside an `<iframe>`. By default, Spring Security blocks iframes as protection against clickjacking. `sameOrigin()` allows iframes only if they come from the same domain.

**`.authorizeHttpRequests { auth -> auth.anyRequest().permitAll() }`**: all requests to these endpoints are allowed without authentication.

### 8. String 4 (`@Order(4)`): Deny-all (catch-all)

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

This string **has no `securityMatcher`**, so it matches any URL that has not been captured by the previous strings. `denyAll()` returns HTTP 403 Forbidden for any request.

This implements the **principle of least privilege** (principle of least privilege): everything is forbidden by default. Only the endpoints explicitly configured in chains 1, 2, and 3 are accessible.

Without this chain, Spring Boot would apply its default security configuration, which could allow unwanted access.

### 9. OAuth2 token encryption: `TokenEncryptor`

OAuth2 tokens (access token and refresh token) are stored in the database. They are **extremely sensitive** data: whoever has an access token can access David’s health data in the Whoop API. That’s why they are encrypted at rest with AES-256-GCM.

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

**AES-256-GCM** (Advanced Encryption Standard with Galois/Counter Mode):

- **AES-256**: symmetric encryption with a 256-bit key. It is the industry standard, approved by NIST and used by governments and financial institutions.
- **GCM** (Galois/Counter Mode): mode of operation that provides **confidentiality** (the data is unreadable without the key) and **authenticity** (it is detected if the encrypted data has been modified). GCM is an **AEAD** mode (Authenticated Encryption with Associated Data).
- **NoPadding**: GCM does not need padding because it operates like a stream cipher.

**Encryption parameters**:

- **IV (Initialization Vector)**: 12 random bytes (96 bits), generated with `SecureRandom`. A different IV for each encryption operation ensures that the same plaintext produces different ciphertexts each time. This is critical: reusing an IV with the same key completely breaks the security of GCM.
- **Authentication tag**: 128 bits. It is a "cryptographic checksum" that detects whether the ciphertext has been modified (tamper detection).
- **Key**: 32 bytes (256 bits) encoded in Base64. It is configured in `application.yaml` and can be generated with `openssl rand -base64 32`.

#### Key validation in block `init`

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

Kotlin’s `init` block runs when the bean instance is created. Here, the encryption key is validated strictly:

1. **It cannot be empty**: a default key is not allowed (`#{null}` in `@Value` means that if the property does not exist, it will be `null`). If the key is not configured, the application **does not start**. This is intentional: it is preferable to fail at startup than to run without encryption.
2. **It must be valid Base64**: it is decoded to obtain the actual bytes.
3. **It must be exactly 32 bytes**: AES-256 requires a key of exactly 256 bits (32 bytes). If the key has a different size, the application fails with a message explaining how to generate a correct key.

#### Encryption process

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

Encryption flow:

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

The IV is prepended to the ciphertext because it is necessary for decryption. It is not secret (it can be in plaintext), but it must be unique for each encryption operation.

The generic error message (`"Error procesando credenciales"`) is intentional: no cryptographic details are revealed externally.

#### Decryption process

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

Decryption flow (inverse of encryption):

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

Validation `combined.size <= GCM_IV_LENGTH` prevents an edge case: if the encrypted data is corrupted or truncated, it is detected before attempting to decrypt.

If the authentication tag doesn’t match (because the data was modified), `cipher.doFinal()` throws a `AEADBadTagException`. This is caught in the catch block and converted into a generic error.

### 10. `EncryptedStringConverter`: automatic encryption with JPA

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

[`AttributeConverter`](https://jakarta.ee/specifications/persistence/3.2/)`<String?, String?>` is a JPA interface that allows you to automatically transform an entity attribute before saving it and after reading it from the database:

- `convertToDatabaseColumn`: it is called when JPA is going to do an INSERT or UPDATE. It encrypts the token before saving it.
- `convertToEntityAttribute`: it is called when JPA reads from the DB. It decrypts the token so that the application works with plain text.

The converter is applied in the entity with the `@Convert` annotation:

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

The column has `length = 4096` because encryption adds overhead:

- IV: 12 bytes
- Original token: up to ~2KB
- GCM authentication tag: 16 bytes
- Base64 encoding: +33% overhead
- Total: the ciphertext is significantly longer than the original

This means that the application code works with tokens in plain text (to send them to the Whoop API), but in the database they are always encrypted. The encryption/decryption is **transparent**: neither the services nor the repositories need to know that the tokens are encrypted.

---

## Complete authentication flow

### Basic Auth Request (Power BI asks for data)

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

### Request denied (route not configured)

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

## Official documentation

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
