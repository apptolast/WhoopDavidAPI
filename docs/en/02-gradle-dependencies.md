# 02 - Gradle and Dependencies

## What is it?

**Gradle** is the build system (compilation, packaging, tests) of the project. We use **Gradle with [Kotlin DSL](https://docs.gradle.org/current/userguide/kotlin_dsl.html)** (`build.gradle.kts`), which means that the configuration file is written in Kotlin instead of classic Groovy (`build.gradle`).

Gradle takes care of:

- **Download dependencies** (libraries needed by the project) from Maven Central
- **Compile** the Kotlin code to JVM bytecode
- **Run tests** with JUnit
- **Package** the application in an executable JAR (`bootJar`)
- **Process annotations** with [kapt](https://kotlinlang.org/docs/kapt.html) (for [MapStruct](https://mapstruct.org/documentation/stable/reference/html/))

---

## Where is it used in this project?

**Main file**: [`build.gradle.kts`](../../build.gradle.kts)

**Supplementary file**: [`settings.gradle.kts`](../settings.gradle.kts) (only defines the root project name)

```kotlin
// settings.gradle.kts
rootProject.name = "whoop-david-api"
```

---

## Code explained

### Plugin Block

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

1. **`kotlin("jvm")`**: Kotlin base plugin for JVM. Compiles `.kt` files to Java bytecode. Without this plugin, Gradle doesn't know how to compile Kotlin

2. **`kotlin("plugin.spring")`**: Alias ​​of [`kotlin-allopen`](https://kotlinlang.org/docs/all-open-plugin.html) configured for Spring. In Kotlin, all classes are `final` by default. Spring needs to create proxies for classes (for `@Transactional`, `@Configuration`, etc.), and proxies require non-final classes. This plugin automatically opens classes annotated with:
   - `@Component`, `@Service`, `@Controller`, `@Repository`
   - `@Configuration`
   - `@Transactional`
   - `@Async`
   - `@Cacheable`

3. **`kotlin("plugin.jpa")`**: Alias ​​of [`kotlin-noarg`](https://kotlinlang.org/docs/no-arg-plugin.html) configured for JPA. Hibernate needs constructors without arguments on entities to be able to instantiate them via reflection. Kotlin with `data class` or classes with parameters in the constructor does not have an empty constructor by default. This plugin generates constructors without arguments (invisible in the code) for classes annotated with `@Entity`, `@MappedSuperclass` and `@Embeddable`

4. **`kotlin("kapt")`**: **Kotlin Annotation Processing Tool**. Necessary so that MapStruct can generate code at compile time. kapt is the bridge between Java's annotation processors and the Kotlin compiler. Without kapt, MapStruct would not generate mapper implementations

5. **`org.springframework.boot`**: The [Spring Boot plugin](https://docs.spring.io/spring-boot/gradle-plugin/) that adds:
   - Task `bootJar`: Package the application as an executable fat JAR with all dependencies included
   - Task `bootRun`: run the application directly from Gradle
   - Version management for Spring Boot dependencies

6. **`io.spring.dependency-management`**: Manages versions of ALL transitive Spring Boot dependencies. Thanks to this plugin, when you type `implementation("org.springframework.boot:spring-boot-starter-data-jpa")` you don't need to specify the version: the plugin automatically resolves it to the version compatible with Spring Boot 4.0.2

### Java configuration

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}
```

This configures the **Java Toolchain**: Gradle will automatically download and use Java 24 (JDK) to compile the project, regardless of the version of Java installed on the system. This ensures that all developers and the CI use the same version of Java.

### Repositories

```kotlin
repositories {
    mavenCentral()
}
```

Dependencies are downloaded from **Maven Central**, the largest public repository of Java/Kotlin artifacts. It is the equivalent of npm for JavaScript.

---

### Dependencies explained

#### Spring Boot Starters

The **starters** are convenience packages that bundle together several related dependencies. Instead of adding 15 dependencies to configure JPA, you add a single starter.

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
```

**What does**: Expose [monitoring endpoints](https://docs.spring.io/spring-boot/reference/actuator/index.html) as `/actuator/health` and `/actuator/info`.
**Where used**: Set to [`src/main/resources/application.yaml`](../../src/main/resources/application.yaml) (lines `management:...`). The Dockerfile uses it for HEALTHCHECK.
**If you remove it**: There are no monitoring endpoints. The Docker HEALTHCHECK would fail. Kubernetes could not verify if the app is alive.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
```

**What it does**: Includes [Spring Data JPA](https://docs.spring.io/spring-boot/reference/data/sql.html) + Hibernate (ORM) + [HikariCP](https://github.com/brettwooldridge/HikariCP) (connection pool). It allows you to define Repository interfaces that Spring implements automatically.
**Where used**: All entities in [`model/entity/`](../../src/main/kotlin/com/example/whoopdavidapi/model/entity/) and repositories in [`repository/`](../../src/main/kotlin/com/example/whoopdavidapi/repository/).
**If you remove it**: There is no access to the database. Nothing works.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security")
```

**What does**: Includes [Spring Security](https://docs.spring.io/spring-security/reference/). By default, it protects ALL endpoints with authentication.
**Where to use**: [`config/SecurityConfig.kt`](../../src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt) - defines the 4 security chains.
**If you remove it**: The API is completely open. Anyone can access the data.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
```

**What does**: Add support for [OAuth2](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html) client. Allows the app to act as an OAuth2 client to authenticate against Whoop.
**Where used**: The OAuth2 flow at [`config/SecurityConfig.kt`](../../src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt) (`oauth2Login { }`) and the provider configuration at [`application.yaml`](../../src/main/resources/application.yaml) (`spring.security.oauth2.client`).
**If you remove it**: You cannot authenticate against Whoop API. You can't get the initial OAuth2 token.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-validation")
```

**What it does**: Includes Jakarta Bean Validation (Hibernate Validator). It allows validating parameters with annotations such as `@NotNull`, `@Size`, etc.
**Where**  is used: Validations with `require()` in controllers, for example in [`controller/CycleController.kt`](../../src/main/kotlin/com/example/whoopdavidapi/controller/CycleController.kt):

```kotlin
require(page >= 1) { "page debe ser >= 1" }
require(pageSize in 1..1000) { "pageSize debe estar entre 1 y 1000" }
```

**If you remove it**: Manual validations with `require()` still work (they're from Kotlin), but you lose the infrastructure of `@Valid` and `@Validated` if you need them in the future.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-webmvc")
```

**What it does**: Includes [Spring Web MVC](https://docs.spring.io/spring-boot/reference/web/servlet.html) + embedded Tomcat. It is the core of the web server.
**Where used**: All controllers in [`controller/`](../../src/main/kotlin/com/example/whoopdavidapi/controller/) use WebMVC's `@RestController` and `@GetMapping`.
**If you remove it**: There is no web server. The application cannot receive HTTP requests.

---

```kotlin
implementation("org.springframework.boot:spring-boot-h2console")
```

**What it does**: Enables the H2 web console (graphical interface to view the in-memory DB in development).
**Where used**: Set to [`application-dev.yaml`](../../src/main/resources/application-dev.yaml) (`spring.h2.console.enabled: true`). Accessible in `http://localhost:8080/h2-console`.
**If you remove it**: You cannot see the H2 DB from the development browser. The DB continues to work.

---

#### Kotlin

```kotlin
implementation("org.jetbrains.kotlin:kotlin-reflect")
```

**What does**: Kotlin reflection library. Spring needs it to inspect Kotlin classes at runtime (read annotations, create instances, etc.).
**Where used**: Internally by Spring Framework, Jackson and Hibernate.
**If you remove it**: Spring cannot work correctly with Kotlin classes. Errors in runtime.

---

```kotlin
implementation("tools.jackson.module:jackson-module-kotlin")
```

**What it does**: Jackson module for Kotlin. Allows Jackson to serialize/deserialize Kotlin `data class` correctly (recognizes constructor parameters, nullable types, default values, etc.).
**Where used**: Automatically by Spring MVC to convert objects to JSON in controller responses.
**If you remove it**: Jackson cannot deserialize Kotlin DTOs. Endpoints return errors.

> **GOTCHA Spring Boot 4**: The package changed from `com.fasterxml.jackson.module:jackson-module-kotlin` (Jackson 2) to `tools.jackson.module:jackson-module-kotlin` (Jackson 3). Spring Boot 4 uses **Jackson 3**, which changed its Maven group from `com.fasterxml` to `tools.jackson`.

---

#### MapStruct

```kotlin
implementation("org.mapstruct:mapstruct:1.6.3")
kapt("org.mapstruct:mapstruct-processor:1.6.3")
```

**What it does**: MapStruct automatically generates the mapping code between Entity and DTO at compile time. `mapstruct` is the annotation library. `mapstruct-processor` is the annotation processor that generates the implementations.
**Where used**: The 4 mappers in [`mapper/`](../../src/main/kotlin/com/example/whoopdavidapi/mapper/). For example, [`mapper/CycleMapper.kt`](../../src/main/kotlin/com/example/whoopdavidapi/mapper/CycleMapper.kt):

```kotlin
@Mapper(componentModel = "spring")
interface CycleMapper {
    fun toDto(entity: WhoopCycle): CycleDTO
    fun toEntity(dto: CycleDTO): WhoopCycle
}
```

MapStruct automatically generates a class `CycleMapperImpl` with the field-by-field mapping code.
**If you remove it**: You need to write the mapping by hand in each service. With 4 entities and many fields, it's a lot of repetitive code.

---

#### Resilience4j

```kotlin
implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
implementation("org.springframework.boot:spring-boot-starter-aspectj")
```

**What does**: [Resilience4j](https://resilience4j.readme.io/docs/getting-started-3) provides resilience patterns (circuit breaker, retry, rate limiter) via annotations. The [AspectJ](https://docs.spring.io/spring-framework/reference/core/aop.html) starter is necessary because Resilience4j uses AOP (Aspect-Oriented Programming) to intercept calls to annotated methods.
**Where is**  used: [`client/WhoopApiClient.kt`](../../src/main/kotlin/com/example/whoopdavidapi/client/WhoopApiClient.kt) - each method has `@CircuitBreaker`, `@Retry` and `@RateLimiter`:

```kotlin
@CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
@Retry(name = "whoopApi")
@RateLimiter(name = "whoopApi")
fun getAllCycles(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
    return getAllRecords("/developer/v1/cycle", start, end)
}
```

Set to [`application.yaml`](../../src/main/resources/application.yaml) (section `resilience4j:`).
**If you remove resilience4j**: Whoop API errors propagate directly. There are no retries or circuit breakers. Synchronization fails completely if Whoop has temporary problems.
**If you remove starter-aspectj**: Resilience4j annotations are silently ignored. The code is executed but without protection.

> **GOTCHA Spring Boot 4**: The starter was renamed from `spring-boot-starter-aop` to `spring-boot-starter-aspectj`. If you use the old name, Gradle doesn't find it.

---

#### OpenAPI/Swagger UI

```kotlin
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")
```

**What it does**: Automatically generates OpenAPI 3.0 documentation from the controllers and exposes it in Swagger UI.
**Where used**: Set to [`config/OpenApiConfig.kt`](../../src/main/kotlin/com/example/whoopdavidapi/config/OpenApiConfig.kt) and [`application.yaml`](../../src/main/resources/application.yaml) (section `springdoc:`). Swagger UI accessible in `/swagger-ui/index.html`.
**If you remove it**: There is no Swagger UI or automatic API documentation.

> **GOTCHA Spring Boot 4**: [springdoc-openapi](https://springdoc.org/) **v3.x** is for Spring Boot 4. Version **v2.x** is for Spring Boot 3. If you use v2.x with Spring Boot 4, it fails because incompatibilities with Jackson 3.

---

#### Database

```kotlin
runtimeOnly("com.h2database:h2")
runtimeOnly("org.postgresql:postgresql")
```

**What they do**: JDBC drivers for [H2](https://www.h2database.com/) (BD in-memory) and [PostgreSQL](https://jdbc.postgresql.org/). `runtimeOnly` means that they are only needed in runtime, not in compilation.
**Where they are used**:

- H2: [`application-dev.yaml`](../../src/main/resources/application-dev.yaml) - developmental profile
- PostgreSQL: [`application-prod.yaml`](../../src/main/resources/application-prod.yaml) - production profile
**If you remove H2**: Profile `dev` does not work (does not have a DB)
**If you remove PostgreSQL**: Profile `prod` does not work (cannot connect to the DB)

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

| Dependence | What does it provide? |
|---|---|
| `actuator-test` | Utilities to test actuator |
| `data-jpa-test` | `@DataJpaTest` for repository tests with embedded DB |
| `security-test` | `httpBasic()`, `csrf()` and other utilities to test security |
| `webmvc-test` | `MockMvc`, `@WebMvcTest`, `@AutoConfigureMockMvc` |
| `kotlin-test-junit5` | Kotlin Assertions + integration with JUnit 5 |
| `junit-platform-launcher` | JUnit 5 Test Runner |

> **GOTCHA Spring Boot 4**: Test annotation packages changed:
>
> - `@WebMvcTest` is now in `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`
> - `@DataJpaTest` is now in `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`
> - `@AutoConfigureMockMvc` is now in `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`
> - `@MockBean` was replaced by `@MockitoBean` from `org.springframework.test.context.bean.override.mockito.MockitoBean`

---

### kapt configuration

```kotlin
kapt {
    correctErrorTypes = true      // (1)
    includeCompileClasspath = false  // (2)
    arguments {
        arg("mapstruct.defaultComponentModel", "spring")  // (3)
    }
}
```

1. **`correctErrorTypes = true`**: Fix type errors when kapt cannot resolve a reference. Required for kapt to work well with Kotlin
2. **`includeCompileClasspath = false`**: Prevent kapt from processing the entire build classpath (better performance)
3. **`mapstruct.defaultComponentModel = "spring"`**: Tells MapStruct to generate the mappers as Spring beans (`@Component`). So they can be injected with `@Autowired` or by constructor

---

### Disable kapt for tests

```kotlin
// Desactivar kapt para test sources (no hay annotation processors en tests)
tasks.matching { it.name == "kaptTestKotlin" || it.name == "kaptGenerateStubsTestKotlin" }.configureEach {
    enabled = false
}
```

> **GOTCHA Spring Boot 4**: kapt attempts to process test annotations (`@DataJpaTest`, `@WebMvcTest`, etc.) from Spring Boot 4 and fails because these annotations changed packages. Since there are no annotation processors needed in the tests, we disable kapt for tests completely.

Without this line, the test compilation fails with kapt errors when trying to resolve the new Spring Boot 4 annotations.

---

### Kotlin compiler options

```kotlin
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}
```

- **`-Xjsr305=strict`**: Causes Kotlin to treat Java nullability annotations (JSR-305's `@Nullable`, `@NonNull`) as strict. This means that if a Spring API declares a parameter as `@NonNull`, Kotlin treats it as non-nullable (`String` instead of `String?`). Improves type safety in Kotlin/Java interoperability.

- **`-Xannotation-default-target=param-property`**: Controls where annotations are placed in Kotlin properties. By default in Kotlin constructors, an annotation like `@Column` could be applied to the constructor parameter instead of the field. With this option, it applies to both the parameter and the property, which is necessary for JPA/Hibernate to detect them correctly.

---

### allOpen plugin for JPA

```kotlin
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
```

As mentioned, in Kotlin all classes are `final` by default. Hibernate needs to create proxies (subclasses) of the entities for functionality like lazy loading. Classes `final` cannot have subclasses.

Block `allOpen` complements plugin `kotlin("plugin.jpa")`:

- `plugin.jpa` generates constructors with no arguments
- `allOpen` opens classes (makes them non-final)

Both are required for JPA/Hibernate to work correctly with Kotlin.

**Concrete example**: The entity `WhoopCycle` in [`model/entity/WhoopCycle.kt`](../../src/main/kotlin/com/example/whoopdavidapi/model/entity/WhoopCycle.kt):

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

Thanks to `allOpen`, this class compiles as `open class WhoopCycle` instead of `final class WhoopCycle`, allowing Hibernate to create proxies.

---

### Test configuration

```kotlin
tasks.withType<Test> {
    useJUnitPlatform()
}
```

Configure Gradle to use JUnit Platform (JUnit 5) as the test execution engine. Without this line, Gradle uses JUnit 4 by default and does not find tests annotated with `@Test` from JUnit 5.

---

## Why this decision?

### Why Gradle Kotlin DSL and not Groovy?

- **Type-safety**: The IDE can check for errors in the build script at write time
- **Autocomplete**: IntelliJ IDEA can suggest methods and properties
- **Consistency**: The entire project is in Kotlin, including the build script

### Why kapt and not KSP for MapStruct?

**KSP** (Kotlin Symbol Processing) is faster than kapt, but MapStruct 1.6.3 does not yet have official support for KSP. Using kapt is the only functional option currently.

### Why should Kotlin plugin versions match?

All Kotlin plugins (`jvm`, `plugin.spring`, `plugin.jpa`, `kapt`) must have the **same version** (2.2.21). If they differ, the Kotlin compiler may produce errors or unexpected behavior.

---

## Official documentation

- [Gradle Kotlin DSL Primer](https://docs.gradle.org/current/userguide/kotlin_dsl.html)
- [Spring Boot Gradle Plugin](https://docs.spring.io/spring-boot/gradle-plugin/)
- [Kotlin Gradle Plugin](https://kotlinlang.org/docs/gradle-configure-project.html)
- [kapt (Kotlin Annotation Processing)](https://kotlinlang.org/docs/kapt.html)
- [allOpen Compiler Plugin](https://kotlinlang.org/docs/all-open-plugin.html)
- [No-arg Compiler Plugin](https://kotlinlang.org/docs/no-arg-plugin.html)
- [MapStruct Reference](https://mapstruct.org/documentation/stable/reference/html/)
- [Spring Boot Dependency Management](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html)
- [Java Toolchains in Gradle](https://docs.gradle.org/current/userguide/toolchains.html)
