# Whoop David API - Learning Documentation

## Welcome

This documentation is designed to teach each [Spring Boot](https://spring.io/projects/spring-boot) concept used in this project. It is not a quick reference, but rather a **learning guide** where each technical decision is explained with the "why" behind it.

**WhoopDavidAPI** is an intermediary REST API that follows the **BFF (Backend For Frontend)** pattern. Its purpose is:

1. **Connect** to the Whoop API v2 (the official API of the Whoop wristband)
2. **Synchronize** health and fitness data in a local [PostgreSQL](https://www.postgresql.org/) database
3. **Expose** that data through a proprietary REST API, consumed by [Power BI](https://www.microsoft.com/en-us/power-platform/products/power-bi/) for visualization

It is a project for a **single user** (David), which simplifies many architectural decisions.

---

## BFF Pattern (Backend For Frontend)

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

The BFF acts as an intermediary: it does not directly expose the Whoop API, but instead stores the data locally and serves it in the format that Power BI needs. This avoids depending on Whoop’s real-time availability and enables pagination, filtering, and data transformation.

---

## Tech stack

| Technology | Version | Purpose |
|---|---|---|
| **[Kotlin](https://kotlinlang.org/)** | 2.2.21 | Main language |
| **Spring Boot** | 4.0.2 | Framework backend |
| **Java** | 24 | JVM runtime |
| **PostgreSQL** | - | Production database |
| **H2** | - | Development database (in-memory) |
| **[MapStruct](https://mapstruct.org/)** | 1.6.3 | Entity <-> DTO Mapping |
| **[Resilience4j](https://resilience4j.readme.io/docs/getting-started)** | 2.3.0 | Circuit breaker, retry, rate limiter |
| **[springdoc-openapi](https://springdoc.org/)** | 3.0.1 | Swagger UI Documentation |
| **Gradle** | - | Build system (Kotlin DSL) |
| **[Docker](https://www.docker.com/)** | - | Containerization |
| **[Kubernetes](https://kubernetes.io/)** | RKE2 | Orchestration in production |

---

## Swagger UI (Interactive API)

| Environment | URL |
|---|---|
| **DEV** | [https://david-whoop-dev.apptolast.com/swagger-ui/index.html](https://david-whoop-dev.apptolast.com/swagger-ui/index.html) |
| **PROD** | [https://david-whoop.apptolast.com/swagger-ui/index.html](https://david-whoop.apptolast.com/swagger-ui/index.html) |

---

## Project structure

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

## Documentation index

Each document explains a Spring Boot concept with references to the project's actual code:

| # | Document | Description |
|---|---|---|
| **01** | [Architecture and BFF pattern](01-architecture.md) | General architecture, data flow, why WebMVC and not WebFlux |
| **02** | [Gradle and dependencies](02-gradle-dependencies.md) | Each dependency explained, Kotlin plugins, Spring Boot 4 gotchas |
| **03** | [JPA and Hibernate Entities](03-jpa-entities.md) | `@Entity`, `@Table`, `@Column`, data types, token encryption |
| **04** | [Spring Data JPA Repositories](04-repositories.md) | Interfaces, derived queries, pagination, `JpaRepository` |
| **05** | [DTOs and MapStruct](05-dtos-mapstruct.md) | Entity/DTO separation, automatic mappers with kapt |
| **06** | [Services layer](06-services.md) | `@Service`, business logic, `WhoopSyncService` |
| **07** | [REST Controllers](07-controllers.md) | `@RestController`, `@GetMapping`, validation, `ResponseEntity` |
| **08** | [Spring Security](08-security.md) | Multi-chain: Basic Auth, OAuth2, public endpoints, deny-all |
| **09** | [RestClient and Resilience4j](09-http-client.md) | HTTP client, circuit breaker, retry, rate limiter |
| **10** | [Scheduled synchronization](10-synchronization.md) | `@Scheduled`, cron, incremental synchronization |
| **11** | [Spring Profiles](11-profiles.md) | dev/prod/demo, `@Profile`, environment-based configuration |
| **12** | [Testing in Spring Boot](12-testing.md) | `@SpringBootTest`, `@DataJpaTest`, `@MockitoBean`, MockMvc |
| **13** | [Docker and Kubernetes](13-docker-k8s.md) | Multi-stage Dockerfile, K8s manifests, Traefik, cert-manager |
| **14** | [CI/CD with GitHub Actions](14-cicd.md) | CI workflows (test), CD (deploy), docs update |

---

## How to use this documentation

1. **Read in order** if you are new to Spring Boot: start with the architecture (01) and move forward sequentially
2. **Jump to what you need** if you’re looking for something specific: each document is self-contained
3. **Follow the links to the code**: each document references the actual source files with clickable relative paths on GitHub
4. **Consult the official documentation**: each document includes links to the official Spring Boot and Kotlin documentation

---

## Official reference documentation

- [Spring Boot 4.0.x Reference](https://docs.spring.io/spring-boot/reference/)
- [Kotlin Language Reference](https://kotlinlang.org/docs/reference/)
- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/reference/)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)
- [Resilience4j Documentation](https://resilience4j.readme.io/docs/getting-started)
- [MapStruct Reference Guide](https://mapstruct.org/documentation/stable/reference/html/)
- [springdoc-openapi](https://springdoc.org/)
