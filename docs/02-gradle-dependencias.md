# 02 - Gradle y Dependencias

## Que es?

**Gradle** es el sistema de build (compilacion, empaquetado, tests) del proyecto. Usamos **Gradle con [Kotlin DSL](https://docs.gradle.org/current/userguide/kotlin_dsl.html)** (`build.gradle.kts`), lo que significa que el archivo de configuracion esta escrito en Kotlin en vez del clasico Groovy (`build.gradle`).

Gradle se encarga de:

- **Descargar dependencias** (librerias que necesita el proyecto) desde Maven Central
- **Compilar** el codigo Kotlin a bytecode JVM
- **Ejecutar tests** con JUnit
- **Empaquetar** la aplicacion en un JAR ejecutable (`bootJar`)
- **Procesar anotaciones** con [kapt](https://kotlinlang.org/docs/kapt.html) (para [MapStruct](https://mapstruct.org/documentation/stable/reference/html/))

---

## Donde se usa en este proyecto?

**Archivo principal**: [`build.gradle.kts`](../build.gradle.kts)

**Archivo complementario**: [`settings.gradle.kts`](../settings.gradle.kts) (solo define el nombre del proyecto raiz)

```kotlin
// settings.gradle.kts
rootProject.name = "whoop-david-api"
```

---

## Codigo explicado

### Bloque de plugins

```kotlin
plugins {
    kotlin("jvm") version "2.2.21"              // (1)
    kotlin("plugin.spring") version "2.2.21"    // (2)
    kotlin("plugin.jpa") version "2.2.21"       // (3)
    kotlin("kapt") version "2.2.21"             // (4)
    id("org.springframework.boot") version "4.0.2"     // (5)
    id("io.spring.dependency-management") version "1.1.7"  // (6)
}
```

1. **`kotlin("jvm")`**: Plugin base de Kotlin para JVM. Compila archivos `.kt` a bytecode Java. Sin este plugin, Gradle no sabe como compilar Kotlin

2. **`kotlin("plugin.spring")`**: Alias de [`kotlin-allopen`](https://kotlinlang.org/docs/all-open-plugin.html) configurado para Spring. En Kotlin, todas las clases son `final` por defecto. Spring necesita crear proxies de las clases (para `@Transactional`, `@Configuration`, etc.), y los proxies requieren clases no-final. Este plugin abre automaticamente las clases anotadas con:
   - `@Component`, `@Service`, `@Controller`, `@Repository`
   - `@Configuration`
   - `@Transactional`
   - `@Async`
   - `@Cacheable`

3. **`kotlin("plugin.jpa")`**: Alias de [`kotlin-noarg`](https://kotlinlang.org/docs/no-arg-plugin.html) configurado para JPA. Hibernate necesita constructores sin argumentos en las entidades para poder instanciarlas via reflection. Kotlin con `data class` o clases con parametros en el constructor no tiene constructor vacio por defecto. Este plugin genera constructores sin argumentos (invisibles en el codigo) para las clases anotadas con `@Entity`, `@MappedSuperclass` y `@Embeddable`

4. **`kotlin("kapt")`**: **Kotlin Annotation Processing Tool**. Necesario para que MapStruct pueda generar codigo en tiempo de compilacion. kapt es el puente entre los annotation processors de Java y el compilador de Kotlin. Sin kapt, MapStruct no generaria las implementaciones de los mappers

5. **`org.springframework.boot`**: El [plugin de Spring Boot](https://docs.spring.io/spring-boot/gradle-plugin/) que agrega:
   - Tarea `bootJar`: empaqueta la aplicacion como un fat JAR ejecutable con todas las dependencias incluidas
   - Tarea `bootRun`: ejecuta la aplicacion directamente desde Gradle
   - Gestion de versiones para las dependencias de Spring Boot

6. **`io.spring.dependency-management`**: Gestiona las versiones de TODAS las dependencias transitivas de Spring Boot. Gracias a este plugin, cuando escribes `implementation("org.springframework.boot:spring-boot-starter-data-jpa")` no necesitas especificar la version: el plugin la resuelve automaticamente a la version compatible con Spring Boot 4.0.2

### Configuracion de Java

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}
```

Esto configura el **Java Toolchain**: Gradle descargara y usara automaticamente Java 24 (JDK) para compilar el proyecto, independientemente de la version de Java instalada en el sistema. Esto garantiza que todos los desarrolladores y el CI usen la misma version de Java.

### Repositorios

```kotlin
repositories {
    mavenCentral()
}
```

Las dependencias se descargan de **Maven Central**, el repositorio publico mas grande de artefactos Java/Kotlin. Es el equivalente a npm para JavaScript.

---

### Dependencias explicadas

#### Spring Boot Starters

Los **starters** son paquetes de conveniencia que agrupan varias dependencias relacionadas. En vez de agregar 15 dependencias para configurar JPA, agregas un solo starter.

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
```

**Que hace**: Expone [endpoints de monitoreo](https://docs.spring.io/spring-boot/reference/actuator/index.html) como `/actuator/health` y `/actuator/info`.
**Donde se usa**: Configurado en [`src/main/resources/application.yaml`](../src/main/resources/application.yaml) (lineas `management:...`). El Dockerfile lo usa para el HEALTHCHECK.
**Si lo quitas**: No hay endpoints de monitoreo. El HEALTHCHECK del Docker fallaria. Kubernetes no podria verificar si la app esta viva.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
```

**Que hace**: Incluye [Spring Data JPA](https://docs.spring.io/spring-boot/reference/data/sql.html) + Hibernate (ORM) + [HikariCP](https://github.com/brettwooldridge/HikariCP) (connection pool). Permite definir interfaces Repository que Spring implementa automaticamente.
**Donde se usa**: Todas las entidades en [`model/entity/`](../src/main/kotlin/com/example/whoopdavidapi/model/entity/) y repositorios en [`repository/`](../src/main/kotlin/com/example/whoopdavidapi/repository/).
**Si lo quitas**: No hay acceso a base de datos. Nada funciona.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security")
```

**Que hace**: Incluye [Spring Security](https://docs.spring.io/spring-security/reference/). Por defecto, protege TODOS los endpoints con autenticacion.
**Donde se usa**: [`config/SecurityConfig.kt`](../src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt) - define las 4 cadenas de seguridad.
**Si lo quitas**: La API queda completamente abierta. Cualquiera puede acceder a los datos.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
```

**Que hace**: Agrega soporte de [cliente OAuth2](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html). Permite que la app actue como cliente OAuth2 para autenticarse contra Whoop.
**Donde se usa**: El flujo OAuth2 en [`config/SecurityConfig.kt`](../src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt) (`oauth2Login { }`) y la configuracion del provider en [`application.yaml`](../src/main/resources/application.yaml) (`spring.security.oauth2.client`).
**Si lo quitas**: No puedes autenticarte contra Whoop API. No puedes obtener el token OAuth2 inicial.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-validation")
```

**Que hace**: Incluye Jakarta Bean Validation (Hibernate Validator). Permite validar parametros con anotaciones como `@NotNull`, `@Size`, etc.
**Donde se usa**: Validaciones con `require()` en los controladores, por ejemplo en [`controller/CycleController.kt`](../src/main/kotlin/com/example/whoopdavidapi/controller/CycleController.kt):

```kotlin
require(page >= 1) { "page debe ser >= 1" }
require(pageSize in 1..1000) { "pageSize debe estar entre 1 y 1000" }
```

**Si lo quitas**: Las validaciones manuales con `require()` siguen funcionando (son de Kotlin), pero pierdes la infraestructura de `@Valid` y `@Validated` si las necesitas en el futuro.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-webmvc")
```

**Que hace**: Incluye [Spring Web MVC](https://docs.spring.io/spring-boot/reference/web/servlet.html) + Tomcat embebido. Es el nucleo del servidor web.
**Donde se usa**: Todos los controladores en [`controller/`](../src/main/kotlin/com/example/whoopdavidapi/controller/) usan `@RestController` y `@GetMapping` de WebMVC.
**Si lo quitas**: No hay servidor web. La aplicacion no puede recibir peticiones HTTP.

---

```kotlin
implementation("org.springframework.boot:spring-boot-h2console")
```

**Que hace**: Habilita la consola web de H2 (interfaz grafica para ver la BD in-memory en desarrollo).
**Donde se usa**: Configurada en [`application-dev.yaml`](../src/main/resources/application-dev.yaml) (`spring.h2.console.enabled: true`). Accesible en `http://localhost:8080/h2-console`.
**Si lo quitas**: No puedes ver la BD H2 desde el navegador en desarrollo. La BD sigue funcionando.

---

#### Kotlin

```kotlin
implementation("org.jetbrains.kotlin:kotlin-reflect")
```

**Que hace**: Libreria de reflection de Kotlin. Spring la necesita para inspeccionar clases Kotlin en runtime (leer anotaciones, crear instancias, etc.).
**Donde se usa**: Internamente por Spring Framework, Jackson y Hibernate.
**Si lo quitas**: Spring no puede trabajar correctamente con clases Kotlin. Errores en runtime.

---

```kotlin
implementation("tools.jackson.module:jackson-module-kotlin")
```

**Que hace**: Modulo de Jackson para Kotlin. Permite a Jackson serializar/deserializar `data class` de Kotlin correctamente (reconoce parametros del constructor, tipos nullable, valores por defecto, etc.).
**Donde se usa**: Automaticamente por Spring MVC para convertir objetos a JSON en las respuestas de los controladores.
**Si lo quitas**: Jackson no puede deserializar DTOs de Kotlin. Los endpoints devuelven errores.

> **GOTCHA Spring Boot 4**: El paquete cambio de `com.fasterxml.jackson.module:jackson-module-kotlin` (Jackson 2) a `tools.jackson.module:jackson-module-kotlin` (Jackson 3). Spring Boot 4 usa **Jackson 3**, que cambio su grupo Maven de `com.fasterxml` a `tools.jackson`.

---

#### MapStruct

```kotlin
implementation("org.mapstruct:mapstruct:1.6.3")
kapt("org.mapstruct:mapstruct-processor:1.6.3")
```

**Que hace**: MapStruct genera automaticamente el codigo de mapeo entre Entity y DTO en tiempo de compilacion. `mapstruct` es la libreria de anotaciones. `mapstruct-processor` es el annotation processor que genera las implementaciones.
**Donde se usa**: Los 4 mappers en [`mapper/`](../src/main/kotlin/com/example/whoopdavidapi/mapper/). Por ejemplo, [`mapper/CycleMapper.kt`](../src/main/kotlin/com/example/whoopdavidapi/mapper/CycleMapper.kt):

```kotlin
@Mapper(componentModel = "spring")
interface CycleMapper {
    fun toDto(entity: WhoopCycle): CycleDTO
    fun toEntity(dto: CycleDTO): WhoopCycle
}
```

MapStruct genera automaticamente una clase `CycleMapperImpl` con el codigo de mapeo campo por campo.
**Si lo quitas**: Necesitas escribir el mapeo a mano en cada servicio. Con 4 entidades y muchos campos, es mucho codigo repetitivo.

---

#### Resilience4j

```kotlin
implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
implementation("org.springframework.boot:spring-boot-starter-aspectj")
```

**Que hace**: [Resilience4j](https://resilience4j.readme.io/docs/getting-started-3) proporciona patrones de resiliencia (circuit breaker, retry, rate limiter) via anotaciones. El starter de [AspectJ](https://docs.spring.io/spring-framework/reference/core/aop.html) es necesario porque Resilience4j usa AOP (Aspect-Oriented Programming) para interceptar llamadas a metodos anotados.
**Donde se usa**: [`client/WhoopApiClient.kt`](../src/main/kotlin/com/example/whoopdavidapi/client/WhoopApiClient.kt) - cada metodo tiene `@CircuitBreaker`, `@Retry` y `@RateLimiter`:

```kotlin
@CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
@Retry(name = "whoopApi")
@RateLimiter(name = "whoopApi")
fun getAllCycles(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
    return getAllRecords("/developer/v1/cycle", start, end)
}
```

Configurado en [`application.yaml`](../src/main/resources/application.yaml) (seccion `resilience4j:`).
**Si quitas resilience4j**: Los errores de Whoop API se propagan directamente. No hay reintentos ni circuit breaker. La sincronizacion falla completamente si Whoop tiene problemas temporales.
**Si quitas starter-aspectj**: Las anotaciones de Resilience4j se ignoran silenciosamente. El codigo se ejecuta pero sin proteccion.

> **GOTCHA Spring Boot 4**: El starter se renombro de `spring-boot-starter-aop` a `spring-boot-starter-aspectj`. Si usas el nombre antiguo, Gradle no lo encuentra.

---

#### OpenAPI / Swagger UI

```kotlin
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")
```

**Que hace**: Genera automaticamente documentacion OpenAPI 3.0 a partir de los controladores y la expone en Swagger UI.
**Donde se usa**: Configurado en [`config/OpenApiConfig.kt`](../src/main/kotlin/com/example/whoopdavidapi/config/OpenApiConfig.kt) y [`application.yaml`](../src/main/resources/application.yaml) (seccion `springdoc:`). Swagger UI accesible en `/swagger-ui/index.html`.
**Si lo quitas**: No hay Swagger UI ni documentacion automatica de la API.

> **GOTCHA Spring Boot 4**: [springdoc-openapi](https://springdoc.org/) **v3.x** es para Spring Boot 4. La version **v2.x** es para Spring Boot 3. Si usas v2.x con Spring Boot 4, falla por incompatibilidades con Jackson 3.

---

#### Base de datos

```kotlin
runtimeOnly("com.h2database:h2")
runtimeOnly("org.postgresql:postgresql")
```

**Que hacen**: Drivers JDBC para [H2](https://www.h2database.com/) (BD in-memory) y [PostgreSQL](https://jdbc.postgresql.org/). `runtimeOnly` significa que solo se necesitan en runtime, no en compilacion.
**Donde se usan**:

- H2: [`application-dev.yaml`](../src/main/resources/application-dev.yaml) - perfil de desarrollo
- PostgreSQL: [`application-prod.yaml`](../src/main/resources/application-prod.yaml) - perfil de produccion
**Si quitas H2**: El perfil `dev` no funciona (no tiene BD)
**Si quitas PostgreSQL**: El perfil `prod` no funciona (no puede conectar a la BD)

---

#### Test

```kotlin
testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
testImplementation("org.springframework.boot:spring-boot-starter-security-test")
testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

| Dependencia | Que proporciona |
|---|---|
| `actuator-test` | Utilidades para testear actuator |
| `data-jpa-test` | `@DataJpaTest` para tests de repositorio con BD embebida |
| `security-test` | `httpBasic()`, `csrf()` y otras utilidades para testear seguridad |
| `webmvc-test` | `MockMvc`, `@WebMvcTest`, `@AutoConfigureMockMvc` |
| `kotlin-test-junit5` | Assertions de Kotlin + integracion con JUnit 5 |
| `junit-platform-launcher` | Ejecutor de tests JUnit 5 |

> **GOTCHA Spring Boot 4**: Los paquetes de las anotaciones de test cambiaron:
>
> - `@WebMvcTest` esta ahora en `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`
> - `@DataJpaTest` esta ahora en `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`
> - `@AutoConfigureMockMvc` esta ahora en `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`
> - `@MockBean` fue reemplazado por `@MockitoBean` de `org.springframework.test.context.bean.override.mockito.MockitoBean`

---

### Configuracion de kapt

```kotlin
kapt {
    correctErrorTypes = true      // (1)
    includeCompileClasspath = false  // (2)
    arguments {
        arg("mapstruct.defaultComponentModel", "spring")  // (3)
    }
}
```

1. **`correctErrorTypes = true`**: Corrige errores de tipos cuando kapt no puede resolver una referencia. Necesario para que kapt funcione bien con Kotlin
2. **`includeCompileClasspath = false`**: Evita que kapt procese todo el classpath de compilacion (mejor rendimiento)
3. **`mapstruct.defaultComponentModel = "spring"`**: Le dice a MapStruct que genere los mappers como beans de Spring (`@Component`). Asi pueden inyectarse con `@Autowired` o por constructor

---

### Desactivar kapt para tests

```kotlin
// Desactivar kapt para test sources (no hay annotation processors en tests)
tasks.matching { it.name == "kaptTestKotlin" || it.name == "kaptGenerateStubsTestKotlin" }.configureEach {
    enabled = false
}
```

> **GOTCHA Spring Boot 4**: kapt intenta procesar las anotaciones de test (`@DataJpaTest`, `@WebMvcTest`, etc.) de Spring Boot 4 y falla porque estas anotaciones cambiaron de paquete. Como no hay annotation processors necesarios en los tests, desactivamos kapt para tests completamente.

Sin esta linea, la compilacion de tests falla con errores de kapt al intentar resolver las nuevas anotaciones de Spring Boot 4.

---

### Opciones del compilador Kotlin

```kotlin
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}
```

- **`-Xjsr305=strict`**: Hace que Kotlin trate las anotaciones de nulabilidad de Java (`@Nullable`, `@NonNull` de JSR-305) como estrictas. Esto significa que si una API de Spring declara un parametro como `@NonNull`, Kotlin lo trata como no-nullable (`String` en vez de `String?`). Mejora la seguridad de tipos en la interoperabilidad Kotlin/Java.

- **`-Xannotation-default-target=param-property`**: Controla donde se colocan las anotaciones en las propiedades de Kotlin. Por defecto en constructores de Kotlin, una anotacion como `@Column` podria aplicarse al parametro del constructor en vez de al campo. Con esta opcion, se aplica tanto al parametro como a la propiedad, lo que es necesario para que JPA/Hibernate las detecte correctamente.

---

### Plugin allOpen para JPA

```kotlin
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
```

Como se menciono, en Kotlin todas las clases son `final` por defecto. Hibernate necesita crear proxies (subclases) de las entidades para funcionalidades como lazy loading. Las clases `final` no pueden tener subclases.

El bloque `allOpen` complementa al plugin `kotlin("plugin.jpa")`:

- `plugin.jpa` genera constructores sin argumentos
- `allOpen` abre las clases (las hace no-final)

Ambos son necesarios para que JPA/Hibernate funcione correctamente con Kotlin.

**Ejemplo concreto**: La entidad `WhoopCycle` en [`model/entity/WhoopCycle.kt`](../src/main/kotlin/com/example/whoopdavidapi/model/entity/WhoopCycle.kt):

```kotlin
@Entity
@Table(name = "whoop_cycles")
class WhoopCycle(
    @Id
    @Column(name = "id")
    var id: Long = 0,
    // ...
)
```

Gracias a `allOpen`, esta clase se compila como `open class WhoopCycle` en vez de `final class WhoopCycle`, permitiendo que Hibernate cree proxies.

---

### Configuracion de tests

```kotlin
tasks.withType<Test> {
    useJUnitPlatform()
}
```

Configura Gradle para usar JUnit Platform (JUnit 5) como motor de ejecucion de tests. Sin esta linea, Gradle usa JUnit 4 por defecto y no encuentra los tests anotados con `@Test` de JUnit 5.

---

## Por que esta decision?

### Por que Gradle Kotlin DSL y no Groovy?

- **Type-safety**: El IDE puede verificar errores en el build script en tiempo de escritura
- **Autocompletado**: IntelliJ IDEA puede sugerir metodos y propiedades
- **Coherencia**: Todo el proyecto esta en Kotlin, incluyendo el build script

### Por que kapt y no KSP para MapStruct?

**KSP** (Kotlin Symbol Processing) es mas rapido que kapt, pero MapStruct 1.6.3 aun no tiene soporte oficial para KSP. Usar kapt es la unica opcion funcional actualmente.

### Por que las versiones de los plugins de Kotlin deben coincidir?

Todos los plugins de Kotlin (`jvm`, `plugin.spring`, `plugin.jpa`, `kapt`) deben tener la **misma version** (2.2.21). Si difieren, el compilador de Kotlin puede producir errores o comportamientos inesperados.

---

## Documentacion oficial

- [Gradle Kotlin DSL Primer](https://docs.gradle.org/current/userguide/kotlin_dsl.html)
- [Spring Boot Gradle Plugin](https://docs.spring.io/spring-boot/gradle-plugin/)
- [Kotlin Gradle Plugin](https://kotlinlang.org/docs/gradle-configure-project.html)
- [kapt (Kotlin Annotation Processing)](https://kotlinlang.org/docs/kapt.html)
- [allOpen Compiler Plugin](https://kotlinlang.org/docs/all-open-plugin.html)
- [No-arg Compiler Plugin](https://kotlinlang.org/docs/no-arg-plugin.html)
- [MapStruct Reference](https://mapstruct.org/documentation/stable/reference/html/)
- [Spring Boot Dependency Management](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html)
- [Java Toolchains in Gradle](https://docs.gradle.org/current/userguide/toolchains.html)
