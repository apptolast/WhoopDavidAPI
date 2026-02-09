# WhoopDavidAPI

> **[Leer en espanol](README.md)**
> **[Read this in English](README.en.md)**

[![Build](https://github.com/apptolast/WhoopDavidAPI/actions/workflows/ci.yml/badge.svg)](https://github.com/apptolast/WhoopDavidAPI/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.21-purple.svg)](https://kotlinlang.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.2-green.svg)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-24-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Private-red.svg)]()

Intermediary REST API that connects **Whoop API v2** to **Power BI**, eliminating manual data entry. It follows a **Backend-For-Frontend (BFF)** pattern: periodically synchronizes sports performance data from the WHOOP bracelet to PostgreSQL and exposes it as plain JSON optimized for the Power BI Web Connector.

## Architecture

```
┌─────────────┐     OAuth2      ┌──────────────┐    @Scheduled    ┌──────────────┐
│  Whoop API  │◄───────────────►│  WhoopDavid  │───────────────►  │  PostgreSQL  │
│    v2       │   RestClient    │     API      │    Sync 30min    │              │
└─────────────┘                 └──────┬───────┘                  └──────────────┘
                                       │
                                       │ Basic Auth
                                       │ GET /api/v1/*
                                       ▼
                                ┌──────────────┐
                                │   Power BI   │
                                │  (Dashboard) │
                                └──────────────┘
```

### Data flow

1. **Automatic synchronization** (every 30 min): `@Scheduled` → `WhoopSyncService` → `WhoopApiClient` → Whoop API v2 → PostgreSQL
2. **Power BI consumption** (on demand): Power BI → `GET /api/v1/*` (Basic Auth) → Controller → Service → Repository → Plain JSON

### Project structure

```
com.example.whoopdavidapi
├── WhoopDavidApiApplication.kt     # Entry point + @EnableScheduling
├── config/                         # SecurityConfig, WhoopClientConfig
├── client/                         # WhoopApiClient (RestClient), WhoopTokenManager
├── controller/                     # REST controllers (cycles, recovery, sleep, workouts, profile)
├── service/                        # Business logic + WhoopSyncService
├── repository/                     # Spring Data JPA repositories
├── model/
│   ├── entity/                     # JPA entities (Cycle, Recovery, Sleep, Workout, OAuthToken)
│   └── dto/                        # Data classes para Power BI (JSON plano)
├── mapper/                         # MapStruct (Entity ↔ DTO)
├── scheduler/                      # WhoopDataSyncScheduler (@Scheduled)
└── exception/                      # GlobalExceptionHandler, WhoopApiException
```

## technical stack

| Technology | Version | Use |
|------------|---------|-----|
| **Kotlin** | 2.2.21 | Primary language |
| **Spring Boot** | 4.0.2 | Framework |
| **Java** | 24 | Runtime |
| **Spring Security** | 7.x | Basic Auth (Power BI) + OAuth2 (Whoop) |
| **Spring Data JPA** | 4.x | Persistence |
| **PostgreSQL** | 17 | Database (production) |
| **H2** | - | Database (development) |
| **Resilience4j** | 2.3.0 | Circuit breaker, retry, rate limiter |
| **MapStruct** | 1.6.3 | Entity ↔ DTO Mapping |
| **Jackson 3** | - | JSON serialization (ISO-8601 by default) |
| **Gradle** | 9.3 | Build tool (Kotlin DSL) |

## Prerequisites

- **Java 24** (JDK)
- **Gradle 9.3+** (or use `./gradlew`)
- **Docker** (for deployment)
- Developer account on [Whoop Developer Portal](https://developer.whoop.com/)

## Installation and configuration

### 1. Clone the repository

```bash
git clone git@github.com:apptolast/WhoopDavidAPI.git
cd WhoopDavidAPI
```

### 2. Configure environment variables

```bash
export WHOOP_CLIENT_ID=tu-client-id
export WHOOP_CLIENT_SECRET=tu-client-secret
export POWERBI_USERNAME=powerbi
export POWERBI_PASSWORD=tu-password-seguro
```

### 3. Run in development mode (H2)

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

The application starts at `http://localhost:8080` with:

- H2 Console: `http://localhost:8080/h2-console`
- Health check: `http://localhost:8080/actuator/health`

### 4. Run in production mode (PostgreSQL)

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/whoop_david
export DB_USERNAME=whoop_user
export DB_PASSWORD=tu-password
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

## Environment variables

| Variable | Mandatory | Description |
|----------|-------------|-------------|
| `WHOOP_CLIENT_ID` | Yeah | Client ID of the app in Whoop Developer |
| `WHOOP_CLIENT_SECRET` | Yeah | Client Secret of the app in Whoop Developer |
| `ENCRYPTION_KEY` | Yes (prod), No (dev) | AES-256-GCM key to encrypt OAuth2 tokens - **Base64 of exactly 32 bytes** (ex: `openssl rand -base64 32`). Profile 'dev' uses default key. |
| `POWERBI_USERNAME` | No (default: `powerbi`) | User for Power BI Basic Auth |
| `POWERBI_PASSWORD` | No (default: `changeme`) | Password for Power BI Basic Auth |
| `DATABASE_URL` | Yes (prod) | PostgreSQL JDBC URL |
| `DB_USERNAME` | Yes (prod) | PostgreSQL user |
| `DB_PASSWORD` | Yes (prod) | PostgreSQL Password |
| `SPRING_PROFILES_ACTIVE` | No (default: `dev`) | Active profile (`dev` or `prod`) |

## API endpoints

All `GET /api/v1/*` endpoints require **Basic Auth** and support:

- **Pagination**: `page` (default 1), `pageSize` (default 100)
- **Date filters**: `from`, `to` (ISO 8601 UTC)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/cycles` | Physiological cycles (strain, kJ, HR) |
| `GET` | `/api/v1/recovery` | Daily recovery (HRV, recovery score, resting HR) |
| `GET` | `/api/v1/sleep` | Sleep data (stages, efficiency, duration) |
| `GET` | `/api/v1/workouts` | Workouts (strain, HR, zones, distance) |
| `GET` | `/api/v1/profile` | Whoop User Profile |
| `GET` | `/actuator/health` | Health check (public) |

### Example response

```json
{
  "data": [
    {
      "whoopId": 123456789,
      "recoveryScore": 85.0,
      "restingHeartRate": 52.0,
      "hrvRmssdMilli": 78.5,
      "spo2Percentage": 97.0,
      "skinTempCelsius": 33.2,
      "createdAt": "2025-01-15T08:00:00Z",
      "updatedAt": "2025-01-15T08:30:00Z"
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 100,
    "totalCount": 365,
    "hasMore": true
  }
}
```

## Development

### Commands

```bash
./gradlew build      # Compilar y ejecutar tests
./gradlew bootRun    # Ejecutar la aplicacion
./gradlew test       # Ejecutar solo tests
./gradlew clean      # Limpiar build
```

### Tests

```bash
./gradlew test
```

The tests include:

- **Context load**: Spring context boot verification
- **Repository**: tests with H2 (`@DataJpaTest`) for queries with date range
- **Controller**: tests with MockMvc (`@WebMvcTest`) for endpoints and authentication
- **Service**: unit tests with Mockito for business logic

## Deployment

### Docker

```bash
# Build
docker build -t apptolast/whoop-david-api:latest .

# Run
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DATABASE_URL=jdbc:postgresql://db:5432/whoop_david \
  -e DB_USERNAME=whoop_user \
  -e DB_PASSWORD=password \
  -e WHOOP_CLIENT_ID=your-client-id \
  -e WHOOP_CLIENT_SECRET=your-client-secret \
  apptolast/whoop-david-api:latest
```

### Kubernetes

The application is deployed on a cluster **RKE2/Rancher** with:

- **Traefik** as ingress controller
- **cert-manager** for TLS (Let's Encrypt via Cloudflare)
- **Longhorn** for persistent volumes
- **Keel** for auto-deploy (poll every 1 min)

```bash
# Crear namespace y recursos
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/01-secrets.yaml   # Editar con valores reales primero
kubectl apply -f k8s/02-deployment.yaml
kubectl apply -f k8s/03-service.yaml
kubectl apply -f k8s/04-ingress.yaml
```

**Production URL**: `https://david-whoop.apptolast.com`

### CI/CD Pipeline

- **CI** (`ci.yml`): build + test in each PR to `main` and `dev`
- **CD** (`cd.yml`): build Docker + push to Docker Hub on push to `main`
- **Keel** detects the new image and updates the deployment automatically

## Resilience

Communication with Whoop API v2 is protected by Resilience4j:

| Pattern | Configuration |
|--------|---------------|
| **Circuit Breaker** | 10 call window, failure threshold 50%, pause 30s |
| **Retry** | 3 attempts, exponential backoff (base 2s, x2 multiplier) |
| **Rate Limiter** | 90 requests/min (Whoop limits to 100/min) |

## Authentication

### Whoop API (OAuth2 Authorization Code)

- Authorization URL: `https://api.prod.whoop.com/oauth/oauth2/auth`
- Token URL: `https://api.prod.whoop.com/oauth/oauth2/token`
- Scopes: `offline, read:profile, read:body_measurement, read:cycles, read:recovery, read:sleep, read:workout`
- Access token expires in 1h, automatic refresh

### Power BI (Basic Auth)

- Endpoints `/api/v1/**` protected with HTTP Basic Auth
- Compatible with Power BI Desktop and Power BI Service (scheduled refresh)

## References

- [Whoop Developer Portal](https://developer.whoop.com/)
- [Whoop API v2 Docs](https://api.prod.whoop.com/developer/)
- [Spring Boot 4 Reference](https://docs.spring.io/spring-boot/reference/)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)
- [Resilience4j Documentation](https://resilience4j.readme.io/docs)
- [Power BI Web Connector](https://learn.microsoft.com/en-us/power-bi/connect-data/desktop-connect-to-web)
