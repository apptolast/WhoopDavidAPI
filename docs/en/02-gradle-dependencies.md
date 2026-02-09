# 02 - Gradle and Dependencies

## What is it?

**Gradle** is the project's build system (compilation, packaging, tests). We use **Gradle with [Kotlin DSL](https://docs.gradle.org/current/userguide/kotlin_dsl.html)** (`build.gradle.kts`), which means that the configuration file is written in Kotlin instead of classic Groovy (`build.gradle`).

Gradle is responsible for:

- **Download dependencies** (libraries that the project needs) from Maven Central
- **Compile** the Kotlin code to JVM bytecode
- **Run tests** with JUnit
- **Package** the application into an executable JAR (`bootJar`)
- **Process annotations** with [kapt](https://kotlinlang.org/docs/kapt.html) (for [MapStruct](https://mapstruct.org/documentation/stable/reference/html/))

---

## Where is it used in this project?

**Main file**: [`build.gradle.kts`](../../build.gradle.kts)

**Supplementary file**: [`settings.gradle.kts`](../settings.gradle.kts) (only defines the name of the root project)

```kotlin
// settings.gradle.kts
rootProject.name = "whoop-david-api"
```

---

## Explained code

### Plugin block

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

1. **`kotlin("jvm")`**: Base Kotlin plugin for JVM. Compiles `.kt` files to Java bytecode. Without this plugin, Gradle does not know how to compile Kotlin

2. **`kotlin("plugin.spring")`**: Alias of [`kotlin-allopen`](https://kotlinlang.org/docs/all-open-plugin.html) configured for Spring. In Kotlin, all classes are `final` by default. Spring needs to create proxies of classes (for `@Transactional`, `@Configuration`, etc.), and proxies require non-final classes. This plugin automatically opens classes annotated with:
   - `@Component`, `@Service`, `@Controller`, `@Repository`
   - `@Configuration`
   - `@Transactional`
   - `@Async`
   - `@Cacheable`

3. **`kotlin("plugin.jpa")`**: Alias of [`kotlin-noarg`](https://kotlinlang.org/docs/no-arg-plugin.html) configured for JPA. Hibernate needs no-argument constructors in entities to be able to instantiate them via reflection. Kotlin with `data class` or classes with parameters in the constructor does not have an empty constructor by default. This plugin generates no-argument constructors (invisible in the code) for classes annotated with `@Entity`, `@MappedSuperclass` and `@Embeddable`

4. **`kotlin("kapt")`**: **Kotlin Annotation Processing Tool**. Required so that MapStruct can generate code at compile time. kapt is the bridge between Java annotation processors and the Kotlin compiler. Without kapt, MapStruct would not generate the mapper implementations

5. **`org.springframework.boot`**: The [Spring Boot plugin](https://docs.spring.io/spring-boot/gradle-plugin/) that adds:
   - Task `bootJar`: package the application as an executable fat JAR with all dependencies included
   - Task `bootRun`: run the application directly from Gradle
   - Version management for Spring Boot dependencies

6. **`io.spring.dependency-management`**: Manages the versions of ALL Spring Boot transitive dependencies. Thanks to this plugin, when you write `implementation("org.springframework.boot:spring-boot-starter-data-jpa")` you don’t need to specify the version: the plugin automatically resolves it to the version compatible with Spring Boot 4.0.2

### Java Configuration

```kotlin
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(24)
    }
}
```

This configures the **Java Toolchain**: Gradle will automatically download and use Java 24 (JDK) to compile the project, regardless of the Java version installed on the system. This ensures that all developers and CI use the same Java version.

### Repositories

```kotlin
repositories {
    mavenCentral()
}
```

Dependencies are downloaded from **Maven Central**, the largest public repository of Java/Kotlin artifacts. It is the equivalent of npm for JavaScript.

---

### Explained dependencies

#### Spring Boot Starters

**starters** are convenience packages that bundle several related dependencies. Instead of adding 15 dependencies to configure JPA, you add a single starter.

```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
```

**What it does**: Exposes [monitoring endpoints](https://docs.spring.io/spring-boot/reference/actuator/index.html) such as `/actuator/health` and `/actuator/info`.
**Where it is used**: Configured in [`src/main/resources/application.yaml`](../../src/main/resources/application.yaml) (lines `management:...`). The Dockerfile uses it for the HEALTHCHECK.
**If you remove it**: There are no monitoring endpoints. The Docker HEALTHCHECK would fail. Kubernetes wouldn’t be able to verify whether the app is alive.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
```

**What it does**: Includes [Spring Data JPA](https://docs.spring.io/spring-boot/reference/data/sql.html) + Hibernate (ORM) + [HikariCP](https://github.com/brettwooldridge/HikariCP) (connection pool). It allows you to define Repository interfaces that Spring implements automatically.
**Where it is used**: All entities in [`model/entity/`](../../src/main/kotlin/com/example/whoopdavidapi/model/entity/) and repositories in [`repository/`](../../src/main/kotlin/com/example/whoopdavidapi/repository/).
**If you remove it**: There is no database access. Nothing works.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security")
```

**What it does**: Includes [Spring Security](https://docs.spring.io/spring-security/reference/). By default, it protects ALL endpoints with authentication.
**Where it is used**: [`config/SecurityConfig.kt`](../../src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt) - defines the 4 security strings.
**If you remove it**: The API is left completely open. Anyone can access the data.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
```

**What it does**: Adds support for the [OAuth2 client](https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html). Allows the app to act as an OAuth2 client to authenticate against Whoop.
**Where it is used**: The OAuth2 flow in [`config/SecurityConfig.kt`](../../src/main/kotlin/com/example/whoopdavidapi/config/SecurityConfig.kt) (`oauth2Login { }`) and the provider configuration in [`application.yaml`](../../src/main/resources/application.yaml) (`spring.security.oauth2.client`).
**If you remove it**: You can’t authenticate against the Whoop API. You can’t obtain the initial OAuth2 token.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-validation")
```

**What it does**: Includes Jakarta Bean Validation (Hibernate Validator). Allows validating parameters with annotations like `@NotNull`, `@Size`, etc.
**Where it is used**: Validations with `require()` in the controllers, for example in [`controller/CycleController.kt`](../../src/main/kotlin/com/example/whoopdavidapi/controller/CycleController.kt):

```kotlin
require(page >= 1) { "page debe ser >= 1" }
require(pageSize in 1..1000) { "pageSize debe estar entre 1 y 1000" }
```

**If you remove it**: Manual validations with `require()` still work (they’re from Kotlin), but you lose the `@Valid` and `@Validated` infrastructure if you need it in the future.

---

```kotlin
implementation("org.springframework.boot:spring-boot-starter-webmvc")
```

**What it does**: Includes [Spring Web MVC](https://docs.spring.io/spring-boot/reference/web/servlet.html) + embedded Tomcat. It is the core of the web server.
**Where it is used**: All controllers in [`controller/`](../../src/main/kotlin/com/example/whoopdavidapi/controller/) use `@RestController` and `@GetMapping` from WebMVC.
**If you remove it**: There is no web server. The application cannot receive HTTP requests.

---

```kotlin
implementation("org.springframework.boot:spring-boot-h2console")
```

**What it does**: Enables the H2 web console (graphical interface to view the in-memory DB during development).
**Where it is used**: Configured in [`application-dev.yaml`](../../src/main/resources/application-dev.yaml) (`spring.h2.console.enabled: true`). Accessible in `http://localhost:8080/h2-console`.
**If you remove it**: You can’t view the H2 DB from the browser in development. The DB keeps working.

---

#### Kotlin

```kotlin
implementation("org.jetbrains.kotlin:kotlin-reflect")
```

**What it does**: Kotlin reflection library. Spring needs it to inspect Kotlin classes at runtime (read annotations, create instances, etc.).
**Where it is used**: Internally by Spring Framework, Jackson, and Hibernate.
**If you remove it**: Spring can’t work correctly with Kotlin classes. Runtime errors.

---

```kotlin
implementation("tools.jackson.module:jackson-module-kotlin")
```

**What it does**: Jackson module for Kotlin. Allows Jackson to correctly serialize/deserialize Kotlin `data class` (recognizes constructor parameters, nullable types, default values, etc.).
**Where it is used**: Automatically by Spring MVC to convert objects to JSON in controller responses.
**If you remove it**: Jackson can’t deserialize Kotlin DTOs. The endpoints return errors.

> **GOTCHA Spring Boot 4**: The package changed from `com.fasterxml.jackson.module:jackson-module-kotlin` (Jackson 2) to `tools.jackson.module:jackson-module-kotlin` (Jackson 3). Spring Boot 4 uses **Jackson 3**, which changed its Maven group from `com.fasterxml` to `tools.jackson`.

---

#### MapStruct

```kotlin
implementation("org.mapstruct:mapstruct:1.6.3")
kapt("org.mapstruct:mapstruct-processor:1.6.3")
```

**What it does**: MapStruct automatically generates the mapping code between Entity and DTO at compile time. `mapstruct` is the annotations library. `mapstruct-processor` is the annotation processor that generates the implementations.
**Where it is used**: The 4 mappers in [`mapper/`](../../src/main/kotlin/com/example/whoopdavidapi/mapper/). For example, [`mapper/CycleMapper.kt`](../../src/main/kotlin/com/example/whoopdavidapi/mapper/CycleMapper.kt):

```kotlin
@Mapper(componentModel = "spring")
interface CycleMapper {
    fun toDto(entity: WhoopCycle): CycleDTO
    fun toEntity(dto: CycleDTO): WhoopCycle
}
```

MapStruct automatically generates a `CycleMapperImpl` class with field-by-field mapping code.
**If you remove it**: You need to write the mapping by hand in each service. With 4 entities and many fields, it’s a lot of repetitive code.

---

#### Resilience4j

```kotlin
implementation("io.github.resilience4j:resilience4j-spring-boot3:2.3.0")
implementation("org.springframework.boot:spring-boot-starter-aspectj")
```

**What it does**: [Resilience4j](https://resilience4j.readme.io/docs/getting-started-3) provides resilience patterns (circuit breaker, retry, rate limiter) via annotations. The [AspectJ](https://docs.spring.io/spring-framework/reference/core/aop.html) starter is necessary because Resilience4j uses AOP (Aspect-Oriented Programming) to intercept calls to annotated methods.
**Where it is used**: [`client/WhoopApiClient.kt`](../../src/main/kotlin/com/example/whoopdavidapi/client/WhoopApiClient.kt) - each method has `@CircuitBreaker`, `@Retry` and `@RateLimiter`:

```kotlin
@CircuitBreaker(name = "whoopApi", fallbackMethod = "fallbackGetAllRecords")
@Retry(name = "whoopApi")
@RateLimiter(name = "whoopApi")
fun getAllCycles(start: Instant? = null, end: Instant? = null): List<Map<String, Any?>> {
    return getAllRecords("/developer/v1/cycle", start, end)
}
```

Configured in [`application.yaml`](../../src/main/resources/application.yaml) (section `resilience4j:`).
**If you remove resilience4j**: Whoop API errors propagate directly. There are no retries or circuit breaker. Synchronization fails completely if Whoop has temporary issues.
**If you remove starter-aspectj**: Resilience4j annotations are silently ignored. The code runs but without protection.

> **GOTCHA Spring Boot 4**: The starter was renamed from `spring-boot-starter-aop` to `spring-boot-starter-aspectj`. If you use the old name, Gradle can’t find it.

---

#### OpenAPI / Swagger UI

```kotlin
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")
```

**What it does**: Automatically generates OpenAPI 3.0 documentation from the controllers and exposes it in Swagger UI.
**Where it is used**: Configured in [`config/OpenApiConfig.kt`](../../src/main/kotlin/com/example/whoopdavidapi/config/OpenApiConfig.kt) and [`application.yaml`](../../src/main/resources/application.yaml) (section `springdoc:`). Swagger UI accessible at `/swagger-ui/index.html`.
**If you remove it**: There is no Swagger UI or automatic API documentation.

> **GOTCHA Spring Boot 4**: [springdoc-openapi](https://springdoc.org/) **v3.x** is for Spring Boot 4. Version **v2.x** is for Spring Boot 3. If you use v2.x with Spring Boot 4, it fails due to incompatibilities with Jackson 3.

---

#### Database

```kotlin
runtimeOnly("com.h2database:h2")
runtimeOnly("org.postgresql:postgresql")
```

**What they do**: JDBC drivers for [H2](https://www.h2database.com/) (in-memory DB) and [PostgreSQL](https://jdbc.postgresql.org/). `runtimeOnly` means they are only needed at runtime, not at compile time.
**Where they are used**:

- H2: [`application-dev.yaml`](../../src/main/resources/application-dev.yaml) - development profile
- PostgreSQL: [`application-prod.yaml`](../../src/main/resources/application-prod.yaml) - production profile
**If you remove H2**: The `dev` profile does not work (it has no DB)
**If you remove PostgreSQL**: The `prod` profile does not work (it cannot connect to the DB)

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

| Dependency | What it provides |
|---|---|
| `actuator-test` | Utilities for testing actuator |
| `data-jpa-test` | `@DataJpaTest` for repository tests with an embedded DB |
| `security-test` | `httpBasic()`, `csrf()` and other utilities for testing security |
| `webmvc-test` | `MockMvc`, `@WebMvcTest`, `@AutoConfigureMockMvc` |
| `kotlin-test-junit5` | Kotlin Assertions + integration with JUnit 5 |
| `junit-platform-launcher` | JUnit 5 test runner |

> **GOTCHA Spring Boot 4**: The packages for the test annotations changed:
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

1. **`correctErrorTypes = true`**: Fixes type errors when kapt cannot resolve a reference. Required for kapt to work properly with Kotlin
2. **`includeCompileClasspath = false`**: Prevents kapt from processing the entire compilation classpath (better performance)
3. **`mapstruct.defaultComponentModel = "spring"`**: Tells MapStruct to generate the mappers as Spring beans (`@Component`). This way they can be injected with `@Autowired` or via constructor

---

### Disable kapt for tests

```kotlin
// Desactivar kapt para test sources (no hay annotation processors en tests)
tasks.matching { it.name == "kaptTestKotlin" || it.name == "kaptGenerateStubsTestKotlin" }.configureEach {
    enabled = false
}
```

> **GOTCHA Spring Boot 4**: kapt tries to process the test annotations (`@DataJpaTest`, `@WebMvcTest`, etc.) from Spring Boot 4 and fails because these annotations changed packages. Since there are no annotation processors needed in tests, we disable kapt for tests entirely.

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

- **`-Xjsr305=strict`**: Makes Kotlin treat Java nullability annotations (`@Nullable`, `@NonNull` from JSR-305) as strict. This means that if a Spring API declares a parameter as `@NonNull`, Kotlin treats it as non-nullable (`String` instead of `String?`). It improves type safety in Kotlin/Java interoperability.

- **`-Xannotation-default-target=param-property`**: Controls where annotations are placed on Kotlin properties. By default in Kotlin constructors, an annotation like `@Column` could be applied to the constructor parameter instead of the field. With this option, it is applied to both the parameter and the property, which is necessary for JPA/Hibernate to detect them correctly.

---

### allOpen plugin for JPA

```kotlin
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
```

As mentioned, in Kotlin all classes are `final` by default. Hibernate needs to create proxies (subclasses) of entities for features such as lazy loading. `final` classes cannot have subclasses.

Block `allOpen` complements plugin `kotlin("plugin.jpa")`:

- `plugin.jpa` generates no-argument constructors
- `allOpen` opens the classes (makes them non-final)

Both are necessary for JPA/Hibernate to work correctly with Kotlin.

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

Thanks to `allOpen`, this class is compiled as `open class WhoopCycle` instead of `final class WhoopCycle`, allowing Hibernate to create proxies.

---

### Test configuration

```kotlin
tasks.withType<Test> {
    useJUnitPlatform()
}
```

Configure Gradle to use the JUnit Platform (JUnit 5) as the test execution engine. Without this line, Gradle uses JUnit 4 by default and does not find tests annotated with JUnit 5’s `@Test`.

---

## Why this decision?

### Why Gradle Kotlin DSL and not Groovy?

- **Type-safety**: The IDE can check for errors in the build script at write time
- **Autocomplete**: IntelliJ IDEA can suggest methods and properties
- **Consistency**: The entire project is in Kotlin, including the build script

### Why kapt and not KSP for MapStruct?

**KSP** (Kotlin Symbol Processing) is faster than kapt, but MapStruct 1.6.3 still does not have official support for KSP. Using kapt is the only functional option currently.

### Why do the versions of the Kotlin plugins have to match?

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
