# Whoop David API - Documentacion de Aprendizaje

## Bienvenido

Esta documentacion esta disenada para ensenar cada concepto de Spring Boot utilizado en este proyecto. No es una referencia rapida, sino una **guia de aprendizaje** donde cada decision tecnica esta explicada con el "por que" detras.

**WhoopDavidAPI** es una API REST intermediaria que sigue el patron **BFF (Backend For Frontend)**. Su proposito es:

1. **Conectarse** a la Whoop API v2 (la API oficial de la pulsera Whoop)
2. **Sincronizar** los datos de salud y fitness en una base de datos PostgreSQL local
3. **Exponer** esos datos a traves de una API REST propia, consumida por Power BI para visualizacion

Es un proyecto de un **unico usuario** (David), lo que simplifica muchas decisiones arquitectonicas.

---

## Patron BFF (Backend For Frontend)

```
┌─────────────┐       ┌──────────────────────┐       ┌─────────────┐
│  Whoop API  │──────>│   WhoopDavidAPI (BFF) │──────>│  Power BI   │
│    v2        │  sync │                      │  REST │  Dashboard  │
└─────────────┘       │  ┌──────────────────┐ │       └─────────────┘
                      │  │   PostgreSQL     │ │
                      │  │   (almacen)      │ │
                      │  └──────────────────┘ │
                      └──────────────────────┘
```

El BFF actua como intermediario: no expone directamente la API de Whoop, sino que almacena los datos localmente y los sirve en el formato que Power BI necesita. Esto evita depender de la disponibilidad de Whoop en tiempo real y permite paginacion, filtrado y transformacion de datos.

---

## Stack tecnologico

| Tecnologia | Version | Proposito |
|---|---|---|
| **Kotlin** | 2.2.21 | Lenguaje principal |
| **Spring Boot** | 4.0.2 | Framework backend |
| **Java** | 24 | JVM runtime |
| **PostgreSQL** | - | Base de datos en produccion |
| **H2** | - | Base de datos en desarrollo (in-memory) |
| **MapStruct** | 1.6.3 | Mapeo Entity <-> DTO |
| **Resilience4j** | 2.3.0 | Circuit breaker, retry, rate limiter |
| **springdoc-openapi** | 3.0.1 | Documentacion Swagger UI |
| **Gradle** | - | Build system (Kotlin DSL) |
| **Docker** | - | Contenedorizacion |
| **Kubernetes** | RKE2 | Orquestacion en produccion |

---

## Swagger UI (API interactiva)

| Entorno | URL |
|---|---|
| **DEV** | [https://david-whoop-dev.apptolast.com/swagger-ui/index.html](https://david-whoop-dev.apptolast.com/swagger-ui/index.html) |
| **PROD** | [https://david-whoop.apptolast.com/swagger-ui/index.html](https://david-whoop.apptolast.com/swagger-ui/index.html) |

---

## Estructura del proyecto

```
WhoopDavidAPI/
├── build.gradle.kts                          # Configuracion de Gradle y dependencias
├── settings.gradle.kts                       # Nombre del proyecto raiz
├── Dockerfile                                # Multi-stage build para Docker
├── k8s/                                      # Manifiestos de Kubernetes
│   ├── 00-namespace.yaml
│   ├── 01-postgresql/
│   ├── 02-api-dev/
│   └── 03-api-prod/
├── .github/workflows/                        # CI/CD con GitHub Actions
│   ├── ci.yml
│   ├── cd.yml
│   └── update-api-docs.yml
│
├── src/main/resources/
│   ├── application.yaml                      # Configuracion base (compartida)
│   ├── application-dev.yaml                  # Perfil dev: H2, logs DEBUG
│   ├── application-prod.yaml                 # Perfil prod: PostgreSQL, logs INFO
│   └── application-demo.yaml                 # Perfil demo: mock API, sin keys reales
│
├── src/main/kotlin/com/example/whoopdavidapi/
│   ├── WhoopDavidApiApplication.kt           # Punto de entrada (@SpringBootApplication)
│   │
│   ├── config/                               # Configuracion de Spring
│   │   ├── SecurityConfig.kt                 # 4 cadenas de seguridad (Basic Auth, OAuth2, public, deny)
│   │   ├── WhoopClientConfig.kt              # Bean RestClient con timeouts
│   │   └── OpenApiConfig.kt                  # Configuracion de Swagger/OpenAPI
│   │
│   ├── model/
│   │   ├── entity/                           # Entidades JPA (tablas de BD)
│   │   │   ├── WhoopCycle.kt                 # Ciclos fisiologicos
│   │   │   ├── WhoopRecovery.kt              # Puntuaciones de recuperacion
│   │   │   ├── WhoopSleep.kt                 # Datos de sueno
│   │   │   ├── WhoopWorkout.kt               # Entrenamientos
│   │   │   └── OAuthTokenEntity.kt           # Tokens OAuth2 (cifrados con AES-256-GCM)
│   │   └── dto/                              # Data Transfer Objects (respuestas API)
│   │       ├── CycleDTO.kt
│   │       ├── RecoveryDTO.kt
│   │       ├── SleepDTO.kt
│   │       ├── WorkoutDTO.kt
│   │       └── PaginatedResponse.kt          # Wrapper generico de paginacion
│   │
│   ├── repository/                           # Spring Data JPA Repositories
│   │   ├── CycleRepository.kt
│   │   ├── RecoveryRepository.kt
│   │   ├── SleepRepository.kt
│   │   ├── WorkoutRepository.kt
│   │   └── OAuthTokenRepository.kt
│   │
│   ├── mapper/                               # MapStruct mappers (Entity <-> DTO)
│   │   ├── CycleMapper.kt
│   │   ├── RecoveryMapper.kt
│   │   ├── SleepMapper.kt
│   │   └── WorkoutMapper.kt
│   │
│   ├── service/                              # Logica de negocio
│   │   ├── CycleService.kt
│   │   ├── RecoveryService.kt
│   │   ├── SleepService.kt
│   │   ├── WorkoutService.kt
│   │   └── WhoopSyncService.kt              # Sincronizacion incremental con Whoop API
│   │
│   ├── controller/                           # Controladores REST (/api/v1/...)
│   │   ├── CycleController.kt
│   │   ├── RecoveryController.kt
│   │   ├── SleepController.kt
│   │   ├── WorkoutController.kt
│   │   └── ProfileController.kt
│   │
│   ├── client/                               # Cliente HTTP para Whoop API
│   │   ├── TokenManager.kt                   # Interface para abstraccion de tokens
│   │   ├── WhoopApiClient.kt                 # RestClient + Resilience4j
│   │   └── WhoopTokenManager.kt              # Gestion de tokens OAuth2 (refresh automatico)
│   │
│   ├── scheduler/                            # Tareas programadas
│   │   └── WhoopDataSyncScheduler.kt        # @Scheduled con cron configurable
│   │
│   ├── exception/                            # Manejo global de errores
│   │   ├── WhoopApiException.kt              # Excepcion custom para errores de Whoop API
│   │   └── GlobalExceptionHandler.kt         # @RestControllerAdvice
│   │
│   ├── mock/                                 # Perfil demo (sin API keys reales)
│   │   ├── MockWhoopApiController.kt         # Endpoints mock que simulan Whoop API
│   │   ├── MockWhoopDataGenerator.kt         # Generador de datos realistas (seed=42)
│   │   ├── DemoWhoopTokenManager.kt          # TokenManager falso para demo
│   │   └── DemoTokenSeeder.kt                # Inserta token demo al arrancar
│   │
│   └── util/                                 # Utilidades
│       ├── TokenEncryptor.kt                 # AES-256-GCM para cifrar tokens en BD
│       └── EncryptedStringConverter.kt       # JPA AttributeConverter automatico
│
└── src/test/kotlin/com/example/whoopdavidapi/
    ├── WhoopDavidApiApplicationTests.kt      # Test de carga del contexto
    ├── controller/CycleControllerTest.kt     # Tests de integracion del controlador
    ├── repository/CycleRepositoryTest.kt     # Tests del repositorio con H2
    └── service/CycleServiceTest.kt           # Tests unitarios del servicio
```

---

## Indice de documentacion

Cada documento explica un concepto de Spring Boot con referencias al codigo real del proyecto:

| # | Documento | Descripcion |
|---|---|---|
| **01** | [Arquitectura y patron BFF](01-arquitectura.md) | Arquitectura general, flujo de datos, por que WebMVC y no WebFlux |
| **02** | [Gradle y dependencias](02-gradle-dependencias.md) | Cada dependencia explicada, plugins de Kotlin, gotchas de Spring Boot 4 |
| **03** | [Entidades JPA e Hibernate](03-entidades-jpa.md) | `@Entity`, `@Table`, `@Column`, tipos de datos, cifrado de tokens |
| **04** | [Spring Data JPA Repositories](04-repositorios.md) | Interfaces, derived queries, paginacion, `JpaRepository` |
| **05** | [DTOs y MapStruct](05-dtos-mapstruct.md) | Separacion Entity/DTO, mappers automaticos con kapt |
| **06** | [Capa de servicios](06-servicios.md) | `@Service`, logica de negocio, `WhoopSyncService` |
| **07** | [Controladores REST](07-controladores.md) | `@RestController`, `@GetMapping`, validacion, `ResponseEntity` |
| **08** | [Spring Security](08-seguridad.md) | Multi-chain: Basic Auth, OAuth2, endpoints publicos, deny-all |
| **09** | [RestClient y Resilience4j](09-cliente-http.md) | Cliente HTTP, circuit breaker, retry, rate limiter |
| **10** | [Sincronizacion programada](10-sincronizacion.md) | `@Scheduled`, cron, sincronizacion incremental |
| **11** | [Perfiles de Spring](11-perfiles.md) | dev/prod/demo, `@Profile`, configuracion por entorno |
| **12** | [Testing en Spring Boot](12-testing.md) | `@SpringBootTest`, `@DataJpaTest`, `@MockitoBean`, MockMvc |
| **13** | [Docker y Kubernetes](13-docker-k8s.md) | Multi-stage Dockerfile, K8s manifests, Traefik, cert-manager |
| **14** | [CI/CD con GitHub Actions](14-cicd.md) | Workflows de CI (test), CD (deploy), actualizacion de docs |

---

## Como usar esta documentacion

1. **Lee en orden** si eres nuevo en Spring Boot: empieza por la arquitectura (01) y avanza secuencialmente
2. **Salta a lo que necesites** si buscas algo concreto: cada documento es autocontenido
3. **Sigue los links al codigo**: cada documento referencia los archivos fuente reales con rutas relativas clickeables en GitHub
4. **Consulta la documentacion oficial**: cada documento incluye links a la documentacion oficial de Spring Boot y Kotlin

---

## Documentacion oficial de referencia

- [Spring Boot 4.0.x Reference](https://docs.spring.io/spring-boot/reference/)
- [Kotlin Language Reference](https://kotlinlang.org/docs/reference/)
- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/reference/)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)
- [Resilience4j Documentation](https://resilience4j.readme.io/docs)
- [MapStruct Reference Guide](https://mapstruct.org/documentation/stable/reference/html/)
- [springdoc-openapi](https://springdoc.org/)
