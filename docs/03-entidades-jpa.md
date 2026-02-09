# 03 - Entidades JPA

> Capa de persistencia: como las clases Kotlin se convierten en tablas de PostgreSQL.

---

## 1. Que es JPA y Hibernate?

**JPA (Jakarta Persistence API)** es una **especificacion** (un contrato, como una interfaz) que define como las aplicaciones Java/Kotlin deben interactuar con bases de datos relacionales usando objetos. JPA **no es una libreria** que ejecutas directamente: es un conjunto de anotaciones ([`@Entity`](https://jakarta.ee/specifications/persistence/3.2/), [`@Table`](https://jakarta.ee/specifications/persistence/3.2/), [`@Column`](https://jakarta.ee/specifications/persistence/3.2/)...) y reglas que cualquier implementacion debe seguir.

**[Hibernate](https://hibernate.org/orm/documentation/)** es la **implementacion** mas popular de JPA. Cuando Spring Boot arranca con `spring-boot-starter-data-jpa`, automaticamente incluye Hibernate como motor ORM (Object-Relational Mapping). Es Hibernate quien realmente genera las sentencias SQL, gestiona el cache de entidades, y traduce objetos Kotlin a filas de base de datos.

```
Tu codigo Kotlin  --->  JPA (anotaciones)  --->  Hibernate (implementacion)  --->  SQL  --->  PostgreSQL
```

**Analogia**: JPA es como una interfaz de Java, e Hibernate es la clase que la implementa. Tu codigo solo usa la interfaz (anotaciones de `jakarta.persistence.*`), nunca importas nada de Hibernate directamente. Esto te da la libertad teorica de cambiar de implementacion sin modificar tus entidades.

### Donde se configura en este proyecto?

La dependencia esta en [`build.gradle.kts`](../build.gradle.kts), linea 27:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
```

Este starter incluye automaticamente:

- **Hibernate 7** (la version compatible con Spring Boot 4.0.2)
- **Spring Data JPA** (repositorios, etc.)
- **Jakarta Persistence API** (las anotaciones `@Entity`, `@Column`, etc.)
- **HikariCP** (pool de conexiones a la base de datos)

---

## 2. Donde se usa en este proyecto?

Las entidades JPA estan en el paquete `model.entity`:

| Archivo | Tabla en BD | ID | Tipo de ID |
|---------|-------------|-----|-----------|
| [`src/main/kotlin/.../model/entity/WhoopCycle.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/entity/WhoopCycle.kt) | `whoop_cycles` | `id: Long` | Del Whoop API |
| [`src/main/kotlin/.../model/entity/WhoopRecovery.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/entity/WhoopRecovery.kt) | `whoop_recoveries` | `cycleId: Long` | Del Whoop API |
| [`src/main/kotlin/.../model/entity/WhoopSleep.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/entity/WhoopSleep.kt) | `whoop_sleeps` | `id: String` | Del Whoop API |
| [`src/main/kotlin/.../model/entity/WhoopWorkout.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/entity/WhoopWorkout.kt) | `whoop_workouts` | `id: String` | Del Whoop API |
| [`src/main/kotlin/.../model/entity/OAuthTokenEntity.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/entity/OAuthTokenEntity.kt) | `oauth_tokens` | `id: Long?` | Auto-generado por BD |

---

## 3. Por que esta decision?

### 3a. Por que JPA y no JDBC directo o jOOQ?

- **Productividad**: Con JPA, no escribes SQL manualmente para operaciones CRUD. Defines una clase y JPA genera la tabla y las queries.
- **Patron BFF de un solo usuario**: Este proyecto sincroniza datos de un solo usuario de Whoop. Las queries son simples (filtros por fecha, paginacion). No necesitamos la flexibilidad de SQL raw que ofrece jOOQ.
- **Ecosistema Spring**: Spring Data JPA se integra nativamente con Spring Boot. Los repositorios, la transaccionalidad y la paginacion funcionan "out of the box".

### 3b. Por que `class` y no `data class` para entidades?

Las entidades JPA usan `class` (no `data class`) por tres razones:

1. **Proxies de Hibernate**: Hibernate necesita crear proxies (subclases) de tus entidades para lazy loading y dirty checking. Las `data class` generan `equals()`, `hashCode()` y `copy()` basados en **todos** los campos del constructor, lo cual interfiere con el mecanismo de proxies.
2. **Identidad vs igualdad**: Dos objetos `WhoopCycle` con el mismo `id` deben ser "la misma entidad" aunque otros campos difieran (Hibernate puede tener la version parcialmente cargada). El `equals()` de `data class` fallaria en esos casos.
3. **Mutabilidad**: JPA necesita `var` (mutables) para poder setear valores al cargar de BD. Las `data class` funcionan mejor con `val` (inmutables).

### 3c. Por que campos `var` con valores por defecto?

```kotlin
var id: Long = 0,           // Valor por defecto: 0
var scoreState: String = "PENDING_SCORE",  // Valor por defecto: string
var end: Instant? = null,   // Valor por defecto: null (nullable)
```

JPA/Hibernate requiere un **constructor sin argumentos** para poder instanciar entidades al cargarlas desde la BD. En Kotlin, al dar valores por defecto a **todos** los parametros del constructor primario, el compilador genera automaticamente un constructor sin argumentos. Esto evita tener que escribir un constructor secundario vacio.

---

## 4. Codigo explicado

### 4a. Anotaciones basicas: `@Entity`, `@Table`, `@Id`, `@Column`

Vamos a analizar [`WhoopCycle.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/entity/WhoopCycle.kt):

```kotlin
package com.example.whoopdavidapi.model.entity

import jakarta.persistence.*       // Todas las anotaciones JPA
import java.time.Instant            // Tipo de timestamp timezone-safe

@Entity                              // (1) Marca esta clase como entidad JPA
@Table(name = "whoop_cycles")       // (2) Nombre de la tabla en la BD
class WhoopCycle(
    @Id                              // (3) Este campo es la clave primaria
    @Column(name = "id")             // (4) Nombre de la columna en la tabla
    var id: Long = 0,

    @Column(name = "user_id", nullable = false)  // (5) NOT NULL en la BD
    var userId: Long = 0,

    @Column(name = "created_at")
    var createdAt: Instant? = null,  // (6) Nullable en Kotlin = nullable en BD

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    @Column(name = "start_time", nullable = false)
    var start: Instant = Instant.now(),

    @Column(name = "end_time")
    var end: Instant? = null,

    @Column(name = "timezone_offset")
    var timezoneOffset: String? = null,

    @Column(name = "score_state", nullable = false)
    var scoreState: String = "PENDING_SCORE",

    // Score fields (flattened)      // (7) Campos "aplanados"
    @Column(name = "strain")
    var strain: Float? = null,

    @Column(name = "kilojoule")
    var kilojoule: Float? = null,

    @Column(name = "average_heart_rate")
    var averageHeartRate: Int? = null,

    @Column(name = "max_heart_rate")
    var maxHeartRate: Int? = null
)
```

**Explicacion linea por linea**:

| # | Anotacion/Concepto | Que hace |
|---|---------------------|----------|
| (1) | `@Entity` | Le dice a JPA: "esta clase representa una tabla en la BD". Sin esta anotacion, Hibernate la ignora completamente. |
| (2) | `@Table(name = "whoop_cycles")` | Especifica el nombre exacto de la tabla. Sin ella, JPA usaria el nombre de la clase (`WhoopCycle` -> `whoop_cycle`). Lo ponemos explicitamente para claridad. |
| (3) | [`@Id`](https://jakarta.ee/specifications/persistence/3.2/) | Marca el campo como **clave primaria**. Toda entidad JPA **debe** tener exactamente un `@Id`. |
| (4) | `@Column(name = "id")` | Mapea la propiedad Kotlin al nombre de columna en SQL. Cuando el nombre de la propiedad y la columna coinciden, es opcional, pero lo incluimos para ser explicitos. |
| (5) | `nullable = false` | Genera `NOT NULL` en el DDL. Hibernate lanzara una excepcion si intentas guardar un `null` en ese campo. |
| (6) | [`Instant`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Instant.html)`?` (nullable en Kotlin) | Los campos con `?` pueden ser `null`. Esto se alinea con la BD: `createdAt` puede no existir si el dato aun no ha sido procesado por Whoop. |
| (7) | Score fields (flattened) | Ver seccion 4c mas abajo. |

### 4b. Estrategia de IDs: por que difiere entre entidades

Este es un punto clave de diseno. Hay **dos estrategias** distintas:

#### Estrategia 1: ID asignado por Whoop API (WhoopCycle, WhoopRecovery, WhoopSleep, WhoopWorkout)

```kotlin
// WhoopCycle.kt
@Id
@Column(name = "id")
var id: Long = 0           // SIN @GeneratedValue --> el ID lo asignamos nosotros
```

```kotlin
// WhoopRecovery.kt
@Id
@Column(name = "cycle_id")
var cycleId: Long = 0      // El ID de recovery ES el cycle_id de Whoop
```

```kotlin
// WhoopSleep.kt
@Id
@Column(name = "id")
var id: String = ""         // El ID de sleep es un String (UUID de Whoop)
```

**Por que?** Estos datos vienen de la Whoop API v2 con su propio identificador unico. Usamos ese mismo ID como clave primaria en nuestra BD por dos razones:

1. **Idempotencia en la sincronizacion**: Cuando el scheduler ejecuta `repository.save(cycle)`, si el `id` ya existe, JPA hace un `UPDATE` en vez de un `INSERT`. Esto significa que podemos re-sincronizar datos sin crear duplicados.
2. **Trazabilidad**: Si necesitas debuggear, puedes buscar el mismo ID en nuestra BD y en la API de Whoop.

Nota que **WhoopSleep** y **WhoopWorkout** usan `String` como tipo de ID (son UUIDs en la API de Whoop), mientras que **WhoopCycle** y **WhoopRecovery** usan `Long`.

#### Estrategia 2: ID auto-generado por la BD (OAuthTokenEntity)

```kotlin
// OAuthTokenEntity.kt
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
var id: Long? = null        // (A) Nullable porque aun no existe hasta el INSERT
```

**[`@GeneratedValue`](https://jakarta.ee/specifications/persistence/3.2/)`(strategy = GenerationType.IDENTITY)`**: Le dice a JPA que la base de datos se encarga de generar el ID. En PostgreSQL, esto usa una columna `BIGSERIAL` (auto-increment). En H2 (desarrollo), usa `IDENTITY`.

**Por que `Long?` (nullable)?** Cuando creas un `OAuthTokenEntity` nuevo, aun no tiene ID:

```kotlin
val token = OAuthTokenEntity(
    accessToken = "eyJhbG...",  // id es null aqui
    refreshToken = "dGhpcw...",
    expiresAt = Instant.now().plusSeconds(3600)
)
oAuthTokenRepository.save(token)  // Ahora id tiene valor (ej: 1, 2, 3...)
```

**Por que esta entidad SI auto-genera y las demas NO?** Los tokens OAuth no vienen de la Whoop API con un ID propio. Son datos internos de nuestra aplicacion. La BD decide el ID.

### 4c. Diseno "aplanado" (flattened): por que no hay tablas separadas para scores

La Whoop API v2 devuelve datos anidados. Por ejemplo, un cycle tiene:

```json
{
  "id": 12345,
  "user_id": 67890,
  "start": "2024-01-15T08:00:00Z",
  "score_state": "SCORED",
  "score": {
    "strain": 15.5,
    "kilojoule": 2500.0,
    "average_heart_rate": 72,
    "max_heart_rate": 185
  }
}
```

En un diseno relacional "puro", harias dos tablas: `whoop_cycles` y `whoop_cycle_scores`, con una relacion `@OneToOne`. Nosotros **aplanamos** esos campos directamente en la entidad:

```kotlin
// En vez de @OneToOne a una tabla CycleScore:
@Column(name = "strain")
var strain: Float? = null,          // score.strain  -> columna directa

@Column(name = "kilojoule")
var kilojoule: Float? = null,       // score.kilojoule  -> columna directa

@Column(name = "average_heart_rate")
var averageHeartRate: Int? = null,   // score.average_heart_rate  -> columna directa

@Column(name = "max_heart_rate")
var maxHeartRate: Int? = null        // score.max_heart_rate  -> columna directa
```

**Por que aplanar?**

1. **Simplicidad**: Este es un proyecto BFF para un solo usuario. No hay millones de filas ni consultas complejas. Una tabla plana es mas facil de consultar en Power BI.
2. **Rendimiento**: Un `JOIN` menos en cada query. En Power BI, conectas una sola tabla y tienes todos los datos.
3. **Mantenimiento**: Menos entidades, menos repositorios, menos mappers. El codigo es mas facil de entender.
4. **Relacion 1:1 estricta**: Un cycle tiene **exactamente** un score (o ninguno si esta `PENDING_SCORE`). No hay relacion 1:N que justifique una tabla separada.

Los campos de score son `nullable` (`Float?`, `Int?`) porque cuando `scoreState = "PENDING_SCORE"`, aun no hay datos de score.

Observa como esto se aplica en todas las entidades:

- **WhoopSleep**: Aplana `stage_summary` (8 campos como `totalInBedTimeMilli`, `totalRemSleepTimeMilli`) y `sleep_needed` (4 campos como `baselineMilli`, `needFromSleepDebtMilli`) directamente en la tabla.
- **WhoopWorkout**: Aplana `score` (8 campos) y `zone_duration` (6 campos: `zoneZeroMilli` a `zoneFiveMilli`) directamente.
- **WhoopRecovery**: Aplana `score` (6 campos: `recoveryScore`, `restingHeartRate`, `hrvRmssdMilli`, etc.).

### 4d. `@Convert` y cifrado de tokens: `EncryptedStringConverter` y `TokenEncryptor`

Los tokens OAuth2 son **datos sensibles**. Si alguien accede a la BD, no deberia poder leer el `accessToken` ni el `refreshToken` en texto plano. JPA ofrece [`@Convert`](https://jakarta.ee/specifications/persistence/3.2/) para transformar datos automaticamente al leer y escribir.

En [`OAuthTokenEntity.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/entity/OAuthTokenEntity.kt):

```kotlin
@Column(name = "access_token", length = 4096)
@Convert(converter = EncryptedStringConverter::class)
var accessToken: String? = null,

@Column(name = "refresh_token", length = 4096)
@Convert(converter = EncryptedStringConverter::class)
var refreshToken: String? = null,
```

**`length = 4096`**: Los tokens cifrados ocupan mas espacio que los originales. El calculo es:

- Token original: ~2KB max
- IV (12 bytes) + ciphertext + GCM tag (16 bytes) = ~2.028KB
- Base64 encoding agrega 33% overhead = ~2.7KB
- Margen de seguridad = 4096 bytes total

**`@Convert(converter = EncryptedStringConverter::class)`**: Cada vez que JPA guarda o lee este campo, pasa por el converter.

El converter esta en [`src/main/kotlin/.../util/EncryptedStringConverter.kt`](../src/main/kotlin/com/example/whoopdavidapi/util/EncryptedStringConverter.kt):

```kotlin
@Converter                                                 // (1) Anotacion JPA
@Component                                                 // (2) Bean de Spring
class EncryptedStringConverter(
    private val encryptor: TokenEncryptor                   // (3) Inyeccion de dependencia
) : AttributeConverter<String?, String?> {                 // (4) Interface JPA

    override fun convertToDatabaseColumn(attribute: String?): String? {
        return encryptor.encrypt(attribute)                 // (5) Kotlin -> BD: cifra
    }

    override fun convertToEntityAttribute(dbData: String?): String? {
        return encryptor.decrypt(dbData)                    // (6) BD -> Kotlin: descifra
    }
}
```

| # | Que hace |
|---|----------|
| (1) | `@Converter` marca esta clase como converter JPA. |
| (2) | `@Component` la registra como bean de Spring, necesario para que la inyeccion de `TokenEncryptor` funcione. |
| (3) | `TokenEncryptor` es otro componente Spring que contiene la logica real de cifrado. |
| (4) | `AttributeConverter<String?, String?>` es la interfaz JPA: transforma `String?` (tipo Kotlin) a `String?` (tipo BD). |
| (5) | Al hacer `repository.save(entity)`, JPA llama a `convertToDatabaseColumn`. El token en claro se cifra antes de llegar a la BD. |
| (6) | Al hacer `repository.findById(id)`, JPA llama a `convertToEntityAttribute`. El texto cifrado se descifra antes de llegar a tu codigo. |

**Flujo completo**:

```
Tu codigo: token.accessToken = "eyJhbGci..."
    |
    v  convertToDatabaseColumn()
    |
BD almacena: "Rk3mX2p...base64...cifrado..."
    |
    v  convertToEntityAttribute()
    |
Tu codigo recibe: "eyJhbGci..."  (transparente)
```

El cifrado real lo hace [`src/main/kotlin/.../util/TokenEncryptor.kt`](../src/main/kotlin/com/example/whoopdavidapi/util/TokenEncryptor.kt), que usa **[AES-256-GCM](https://en.wikipedia.org/wiki/Galois/Counter_Mode)**:

```kotlin
@Component
class TokenEncryptor(
    @Value("\${app.security.encryption-key:#{null}}") private val encryptionKey: String?
) {
    private val algorithm = "AES/GCM/NoPadding"
    private val keySpec: SecretKeySpec
    private val secureRandom = SecureRandom()

    companion object {
        private const val GCM_IV_LENGTH = 12    // 96 bits, recomendado para GCM
        private const val GCM_TAG_LENGTH = 128  // 128 bits, tag de autenticacion
    }

    init {
        // Falla al arrancar si no hay clave configurada
        require(!encryptionKey.isNullOrBlank()) {
            "app.security.encryption-key debe estar configurada."
        }

        // Decodifica la clave desde Base64
        val keyBytes = Base64.getDecoder().decode(encryptionKey)

        // Valida que sean exactamente 32 bytes (256 bits)
        require(keyBytes.size == 32) {
            "La clave debe representar exactamente 32 bytes (256 bits)."
        }

        keySpec = SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plainText: String?): String? {
        if (plainText == null) return null
        val cipher = Cipher.getInstance(algorithm)
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)                          // IV aleatorio cada vez
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + encryptedBytes                  // IV + ciphertext + GCM tag
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(encryptedText: String?): String? {
        if (encryptedText == null) return null
        val combined = Base64.getDecoder().decode(encryptedText)
        val iv = combined.take(GCM_IV_LENGTH).toByteArray()
        val ciphertext = combined.drop(GCM_IV_LENGTH).toByteArray()
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        val cipher = Cipher.getInstance(algorithm)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
```

**Conceptos clave de AES-256-GCM**:

- **AES-256**: Algoritmo de cifrado simetrico con clave de 256 bits. "Simetrico" = la misma clave cifra y descifra.
- **GCM (Galois/Counter Mode)**: Modo de operacion que proporciona **confidencialidad** (nadie puede leer el texto) Y **autenticidad** (nadie puede modificar el texto cifrado sin que lo detectemos). El "tag" de 128 bits es como una firma digital del contenido cifrado.
- **IV (Initialization Vector)**: 12 bytes aleatorios que se generan para **cada operacion de cifrado**. Esto garantiza que cifrar el mismo texto dos veces produce resultados distintos. El IV se almacena junto con el texto cifrado (no es secreto).
- **NoPadding**: GCM no necesita padding (a diferencia de CBC), lo que simplifica la implementacion.

**Por que la clave viene de `application.yaml` y no esta hardcodeada?** La propiedad `app.security.encryption-key` se configura como variable de entorno o secreto de Kubernetes. Nunca se commitea al repositorio.

### 4e. El plugin `allOpen` y por que es necesario en Kotlin

En Kotlin, **todas las clases son `final` por defecto**. Esto significa que no se pueden heredar (no puedes hacer `class Hijo : WhoopCycle()`). Pero Hibernate necesita crear **proxies** de tus entidades, y los proxies son subclases.

El plugin `kotlin("plugin.jpa")` (que incluye [`allOpen`](https://kotlinlang.org/docs/all-open-plugin.html)) resuelve esto. En [`build.gradle.kts`](../build.gradle.kts):

```kotlin
plugins {
    kotlin("plugin.jpa") version "2.2.21"    // (1) Incluye allOpen para JPA
}

// ...

allOpen {                                     // (2) Configuracion explicita
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
```

**Que hace esto?** Cuando el compilador de Kotlin encuentra una clase con `@Entity`, `@MappedSuperclass` o `@Embeddable`, automaticamente la hace `open` (no final). Asi:

```kotlin
// Lo que TU escribes:
@Entity
class WhoopCycle(...)

// Lo que el COMPILADOR genera (gracias a allOpen):
@Entity
open class WhoopCycle(...)    // <-- ahora Hibernate puede crear proxies
```

Sin este plugin, Hibernate lanzaria un error al intentar crear proxies de clases finales, o funcionaria en modo degradado sin lazy loading.

### 4f. `java.time.Instant` para timestamps: por que NO usar `Date` ni `LocalDateTime`

Todas las entidades usan `java.time.Instant` para campos temporales:

```kotlin
var createdAt: Instant? = null,
var updatedAt: Instant? = null,
var start: Instant = Instant.now(),
var end: Instant? = null,
var expiresAt: Instant = Instant.now(),
```

**Por que `Instant` y no las alternativas?**

| Tipo | Problema | Zona horaria? |
|------|----------|---------------|
| `java.util.Date` | API legacy llena de bugs, mutable, no thread-safe. Deprecada en la practica. | Internamente UTC, pero la API es confusa. |
| `LocalDateTime` | **No tiene zona horaria**. `2024-01-15T08:00:00` puede ser las 8 AM en Madrid o en Tokyo. Peligroso para datos que cruzan zonas horarias. | NO |
| `OffsetDateTime` | Buena opcion, pero mas compleja de lo necesario para nuestro caso. | SI (offset fijo) |
| `Instant` | Punto exacto en el tiempo, siempre UTC. Simple, inmutable, thread-safe. | SI (siempre UTC) |

**`Instant` es la mejor opcion para nuestro caso porque:**

1. La Whoop API v2 devuelve timestamps en **ISO-8601 con UTC** (ej: `2024-01-15T08:00:00.000Z`). `Instant.parse()` los lee directamente.
2. PostgreSQL almacena `TIMESTAMP WITH TIME ZONE` en UTC internamente. `Instant` se mapea a este tipo de forma natural.
3. Power BI puede convertir UTC a la zona horaria local del dashboard. El dato en BD siempre es "la verdad absoluta".
4. La Whoop API incluye un campo `timezone_offset` separado (ej: `"-05:00"`), que almacenamos como `String` por si Power BI lo necesita.

---

## 5. Documentacion oficial

- [Jakarta Persistence API (JPA 3.2) Specification](https://jakarta.ee/specifications/persistence/3.2/)
- [Hibernate ORM 7 User Guide](https://docs.hibernate.org/orm/7.0/userguide/html_single/)
- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/reference/)
- [Kotlin allOpen plugin](https://kotlinlang.org/docs/all-open-plugin.html)
- [java.time.Instant JavaDoc](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/time/Instant.html)
- [AES-GCM (NIST SP 800-38D)](https://csrc.nist.gov/pubs/sp/800/38/d/final)
- [JPA AttributeConverter JavaDoc](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/attributeconverter)

---

> **Siguiente**: [04 - Repositorios](./04-repositorios.md) -- como Spring Data JPA genera queries automaticamente a partir de nombres de metodo.
