# 12. Testing en Spring Boot

## Indice

1. [Que es el testing en Spring Boot](#1-que-es-el-testing-en-spring-boot)
2. [La piramide de testing](#2-la-piramide-de-testing)
3. [Donde se usa en nuestro proyecto](#3-donde-se-usa-en-nuestro-proyecto)
4. [Por que testeamos asi](#4-por-que-testeamos-asi)
5. [Anotaciones clave explicadas](#5-anotaciones-clave-explicadas)
6. [Codigo explicado](#6-codigo-explicado)
7. [Gotchas de Spring Boot 4](#7-gotchas-de-spring-boot-4)
8. [Configuracion en build.gradle.kts](#8-configuracion-en-buildgradlekts)
9. [Documentacion oficial](#9-documentacion-oficial)

---

## 1. Que es el testing en Spring Boot

Spring Boot proporciona un framework de testing integrado que permite verificar el comportamiento de tu aplicacion a distintos niveles: desde funciones aisladas (tests unitarios) hasta la aplicacion completa funcionando con base de datos, seguridad y HTTP (tests de integracion).

El framework se basa en **JUnit 5** como motor de ejecucion y ofrece anotaciones especializadas que levantan solo las partes del contexto de Spring que necesitas para cada tipo de test.

---

## 2. La piramide de testing

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

| Nivel | Velocidad | Contexto Spring | Ejemplo en el proyecto |
|-------|-----------|-----------------|------------------------|
| **Unitario** | Muy rapido | No | `CycleServiceTest` |
| **Integracion (JPA)** | Medio | Solo capa JPA | `CycleRepositoryTest` |
| **Integracion (Web)** | Medio-lento | Completo + MockMvc | `CycleControllerTest` |
| **Contexto completo** | Lento | Todo | `WhoopDavidApiApplicationTests` |

**Regla general**: cuanto mas abajo en la piramide, mas tests deberias tener. Los tests unitarios son rapidos y baratos; los de integracion son lentos pero verifican que las piezas encajan.

---

## 3. Donde se usa en nuestro proyecto

Todos los tests estan en `src/test/kotlin/com/example/whoopdavidapi/`:

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

Se ejecutan con:

```bash
./gradlew build      # Compila + ejecuta tests
./gradlew test       # Solo ejecuta tests
```

---

## 4. Por que testeamos asi

| Decision | Razon |
|----------|-------|
| `@ActiveProfiles("dev")` en todos los tests | Usa H2 en memoria en lugar de PostgreSQL real |
| `@ExtendWith(MockitoExtension::class)` para servicios | No necesitamos Spring para testear logica de negocio pura |
| `@DataJpaTest` para repositorios | Levanta solo JPA + H2, mucho mas rapido que `@SpringBootTest` |
| `@SpringBootTest + @AutoConfigureMockMvc` para controllers | Necesitamos seguridad (Basic Auth) y HTTP real para verificar endpoints |
| `@MockitoBean` para inyectar mocks en tests de integracion | Aisla el controller de sus dependencias reales (servicio, repositorio) |
| `@Import(TokenEncryptor::class)` en `@DataJpaTest` | `@DataJpaTest` no carga todos los beans, pero `OAuthTokenEntity` necesita `TokenEncryptor` como converter JPA |

---

## 5. Anotaciones clave explicadas

### `@SpringBootTest`

Carga el **contexto completo** de la aplicacion Spring. Es la anotacion mas pesada: levanta todos los beans, la configuracion, la seguridad, JPA, etc.

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

**Cuando usarla**: para verificar que toda la aplicacion arranca sin errores, o para tests de integracion completos.

### `@DataJpaTest`

Carga **solo la capa JPA**: entidades, repositorios, Hibernate y una base de datos embebida (H2). **No** carga controllers, servicios, seguridad ni nada mas.

```kotlin
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest  // Spring Boot 4

@DataJpaTest
@Import(TokenEncryptor::class)  // Bean extra necesario para el converter JPA
@ActiveProfiles("dev")
class CycleRepositoryTest { ... }
```

**Cuando usarla**: para testear queries de repositorio, validar que las entidades JPA se mapean bien a la BD, probar paginacion.

### `@WebMvcTest` y `@AutoConfigureMockMvc`

Ambas sirven para testear la capa web (controllers), pero de forma diferente:

- **`@WebMvcTest(CycleController::class)`**: carga SOLO el controller indicado y sus dependencias web. No carga servicios, repositorios, etc. Hay que mockear todo.
- **`@SpringBootTest + @AutoConfigureMockMvc`**: carga el contexto completo PERO te da un `MockMvc` para hacer peticiones HTTP sin levantar un servidor real.

En nuestro proyecto usamos la segunda opcion:

```kotlin
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc  // Spring Boot 4

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CycleControllerTest { ... }
```

**Por que `@SpringBootTest + @AutoConfigureMockMvc` en vez de `@WebMvcTest`**: porque necesitamos que la capa de seguridad (Spring Security con Basic Auth) este cargada y configurada correctamente. `@WebMvcTest` no carga la configuracion de seguridad completa.

### `@MockitoBean` (reemplaza a `@MockBean`)

Inyecta un mock de Mockito **dentro del contexto de Spring**, reemplazando el bean real. Se usa en tests de integracion para aislar el componente bajo test.

```kotlin
import org.springframework.test.context.bean.override.mockito.MockitoBean  // Spring Boot 4

@MockitoBean
lateinit var cycleService: CycleService  // Spring inyecta un mock en vez del servicio real
```

**Diferencia con `@Mock`**: `@Mock` es de Mockito puro (sin Spring). `@MockitoBean` registra el mock en el ApplicationContext de Spring para que los beans que dependan de el reciban el mock.

### `@ExtendWith(MockitoExtension::class)`

Activa Mockito en un test **sin Spring**. Es la forma de hacer tests unitarios puros: rapidos, sin contexto de Spring, solo logica de negocio.

```kotlin
@ExtendWith(MockitoExtension::class)  // Activa @Mock, @InjectMocks, etc.
class CycleServiceTest {
    @Mock lateinit var cycleRepository: CycleRepository    // Mock puro de Mockito
    @Mock lateinit var cycleMapper: CycleMapper            // Mock puro de Mockito
    @InjectMocks lateinit var cycleService: CycleService   // Inyecta los mocks arriba
}
```

### `MockMvc`

Es una herramienta de Spring que simula peticiones HTTP sin levantar un servidor real. Permite testear controllers, seguridad, serializacion JSON, codigos de respuesta, etc.

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

## 6. Codigo explicado

### Test 1: Carga del contexto (`WhoopDavidApiApplicationTests.kt`)

**Archivo**: `src/test/kotlin/com/example/whoopdavidapi/WhoopDavidApiApplicationTests.kt`

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

**Que hace**: verifica que toda la aplicacion Spring Boot arranca sin errores. Si hay un bean mal configurado, una dependencia circular, un `application.yaml` con errores o cualquier problema de configuracion, este test falla.

**Por que esta vacio**: el test no necesita hacer nada. El simple hecho de que `@SpringBootTest` levante el contexto sin lanzar una excepcion ya verifica que todo esta correcto.

**`@ActiveProfiles("dev")`**: activa el perfil `dev`, que usa H2 en memoria en lugar de PostgreSQL. Asi los tests no necesitan una base de datos externa.

---

### Test 2: Repositorio JPA (`CycleRepositoryTest.kt`)

**Archivo**: `src/test/kotlin/com/example/whoopdavidapi/repository/CycleRepositoryTest.kt`

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

Linea por linea:

- **`@DataJpaTest`**: levanta solo Hibernate + H2. No carga controllers, servicios, seguridad.
- **`@Import(TokenEncryptor::class)`**: `@DataJpaTest` solo carga los beans JPA. Pero la entidad `OAuthTokenEntity` usa un `@Convert(converter = TokenEncryptorConverter::class)` que necesita `TokenEncryptor`. Sin este `@Import`, el contexto falla al intentar crear la tabla.
- **`@Autowired lateinit var cycleRepository`**: Spring inyecta el repositorio real (no un mock). Las queries van contra H2 en memoria.
- **`cycleRepository.save(cycle)`**: ejecuta un `INSERT INTO whoop_cycle ...` contra H2.
- **`cycleRepository.findById(12345L)`**: ejecuta un `SELECT * FROM whoop_cycle WHERE id = 12345`.
- **`assertTrue(found.isPresent)`**: verifica que el ciclo se persisitio y se puede recuperar.

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

**Que verifica**: que el metodo `findByStartBetween` del repositorio genera correctamente la query SQL `WHERE start BETWEEN ? AND ?`. Se guardan 3 ciclos con fechas distintas y se espera que solo 1 caiga dentro del rango.

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

**Que verifica**: que `PageRequest.of(0, 2)` devuelve solo 2 resultados de los 5 guardados, que `totalElements` es 5 (el total real) y que `hasNext()` es `true` (hay mas paginas).

---

### Test 3: Servicio con Mockito (`CycleServiceTest.kt`)

**Archivo**: `src/test/kotlin/com/example/whoopdavidapi/service/CycleServiceTest.kt`

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

**Anatomia**:

- **`@ExtendWith(MockitoExtension::class)`**: NO levanta Spring. Solo activa las anotaciones de Mockito (`@Mock`, `@InjectMocks`). Esto hace el test muy rapido.
- **`@Mock lateinit var cycleRepository`**: crea un objeto falso que simula el repositorio. Cuando le dices `when(cycleRepository.findAll(...)).thenReturn(...)`, devuelve lo que tu le indiques sin ir a la BD.
- **`@Mock lateinit var cycleMapper`**: igual para el mapper. No ejecuta MapStruct, devuelve lo que configures.
- **`@InjectMocks lateinit var cycleService`**: crea una instancia real de `CycleService` e inyecta los mocks anteriores en sus dependencias.

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

**Flujo del test**:

1. **Preparar datos**: crea una entidad `WhoopCycle` y un DTO `CycleDTO`.
2. **Configurar mocks**: cuando el servicio llame a `cycleRepository.findAll(pageable)`, devuelve la pagina con la entidad. Cuando llame a `cycleMapper.toDto(entity)`, devuelve el DTO.
3. **Ejecutar**: llama a `cycleService.getCycles(null, null, 1, 100)` -- esto invoca la logica real del servicio, que usa los mocks.
4. **Verificar**: comprueba que el resultado tiene los valores esperados.

**Que estamos testeando realmente**: la logica del servicio -- como construye el `Pageable`, como decide que metodo del repositorio llamar (con filtros o sin ellos), como transforma entidades a DTOs, como construye el `PaginatedResponse`.

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

**Que verifica**: que cuando se pasa un parametro `from`, el servicio llama a `findByStartGreaterThanEqual` en vez de `findAll`. Si el servicio llamara al metodo incorrecto, Mockito no tendria configurada la respuesta y el test fallaria.

---

### Test 4: Controller con MockMvc (`CycleControllerTest.kt`)

**Archivo**: `src/test/kotlin/com/example/whoopdavidapi/controller/CycleControllerTest.kt`

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

Fijate en los imports -- aqui estan los cambios de Spring Boot 4:

- **`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`**: antes era `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc`
- **`org.springframework.test.context.bean.override.mockito.MockitoBean`**: antes era `org.springframework.boot.test.mock.mockito.MockBean`

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

- **`@SpringBootTest`**: levanta todo el contexto (incluida la seguridad).
- **`@AutoConfigureMockMvc`**: crea un `MockMvc` auto-configurado que Spring inyecta.
- **`@MockitoBean lateinit var cycleService`**: reemplaza el `CycleService` real en el contexto de Spring por un mock. Asi el controller llama al mock en vez de al servicio real.

```kotlin
    @Test
    fun `GET cycles sin autenticacion devuelve 401`() {
        mockMvc.perform(get("/api/v1/cycles"))
            .andExpect(status().isUnauthorized)
    }
```

**Que verifica**: que Spring Security esta configurado correctamente y rechaza peticiones a `/api/v1/cycles` sin credenciales. Devuelve HTTP 401 Unauthorized.

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

**Que verifica**:
1. Que con Basic Auth (`powerbi`/`changeme`) se obtiene HTTP 200.
2. Que la respuesta JSON tiene la estructura correcta (`$.data` es un array, `$.pagination` tiene los campos esperados).
3. Que Jackson serializa correctamente los DTOs a JSON.

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

**Que verifica**: que los parametros de query `?page=2&pageSize=50` se pasan correctamente al servicio. Verifica que el binding de `@RequestParam` funciona.

---

## 7. Gotchas de Spring Boot 4

Spring Boot 4.0.2 introdujo cambios importantes en los paquetes de las anotaciones de test. Si vienes de Spring Boot 3.x, estas son las migraciones:

### Los paquetes de anotaciones de test cambiaron

| Anotacion | Spring Boot 3.x | Spring Boot 4.x |
|-----------|-----------------|-----------------|
| `@DataJpaTest` | `org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest` | `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest` |
| `@WebMvcTest` | `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` |
| `@AutoConfigureMockMvc` | `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` |
| `@MockBean` | `org.springframework.boot.test.mock.mockito.MockBean` | **Eliminada** -- usar `@MockitoBean` |
| `@MockitoBean` | No existia | `org.springframework.test.context.bean.override.mockito.MockitoBean` |

### `@MockBean` ya no existe -- usar `@MockitoBean`

En Spring Boot 3.x se usaba `@MockBean` del paquete `org.springframework.boot.test.mock.mockito`. En Spring Boot 4.x esta anotacion fue reemplazada por `@MockitoBean` del paquete `org.springframework.test.context.bean.override.mockito`.

Antes (Spring Boot 3):
```kotlin
import org.springframework.boot.test.mock.mockito.MockBean

@MockBean
lateinit var cycleService: CycleService
```

Ahora (Spring Boot 4):
```kotlin
import org.springframework.test.context.bean.override.mockito.MockitoBean

@MockitoBean
lateinit var cycleService: CycleService
```

### kapt debe desactivarse para las fuentes de test

Si usas `kapt` (por ejemplo para MapStruct), las tareas de kapt para test (`kaptTestKotlin`, `kaptGenerateStubsTestKotlin`) pueden fallar porque intentan procesar las nuevas anotaciones de test de Spring Boot 4 y no las reconocen.

La solucion esta en `build.gradle.kts`:

```kotlin
// Desactivar kapt para test sources (no hay annotation processors en tests)
tasks.matching { it.name == "kaptTestKotlin" || it.name == "kaptGenerateStubsTestKotlin" }.configureEach {
    enabled = false
}
```

Esto es seguro porque en nuestro proyecto no tenemos annotation processors que necesiten ejecutarse sobre el codigo de test (MapStruct solo procesa los mappers en `src/main`).

### Starters de test tambien cambiaron

En `build.gradle.kts`, las dependencias de test usan los nuevos starters de Spring Boot 4:

```kotlin
testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
testImplementation("org.springframework.boot:spring-boot-starter-security-test")
testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
```

Estos reemplazan a los starters generico `spring-boot-starter-test` de Spring Boot 3.x, proporcionando las dependencias de test organizadas por modulo.

---

## 8. Configuracion en build.gradle.kts

**Archivo**: `build.gradle.kts`

Las partes relevantes para testing:

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

- **`spring-boot-starter-*-test`**: incluye JUnit 5, Mockito, AssertJ, MockMvc, H2 y otras herramientas de test.
- **`spring-boot-starter-security-test`**: proporciona `SecurityMockMvcRequestPostProcessors.httpBasic()` para simular autenticacion en tests.
- **`kotlin-test-junit5`**: integracion de Kotlin con JUnit 5.
- **`junit-platform-launcher`**: necesario en runtime para que Gradle ejecute los tests con JUnit 5.

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

## 9. Documentacion oficial

- [Spring Boot Testing - Referencia oficial](https://docs.spring.io/spring-boot/reference/testing/index.html)
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [Mockito Framework](https://site.mockito.org/)
- [Spring Security Testing](https://docs.spring.io/spring-security/reference/servlet/test/index.html)
- [MockMvc - Documentacion de Spring](https://docs.spring.io/spring-framework/reference/testing/spring-mvc-test-framework.html)
- [Spring Boot 4 Release Notes - Test annotations migration](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes)
