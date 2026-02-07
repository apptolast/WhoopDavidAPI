# Whoop David API - API Intermediaria Whoop → Power BI

## Que es este Proyecto

API REST intermediaria en Spring Boot que conecta la Whoop API (datos de rendimiento deportivo de la pulsera WHOOP) con Power BI, eliminando la entrada manual de datos en formularios de Google. Sigue un patron Backend-For-Frontend (BFF): sincroniza periodicamente datos de Whoop hacia una base de datos local y los expone como JSON plano optimizado para el conector Web de Power BI.

El usuario (David, deportista de alto rendimiento) lleva una pulsera WHOOP. La API se conecta a la plataforma Whoop via OAuth2, descarga automaticamente los datos de recovery, sleep, workout y cycles, los almacena en PostgreSQL y los expone via endpoints REST que Power BI consume directamente.

## Stack Tecnico

- **Lenguaje:** Kotlin 2.2.21
- **Framework:** Spring Boot 4.0.2
- **Java:** 24
- **Build:** Gradle con Kotlin DSL (`./gradlew`)
- **Web:** Spring MVC (spring-boot-starter-web)
- **Seguridad:** Spring Security + OAuth2 Client (Whoop) + Basic Auth (Power BI)
- **Persistencia:** Spring Data JPA + H2 (dev) + PostgreSQL (prod)
- **Resiliencia:** Resilience4j (circuit breaker, retry, rate limiter)
- **Mapeo:** MapStruct (entity ↔ DTO)
- **JSON:** Jackson Kotlin Module
- **Monitoreo:** Spring Boot Actuator
- **Scheduling:** Spring @Scheduled (sincronizacion periodica)
- **Cliente HTTP:** RestClient (recomendado sobre RestTemplate)

## Arquitectura Objetivo

```
com.example.whoopdavidapi
├── WhoopDavidApiApplication.kt
├── config/         → SecurityConfig, OAuth2ClientConfig, WhoopClientConfig, CorsConfig, ResilienceConfig
├── client/         → WhoopApiClient, WhoopTokenManager
├── controller/     → CycleController, RecoveryController, SleepController, WorkoutController, ProfileController
├── service/        → WhoopSyncService, CycleService, RecoveryService, SleepService, WorkoutService
├── repository/     → CycleRepository, RecoveryRepository, SleepRepository, WorkoutRepository, OAuthTokenRepository
├── model/
│   ├── entity/     → WhoopCycle, WhoopRecovery, WhoopSleep, WhoopWorkout, OAuthTokenEntity
│   └── dto/        → CycleDTO, RecoveryDTO, SleepDTO, WorkoutDTO (data classes)
├── mapper/         → CycleMapper, RecoveryMapper, SleepMapper, WorkoutMapper (MapStruct)
├── scheduler/      → WhoopDataSyncScheduler
└── exception/      → GlobalExceptionHandler, WhoopApiException
```

## Flujo de Datos

### Sincronizacion (automatica cada 30 min)
```
@Scheduled (cron) → WhoopSyncService
  → WhoopApiClient: GET /v2/cycle, /v2/recovery, /v2/activity/sleep, /v2/activity/workout
  → Paginacion con nextToken (max 25 por pagina)
  → Mapeo API response → Entity JPA
  → Persistencia en PostgreSQL
```

### Consumo Power BI (bajo demanda)
```
Power BI → GET /api/v1/recovery?from=...&to=...&page=1&pageSize=100
  → RecoveryController → RecoveryService → RecoveryRepository
  → Respuesta JSON plana: { "data": [...], "pagination": { page, pageSize, totalCount, hasMore } }
```

## Endpoints de la API (para Power BI)

| Metodo | Ruta | Auth | Descripcion |
|--------|------|------|-------------|
| GET | `/api/v1/cycles` | Basic Auth | Ciclos fisiologicos (strain, kJ, HR) |
| GET | `/api/v1/recovery` | Basic Auth | Recovery diario (HRV, recovery score, HR reposo) |
| GET | `/api/v1/sleep` | Basic Auth | Datos de sueno (etapas, eficiencia, duracion) |
| GET | `/api/v1/workouts` | Basic Auth | Workouts (strain, HR, zonas, distancia) |
| GET | `/api/v1/profile` | Basic Auth | Perfil basico del usuario Whoop |
| GET | `/actuator/health` | Publico | Health check |

Todos los endpoints GET soportan:
- Paginacion: `page` (default 1), `pageSize` (default 100)
- Filtros de fecha: `from`, `to` (ISO 8601 UTC)

## Autenticacion (doble)

### Whoop API (OAuth2 Authorization Code)
- **Authorization URL:** `https://api.prod.whoop.com/oauth/oauth2/auth`
- **Token URL:** `https://api.prod.whoop.com/oauth/oauth2/token`
- **Scopes:** `offline,read:profile,read:body_measurement,read:cycles,read:recovery,read:sleep,read:workout`
- **Access token:** expira en 1 hora (3600s)
- **Refresh token:** se obtiene con scope `offline`, se refresca automaticamente
- **Al refrescar, el token anterior se invalida inmediatamente**
- **Rate limits:** 100 req/min, 10,000 req/dia

### Power BI (Basic Auth con Spring Security)
- Endpoints `/api/v1/**` protegidos con Basic Auth
- Power BI se conecta seleccionando "Anonymous" y enviando header Authorization en codigo M, O usando el dialogo nativo "Basic"
- Compatible con Power BI Service para scheduled refresh

## Whoop API v2 - Endpoints Disponibles

| Endpoint | Paginado | Datos clave |
|----------|----------|-------------|
| `GET /v2/user/profile/basic` | No | user_id, email, first_name, last_name |
| `GET /v2/user/measurement/body` | No | height_meter, weight_kilogram, max_heart_rate |
| `GET /v2/cycle` | Si (nextToken, max 25/pagina) | strain (0-21), kilojoules, avg_hr, max_hr |
| `GET /v2/recovery` | Si | recovery_score (0-100%), resting_hr, hrv_rmssd_milli, spo2, skin_temp |
| `GET /v2/activity/sleep` | Si | etapas (light, SWS, REM, awake), sleep_performance, efficiency, respiratory_rate |
| `GET /v2/activity/workout` | Si | strain, hr avg/max, kilojoules, distancia, zone_durations (zone_zero a zone_five) |

Formato de coleccion: `{ "records": [...], "next_token": "..." }`. Cuando `next_token` es null, fin de paginacion.

## Convenciones

- Paquete base: `com.example.whoopdavidapi`
- Configuraciones en paquete `config/`
- Controllers con `@RestController` + `@RequestMapping("/api/v1")`
- Kotlin idiomatico: data classes para DTOs, extension functions, coroutines, null safety
- Tests con JUnit 5 + `@SpringBootTest`, `@WebMvcTest` para controllers
- Nombres de variables/funciones en ingles, comentarios en espanol
- Validacion con `@Valid` + anotaciones de Jakarta Validation
- Manejo de errores global con `@RestControllerAdvice`
- JSON plano para Power BI: maximo 2 niveles de anidacion, timestamps ISO 8601 UTC
- Esquema fijo: usar `null` en vez de omitir campos

## Comandos

```bash
./gradlew build      # Compilar
./gradlew bootRun    # Ejecutar
./gradlew test       # Tests
./gradlew clean      # Limpiar
```

## Variables de Entorno

| Variable | Descripcion |
|----------|-------------|
| `WHOOP_CLIENT_ID` | Client ID de la app registrada en Whoop Developer Dashboard |
| `WHOOP_CLIENT_SECRET` | Client Secret de la app Whoop |
| `POWERBI_USERNAME` | Usuario para Basic Auth de Power BI |
| `POWERBI_PASSWORD` | Password para Basic Auth de Power BI |
| `DATABASE_URL` | URL de PostgreSQL (prod) |
| `DB_USERNAME` | Usuario de PostgreSQL |
| `DB_PASSWORD` | Password de PostgreSQL |
| `SPRING_PROFILES_ACTIVE` | Perfil activo (dev/prod) |

## Estado Actual

Proyecto recien creado con Spring Initializr. Implementado hasta ahora:
- WhoopDavidApiApplication.kt con @SpringBootApplication
- application.yaml con solo `spring.application.name`
- build.gradle.kts con todas las dependencias base configuradas

Pendiente de implementar:
- **Todo el proyecto:** config/, client/, controller/, service/, repository/, model/, mapper/, scheduler/, exception/
- Configuracion OAuth2 Client para Whoop
- SecurityConfig con Basic Auth para Power BI
- WhoopApiClient con RestClient
- Sincronizacion programada con @Scheduled
- Entidades JPA y repositorios
- Controllers REST para Power BI
- Resilience4j (circuit breaker, retry, rate limiter)
- Tests
- Dockerizacion y despliegue en Kubernetes

## Notas para Agentes

- **MODO APRENDIZAJE**: El usuario esta aprendiendo Spring Boot. NO codificar por el. Explicar con detalle y dar enlaces a docs oficiales.
- No modificar `application.yaml` sin consultar (contendra secrets via env vars)
- Spring Boot 4.0.2 - verificar API en docs oficiales, puede haber cambios vs 3.x
- **Conflicto WebFlux vs WebMVC**: resuelto - solo se usa WebMVC (WebFlux eliminado en Etapa 0)
- `application.yaml` se versiona en el repositorio. NUNCA commitear secrets - usar variables de entorno o config externa para credenciales.
- **Whoop API v2** (mayo 2024): la v1 esta deprecada. Especificacion OpenAPI disponible en `https://api.prod.whoop.com/developer/doc/openapi.json`
- **RestClient** es el cliente HTTP recomendado (RestTemplate esta en deprecacion)
- **Power BI requiere JSON plano, URL base estatica, y endpoints GET solamente**
- Datos de Whoop: ~4 KB/dia por usuario. Un ano completo cabe en una respuesta bien paginada.

## Documentacion Oficial de Referencia

- Spring Boot: https://docs.spring.io/spring-boot/reference/
- Spring Security: https://docs.spring.io/spring-security/reference/
- Spring Security OAuth2 Client: https://docs.spring.io/spring-security/reference/servlet/oauth2/client/
- Spring Data JPA: https://docs.spring.io/spring-data/jpa/reference/
- Kotlin: https://kotlinlang.org/docs/home.html
- Whoop Developer: https://developer.whoop.com/
- Whoop API v2: https://api.prod.whoop.com/developer/
- Whoop OpenAPI Spec: https://api.prod.whoop.com/developer/doc/openapi.json
- Resilience4j: https://resilience4j.readme.io/docs
- MapStruct: https://mapstruct.org/documentation/stable/reference/html/
- Power BI REST API: https://learn.microsoft.com/en-us/power-bi/

## Server Infrastructure

### Deployment Environment
- Cloud provider: Hetzner Cloud
- Container orchestration: Kubernetes (RKE2 via Rancher)
- Networking: Calico
- Ingress: Traefik
- Load Balancer: MetalLB
- Storage: Longhorn (persistent volumes)
- SSL/TLS: cert-manager with Cloudflare
- Auto-deploy: Keel (polls every 1m, force policy)
- DNS: CoreDNS
- Docker registry: Docker Hub (ocholoko888/*)

### DNS Configuration
- Domain: apptolast.com
- DNS provider: Cloudflare
- App subdomain: david-whoop.apptolast.com

### Deployment Patterns

#### PostgreSQL via Bitnami Helm Chart
```bash
helm install postgresql bitnami/postgresql -n <namespace> \
  --set auth.username=<user> \
  --set auth.password=<password> \
  --set auth.database=<dbname> \
  --set primary.persistence.storageClass=longhorn \
  --set primary.persistence.size=10Gi \
  --set primary.resources.requests.cpu=100m \
  --set primary.resources.requests.memory=256Mi \
  --set primary.resources.limits.cpu=500m \
  --set primary.resources.limits.memory=512Mi
```

#### App Deployment Pattern
- Docker image: ocholoko888/<app>:latest
- Keel annotations: `keel.sh/policy: force`, `keel.sh/pollSchedule: @every 1m`, `keel.sh/trigger: poll`
- Secrets via: `envFrom: [secretRef: {name: <app>-secrets}]`
- Health probes on /actuator/health
- Resources: requests 200m CPU / 384Mi mem, limits 1000m CPU / 768Mi mem
- JAVA_OPTS: -Xms256m -Xmx512m

#### Ingress Pattern (Traefik)
```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  annotations:
    cert-manager.io/cluster-issuer: cloudflare-clusterissuer
    traefik.ingress.kubernetes.io/router.entrypoints: websecure
    traefik.ingress.kubernetes.io/router.tls: "true"
spec:
  ingressClassName: traefik
  rules:
  - host: <subdomain>.apptolast.com
    http:
      paths:
      - backend:
          service:
            name: <service>
            port:
              number: 8080
        path: /
        pathType: Prefix
  tls:
  - hosts:
    - <subdomain>.apptolast.com
    secretName: <app>-tls
```
