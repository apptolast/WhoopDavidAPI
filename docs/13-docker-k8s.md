# 13. Docker y Kubernetes

## Indice

1. [Que es Docker](#1-que-es-docker)
2. [Dockerfile explicado linea por linea](#2-dockerfile-explicado-linea-por-linea)
3. [Que es Kubernetes](#3-que-es-kubernetes)
4. [Estructura de manifiestos K8s del proyecto](#4-estructura-de-manifiestos-k8s-del-proyecto)
5. [Conceptos K8s explicados con nuestro codigo](#5-conceptos-k8s-explicados-con-nuestro-codigo)
6. [Flujo completo: del codigo al pod](#6-flujo-completo-del-codigo-al-pod)
7. [Documentacion oficial](#7-documentacion-oficial)

---

## 1. Que es Docker

Docker es una herramienta que empaqueta tu aplicacion junto con todo lo que necesita para ejecutarse (JRE, dependencias, configuracion) en una **imagen**. Esa imagen se puede ejecutar en cualquier maquina que tenga Docker instalado, garantizando que funciona igual en desarrollo, CI/CD y produccion.

### Conceptos clave

| Concepto | Descripcion |
|----------|-------------|
| **Imagen** | Un paquete inmutable con tu app + dependencias. Se crea a partir de un `Dockerfile`. |
| **Contenedor** | Una instancia en ejecucion de una imagen. Es como un proceso aislado. |
| **[Dockerfile](https://docs.docker.com/reference/dockerfile/)** | Un archivo de instrucciones para construir una imagen. |
| **[Multi-stage build](https://docs.docker.com/build/building/multi-stage/)** | Tecnica que usa multiples `FROM` para separar la fase de compilacion de la de ejecucion, reduciendo el tamano de la imagen final. |
| **Layer caching** | Docker cachea cada instruccion (`RUN`, `COPY`, etc.) como una capa. Si una capa no cambia, no se re-ejecuta. |
| **Fat JAR (bootJar)** | Spring Boot empaqueta toda la aplicacion + dependencias en un unico archivo `.jar` ejecutable. |

---

## 2. Dockerfile explicado linea por linea

**Archivo**: `Dockerfile`

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

### Stage 1: Build (compilacion)

```dockerfile
FROM eclipse-temurin:24-jdk-noble AS builder
```

- Usa la imagen base **Eclipse Temurin JDK 24** sobre Ubuntu Noble (24.04).
- `JDK` (no JRE) porque necesitamos el compilador Java para construir el proyecto.
- `AS builder` le da un nombre a esta etapa para poder referenciarla despues.

```dockerfile
WORKDIR /app
```

- Establece `/app` como directorio de trabajo dentro del contenedor. Todos los comandos posteriores se ejecutan desde aqui.

```dockerfile
COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon || true
```

- **Truco de cache de capas**: primero se copian SOLO los archivos de configuracion de Gradle y se descargan las dependencias.
- Como las dependencias cambian poco, Docker cachea esta capa. Si solo cambias codigo fuente (no `build.gradle.kts`), esta capa se reutiliza y no se vuelven a descargar las dependencias.
- `--no-daemon` evita que el daemon de Gradle quede corriendo en el contenedor (no tiene sentido en builds de CI/Docker).
- `|| true` evita que el build falle si hay dependencias que no se resuelven en esta fase (algunas necesitan el codigo fuente).

```dockerfile
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test
```

- Ahora si se copia el codigo fuente.
- `bootJar` es la tarea de Spring Boot que genera el fat JAR (un unico `.jar` con toda la aplicacion + dependencias embebidas).
- `-x test` salta los tests (ya se ejecutaron en el workflow de CI).

### Stage 2: Runtime (ejecucion)

```dockerfile
FROM eclipse-temurin:24-jre-alpine
```

- **Segunda imagen base**: esta vez solo **JRE** (Java Runtime Environment), no JDK.
- Usa **Alpine Linux**, que es mucho mas pequena que Ubuntu (~5 MB vs ~80 MB).
- **Resultado**: la imagen final NO contiene el compilador Java, ni el codigo fuente, ni Gradle, ni las dependencias de compilacion. Solo el JAR y el JRE.

```dockerfile
RUN addgroup -S spring && adduser -S spring -G spring
```

- Crea un usuario `spring` sin privilegios de root. **Seguridad**: si alguien compromete la aplicacion, no tiene acceso root al contenedor.
- `-S` = system user/group (sin home directory, sin password).

```dockerfile
RUN apk add --no-cache curl
```

- Instala `curl` para el `HEALTHCHECK`. Alpine no lo incluye por defecto.
- `--no-cache` evita almacenar el indice de paquetes, reduciendo el tamano de la imagen.

```dockerfile
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
```

- **`--from=builder`**: copia el JAR desde la etapa 1 (builder) a la etapa 2 (runtime).
- Solo se copia el artefacto final. Todo el tooling de compilacion queda descartado.

```dockerfile
RUN chown spring:spring app.jar
USER spring:spring
```

- Cambia el propietario del JAR al usuario `spring`.
- **`USER spring:spring`**: a partir de aqui, todos los comandos se ejecutan como el usuario `spring`, no como root.

```dockerfile
EXPOSE 8080
```

- Documenta que el contenedor escucha en el puerto 8080. Es informativo, no abre el puerto realmente (eso lo hace Kubernetes con el `containerPort` o Docker con `-p`).

```dockerfile
ENV JAVA_OPTS="-Xms256m -Xmx512m"
```

- Define opciones de JVM por defecto: 256 MB de heap minimo, 512 MB maximo.
- Se puede sobrescribir desde Kubernetes con una variable de entorno `JAVA_OPTS`.

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
```

- Docker comprueba la salud del contenedor cada 30 segundos.
- `--start-period=60s`: da 60 segundos al arranque de Spring Boot antes de empezar a comprobar.
- `--retries=3`: despues de 3 fallos consecutivos, marca el contenedor como `unhealthy`.
- El endpoint `/actuator/health` es de Spring Boot Actuator.

```dockerfile
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

- Arranca la aplicacion. Usa `sh -c` para que la variable `$JAVA_OPTS` se interprete.
- Si usara `["java", "$JAVA_OPTS", "-jar", "app.jar"]` (exec form directa), `$JAVA_OPTS` no se expandiria.

### Comparacion de tamano (multi-stage vs single-stage)

| Tipo de imagen | Contiene | Tamano aprox. |
|----------------|----------|---------------|
| JDK completo (single-stage) | JDK 24 + Gradle + sources + JAR | ~800 MB |
| JRE Alpine (multi-stage) | JRE 24 + JAR + curl | ~200 MB |

---

## 3. Que es Kubernetes

Kubernetes (K8s) es un **orquestador de contenedores**. Se encarga de:

- **Desplegar** contenedores en un cluster de servidores.
- **Escalar** (mas replicas si hay mas carga).
- **Recuperar** (si un contenedor muere, Kubernetes lo reinicia).
- **Balancear** trafico entre replicas.
- **Gestionar** secretos, configuracion, almacenamiento y certificados TLS.

Nuestro cluster usa **RKE2** (distribucion de Kubernetes de Rancher) en el servidor `138.199.157.58` con estos componentes adicionales:

| Componente | Funcion |
|------------|---------|
| **[Traefik](https://doc.traefik.io/traefik/)** | Ingress controller / reverse proxy. Enruta trafico externo a los pods. |
| **[Longhorn](https://longhorn.io/docs/)** | Almacenamiento persistente distribuido. Proporciona PersistentVolumes. |
| **[cert-manager](https://cert-manager.io/docs/)** | Genera y renueva certificados TLS automaticamente (via Cloudflare DNS). |
| **[Keel](https://keel.sh/docs/)** | Detecta nuevas imagenes Docker y actualiza los deployments automaticamente. |

---

## 4. Estructura de manifiestos K8s del proyecto

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

**Tres entornos separados por namespaces**:

- `apptolast-whoop-david-api` -- contiene solo PostgreSQL (compartido)
- `apptolast-whoop-david-api-dev` -- API en modo demo (imagen `:develop`)
- `apptolast-whoop-david-api-prod` -- API en modo produccion (imagen `:latest`)

---

## 5. Conceptos K8s explicados con nuestro codigo

### 5.1 Namespace: aislamiento logico

**Archivo**: `k8s/00-namespace.yaml`

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: apptolast-whoop-david-api
  labels:
    name: apptolast-whoop-david-api
    app: whoop-david-api
```

**Que es**: un [Namespace](https://kubernetes.io/docs/concepts/overview/working-with-objects/namespaces/) es como una carpeta en Kubernetes. Aisla recursos para que no se mezclen. Los pods, servicios y secretos de un namespace no son visibles desde otro (a menos que se use el nombre DNS completo).

**Por que 3 namespaces**: separar PostgreSQL, dev y prod permite:

- Limitar permisos por namespace (RBAC).
- Evitar que un error en dev afecte a prod.
- Tener secretos diferentes por entorno.

### 5.2 StatefulSet vs Deployment: bases de datos

**Archivo**: `k8s/01-postgresql/statefulset.yaml`

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

**Por que [StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/) y no [Deployment](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)**:

| Caracteristica | Deployment | StatefulSet |
|---------------|------------|-------------|
| Identidad del pod | Aleatoria (pod-xyz123) | Estable (postgresql-0) |
| Almacenamiento | Efimero (se pierde al reiniciar) | Persistente (PVC se mantiene) |
| Orden de arranque | Todos a la vez | Uno a uno, en orden |
| Caso de uso | Apps stateless (API) | Apps stateful (BD, caches) |

PostgreSQL necesita que los datos sobrevivan a los reinicios, por eso usa StatefulSet + [PersistentVolumeClaim](https://kubernetes.io/docs/concepts/storage/persistent-volumes/).

**Las credenciales vienen de un [Secret](https://kubernetes.io/docs/concepts/configuration/secret/)** (`secretKeyRef`), no estan hardcodeadas en el manifiesto.

**`securityContext.fsGroup: 999`**: el grupo 999 es el grupo `postgres` dentro del contenedor. Esto asegura que los archivos del volumen persistente tengan los permisos correctos.

### 5.3 PersistentVolumeClaim: almacenamiento con Longhorn

**Archivo**: `k8s/01-postgresql/pvc.yaml`

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

- **`storageClassName: longhorn`**: usa Longhorn como proveedor de almacenamiento. Longhorn replica los datos en multiples nodos del cluster para durabilidad.
- **`ReadWriteOnce`**: solo un pod puede montar este volumen a la vez (correcto para una BD con 1 replica).
- **`2Gi`**: solicita 2 GB de almacenamiento.

### 5.4 Secret: datos sensibles

**Archivo**: `k8s/01-postgresql/secret.yaml`

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

- **`type: Opaque`**: es el tipo generico para secretos arbitrarios.
- **`stringData`**: permite escribir los valores en texto plano en el YAML. Kubernetes los codifica automaticamente en base64 al almacenarlos en etcd.
- Los pods referencian estos secretos con `secretKeyRef` en sus variables de entorno.

**Importante**: en produccion, estos secretos deberian gestionarse con herramientas como Sealed Secrets, Vault o SOPS. Tenerlos en texto plano en Git es un riesgo de seguridad (aceptable para aprendizaje).

### 5.5 ConfigMap: configuracion no sensible

**Archivo**: `k8s/02-api-dev/02-configmap.yaml`

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

- Contiene el `application.yaml` que se inyecta en el pod como un archivo montado en `/app/config/`.
- **`SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/`** en el Deployment le dice a Spring Boot que lea esta configuracion adicional.
- La URL de la BD usa el DNS interno de Kubernetes: `postgresql.apptolast-whoop-david-api.svc.cluster.local` -- esto es `<servicio>.<namespace>.svc.cluster.local`.
- `${DB_USERNAME}` y `${DB_PASSWORD}` se resuelven desde las variables de entorno del pod (que vienen del Secret).

**Diferencia Secret vs ConfigMap**:

- **Secret**: datos sensibles (passwords, tokens, claves). Se almacenan cifrados en etcd.
- **[ConfigMap](https://kubernetes.io/docs/concepts/configuration/configmap/)**: datos no sensibles (configuracion, URLs, flags). Se almacenan en texto plano.

### 5.6 Deployment: la API

**Archivo**: `k8s/02-api-dev/03-deployment.yaml`

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

Explicacion por secciones:

#### Anotaciones Keel (auto-deploy)

```yaml
annotations:
  keel.sh/policy: force
  keel.sh/pollSchedule: "@every 1m"
  keel.sh/trigger: poll
```

- **Keel** es un operador de Kubernetes que vigila Docker Hub.
- `keel.sh/policy: force` -- actualiza el deployment cada vez que hay una nueva imagen, sin importar el tag.
- `keel.sh/pollSchedule: "@every 1m"` -- comprueba cada minuto si hay una imagen nueva.
- `keel.sh/trigger: poll` -- usa polling (en vez de webhooks).
- **Resultado**: cuando el workflow de CD sube una nueva imagen `apptolast/whoop-david-api:develop`, Keel la detecta en ~1 minuto y actualiza el deployment automaticamente.

#### Estrategia de rolling update

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1
    maxUnavailable: 0
```

- `maxSurge: 1` -- puede crear hasta 1 pod nuevo antes de eliminar el viejo.
- `maxUnavailable: 0` -- nunca elimina el pod viejo hasta que el nuevo este listo.
- **Resultado**: zero-downtime deployment. Siempre hay al menos 1 pod sirviendo trafico.

#### Imagen y perfil

```yaml
image: apptolast/whoop-david-api:develop    # Dev usa tag :develop
env:
  - name: SPRING_PROFILES_ACTIVE
    value: "demo"                           # Perfil Spring Boot activo
```

Comparacion dev vs prod:

| | Dev | Prod |
|---|-----|------|
| Imagen | `apptolast/whoop-david-api:develop` | `apptolast/whoop-david-api:latest` |
| Perfil | `demo` | `prod` |
| CPU limit | 500m | 1000m |
| Dominio | `david-whoop-dev.apptolast.com` | `david-whoop.apptolast.com` |

#### Resources (limites de recursos)

```yaml
resources:
  requests:
    cpu: 200m       # Garantiza 200 milicores (0.2 CPU)
    memory: 384Mi   # Garantiza 384 MB RAM
  limits:
    cpu: 500m       # Maximo 500 milicores (0.5 CPU)
    memory: 768Mi   # Maximo 768 MB RAM
```

- **requests**: lo que Kubernetes garantiza al pod. Se usa para decidir en que nodo colocar el pod.
- **limits**: el tope maximo. Si el pod supera el limite de memoria, Kubernetes lo mata (OOMKilled). Si supera CPU, lo throttlea.

#### Probes (sondas de salud)

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

| Probe | Pregunta que responde | Si falla... |
|-------|----------------------|-------------|
| **startupProbe** | "Ya termino de arrancar?" | Espera mas (hasta 30 * 5s = 150s) |
| **readinessProbe** | "Puede recibir trafico?" | Deja de enviarle trafico (pero no lo mata) |
| **livenessProbe** | "Sigue vivo?" | Kubernetes lo reinicia |

**Por que 3 probes**: Spring Boot tarda bastante en arrancar (~60-90s). Sin `startupProbe`, la `livenessProbe` podria matar el pod antes de que termine de arrancar.

#### Volume mount del ConfigMap

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

- Monta el contenido del ConfigMap `whoop-david-api-config` como un archivo en `/app/config/application.yaml`.
- La variable `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/app/config/` le dice a Spring Boot que lea este archivo ademas del `application.yaml` embebido en el JAR.

### 5.7 Service: exponer la aplicacion

**Archivo**: `k8s/02-api-dev/04-service.yaml`

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

**Que es un [Service](https://kubernetes.io/docs/concepts/services-networking/service/)**: un Service da una IP estable y un nombre DNS a un conjunto de pods. Los pods pueden morir y renacer con IPs distintas; el Service siempre los encuentra via `selector`.

**Tipos de Service en nuestro proyecto**:

| Tipo | Acceso | Ejemplo |
|------|--------|---------|
| **[ClusterIP](https://kubernetes.io/docs/concepts/services-networking/service/#type-clusterip)** | Solo dentro del cluster | `whoop-david-api` (API) y `postgresql` (BD interna) |
| **[NodePort](https://kubernetes.io/docs/concepts/services-networking/service/#type-nodeport)** | Desde fuera del cluster via IP:puerto | `postgresql-external` (puerto 30434 para DBeaver/pgAdmin) |

**ClusterIP para la API**: el acceso externo lo gestiona Traefik (IngressRoute), no el Service directamente.

**NodePort para PostgreSQL externo** (`k8s/01-postgresql/service-external.yaml`):

```yaml
spec:
  type: NodePort
  ports:
  - port: 5432
    targetPort: 5432
    nodePort: 30434
```

Permite conectarse a PostgreSQL desde herramientas externas como DBeaver usando `138.199.157.58:30434`.

### 5.8 Certificate: TLS automatico con cert-manager

**Archivo**: `k8s/02-api-dev/05-certificate.yaml`

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

- **cert-manager** es un operador de Kubernetes que gestiona [certificados TLS](https://cert-manager.io/docs/usage/certificate/) automaticamente.
- **`cloudflare-clusterissuer`**: usa el DNS challenge de Cloudflare para validar el dominio y obtener un certificado de Let's Encrypt.
- El certificado se almacena como un Secret de Kubernetes llamado `whoop-david-api-tls`.
- Se renueva automaticamente 30 dias antes de expirar.

**Dominios**:

- Dev: `david-whoop-dev.apptolast.com`
- Prod: `david-whoop.apptolast.com`

### 5.9 IngressRoute: Traefik como reverse proxy

**Archivo**: `k8s/02-api-dev/06-ingressroute.yaml`

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

- **IngressRoute** es un CRD (Custom Resource Definition) de Traefik. Funciona como un [Ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/) pero con mas funcionalidad.
- **`entryPoints: websecure`**: solo acepta HTTPS (puerto 443).
- **`match: Host(...)`**: enruta peticiones para `david-whoop-dev.apptolast.com` al servicio `whoop-david-api:8080`.
- **`tls.secretName`**: usa el certificado generado por cert-manager.

#### Middleware de seguridad

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

| Header | Proteccion |
|--------|-----------|
| `X-Robots-Tag: noindex, nofollow` | Evita que buscadores indexen la API |
| `Server: ""` | Oculta la version del servidor |
| `sslRedirect: true` | Redirige HTTP a HTTPS |
| `browserXssFilter: true` | Activa el filtro XSS del navegador |
| `contentTypeNosniff: true` | Previene MIME-type sniffing |
| `forceSTSHeader + stsSeconds` | HSTS: fuerza HTTPS durante 1 ano |
| `frameDeny: true` | Previene clickjacking (no permitir iframes) |

### 5.10 ConfigMap de inicializacion de PostgreSQL

**Archivo**: `k8s/01-postgresql/configmap-init.yaml`

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

- Se monta en `/docker-entrypoint-initdb.d/` del contenedor de PostgreSQL.
- PostgreSQL ejecuta automaticamente los scripts SQL de este directorio al inicializarse por primera vez.
- Crea la base de datos de dev (`whoop_david_dev`). La de prod (`whoop_david`) ya la crea PostgreSQL automaticamente porque esta configurada como `POSTGRES_DB`.

---

## 6. Flujo completo: del codigo al pod

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

## 7. Documentacion oficial

- [Docker - Dockerfile reference](https://docs.docker.com/reference/dockerfile/)
- [Docker - Multi-stage builds](https://docs.docker.com/build/building/multi-stage/)
- [Kubernetes - Conceptos](https://kubernetes.io/docs/concepts/)
- [Kubernetes - Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)
- [Kubernetes - StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- [Kubernetes - Services](https://kubernetes.io/docs/concepts/services-networking/service/)
- [Kubernetes - ConfigMaps](https://kubernetes.io/docs/concepts/configuration/configmap/)
- [Kubernetes - Secrets](https://kubernetes.io/docs/concepts/configuration/secret/)
- [Kubernetes - Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- [Kubernetes - PersistentVolumeClaims](https://kubernetes.io/docs/concepts/storage/persistent-volumes/)
- [Traefik - IngressRoute](https://doc.traefik.io/traefik/routing/providers/kubernetes-crd/)
- [cert-manager - Certificate](https://cert-manager.io/docs/usage/certificate/)
- [Longhorn - Documentacion](https://longhorn.io/docs/)
- [Keel - Documentacion](https://keel.sh/docs/)
- [Spring Boot - Docker](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html)
- [Eclipse Temurin](https://adoptium.net/)
