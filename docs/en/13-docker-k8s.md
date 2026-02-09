# 13. Docker and Kubernetes

## Index

1. [What is Docker](#1-what-is-docker)
2. [Dockerfile explained line by line](#2-dockerfile-explained-line-by-line)
3. [What is Kubernetes](#3-what-is-kubernetes)
4. [K8s manifests structure of the project](#4-k8s-manifest-structure-for-the-project)
5. [K8s Concepts Explained with Our Code](#5-k8s-concepts-explained-with-our-code)
6. [Complete flow: from code to pod](#6-complete-flow-from-code-to-pod)
7. [Official documentation](#7-official-documentation)

---

## 1. What is Docker?

Docker is a tool that packages your application together with everything it needs to run (JRE, dependencies, configuration) into an **image**. That image can be run on any machine that has Docker installed, ensuring it works the same in development, CI/CD, and production.

### Key concepts

| Concept | Description |
|----------|-------------|
| **Image** | An immutable package with your app + dependencies. It is created from a `Dockerfile`. |
| **Container** | A running instance of an image. It is like an isolated process. |
| **[Dockerfile](https://docs.docker.com/reference/dockerfile/)** | An instruction file to build an image. |
| **[Multi-stage build](https://docs.docker.com/build/building/multi-stage/)** | Technique that uses multiple `FROM` to separate the compilation phase from the execution phase, reducing the size of the final image. |
| **Layer caching** | Docker caches each instruction (`RUN`, `COPY`, etc.) as a layer. If a layer doesn’t change, it isn’t re-executed. |
| **Fat JAR (bootJar)** | Spring Boot packages the entire application + dependencies into a single executable `.jar` file. |

---

## 2. Dockerfile explained line by line

**File**: `Dockerfile`

```dockerfile
# Stage 1: Build
FROM eclipse-temurin:24-jdk-noble AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon || true
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:24-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown spring:spring app.jar
USER spring:spring
EXPOSE 8080
ENV JAVA_OPTS="-Xms256m -Xmx512m"
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### Stage 1: Build (compilation)

```dockerfile
FROM eclipse-temurin:24-jdk-noble AS builder
```

- Use the base image **Eclipse Temurin JDK 24** on Ubuntu Noble (24.04).
- `JDK` (no JRE) because we need the Java compiler to build the project.
- `AS builder` gives this stage a name so it can be referenced later.

```dockerfile
WORKDIR /app
```

- Set `/app` as the working directory inside the container. All subsequent commands are run from here.

```dockerfile
COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon || true
```

- **Layer cache trick**: first, ONLY the Gradle configuration files are copied and the dependencies are downloaded.
- Since dependencies change little, Docker caches this layer. If you only change source code (not `build.gradle.kts`), this layer is reused and the dependencies are not downloaded again.
- `--no-daemon` prevents the Gradle daemon from staying running in the container (it doesn’t make sense in CI/Docker builds).
- `|| true` prevents the build from failing if there are dependencies that are not resolved at this stage (some require the source code).

```dockerfile
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test
```

- Now the source code is copied.
- `bootJar` is the Spring Boot task that generates the fat JAR (a single `.jar` with the entire application + embedded dependencies).
- `-x test` skips the tests (they were already run in the CI workflow).

### Stage 2: Runtime (execution)

```dockerfile
FROM eclipse-temurin:24-jre-alpine
```

- **Second base image**: this time only **JRE** (Java Runtime Environment), not JDK.
- Use **Alpine Linux**, which is much smaller than Ubuntu (~5 MB vs ~80 MB).
- **Result**: the final image does NOT contain the Java compiler, nor the source code, nor Gradle, nor the build dependencies. Only the JAR and the JRE.

```dockerfile
RUN addgroup -S spring && adduser -S spring -G spring
```

- Create a user `spring` without root privileges. **Security**: if someone compromises the application, they do not have root access to the container.
- `-S` = system user/group (no home directory, no password).

```dockerfile
RUN apk add --no-cache curl
```

- Install `curl` for `HEALTHCHECK`. Alpine does not include it by default.
- `--no-cache` avoids storing the package index, reducing the image size.

```dockerfile
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
```

- **`--from=builder`**: copy the JAR from stage 1 (builder) to stage 2 (runtime).
- Only the final artifact is copied. All the compilation tooling is discarded.

```dockerfile
RUN chown spring:spring app.jar
USER spring:spring
```

- Change the owner of the JAR to user `spring`.
- **`USER spring:spring`**: from here on, all commands are executed as the user `spring`, not as root.

```dockerfile
EXPOSE 8080
```

- Document that the container listens on port 8080. It’s informational; it doesn’t actually open the port (Kubernetes does that with `containerPort` or Docker with `-p`).

```dockerfile
ENV JAVA_OPTS="-Xms256m -Xmx512m"
```

- Define default JVM options: 256 MB minimum heap, 512 MB maximum.
- It can be overridden from Kubernetes with an environment variable `JAVA_OPTS`.

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
```

- Docker checks the container’s health every 30 seconds.
- `--start-period=60s`: give Spring Boot 60 seconds to start up before beginning to check.
- `--retries=3`: after 3 consecutive failures, mark the container as `unhealthy`.
- The `/actuator/health` endpoint is from Spring Boot Actuator.

```dockerfile
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

- Start the application. Use `sh -c` so that the variable `$JAVA_OPTS` is interpreted.
- If I used `["java", "$JAVA_OPTS", "-jar", "app.jar"]` (direct exec form), `$JAVA_OPTS` would not be expanded.

### Size comparison (multi-stage vs single-stage)

| Image type | Contains | Approx. size |
|----------------|----------|---------------|
| Full JDK (single-stage) | JDK 24 + Gradle + sources + JAR | ~800 MB |
| JRE Alpine (multi-stage) | JRE 24 + JAR + curl | ~200 MB |

---

## 3. What is Kubernetes?

Kubernetes (K8s) is a **container orchestrator**. It is responsible for:

- **Deploy** containers in a server cluster.
- **Scale** (more replicas if there is more load).
- **Recover** (if a container dies, Kubernetes restarts it).
- **Load-balance** traffic between replicas.
- **Manage** secrets, configuration, storage, and TLS certificates.

Our cluster uses **RKE2** (Rancher’s Kubernetes distribution) on server `138.199.157.58` with these additional components:

| Component | Function |
|------------|---------|
| **[Traefik](https://doc.traefik.io/traefik/)** | Ingress controller / reverse proxy. Routes external traffic to the pods. |
| **[Longhorn](https://longhorn.io/docs/)** | Distributed persistent storage. Provides PersistentVolumes. |
| **[cert-manager](https://cert-manager.io/docs/)** | Automatically generate and renew TLS certificates (via Cloudflare DNS). |
| **[Keel](https://keel.sh/docs/)** | Detect new Docker images and automatically update the deployments. |

---

## 4. K8s manifest structure for the project

```
k8s/
├── 00-namespace.yaml                    # Namespace compartido para PostgreSQL
├── 01-postgresql/
│   ├── configmap-init.yaml              # Script SQL de inicializacion
│   ├── pvc.yaml                         # PersistentVolumeClaim (2 Gi con Longhorn)
│   ├── secret.yaml                      # Credenciales de PostgreSQL
│   ├── service.yaml                     # Service ClusterIP (acceso interno)
│   ├── service-external.yaml            # Service NodePort (acceso externo: 30434)
│   └── statefulset.yaml                 # StatefulSet de PostgreSQL 16
├── 02-api-dev/
│   ├── 00-namespace.yaml                # Namespace: apptolast-whoop-david-api-dev
│   ├── 01-secret.yaml                   # Secrets de la API (Whoop, PowerBI, DB, encryption)
│   ├── 02-configmap.yaml                # ConfigMap con application.yaml override
│   ├── 03-deployment.yaml               # Deployment con anotaciones Keel
│   ├── 04-service.yaml                  # Service ClusterIP
│   ├── 05-certificate.yaml              # Certificate cert-manager (TLS)
│   └── 06-ingressroute.yaml             # IngressRoute Traefik + Middleware seguridad
└── 03-api-prod/
    ├── 00-namespace.yaml                # Namespace: apptolast-whoop-david-api-prod
    ├── 01-secret.yaml                   # Secrets de produccion
    ├── 02-configmap.yaml                # ConfigMap con application.yaml override
    ├── 03-deployment.yaml               # Deployment (image: latest, profile: prod)
    ├── 04-service.yaml                  # Service ClusterIP
    ├── 05-certificate.yaml              # Certificate (david-whoop.apptolast.com)
    └── 06-ingressroute.yaml             # IngressRoute Traefik
```

**Three environments separated by namespaces**:

- `apptolast-whoop-david-api` -- contains only PostgreSQL (shared)
- `apptolast-whoop-david-api-dev` -- API in demo mode (image `:develop`)
- `apptolast-whoop-david-api-prod` -- API in production mode (image `:latest`)

---

## 5. K8s concepts explained with our code

### 5.1 Namespace: logical isolation

**File**: `k8s/00-namespace.yaml`

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: apptolast-whoop-david-api
  labels:
    name: apptolast-whoop-david-api
    app: whoop-david-api
```

**What is**: a [Namespace](https://kubernetes.io/docs/concepts/overview/working-with-objects/namespaces/) is like a folder in Kubernetes. It isolates resources so they don’t get mixed together. The pods, services, and secrets in a namespace are not visible from another (unless the full DNS name is used).

**Why 3 namespaces**: separating PostgreSQL, dev, and prod allows:

- Limit permissions by namespace (RBAC).
- Prevent a dev error from affecting prod.
- Have different secrets per environment.

### 5.2 StatefulSet vs Deployment: databases

**File**: `k8s/01-postgresql/statefulset.yaml`

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgresql
  namespace: apptolast-whoop-david-api
spec:
  serviceName: postgresql
  replicas: 1
  template:
    spec:
      securityContext:
        fsGroup: 999
      containers:
      - name: postgresql
        image: postgres:16-alpine
        env:
        - name: POSTGRES_USER
          valueFrom:
            secretKeyRef:
              name: postgresql-credentials
              key: POSTGRES_USER
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgresql-credentials
              key: POSTGRES_PASSWORD
        - name: POSTGRES_DB
          valueFrom:
            secretKeyRef:
              name: postgresql-credentials
              key: POSTGRES_DB
        - name: PGDATA
          value: /var/lib/postgresql/data/pgdata
        resources:
          requests:
            cpu: 250m
            memory: 512Mi
          limits:
            cpu: 1000m
            memory: 2Gi
        volumeMounts:
        - name: data
          mountPath: /var/lib/postgresql/data
        - name: init-scripts
          mountPath: /docker-entrypoint-initdb.d
        livenessProbe:
          exec:
            command: ["/bin/sh", "-c", "pg_isready -U $POSTGRES_USER -d $POSTGRES_DB"]
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          exec:
            command: ["/bin/sh", "-c", "pg_isready -U $POSTGRES_USER -d $POSTGRES_DB && psql -U $POSTGRES_USER -d $POSTGRES_DB -c 'SELECT 1'"]
          initialDelaySeconds: 10
          periodSeconds: 5
      volumes:
      - name: data
        persistentVolumeClaim:
          claimName: postgresql-data
      - name: init-scripts
        configMap:
          name: postgresql-init-scripts
```

**Why [StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/) and not [Deployment](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)**:

| Feature | Deployment | StatefulSet |
|---------------|------------|-------------|
| Pod identity | Random (pod-xyz123) | Stable (postgresql-0) |
| Storage | Ephemeral (lost upon restart) | Persistent (PVC remains) |
| Boot order | All at once | One by one, in order |
| Use case | Apps stateless (API) | Apps stateful (DB, caches) |

PostgreSQL needs data to survive restarts, which is why it uses StatefulSet + [PersistentVolumeClaim](https://kubernetes.io/docs/concepts/storage/persistent-volumes/).

**The credentials come from a [Secret](https://kubernetes.io/docs/concepts/configuration/secret/)** (`secretKeyRef`), they are not hardcoded in the manifest.

**`securityContext.fsGroup: 999`**: group 999 is the `postgres` group inside the container. This ensures that the persistent volume files have the correct permissions.

### 5.3 PersistentVolumeClaim: storage with Longhorn

**File**: `k8s/01-postgresql/pvc.yaml`

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgresql-data
  namespace: apptolast-whoop-david-api
spec:
  storageClassName: longhorn
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 2Gi
```

- **`storageClassName: longhorn`**: uses Longhorn as the storage provider. Longhorn replicates data across multiple nodes in the cluster for durability.
- **`ReadWriteOnce`**: only one pod can mount this volume at a time (correct for a DB with 1 replica).
- **`2Gi`**: requests 2 GB of storage.

### 5.4 Secret: sensitive data

**File**: `k8s/01-postgresql/secret.yaml`

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: postgresql-credentials
  namespace: apptolast-whoop-david-api
type: Opaque
stringData:
  POSTGRES_USER: admin
  POSTGRES_PASSWORD: "..."
  POSTGRES_DB: whoop_david
```

- **`type: Opaque`**: is the generic type for arbitrary secrets.
- **`stringData`**: allows writing the values in plain text in the YAML. Kubernetes automatically encodes them in base64 when storing them in etcd.
- The pods reference these secrets with `secretKeyRef` in their environment variables.

**Important**: in production, these secrets should be managed with tools like Sealed Secrets, Vault, or SOPS. Keeping them in plain text in Git is a security risk (acceptable for learning).

### 5.5 ConfigMap: non-sensitive configuration

**File**: `k8s/02-api-dev/02-configmap.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: whoop-david-api-config
  namespace: apptolast-whoop-david-api-dev
data:
  application.yaml: |
    spring:
      datasource:
        url: jdbc:postgresql://postgresql.apptolast-whoop-david-api.svc.cluster.local:5432/whoop_david_dev
        username: ${DB_USERNAME}
        password: ${DB_PASSWORD}
        driver-class-name: org.postgresql.Driver
      h2:
        console:
          enabled: false
      jpa:
        hibernate:
          ddl-auto: update
        properties:
          hibernate:
            dialect: org.hibernate.dialect.PostgreSQLDialect
```

- It contains the `application.yaml` that is injected into the pod as a file mounted at `/app/config/`.
- **`SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/`** in the Deployment tells Spring Boot to read this additional configuration.
- The DB URL uses Kubernetes’ internal DNS: `postgresql.apptolast-whoop-david-api.svc.cluster.local` -- this is `<servicio>.<namespace>.svc.cluster.local`.
- `${DB_USERNAME}` and `${DB_PASSWORD}` are resolved from the pod's environment variables (which come from the Secret).

**Difference: Secret vs ConfigMap**:

- **Secret**: sensitive data (passwords, tokens, keys). They are stored encrypted in etcd.
- **[ConfigMap](https://kubernetes.io/docs/concepts/configuration/configmap/)**: non-sensitive data (configuration, URLs, flags). They are stored in plain text.

### 5.6 Deployment: the API

**File**: `k8s/02-api-dev/03-deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: whoop-david-api
  namespace: apptolast-whoop-david-api-dev
  labels:
    app: whoop-david-api
    environment: development
  annotations:
    keel.sh/policy: force
    keel.sh/pollSchedule: "@every 1m"
    keel.sh/trigger: poll
spec:
  replicas: 1
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app: whoop-david-api
      environment: development
  template:
    metadata:
      labels:
        app: whoop-david-api
        environment: development
    spec:
      containers:
        - name: whoop-david-api
          image: apptolast/whoop-david-api:develop
          imagePullPolicy: Always
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          env:
            - name: SPRING_PROFILES_ACTIVE
              value: "demo"
            - name: SPRING_CONFIG_ADDITIONAL_LOCATION
              value: "file:/app/config/"
            - name: JAVA_OPTS
              value: "-Xms256m -Xmx512m"
            - name: WHOOP_CLIENT_ID
              valueFrom:
                secretKeyRef:
                  name: whoop-david-api-secrets
                  key: WHOOP_CLIENT_ID
            - name: WHOOP_CLIENT_SECRET
              valueFrom:
                secretKeyRef:
                  name: whoop-david-api-secrets
                  key: WHOOP_CLIENT_SECRET
            - name: POWERBI_USERNAME
              valueFrom:
                secretKeyRef:
                  name: whoop-david-api-secrets
                  key: POWERBI_USERNAME
            - name: POWERBI_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: whoop-david-api-secrets
                  key: POWERBI_PASSWORD
            - name: DB_USERNAME
              valueFrom:
                secretKeyRef:
                  name: whoop-david-api-secrets
                  key: DB_USERNAME
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: whoop-david-api-secrets
                  key: DB_PASSWORD
            - name: ENCRYPTION_KEY
              valueFrom:
                secretKeyRef:
                  name: whoop-david-api-secrets
                  key: ENCRYPTION_KEY
          volumeMounts:
            - name: config
              mountPath: /app/config
              readOnly: true
          resources:
            requests:
              cpu: 200m
              memory: 384Mi
            limits:
              cpu: 500m
              memory: 768Mi
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 90
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 5
            timeoutSeconds: 3
            failureThreshold: 3
          startupProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 0
            periodSeconds: 5
            failureThreshold: 30
      volumes:
        - name: config
          configMap:
            name: whoop-david-api-config
            items:
              - key: application.yaml
                path: application.yaml
      restartPolicy: Always
      terminationGracePeriodSeconds: 30
```

Explanation by sections:

#### Keel annotations (auto-deploy)

```yaml
annotations:
  keel.sh/policy: force
  keel.sh/pollSchedule: "@every 1m"
  keel.sh/trigger: poll
```

- **Keel** is a Kubernetes operator that monitors Docker Hub.
- `keel.sh/policy: force` -- updates the deployment every time there is a new image, regardless of the tag.
- `keel.sh/pollSchedule: "@every 1m"` -- checks every minute whether there is a new image.
- `keel.sh/trigger: poll` -- uses polling (instead of webhooks).
- **Result**: when the CD workflow pushes a new `apptolast/whoop-david-api:develop` image, Keel detects it in ~1 minute and automatically updates the deployment.

#### Rolling update strategy

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
```

- `maxSurge: 1` -- can create up to 1 new pod before deleting the old one.
- `maxUnavailable: 0` -- it never deletes the old pod until the new one is ready.
- **Result**: zero-downtime deployment. There is always at least 1 pod serving traffic.

#### Image and profile

```yaml
image: apptolast/whoop-david-api:develop    # Dev usa tag :develop
env:
  - name: SPRING_PROFILES_ACTIVE
    value: "demo"                           # Perfil Spring Boot activo
```

Dev vs. prod comparison:

|| Dev | Prod |
|---|-----|------|
| Image | `apptolast/whoop-david-api:develop` | `apptolast/whoop-david-api:latest` |
| Profile | `demo` | `prod` |
| CPU limit | 500m | 1000m |
| Domain | `david-whoop-dev.apptolast.com` | `david-whoop.apptolast.com` |

#### Resources (resource limits)

```yaml
resources:
  requests:
    cpu: 200m       # Garantiza 200 milicores (0.2 CPU)
    memory: 384Mi   # Garantiza 384 MB RAM
  limits:
    cpu: 500m       # Maximo 500 milicores (0.5 CPU)
    memory: 768Mi   # Maximo 768 MB RAM
```

- **requests**: what Kubernetes guarantees to the pod. It is used to decide which node to place the pod on.
- **limits**: the maximum cap. If the pod exceeds the memory limit, Kubernetes kills it (OOMKilled). If it exceeds CPU, it throttles it.

#### Probes (health probes)

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 90
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 5
  failureThreshold: 3

startupProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 0
  periodSeconds: 5
  failureThreshold: 30
```

| Probe | Question answered | If it fails... |
|-------|----------------------|-------------|
| **startupProbe** | "Has it finished starting up?" | Wait longer (up to 30 * 5s = 150s) |
| **readinessProbe** | "Can it receive traffic?" | Stop sending traffic to it (but it doesn’t kill it) |
| **livenessProbe** | "Is he still alive?" | Kubernetes restarts it |

**Why 3 probes**: Spring Boot takes quite a while to start up (~60-90s). Without `startupProbe`, `livenessProbe` could kill the pod before it finishes starting up.

#### ConfigMap volume mount

```yaml
volumeMounts:
  - name: config
    mountPath: /app/config
    readOnly: true
volumes:
  - name: config
    configMap:
      name: whoop-david-api-config
      items:
        - key: application.yaml
          path: application.yaml
```

- Mount the contents of ConfigMap `whoop-david-api-config` as a file at `/app/config/application.yaml`.
- The `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/` variable tells Spring Boot to read this file in addition to the `application.yaml` embedded in the JAR.

### 5.7 Service: expose the application

**File**: `k8s/02-api-dev/04-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: whoop-david-api
  namespace: apptolast-whoop-david-api-dev
spec:
  type: ClusterIP
  ports:
    - name: http
      port: 8080
      targetPort: 8080
      protocol: TCP
  selector:
    app: whoop-david-api
    environment: development
```

**What is a [Service](https://kubernetes.io/docs/concepts/services-networking/service/)**: a Service provides a stable IP and a DNS name to a set of pods. Pods can die and be reborn with different IPs; the Service always finds them via `selector`.

**Types of Service in our project**:

| Type | Access | Example |
|------|--------|---------|
| **[ClusterIP](https://kubernetes.io/docs/concepts/services-networking/service/#type-clusterip)** | Only within the cluster | `whoop-david-api` (API) and `postgresql` (internal DB) |
| **[NodePort](https://kubernetes.io/docs/concepts/services-networking/service/#type-nodeport)** | From outside the cluster via IP:port | `postgresql-external` (port 30434 for DBeaver/pgAdmin) |

**ClusterIP for the API**: external access is handled by Traefik (IngressRoute), not by the Service directly.

**NodePort for external PostgreSQL** (`k8s/01-postgresql/service-external.yaml`):

```yaml
spec:
  type: NodePort
  ports:
  - port: 5432
    targetPort: 5432
    nodePort: 30434
```

Allows connecting to PostgreSQL from external tools such as DBeaver using `138.199.157.58:30434`.

### 5.8 Certificate: Automatic TLS with cert-manager

**File**: `k8s/02-api-dev/05-certificate.yaml`

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: whoop-david-api-tls
  namespace: apptolast-whoop-david-api-dev
spec:
  secretName: whoop-david-api-tls
  issuerRef:
    name: cloudflare-clusterissuer
    kind: ClusterIssuer
  dnsNames:
    - david-whoop-dev.apptolast.com
  duration: 2160h      # 90 dias
  renewBefore: 720h    # Renueva 30 dias antes de expirar
```

- **cert-manager** is a Kubernetes operator that manages [TLS certificates](https://cert-manager.io/docs/usage/certificate/) automatically.
- **`cloudflare-clusterissuer`**: use Cloudflare's DNS challenge to validate the domain and obtain a Let's Encrypt certificate.
- The certificate is stored as a Kubernetes Secret named `whoop-david-api-tls`.
- It renews automatically 30 days before it expires.

**Domains**:

- Dev: `david-whoop-dev.apptolast.com`
- Prod: `david-whoop.apptolast.com`

### 5.9 IngressRoute: Traefik as a reverse proxy

**File**: `k8s/02-api-dev/06-ingressroute.yaml`

```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: whoop-david-api
  namespace: apptolast-whoop-david-api-dev
spec:
  entryPoints:
    - websecure
  routes:
    - match: Host(`david-whoop-dev.apptolast.com`)
      kind: Rule
      services:
        - name: whoop-david-api
          port: 8080
      middlewares:
        - name: security-headers
  tls:
    secretName: whoop-david-api-tls
```

- **IngressRoute** is a Traefik CRD (Custom Resource Definition). It works like an [Ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/) but with more functionality.
- **`entryPoints: websecure`**: only accepts HTTPS (port 443).
- **`match: Host(...)`**: routes requests for `david-whoop-dev.apptolast.com` to service `whoop-david-api:8080`.
- **`tls.secretName`**: use the certificate generated by cert-manager.

#### Security middleware

```yaml
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: security-headers
  namespace: apptolast-whoop-david-api-dev
spec:
  headers:
    customResponseHeaders:
      X-Robots-Tag: "noindex, nofollow"
      Server: ""
    sslRedirect: true
    browserXssFilter: true
    contentTypeNosniff: true
    forceSTSHeader: true
    stsIncludeSubdomains: true
    stsPreload: true
    stsSeconds: 31536000
    frameDeny: true
```

| Header | Protection |
|--------|-----------|
| `X-Robots-Tag: noindex, nofollow` | Prevent search engines from indexing the API |
| `Server: ""` | Hide the server version |
| `sslRedirect: true` | Redirect HTTP to HTTPS |
| `browserXssFilter: true` | Enable the browser's XSS filter |
| `contentTypeNosniff: true` | Prevents MIME-type sniffing |
| `forceSTSHeader + stsSeconds` | HSTS: forces HTTPS for 1 year |
| `frameDeny: true` | Prevents clickjacking (do not allow iframes) |

### 5.10 PostgreSQL initialization ConfigMap

**File**: `k8s/01-postgresql/configmap-init.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: postgresql-init-scripts
  namespace: apptolast-whoop-david-api
data:
  01-create-databases.sql: |
    -- Create dev database (prod database 'whoop_david' is created automatically as POSTGRES_DB)
    CREATE DATABASE whoop_david_dev;
    GRANT ALL PRIVILEGES ON DATABASE whoop_david_dev TO admin;
```

- It is mounted in `/docker-entrypoint-initdb.d/` of the PostgreSQL container.
- PostgreSQL automatically executes the SQL scripts in this directory when it is initialized for the first time.
- Create the dev database (`whoop_david_dev`). The prod one (`whoop_david`) is already created automatically by PostgreSQL because it is configured as `POSTGRES_DB`.

---

## 6. Complete flow: from code to pod

```
1. Developer hace push a la rama dev
        |
2. GitHub Actions CI: ./gradlew build (compila + tests)
        |
3. GitHub Actions CD: ./gradlew bootJar + docker build + docker push
        |
4. Imagen subida a Docker Hub: apptolast/whoop-david-api:develop
        |
5. Keel detecta nueva imagen (poll cada 1 min)
        |
6. Keel actualiza el Deployment (cambia el digest de la imagen)
        |
7. Kubernetes crea un nuevo pod con la nueva imagen
        |
8. startupProbe verifica que Spring Boot arranco
        |
9. readinessProbe confirma que puede recibir trafico
        |
10. Kubernetes redirige el Service al nuevo pod
        |
11. Kubernetes termina el pod viejo (graceful shutdown, 30s)
        |
12. Traefik enruta david-whoop-dev.apptolast.com al Service
        |
13. Usuario accede a https://david-whoop-dev.apptolast.com/api/v1/cycles
```

---

## 7. Official documentation

- [Docker - Dockerfile reference](https://docs.docker.com/reference/dockerfile/)
- [Docker - Multi-stage builds](https://docs.docker.com/build/building/multi-stage/)
- [Kubernetes - Concepts](https://kubernetes.io/docs/concepts/)
- [Kubernetes - Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)
- [Kubernetes - StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- [Kubernetes - Services](https://kubernetes.io/docs/concepts/services-networking/service/)
- [Kubernetes - ConfigMaps](https://kubernetes.io/docs/concepts/configuration/configmap/)
- [Kubernetes - Secrets](https://kubernetes.io/docs/concepts/configuration/secret/)
- [Kubernetes - Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [Kubernetes - PersistentVolumeClaims](https://kubernetes.io/docs/concepts/storage/persistent-volumes/)
- [Traefik - IngressRoute](https://doc.traefik.io/traefik/routing/providers/kubernetes-crd/)
- [cert-manager - Certificate](https://cert-manager.io/docs/usage/certificate/)
- [Longhorn - Documentation](https://longhorn.io/docs/)
- [Keel - Documentation](https://keel.sh/docs/)
- [Spring Boot - Docker](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html)
- [Eclipse Temurin](https://adoptium.net/)
