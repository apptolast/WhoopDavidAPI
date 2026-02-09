# 12. Testing in Spring Boot

## Index

1. [What is testing in Spring Boot](#1-what-is-testing-in-spring-boot)
2. [The testing pyramid](#2-the-testing-pyramid)
3. [Where it is used in our project](#3-where-is-it-used-in-our-project)
4. [Why do we test like this](#4-why-do-we-test-like-this)
5. [Key annotations explained](#5-key-annotations-explained)
6. [Explained code](#6-explained-code)
7. [Spring Boot 4 Gotchas](#7-spring-boot-4-gotchas)
8. [Configuration in build.gradle.kts](#8-configuration-in-buildgradlekts)
9. [Official documentation](#9-official-documentation)

---

## 1. What is testing in Spring Boot?

Spring Boot provides an integrated testing framework that allows you to verify the behavior of your application at different levels: from isolated functions (unit tests) to the complete application running with database, security, and HTTP (integration tests).

The framework is based on **[JUnit 5](https://docs.junit.org/6.0.2/user-guide/)** as the execution engine and provides specialized annotations that bring up only the parts of the Spring context you need for each type of test.

---

## 2. The testing pyramid

```
          /\
         /  \          Tests E2E (end-to-end)
        /    \         - App completa desplegada
       /------\        - Lentos, fragiles, pocos
      /        \
     /  Integr. \      Tests de integracion
    /            \     - @SpringBootTest, @DataJpaTest
   /--------------\    - Levantan contexto Spring parcial o completo
  /                \
 /   Tests Unit.    \  Tests unitarios
/____________________\ - @ExtendWith(MockitoExtension::class)
                       - Rapidos, sin Spring, muchos
```

| Level | Speed | Spring Context | Example in the project |
|-------|-----------|-----------------|------------------------|
| **Unitary** | Very fast | No | `CycleServiceTest` |
| **Integration (JPA)** | Medium | JPA layer only | `CycleRepositoryTest` |
| **Integration (Web)** | Medium-slow | Complete + [MockMvc](https://docs.spring.io/spring-framework/reference/testing/mockmvc.html) | `CycleControllerTest` |
| **Full context** | Slow | Everything | `WhoopDavidApiApplicationTests` |

**General rule**: the lower you are in the pyramid, the more tests you should have. Unit tests are fast and cheap; integration tests are slow but verify that the pieces fit together.

---

## 3. Where is it used in our project?

All the tests are in `src/test/kotlin/com/example/whoopdavidapi/`:

```
src/test/kotlin/com/example/whoopdavidapi/
├── WhoopDavidApiApplicationTests.kt          # Test de carga del contexto
├── repository/
│   └── CycleRepositoryTest.kt                # Tests de repositorio JPA
├── service/
│   └── CycleServiceTest.kt                   # Tests unitarios del servicio
└── controller/
    └── CycleControllerTest.kt                # Tests de integracion HTTP
```

They are executed with:

```bash
./gradlew build      # Compila + ejecuta tests
./gradlew test       # Solo ejecuta tests
```

---

## 4. Why do we test like this?

| Decision | Reason |
|----------|-------|
| [`@ActiveProfiles("dev")`](https://docs.spring.io/spring-framework/reference/testing/annotations.html) in all tests | Use in-memory H2 instead of a real PostgreSQL instance |
| [`@ExtendWith(MockitoExtension::class)`](https://docs.junit.org/6.0.2/user-guide/#extensions) for services | We don’t need Spring to test pure business logic. |
| [`@DataJpaTest`](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.autoconfigured-spring-data-jpa) for repositories | Start only JPA + H2, much faster than [`@SpringBootTest`](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html) |
| `@SpringBootTest` + [`@AutoConfigureMockMvc`](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html) for controllers | We need security (Basic Auth) and real HTTP to verify endpoints |
| [`@MockitoBean`](https://docs.spring.io/spring-framework/reference/testing/annotations.html) to inject mocks in integration tests | Isolate the controller from its real dependencies (service, repository) |
| `@Import(TokenEncryptor::class)` in `@DataJpaTest` | `@DataJpaTest` does not load all the beans, but `OAuthTokenEntity` needs `TokenEncryptor` as a JPA converter |

---

## 5. Key Annotations Explained

### `@SpringBootTest`

It loads the **full context** of the Spring application. It is the heaviest annotation: it brings up all the beans, configuration, security, JPA, etc.

```kotlin
@SpringBootTest  // Levanta toda la aplicacion
@ActiveProfiles("dev")  // Usa application-dev.yaml (H2 en memoria)
class WhoopDavidApiApplicationTests {
    @Test
    fun contextLoads() {
        // Si este test pasa, toda la configuracion de Spring es correcta
    }
}
```

**When to use it**: to verify that the entire application starts without errors, or for full integration tests.

### `@DataJpaTest`

Load **only the JPA layer**: entities, repositories, Hibernate, and an embedded database (H2). **Do not** load controllers, services, security, or anything else.

```kotlin
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest  // Spring Boot 4

@DataJpaTest
@Import(TokenEncryptor::class)  // Bean extra necesario para el converter JPA
@ActiveProfiles("dev")
class CycleRepositoryTest { ... }
```

**When to use it**: to test repository queries, validate that JPA entities are mapped correctly to the DB, test pagination.

### `@WebMvcTest` and `@AutoConfigureMockMvc`

Both are used to test the web layer (controllers), but in different ways:

- **[`@WebMvcTest(CycleController::class)`](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.spring-mvc-tests)**: loads ONLY the specified controller and its web dependencies. It does not load services, repositories, etc. You have to mock everything.
- **`@SpringBootTest + @AutoConfigureMockMvc`**: loads the full context BUT gives you a `MockMvc` to make HTTP requests without spinning up a real server.

In our project we use the second option:

```kotlin
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc  // Spring Boot 4

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CycleControllerTest { ... }
```

**Why `@SpringBootTest + @AutoConfigureMockMvc` instead of `@WebMvcTest`**: because we need the security layer (Spring Security with Basic Auth) to be loaded and configured correctly. `@WebMvcTest` does not load the complete security configuration.

### `@MockitoBean` (replaces `@MockBean`)

Injects a [Mockito](https://site.mockito.org/) mock **into the Spring** context, replacing the real bean. It is used in integration tests to isolate the component under test.

```kotlin
import org.springframework.test.context.bean.override.mockito.MockitoBean  // Spring Boot 4

@MockitoBean
lateinit var cycleService: CycleService  // Spring inyecta un mock en vez del servicio real
```

**Difference with `@Mock`**: `@Mock` is from pure Mockito (without Spring). `@MockitoBean` registers the mock in Spring’s ApplicationContext so that the beans that depend on it receive the mock.

### `@ExtendWith(MockitoExtension::class)`

Enable Mockito in a test **without Spring**. It’s the way to write pure unit tests: fast, without a Spring context, just business logic.

```kotlin
@ExtendWith(MockitoExtension::class)  // Activa @Mock, @InjectMocks, etc.
class CycleServiceTest {
    @Mock lateinit var cycleRepository: CycleRepository    // Mock puro de Mockito
    @Mock lateinit var cycleMapper: CycleMapper            // Mock puro de Mockito
    @InjectMocks lateinit var cycleService: CycleService   // Inyecta los mocks arriba
}
```

### `MockMvc`

It is a Spring tool that simulates HTTP requests without starting a real server. It allows you to test controllers, security, JSON serialization, response codes, etc.

```kotlin
mockMvc.perform(
    get("/api/v1/cycles")                                // Simula GET /api/v1/cycles
        .with(httpBasic("powerbi", "changeme"))          // Envia cabecera Basic Auth
)
    .andExpect(status().isOk)                            // Verifica HTTP 200
    .andExpect(jsonPath("$.data").isArray)                // Verifica que $.data es un array
    .andExpect(jsonPath("$.data[0].id").value(1))        // Verifica el valor del campo
    .andExpect(jsonPath("$.pagination.hasMore").value(false))
```

---

## 6. Explained code

### Test 1: Context loading (`WhoopDavidApiApplicationTests.kt`)

**File**: `src/test/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplicationTests.kt`

```kotlin
package com.example.whoopdavidapi

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("dev")
class WhoopDavidApiApplicationTests {

    @Test
    fun contextLoads() {
    }
}
```

**What it does**: verifies that the entire Spring Boot application starts without errors. If there is a misconfigured bean, a circular dependency, a `application.yaml` with errors, or any configuration issue, this test fails.

**Why is it empty**: the test doesn’t need to do anything. The mere fact that `@SpringBootTest` brings up the context without throwing an exception already verifies that everything is correct.

**`@ActiveProfiles("dev")`**: activates the `dev` profile, which uses in-memory H2 instead of PostgreSQL. This way, the tests don’t need an external database.

---

### Test 2: JPA Repository (`CycleRepositoryTest.kt`)

**File**: `src/test/kotlin/com/example/whoopdavidapi/repository/CycleRepositoryTest.kt`

```kotlin
package com.example.whoopdavidapi.repository

import com.example.whoopdavidapi.model.entity.WhoopCycle
import com.example.whoopdavidapi.util.TokenEncryptor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@DataJpaTest
@Import(TokenEncryptor::class)
@ActiveProfiles("dev")
class CycleRepositoryTest {

    @Autowired
    lateinit var cycleRepository: CycleRepository

    @Test
    fun `guardar y recuperar un ciclo`() {
        val cycle = WhoopCycle(
            id = 12345L,
            userId = 1L,
            start = Instant.parse("2024-01-15T08:00:00Z"),
            end = Instant.parse("2024-01-16T08:00:00Z"),
            scoreState = "SCORED",
            strain = 15.5f,
            kilojoule = 2500.0f,
            averageHeartRate = 72,
            maxHeartRate = 185
        )
        cycleRepository.save(cycle)

        val found = cycleRepository.findById(12345L)
        assertTrue(found.isPresent)
        assertEquals(15.5f, found.get().strain)
        assertEquals(72, found.get().averageHeartRate)
    }
```

Line by line:

- **`@DataJpaTest`**: starts up only Hibernate + H2. It does not load controllers, services, or security.
- **`@Import(TokenEncryptor::class)`**: `@DataJpaTest` only loads the JPA beans. But the `OAuthTokenEntity` entity uses a `@Convert(converter = TokenEncryptorConverter::class)` that needs `TokenEncryptor`. Without this `@Import`, the context fails when trying to create the table.
- **`@Autowired lateinit var cycleRepository`**: Spring injects the real repository (not a mock). The queries run against in-memory H2.
- **`cycleRepository.save(cycle)`**: runs a `INSERT INTO whoop_cycle ...` against H2.
- **`cycleRepository.findById(12345L)`**: runs a `SELECT * FROM whoop_cycle WHERE id = 12345`.
- **`assertTrue(found.isPresent)`**: verify that the cycle was persisted and can be retrieved.

```kotlin
    @Test
    fun `filtrar ciclos por rango de fechas`() {
        val cycle1 = WhoopCycle(id = 1L, userId = 1L, start = Instant.parse("2024-01-10T00:00:00Z"), scoreState = "SCORED")
        val cycle2 = WhoopCycle(id = 2L, userId = 1L, start = Instant.parse("2024-01-15T00:00:00Z"), scoreState = "SCORED")
        val cycle3 = WhoopCycle(id = 3L, userId = 1L, start = Instant.parse("2024-01-20T00:00:00Z"), scoreState = "SCORED")
        cycleRepository.saveAll(listOf(cycle1, cycle2, cycle3))

        val result = cycleRepository.findByStartBetween(
            Instant.parse("2024-01-12T00:00:00Z"),
            Instant.parse("2024-01-18T00:00:00Z"),
            PageRequest.of(0, 10)
        )

        assertEquals(1, result.totalElements)
        assertEquals(2L, result.content[0].id)
    }
```

**What it verifies**: that the `findByStartBetween` method of the repository correctly generates the SQL query `WHERE start BETWEEN ? AND ?`. Three cycles with different dates are saved, and it is expected that only 1 falls within the range.

```kotlin
    @Test
    fun `paginacion funciona correctamente`() {
        for (i in 1L..5L) {
            cycleRepository.save(
                WhoopCycle(id = i, userId = 1L, start = Instant.now().minusSeconds(i * 86400), scoreState = "SCORED")
            )
        }

        val page1 = cycleRepository.findAll(PageRequest.of(0, 2))
        assertEquals(2, page1.content.size)
        assertEquals(5, page1.totalElements)
        assertTrue(page1.hasNext())
    }
```

**What it verifies**: that `PageRequest.of(0, 2)` returns only 2 results out of the 5 saved, that `totalElements` is 5 (the real total), and that `hasNext()` is `true` (there are more pages).

---

### Test 3: Service with Mockito (`CycleServiceTest.kt`)

**File**: `src/test/kotlin/com/example/whoopdavidapi/service/CycleServiceTest.kt`

```kotlin
package com.example.whoopdavidapi.service

import com.example.whoopdavidapi.mapper.CycleMapper
import com.example.whoopdavidapi.model.dto.CycleDTO
import com.example.whoopdavidapi.model.entity.WhoopCycle
import com.example.whoopdavidapi.repository.CycleRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class CycleServiceTest {

    @Mock
    lateinit var cycleRepository: CycleRepository

    @Mock
    lateinit var cycleMapper: CycleMapper

    @InjectMocks
    lateinit var cycleService: CycleService
```

**Anatomy**:

- **`@ExtendWith(MockitoExtension::class)`**: It does NOT start Spring. It only activates Mockito annotations (`@Mock`, `@InjectMocks`). This makes the test very fast.
- **`@Mock lateinit var cycleRepository`**: creates a fake object that simulates the repository. When you tell it `when(cycleRepository.findAll(...)).thenReturn(...)`, it returns whatever you specify without going to the DB.
- **`@Mock lateinit var cycleMapper`**: same for the mapper. It doesn’t run MapStruct; it returns whatever you configure.
- **`@InjectMocks lateinit var cycleService`**: creates a real instance of `CycleService` and injects the previous mocks into its dependencies.

```kotlin
    @Test
    fun `getCycles sin filtros devuelve todos los ciclos paginados`() {
        val entity = WhoopCycle(
            id = 1L, userId = 100L,
            start = Instant.parse("2024-01-15T08:00:00Z"),
            scoreState = "SCORED", strain = 15.5f
        )
        val dto = CycleDTO(
            id = 1L, userId = 100L,
            createdAt = null, updatedAt = null,
            start = Instant.parse("2024-01-15T08:00:00Z"),
            end = null, timezoneOffset = null,
            scoreState = "SCORED", strain = 15.5f,
            kilojoule = null, averageHeartRate = null, maxHeartRate = null
        )

        val pageable = PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "start"))
        val page = PageImpl(listOf(entity), pageable, 1)

        `when`(cycleRepository.findAll(pageable)).thenReturn(page)
        `when`(cycleMapper.toDto(entity)).thenReturn(dto)

        val result = cycleService.getCycles(null, null, 1, 100)

        assertEquals(1, result.data.size)
        assertEquals(1L, result.data[0].id)
        assertEquals(15.5f, result.data[0].strain)
        assertEquals(1, result.pagination.page)
        assertEquals(false, result.pagination.hasMore)
    }
```

**Test flow**:

1. **Prepare data**: create an entity `WhoopCycle` and a DTO `CycleDTO`.
2. **Configure mocks**: when the service calls `cycleRepository.findAll(pageable)`, return the page with the entity. When it calls `cycleMapper.toDto(entity)`, return the DTO.
3. **Execute**: calls `cycleService.getCycles(null, null, 1, 100)` -- this invokes the real service logic, which uses the mocks.
4. **Verify**: checks that the result has the expected values.

**What we are really testing**: the service logic -- how it builds the `Pageable`, how it decides which repository method to call (with filters or without them), how it transforms entities into DTOs, how it builds the `PaginatedResponse`.

```kotlin
    @Test
    fun `getCycles con filtro from usa findByStartGreaterThanEqual`() {
        val from = Instant.parse("2024-01-10T00:00:00Z")
        val pageable = PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "start"))
        val emptyPage = PageImpl(emptyList<WhoopCycle>(), pageable, 0)

        `when`(cycleRepository.findByStartGreaterThanEqual(from, pageable)).thenReturn(emptyPage)

        val result = cycleService.getCycles(from, null, 1, 100)

        assertEquals(0, result.data.size)
        assertEquals(0, result.pagination.totalCount)
    }
```

**What it verifies**: that when a `from` parameter is passed, the service calls `findByStartGreaterThanEqual` instead of `findAll`. If the service were to call the incorrect method, Mockito would not have the response configured and the test would fail.

---

### Test 4: Controller with MockMvc (`CycleControllerTest.kt`)

**File**: `src/test/kotlin/com/example/whoopdavidapi/controller/CycleControllerTest.kt`

```kotlin
package com.example.whoopdavidapi.controller

import com.example.whoopdavidapi.model.dto.CycleDTO
import com.example.whoopdavidapi.model.dto.PaginatedResponse
import com.example.whoopdavidapi.model.dto.PaginationInfo
import com.example.whoopdavidapi.service.CycleService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Instant
```

Look at the imports — here are the Spring Boot 4 changes:

- **`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`**: it used to be `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc`
- **`org.springframework.test.context.bean.override.mockito.MockitoBean`**: it used to be `org.springframework.boot.test.mock.mockito.MockBean`

```kotlin
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CycleControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var cycleService: CycleService
```

- **`@SpringBootTest`**: brings up the entire context (including security).
- **`@AutoConfigureMockMvc`**: creates an auto-configured `MockMvc` that Spring injects.
- **`@MockitoBean lateinit var cycleService`**: replaces the real `CycleService` in the Spring context with a mock. This way the controller calls the mock instead of the real service.

```kotlin
    @Test
    fun `GET cycles sin autenticacion devuelve 401`() {
        mockMvc.perform(get("/api/v1/cycles"))
            .andExpect(status().isUnauthorized)
    }
```

**What it verifies**: that Spring Security is configured correctly and rejects requests to `/api/v1/cycles` without credentials. Returns HTTP 401 Unauthorized.

```kotlin
    @Test
    fun `GET cycles con Basic Auth devuelve 200`() {
        val response = PaginatedResponse(
            data = listOf(
                CycleDTO(
                    id = 1L, userId = 100L,
                    createdAt = null, updatedAt = null,
                    start = Instant.parse("2024-01-15T08:00:00Z"),
                    end = Instant.parse("2024-01-16T08:00:00Z"),
                    timezoneOffset = null,
                    scoreState = "SCORED", strain = 15.5f,
                    kilojoule = 2500.0f, averageHeartRate = 72, maxHeartRate = 185
                )
            ),
            pagination = PaginationInfo(page = 1, pageSize = 100, totalCount = 1, hasMore = false)
        )

        `when`(cycleService.getCycles(null, null, 1, 100)).thenReturn(response)

        mockMvc.perform(
            get("/api/v1/cycles")
                .with(httpBasic("powerbi", "changeme"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data[0].id").value(1))
            .andExpect(jsonPath("$.data[0].strain").value(15.5))
            .andExpect(jsonPath("$.pagination.totalCount").value(1))
            .andExpect(jsonPath("$.pagination.hasMore").value(false))
    }
```

**What it checks**:

1. That with Basic Auth (`powerbi`/`changeme`) you get HTTP 200.
2. That the JSON response has the correct structure (`$.data` is an array, `$.pagination` has the expected fields).
3. That Jackson correctly serializes the DTOs to JSON.

```kotlin
    @Test
    fun `GET cycles con parametros de paginacion`() {
        val response = PaginatedResponse(
            data = emptyList<CycleDTO>(),
            pagination = PaginationInfo(page = 2, pageSize = 50, totalCount = 0, hasMore = false)
        )

        `when`(cycleService.getCycles(null, null, 2, 50)).thenReturn(response)

        mockMvc.perform(
            get("/api/v1/cycles")
                .param("page", "2")
                .param("pageSize", "50")
                .with(httpBasic("powerbi", "changeme"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pagination.page").value(2))
            .andExpect(jsonPath("$.pagination.pageSize").value(50))
    }
```

**What it verifies**: that the query parameters `?page=2&pageSize=50` are passed correctly to the service. It verifies that the binding of `@RequestParam` works.

---

## 7. Spring Boot 4 Gotchas

Spring Boot 4.0.2 introduced important changes to the packages of test annotations. If you’re coming from Spring Boot 3.x, these are the migrations:

### The test annotation packages changed

| Annotation | Spring Boot 3.x | Spring Boot 4.x |
|-----------|-----------------|-----------------|
| `@DataJpaTest` | `org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest` | `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` |
| `@WebMvcTest` | `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| `@AutoConfigureMockMvc` | `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` |
| `@MockBean` | `org.springframework.boot.test.mock.mockito.MockBean` | **Deleted** -- use `@MockitoBean` |
| `@MockitoBean` | It didn’t exist | `org.springframework.test.context.bean.override.mockito.MockitoBean` |

### `@MockBean` no longer exists -- use `@MockitoBean`

In Spring Boot 3.x, `@MockBean` from the `org.springframework.boot.test.mock.mockito` package was used. In Spring Boot 4.x, this annotation was replaced by `@MockitoBean` from the `org.springframework.test.context.bean.override.mockito` package.

Before (Spring Boot 3):

```kotlin
import org.springframework.boot.test.mock.mockito.MockBean

@MockBean
lateinit var cycleService: CycleService
```

Now (Spring Boot 4):

```kotlin
import org.springframework.test.context.bean.override.mockito.MockitoBean

@MockitoBean
lateinit var cycleService: CycleService
```

### kapt must be disabled for the test sources

If you use `kapt` (for example for MapStruct), the kapt tasks for tests (`kaptTestKotlin`, `kaptGenerateStubsTestKotlin`) may fail because they try to process Spring Boot 4’s new test annotations and don’t recognize them.

The solution is in `build.gradle.kts`:

```kotlin
// Desactivar kapt para test sources (no hay annotation processors en tests)
tasks.matching { it.name == "kaptTestKotlin" || it.name == "kaptGenerateStubsTestKotlin" }.configureEach {
    enabled = false
}
```

This is safe because in our project we don’t have annotation processors that need to run on the test code (MapStruct only processes the mappers in `src/main`).

### Test starters also changed

In `build.gradle.kts`, the test dependencies use the new Spring Boot 4 starters:

```kotlin
testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
testImplementation("org.springframework.boot:spring-boot-starter-security-test")
testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
```

These replace the generic Spring Boot 3.x `spring-boot-starter-test` starters, providing test dependencies organized by module.

---

## 8. Configuration in build.gradle.kts

**File**: `build.gradle.kts`

The parts relevant for testing:

```kotlin
dependencies {
    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- **`spring-boot-starter-*-test`**: includes JUnit 5, Mockito, [AssertJ](https://assertj.github.io/doc/), MockMvc, H2, and other testing tools.
- **`spring-boot-starter-security-test`**: provides `SecurityMockMvcRequestPostProcessors.httpBasic()` to simulate authentication in tests.
- **`kotlin-test-junit5`**: Kotlin integration with JUnit 5.
- **`junit-platform-launcher`**: required at runtime for Gradle to run the tests with JUnit 5.

```kotlin
// Desactivar kapt para test sources
tasks.matching { it.name == "kaptTestKotlin" || it.name == "kaptGenerateStubsTestKotlin" }.configureEach {
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()  // Usa JUnit 5 como motor de tests
}
```

---

## 9. Official documentation

- [Spring Boot Testing - Official Reference](https://docs.spring.io/spring-boot/reference/testing/index.html)
- [JUnit 5 User Guide](https://docs.junit.org/6.0.2/user-guide/)
- [Mockito Framework](https://site.mockito.org/)
- [Spring Security Testing](https://docs.spring.io/spring-security/reference/servlet/test/index.html)
- [MockMvc - Spring Documentation](https://docs.spring.io/spring-framework/reference/testing/mockmvc.html)
- [Spring Boot 4 Release Notes - Test annotations migration](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes)
