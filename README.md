# WhoopDavidAPI

[![Build](https://github.com/apptolast/WhoopDavidAPI/actions/workflows/ci.yml/badge.svg)](https://github.com/apptolast/WhoopDavidAPI/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.21-purple.svg)](https://kotlinlang.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.2-green.svg)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-24-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Private-red.svg)]()

API REST intermediaria que conecta la **Whoop API v2** con **Power BI**, eliminando la entrada manual de datos. Sigue un patron **Backend-For-Frontend (BFF)**: sincroniza periodicamente datos de rendimiento deportivo desde la pulsera WHOOP hacia PostgreSQL y los expone como JSON plano optimizado para el conector Web de Power BI.

## Arquitectura

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

### Flujo de datos

1. **Sincronizacion automatica** (cada 30 min): `@Scheduled` → `WhoopSyncService` → `WhoopApiClient` → Whoop API v2 → PostgreSQL
2. **Consumo Power BI** (bajo demanda): Power BI → `GET /api/v1/*` (Basic Auth) → Controller → Service → Repository → JSON plano

### Estructura del proyecto

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

## Stack tecnico

| Tecnologia | Version | Uso |
|------------|---------|-----|
| **Kotlin** | 2.2.21 | Lenguaje principal |
| **Spring Boot** | 4.0.2 | Framework |
| **Java** | 24 | Runtime |
| **Spring Security** | 7.x | Basic Auth (Power BI) + OAuth2 (Whoop) |
| **Spring Data JPA** | 4.x | Persistencia |
| **PostgreSQL** | 17 | Base de datos (produccion) |
| **H2** | - | Base de datos (desarrollo) |
| **Resilience4j** | 2.3.0 | Circuit breaker, retry, rate limiter |
| **MapStruct** | 1.6.3 | Mapeo Entity ↔ DTO |
| **Jackson 3** | - | Serializacion JSON (ISO-8601 por defecto) |
| **Gradle** | 9.3 | Build tool (Kotlin DSL) |

## Prerrequisitos

- **Java 24** (JDK)
- **Gradle 9.3+** (o usar `./gradlew`)
- **Docker** (para despliegue)
- Cuenta de desarrollador en [Whoop Developer Portal](https://developer.whoop.com/)

## Instalacion y configuracion

### 1. Clonar el repositorio

```bash
git clone git@github.com:apptolast/WhoopDavidAPI.git
cd WhoopDavidAPI
```

### 2. Configurar variables de entorno

```bash
export WHOOP_CLIENT_ID=tu-client-id
export WHOOP_CLIENT_SECRET=tu-client-secret
export POWERBI_USERNAME=powerbi
export POWERBI_PASSWORD=tu-password-seguro
```

### 3. Ejecutar en modo desarrollo (H2)

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

La aplicacion arranca en `http://localhost:8080` con:
- H2 Console: `http://localhost:8080/h2-console`
- Health check: `http://localhost:8080/actuator/health`

### 4. Ejecutar en modo produccion (PostgreSQL)

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/whoop_david
export DB_USERNAME=whoop_user
export DB_PASSWORD=tu-password
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

## Variables de entorno

| Variable | Obligatoria | Descripcion |
|----------|-------------|-------------|
| `WHOOP_CLIENT_ID` | Si | Client ID de la app en Whoop Developer |
| `WHOOP_CLIENT_SECRET` | Si | Client Secret de la app en Whoop Developer |
| `ENCRYPTION_KEY` | Si (prod), No (dev) | Clave AES-256-GCM para cifrar tokens OAuth2 - **Base64 de exactamente 32 bytes** (ej: `openssl rand -base64 32`). Perfil 'dev' usa clave predeterminada. |
| `POWERBI_USERNAME` | No (default: `powerbi`) | Usuario para Basic Auth de Power BI |
| `POWERBI_PASSWORD` | No (default: `changeme`) | Password para Basic Auth de Power BI |
| `DATABASE_URL` | Si (prod) | URL JDBC de PostgreSQL |
| `DB_USERNAME` | Si (prod) | Usuario de PostgreSQL |
| `DB_PASSWORD` | Si (prod) | Password de PostgreSQL |
| `SPRING_PROFILES_ACTIVE` | No (default: `dev`) | Perfil activo (`dev` o `prod`) |

## Endpoints de la API

Todos los endpoints `GET /api/v1/*` requieren **Basic Auth** y soportan:
- **Paginacion**: `page` (default 1), `pageSize` (default 100)
- **Filtros de fecha**: `from`, `to` (ISO 8601 UTC)

| Metodo | Endpoint | Descripcion |
|--------|----------|-------------|
| `GET` | `/api/v1/cycles` | Ciclos fisiologicos (strain, kJ, HR) |
| `GET` | `/api/v1/recovery` | Recovery diario (HRV, recovery score, HR reposo) |
| `GET` | `/api/v1/sleep` | Datos de sueno (etapas, eficiencia, duracion) |
| `GET` | `/api/v1/workouts` | Workouts (strain, HR, zonas, distancia) |
| `GET` | `/api/v1/profile` | Perfil del usuario Whoop |
| `GET` | `/actuator/health` | Health check (publico) |

### Ejemplo de respuesta

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

## Desarrollo

### Comandos

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

Los tests incluyen:
- **Context load**: verificacion de arranque del contexto Spring
- **Repository**: tests con H2 (`@DataJpaTest`) para queries con rango de fechas
- **Controller**: tests con MockMvc (`@WebMvcTest`) para endpoints y autenticacion
- **Service**: tests unitarios con Mockito para logica de negocio

## Despliegue

### Docker

```bash
# Build
docker build -t ocholoko888/whoop-david-api:latest .

# Run
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DATABASE_URL=jdbc:postgresql://db:5432/whoop_david \
  -e DB_USERNAME=whoop_user \
  -e DB_PASSWORD=password \
  -e WHOOP_CLIENT_ID=your-client-id \
  -e WHOOP_CLIENT_SECRET=your-client-secret \
  ocholoko888/whoop-david-api:latest
```

### Kubernetes

La aplicacion se despliega en un cluster **RKE2/Rancher** con:
- **Traefik** como ingress controller
- **cert-manager** para TLS (Let's Encrypt via Cloudflare)
- **Longhorn** para persistent volumes
- **Keel** para auto-deploy (poll cada 1 min)

```bash
# Crear namespace y recursos
kubectl apply -f k8s/00-namespace.yaml
kubectl apply -f k8s/01-secrets.yaml   # Editar con valores reales primero
kubectl apply -f k8s/02-deployment.yaml
kubectl apply -f k8s/03-service.yaml
kubectl apply -f k8s/04-ingress.yaml
```

**URL de produccion**: `https://david-whoop.apptolast.com`

### CI/CD Pipeline

- **CI** (`ci.yml`): build + test en cada PR a `main` y `dev`
- **CD** (`cd.yml`): build Docker + push a Docker Hub en push a `main`
- **Keel** detecta la nueva imagen y actualiza el deployment automaticamente

## Resiliencia

La comunicacion con Whoop API v2 esta protegida por Resilience4j:

| Patron | Configuracion |
|--------|---------------|
| **Circuit Breaker** | Ventana de 10 llamadas, umbral de fallo 50%, pausa 30s |
| **Retry** | 3 intentos, backoff exponencial (2s base, multiplicador x2) |
| **Rate Limiter** | 90 requests/min (Whoop limita a 100/min) |

## Autenticacion

### Whoop API (OAuth2 Authorization Code)
- Authorization URL: `https://api.prod.whoop.com/oauth/oauth2/auth`
- Token URL: `https://api.prod.whoop.com/oauth/oauth2/token`
- Scopes: `offline, read:profile, read:body_measurement, read:cycles, read:recovery, read:sleep, read:workout`
- Access token expira en 1h, refresh automatico

### Power BI (Basic Auth)
- Endpoints `/api/v1/**` protegidos con HTTP Basic Auth
- Compatible con Power BI Desktop y Power BI Service (scheduled refresh)

## Referencias

- [Whoop Developer Portal](https://developer.whoop.com/)
- [Whoop API v2 Docs](https://api.prod.whoop.com/developer/)
- [Spring Boot 4 Reference](https://docs.spring.io/spring-boot/reference/)
- [Spring Security Reference](https://docs.spring.io/spring-security/reference/)
- [Resilience4j Documentation](https://resilience4j.readme.io/docs)
- [Power BI Web Connector](https://learn.microsoft.com/en-us/power-bi/connect-data/desktop-connect-to-web)
