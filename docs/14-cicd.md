# 14. CI/CD con GitHub Actions

## Indice

1. [Que es CI/CD](#1-que-es-cicd)
2. [Donde se usa en nuestro proyecto](#2-donde-se-usa-en-nuestro-proyecto)
3. [Los 3 workflows y como se encadenan](#3-los-3-workflows-y-como-se-encadenan)
4. [Workflow 1: CI (Continuous Integration)](#4-workflow-1-ci-continuous-integration)
5. [Workflow 2: CD (Continuous Deployment)](#5-workflow-2-cd-continuous-deployment)
6. [Workflow 3: Update API Documentation](#6-workflow-3-update-api-documentation)
7. [Flujo completo del pipeline](#7-flujo-completo-del-pipeline)
8. [Conceptos de GitHub Actions explicados](#8-conceptos-de-github-actions-explicados)
9. [Documentacion oficial](#9-documentacion-oficial)

---

## 1. Que es CI/CD

**CI (Continuous Integration)**: cada vez que alguien hace push o abre un pull request, se ejecutan automaticamente los tests y la compilacion. Si algo falla, el equipo lo sabe de inmediato.

**CD (Continuous Deployment)**: despues de que CI pasa, se construye la imagen Docker y se sube a [Docker Hub](https://hub.docker.com/). Luego, Keel detecta la nueva imagen y actualiza el cluster de Kubernetes automaticamente.

**El objetivo**: que cada cambio en el codigo llegue a produccion (o al entorno de dev) sin intervencion manual, pero con la garantia de que ha pasado los tests.

---

## 2. Donde se usa en nuestro proyecto

Los [workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) estan en `.github/workflows/`:

```
.github/workflows/
├── ci.yml                  # Compilacion y tests
├── cd.yml                  # Build Docker + push a Docker Hub
└── update-api-docs.yml     # Actualiza OpenAPI spec y coleccion Postman
```

---

## 3. Los 3 workflows y como se encadenan

```
                    PR creado/actualizado
                           |
                    +------v------+
                    |    CI       |  Compila + ejecuta tests
                    | (ci.yml)   |  Sube reportes como artefacto
                    +------+------+
                           |
                     PR mergeado
                     (push a dev/main)
                           |
              +------------+------------+
              |                         |
       +------v------+          +------v------+
       |    CI       |          |    CD       |  Construye imagen Docker
       | (ci.yml)    |          | (cd.yml)    |  Sube a Docker Hub
       +-------------+          +------+------+
                                       |
                                       | workflow_run (completed)
                                       |
                                +------v------+
                                | Update Docs |  Espera 60s al deploy
                                | (update-    |  Descarga OpenAPI spec
                                |  api-docs)  |  Genera coleccion Postman
                                +-------------+  Commit al repositorio
```

**Importante**: CI y CD se ejecutan **en paralelo** cuando hay un push a `dev` o `main`. No dependen uno del otro. Update API Docs se ejecuta **despues** de que CD complete con exito.

---

## 4. Workflow 1: CI (Continuous Integration)

**Archivo**: `.github/workflows/ci.yml`

```yaml
name: CI

on:
  pull_request:
    branches: [main, dev]
  push:
    branches: [dev]

permissions:
  contents: read

jobs:
  build:
    name: Build & Test
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java 24
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 24

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build and test
        run: ./gradlew build

      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: build/reports/tests/
          retention-days: 7
```

### Explicacion linea por linea

#### Triggers (`on`)

```yaml
on:
  pull_request:
    branches: [main, dev]
  push:
    branches: [dev]
```

- **[`pull_request`](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request)**: `branches: [main, dev]` -- se ejecuta cuando se crea o actualiza un PR hacia `main` o `dev`. Esto permite verificar el codigo ANTES de mergearlo.
- **[`push`](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#push)**: `branches: [dev]` -- se ejecuta cuando hay un push directo a `dev` (incluye merges de PRs).
- **No hay `push: branches: [main]`**: los pushes a `main` solo se hacen via PR (que ya ejecuto CI en el PR). El CD si se ejecuta en pushes a `main`.

#### Permisos

```yaml
permissions:
  contents: read
```

- El workflow solo necesita **leer** el repositorio (checkout del codigo). No necesita escribir.
- **Principio de minimo privilegio**: si el workflow se compromete, no puede modificar el repositorio.

#### Steps

**Step 1: Checkout**

```yaml
- name: Checkout
  uses: actions/checkout@v4
```

Descarga el codigo del repositorio en el runner. Sin esto, el runner esta vacio.

**Step 2: Setup Java**

```yaml
- name: Setup Java 24
  uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: 24
```

Instala Java 24 (Eclipse Temurin) en el runner. Los runners de [GitHub Actions](https://docs.github.com/en/actions) vienen con Java pero no necesariamente la version correcta.

**Step 3: Setup Gradle**

```yaml
- name: Setup Gradle
  uses: gradle/actions/setup-gradle@v4
```

Configura Gradle con caching automatico. Las dependencias descargadas se cachean entre ejecuciones para acelerar los builds.

**Step 4: Build and test**

```yaml
- name: Build and test
  run: ./gradlew build
```

`./gradlew build` hace todo: compila Kotlin, ejecuta kapt (MapStruct), compila tests, ejecuta tests, y genera reportes. Si cualquier test falla, el step falla y el workflow se marca como fallido.

**Step 5: Upload test reports**

```yaml
- name: Upload test reports
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: test-reports
    path: build/reports/tests/
    retention-days: 7
```

- **`if: always()`**: se ejecuta SIEMPRE, incluso si el build fallo. Asi puedes ver los reportes de test para diagnosticar fallos.
- **`actions/upload-artifact@v4`**: sube los reportes HTML de tests como un artefacto descargable desde la UI de GitHub Actions.
- **`retention-days: 7`**: los artefactos se borran despues de 7 dias para no consumir almacenamiento.

---

## 5. Workflow 2: CD (Continuous Deployment)

**Archivo**: `.github/workflows/cd.yml`

```yaml
name: CD

on:
  push:
    branches: [main, dev]

permissions:
  contents: read

jobs:
  docker:
    name: Build & Push Docker Image
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java 24
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 24

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build (skip tests - already passed in CI)
        run: ./gradlew bootJar -x test

      - name: Login to Docker Hub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Determine Docker tags
        id: tags
        run: |
          if [ "${{ github.ref_name }}" = "main" ]; then
            echo "tags=apptolast/whoop-david-api:latest,apptolast/whoop-david-api:${{ github.sha }}" >> $GITHUB_OUTPUT
          elif [ "${{ github.ref_name }}" = "dev" ]; then
            echo "tags=apptolast/whoop-david-api:develop,apptolast/whoop-david-api:dev-${{ github.sha }}" >> $GITHUB_OUTPUT
          fi

      - name: Build and push
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: ${{ steps.tags.outputs.tags }}
          cache-from: type=gha
          cache-to: type=gha,mode=max
```

### Explicacion linea por linea

#### Trigger

```yaml
on:
  push:
    branches: [main, dev]
```

Solo se ejecuta en pushes a `main` o `dev`. No se ejecuta en PRs (no queremos publicar imagenes Docker de codigo no mergeado).

#### Build del JAR (sin tests)

```yaml
- name: Build (skip tests - already passed in CI)
  run: ./gradlew bootJar -x test
```

- `bootJar` genera el fat JAR de Spring Boot.
- `-x test` salta los tests. Ya se ejecutaron en el workflow CI (que corre en paralelo).
- **Por que no reutilizar el JAR de CI**: cada workflow se ejecuta en un runner distinto. No comparten archivos.

#### Login en Docker Hub

```yaml
- name: Login to Docker Hub
  uses: docker/login-action@v3
  with:
    username: ${{ secrets.DOCKERHUB_USERNAME }}
    password: ${{ secrets.DOCKERHUB_TOKEN }}
```

- **`${{ secrets.DOCKERHUB_USERNAME }}`** y **`${{ secrets.DOCKERHUB_TOKEN }}`**: [secretos](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets) configurados en GitHub (Settings > Secrets and variables > Actions).
- Se usa un Access Token de Docker Hub, no la contrasena directa.

#### Docker Buildx

```yaml
- name: Set up Docker Buildx
  uses: docker/setup-buildx-action@v3
```

Buildx es una extension de Docker que permite builds avanzados: caching en GitHub Actions, builds multi-plataforma, etc.

#### Estrategia de tags

```yaml
- name: Determine Docker tags
  id: tags
  run: |
    if [ "${{ github.ref_name }}" = "main" ]; then
      echo "tags=apptolast/whoop-david-api:latest,apptolast/whoop-david-api:${{ github.sha }}" >> $GITHUB_OUTPUT
    elif [ "${{ github.ref_name }}" = "dev" ]; then
      echo "tags=apptolast/whoop-david-api:develop,apptolast/whoop-david-api:dev-${{ github.sha }}" >> $GITHUB_OUTPUT
    fi
```

| Rama | Tags generados | Keel detecta | Despliega en |
|------|---------------|--------------|--------------|
| `main` | `latest` + `<sha-completo>` | `latest` | Produccion (`apptolast-whoop-david-api-prod`) |
| `dev` | `develop` + `dev-<sha-completo>` | `develop` | Dev (`apptolast-whoop-david-api-dev`) |

**Por que 2 tags**:

- El tag mutable (`latest` / `develop`) es el que Keel vigila para auto-deploy.
- El tag con SHA (`abc123...` / `dev-abc123...`) es inmutable y permite hacer rollback a una version exacta.

**`$GITHUB_OUTPUT`**: es la forma de pasar valores entre steps en GitHub Actions. El step `tags` escribe el valor; el step `Build and push` lo lee con `${{ steps.tags.outputs.tags }}`.

#### Build y push de la imagen Docker

```yaml
- name: Build and push
  uses: docker/build-push-action@v6
  with:
    context: .
    push: true
    tags: ${{ steps.tags.outputs.tags }}
    cache-from: type=gha
    cache-to: type=gha,mode=max
```

- **`context: .`**: usa el directorio raiz del repo como contexto de Docker (donde esta el `Dockerfile`).
- **`push: true`**: sube la imagen a Docker Hub despues de construirla.
- **`tags`**: los tags calculados en el step anterior.
- **`cache-from: type=gha`** y **`cache-to: type=gha,mode=max`**: usa el [cache de GitHub Actions para las capas de Docker](https://docs.docker.com/build/cache/). Esto acelera mucho los builds (las capas de dependencias no se reconstruyen si no cambiaron).

**`mode=max`**: cachea todas las capas intermedias, no solo las del resultado final. Util para multi-stage builds porque cachea tambien las capas del builder.

---

## 6. Workflow 3: Update API Documentation

**Archivo**: `.github/workflows/update-api-docs.yml`

```yaml
name: Update API Documentation

on:
  workflow_run:
    workflows: ["CD"]
    types:
      - completed
    branches:
      - main
      - dev

  workflow_dispatch:
    inputs:
      environment:
        description: 'Entorno desde el cual obtener la spec'
        required: true
        default: 'dev'
        type: choice
        options:
          - dev
          - prod

permissions:
  contents: write

env:
  API_URL_DEV: https://david-whoop-dev.apptolast.com
  API_URL_PROD: https://david-whoop.apptolast.com
```

### Trigger: `workflow_run`

```yaml
on:
  workflow_run:
    workflows: ["CD"]
    types: [completed]
    branches: [main, dev]
```

- **[`workflow_run`](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run)**: se ejecuta DESPUES de que otro workflow termine.
- **`workflows: ["CD"]`**: se dispara cuando el workflow llamado "CD" completa.
- **`types: [completed]`**: se ejecuta tanto si CD tuvo exito como si fallo. La condicion `if` del job filtra solo los exitosos.
- **`branches: [main, dev]`**: solo si el CD fue para estas ramas.

```yaml
  workflow_dispatch:
    inputs:
      environment:
        description: 'Entorno desde el cual obtener la spec'
        required: true
        default: 'dev'
        type: choice
        options: [dev, prod]
```

- **`workflow_dispatch`**: permite ejecutar el workflow manualmente desde la UI de GitHub Actions.
- Define un input `environment` con un selector de opciones.

### Permisos de escritura

```yaml
permissions:
  contents: write
```

Este workflow necesita **escribir** en el repositorio porque hace commit de los archivos generados (OpenAPI spec y coleccion Postman).

### Job: update-docs

```yaml
jobs:
  update-docs:
    name: Update OpenAPI & Postman Collection
    runs-on: ubuntu-latest
    if: ${{ github.event.workflow_run.conclusion == 'success' || github.event_name == 'workflow_dispatch' }}
```

- **`if: ... conclusion == 'success'`**: solo se ejecuta si el workflow CD fue exitoso. Si CD fallo, no tiene sentido intentar actualizar la documentacion.
- **`|| github.event_name == 'workflow_dispatch'`**: tambien se ejecuta si se lanzo manualmente.

### Steps explicados

**Step 1: Checkout**

```yaml
- name: Checkout repository
  uses: actions/checkout@v4
  with:
    token: ${{ secrets.GITHUB_TOKEN }}
    ref: ${{ github.event.workflow_run.head_branch || github.ref }}
```

- **`ref`**: hace checkout de la rama que disparo el workflow CD, no de la rama por defecto. Asi el commit se hace en la rama correcta.

**Step 2-3: Setup Node.js + Install openapi2postmanv2**

```yaml
- name: Setup Node.js
  uses: actions/setup-node@v4
  with:
    node-version: '20'

- name: Install openapi-to-postmanv2
  run: npm install -g openapi-to-postmanv2
```

La herramienta `openapi2postmanv2` convierte un spec OpenAPI a una coleccion Postman. Es una herramienta de Node.js.

**Step 4: Determinar URL de la API**

```yaml
- name: Determine API URL
  id: api-url
  run: |
    if [ "${{ github.event_name }}" = "workflow_dispatch" ]; then
      ENV="${{ github.event.inputs.environment }}"
    elif [ "${{ github.event.workflow_run.head_branch }}" = "main" ]; then
      ENV="prod"
    else
      ENV="dev"
    fi

    if [ "$ENV" = "prod" ]; then
      echo "url=${{ env.API_URL_PROD }}" >> $GITHUB_OUTPUT
    else
      echo "url=${{ env.API_URL_DEV }}" >> $GITHUB_OUTPUT
    fi
    echo "env=$ENV" >> $GITHUB_OUTPUT
```

| Rama | URL |
|------|-----|
| `main` | `https://david-whoop.apptolast.com` |
| `dev` | `https://david-whoop-dev.apptolast.com` |

**Step 5: Esperar al deploy**

```yaml
- name: Wait for API deployment
  run: |
    echo "Esperando 60 segundos para que el deployment complete..."
    sleep 60
```

Keel tarda ~1 minuto en detectar la imagen nueva y desplegar. Este sleep da tiempo a que el pod nuevo arranque.

**Step 6: Descargar OpenAPI spec**

```yaml
- name: Fetch OpenAPI spec
  id: fetch-spec
  run: |
    API_URL="${{ steps.api-url.outputs.url }}"
    echo "Obteniendo OpenAPI spec desde: $API_URL/v3/api-docs"

    for i in 1 2 3; do
      HTTP_CODE=$(curl -s -w "%{http_code}" -o openapi.json "$API_URL/v3/api-docs")
      if [ "$HTTP_CODE" = "200" ]; then
        echo "OpenAPI spec obtenido exitosamente"
        cat openapi.json | jq '.' > openapi_formatted.json
        mv openapi_formatted.json openapi.json
        break
      fi
      echo "Intento $i fallido (HTTP $HTTP_CODE), esperando 30 segundos..."
      sleep 30
    done

    if [ ! -s openapi.json ]; then
      echo "Error: No se pudo obtener el OpenAPI spec"
      exit 1
    fi
```

- Hace hasta 3 intentos de descargar `/v3/api-docs` (endpoint de springdoc-openapi).
- Si el primer intento falla (la API puede no estar lista), espera 30 segundos y reintenta.
- Formatea el JSON con `jq` para facilitar los diffs en Git.

**Step 7: Generar coleccion Postman**

```yaml
- name: Generate Postman Collection
  run: |
    openapi2postmanv2 \
      -s openapi.json \
      -o WhoopDavidAPI_Collection_NEW.postman_collection.json \
      -p \
      -O folderStrategy=Tags,includeAuthInfoInExample=true
```

- `-s`: archivo fuente (OpenAPI spec).
- `-o`: archivo de salida.
- `-p`: pretty print.
- `-O folderStrategy=Tags`: organiza las peticiones por tags (en vez de por paths).
- `includeAuthInfoInExample=true`: incluye la autenticacion en los ejemplos.

**Step 8: Detectar cambios**

```yaml
- name: Check for changes
  id: changes
  run: |
    git add openapi.json WhoopDavidAPI_Collection.postman_collection.json
    git diff --staged --quiet || echo "changed=true" >> $GITHUB_OUTPUT
```

- **`git add`**: agrega los archivos al staging area. Esto es importante porque pueden ser archivos **nuevos** (que `git diff` sin `--staged` no detectaria).
- **`git diff --staged --quiet`**: retorna exit code 0 si no hay cambios, 1 si hay cambios.
- **`|| echo "changed=true"`**: si hay cambios, setea la variable `changed` para el siguiente step.

**Por que `git add` + `git diff --staged` en vez de `git diff --quiet`**: `git diff --quiet` solo detecta cambios en archivos ya trackeados. Si `openapi.json` es un archivo nuevo (primera vez que se genera), `git diff --quiet` no lo detectaria. `git add` primero y `git diff --staged` despues detecta tanto archivos nuevos como modificados.

**Step 9: Commit y push**

```yaml
- name: Commit and push changes
  if: steps.changes.outputs.changed == 'true'
  run: |
    git config --local user.email "github-actions[bot]@users.noreply.github.com"
    git config --local user.name "github-actions[bot]"

    BRANCH="${{ github.event.workflow_run.head_branch || github.ref_name }}"
    ENV="${{ steps.api-url.outputs.env }}"

    git commit -m "docs: auto-update OpenAPI spec and Postman collection

    - Updated from $ENV environment API
    - Branch: $BRANCH
    - Generated by GitHub Actions

    Co-Authored-By: github-actions[bot] <github-actions[bot]@users.noreply.github.com>"

    git push
```

- Solo se ejecuta si hubo cambios (`if: steps.changes.outputs.changed == 'true'`).
- Configura el usuario de Git como `github-actions[bot]` para que los commits aparezcan como automaticos.
- Hace commit y push directamente a la rama.

**Step 10: Summary**

```yaml
- name: Summary
  run: |
    echo "## API Documentation Update" >> $GITHUB_STEP_SUMMARY
    echo "" >> $GITHUB_STEP_SUMMARY
    echo "| Item | Status |" >> $GITHUB_STEP_SUMMARY
    ...
```

- **`$GITHUB_STEP_SUMMARY`**: genera un resumen visible en la pagina del workflow en GitHub. Es una tabla markdown con el resultado de la ejecucion.

---

## 7. Flujo completo del pipeline

### Cuando se crea un PR hacia `dev` o `main`

```
1. Developer crea/actualiza PR
2. CI workflow se ejecuta:
   - Compila el proyecto
   - Ejecuta los 9 tests
   - Sube reportes de test como artefacto
3. GitHub muestra check verde/rojo en el PR
4. Si es verde, el PR se puede mergear
```

### Cuando se mergea un PR a `dev`

```
1. Push a dev (merge del PR)
        |
        +---> CI workflow: compila + tests (redundante pero seguro)
        |
        +---> CD workflow (en paralelo):
                |
                1. ./gradlew bootJar -x test
                2. docker login (Docker Hub)
                3. Calcula tags: develop + dev-<sha>
                4. docker build + push
                |
                +---> Imagen en Docker Hub: apptolast/whoop-david-api:develop
                        |
                        +---> Keel detecta (~1 min)
                        |     Actualiza Deployment en apptolast-whoop-david-api-dev
                        |     Rolling update: nuevo pod arranca, viejo se elimina
                        |
                        +---> Update API Docs workflow (tras CD exitoso):
                              1. Espera 60s
                              2. GET https://david-whoop-dev.apptolast.com/v3/api-docs
                              3. Convierte a coleccion Postman
                              4. Commit + push al repo
```

### Cuando se mergea un PR a `main`

```
1. Push a main (merge del PR)
        |
        +---> CD workflow:
                |
                1. Calcula tags: latest + <sha>
                2. docker build + push
                |
                +---> Imagen en Docker Hub: apptolast/whoop-david-api:latest
                        |
                        +---> Keel detecta (~1 min)
                        |     Actualiza Deployment en apptolast-whoop-david-api-prod
                        |
                        +---> Update API Docs workflow:
                              1. GET https://david-whoop.apptolast.com/v3/api-docs
                              2. Commit coleccion Postman actualizada
```

### Resumen visual

```
                     PR a dev/main
                          |
                    +-----v-----+
                    |    CI     |  ./gradlew build
                    +-----------+  (upload test reports)
                          |
                    merge a dev/main
                          |
              +-----------+-----------+
              |                       |
        +-----v-----+          +-----v-----+
        |    CI     |          |    CD     |  bootJar + docker push
        +-----------+          +-----+-----+
                                     |
                               +-----v-----+
                               | Update    |  OpenAPI + Postman
                               | API Docs  |
                               +-----------+
                                     |
                          Keel detecta imagen nueva
                                     |
                          Kubernetes hace rolling update
                                     |
                          App desplegada en K8s
```

---

## 8. Conceptos de GitHub Actions explicados

### Workflows, Jobs y Steps

```
Workflow (.yml file)
  └── Job (runs on a runner)
       └── Step 1 (action o comando)
       └── Step 2
       └── Step 3
```

- **Workflow**: un archivo YAML en `.github/workflows/`. Define cuando y que se ejecuta.
- **Job**: un grupo de steps que se ejecutan en el mismo runner (maquina virtual). Jobs diferentes pueden correr en paralelo.
- **Step**: una accion individual (ejecutar un comando, usar una action).

### `actions/...` (Actions reutilizables)

Las actions son bloques de funcionalidad reutilizables, publicadas por la comunidad o por GitHub:

| Action | Version | Funcion |
|--------|---------|---------|
| [`actions/checkout@v4`](https://github.com/actions/checkout) | v4 | Descarga el codigo del repo |
| [`actions/setup-java@v4`](https://github.com/actions/setup-java) | v4 | Instala una version de Java |
| `gradle/actions/setup-gradle@v4` | v4 | Configura Gradle con cache |
| `actions/upload-artifact@v4` | v4 | Sube archivos como artefactos |
| `docker/login-action@v3` | v3 | Login en un registry de Docker |
| `docker/setup-buildx-action@v3` | v3 | Configura Docker Buildx |
| [`docker/build-push-action@v6`](https://github.com/docker/build-push-action) | v6 | Build + push de imagen Docker |
| `actions/setup-node@v4` | v4 | Instala Node.js |

### `secrets` (Secretos)

Los secretos se configuran en GitHub (Settings > Secrets and variables > Actions) y se referencian como `${{ secrets.NOMBRE }}`.

Secretos usados en nuestro proyecto:

| Secreto | Usado en | Proposito |
|---------|----------|-----------|
| `DOCKERHUB_USERNAME` | CD | Usuario de Docker Hub |
| `DOCKERHUB_TOKEN` | CD | Access Token de Docker Hub |
| `GITHUB_TOKEN` | Update API Docs | Token automatico de GitHub para hacer push |

**[`GITHUB_TOKEN`](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token)** es especial: GitHub lo genera automaticamente para cada ejecucion del workflow. No necesitas configurarlo manualmente.

### `permissions`

Controla que puede hacer el workflow con los permisos del `GITHUB_TOKEN`:

```yaml
permissions:
  contents: read    # CI y CD: solo leer el repo
```

```yaml
permissions:
  contents: write   # Update API Docs: necesita escribir (commit + push)
```

### `$GITHUB_OUTPUT` (comunicacion entre steps)

```yaml
# Step 1: escribe
echo "tags=apptolast/whoop-david-api:latest" >> $GITHUB_OUTPUT

# Step 2: lee
tags: ${{ steps.tags.outputs.tags }}
```

`$GITHUB_OUTPUT` es un archivo especial donde un step puede escribir pares clave=valor. Otros steps del mismo job pueden leer esos valores con `${{ steps.<id>.outputs.<clave> }}`.

### `$GITHUB_STEP_SUMMARY`

```yaml
echo "## Titulo" >> $GITHUB_STEP_SUMMARY
echo "| Col1 | Col2 |" >> $GITHUB_STEP_SUMMARY
```

Genera un resumen en markdown visible en la pagina del workflow en GitHub. Util para mostrar informacion resumida del resultado.

### `if: always()` y `if: steps.X.outputs.Y == 'true'`

```yaml
- name: Upload test reports
  if: always()  # Se ejecuta incluso si steps anteriores fallaron
```

```yaml
- name: Commit and push
  if: steps.changes.outputs.changed == 'true'  # Solo si hubo cambios
```

Los steps se pueden condicionar con expresiones. `always()` garantiza la ejecucion aunque el workflow este fallando.

### Docker layer caching con `type=gha`

```yaml
cache-from: type=gha
cache-to: type=gha,mode=max
```

- **`type=gha`**: usa el cache de GitHub Actions (no Docker Hub ni un registry externo).
- **`cache-from`**: al construir, busca capas cacheadas en GitHub.
- **`cache-to`**: despues de construir, sube las capas nuevas al cache.
- **`mode=max`**: cachea TODAS las capas intermedias (incluidas las del builder stage en un multi-stage build).

**Resultado**: si solo cambio el codigo fuente (no las dependencias), Docker reutiliza la capa de `./gradlew dependencies` del cache, ahorrando varios minutos.

### `workflow_run` (encadenar workflows)

```yaml
on:
  workflow_run:
    workflows: ["CD"]
    types: [completed]
    branches: [main, dev]
```

Es la forma de ejecutar un workflow DESPUES de que otro termine. A diferencia de poner todo en un solo workflow, permite:

- Que cada workflow tenga permisos diferentes (`read` vs `write`).
- Que los workflows sean independientes y reutilizables.
- Que el tercer workflow solo se ejecute si el segundo (CD) fue exitoso.

**Atencion**: `types: [completed]` se ejecuta tanto si fue exitoso como si fallo. Por eso el job tiene `if: github.event.workflow_run.conclusion == 'success'`.

---

## 9. Documentacion oficial

- [GitHub Actions - Documentacion](https://docs.github.com/en/actions)
- [GitHub Actions - Workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
- [GitHub Actions - Events that trigger workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)
- [GitHub Actions - Encrypted secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
- [GitHub Actions - workflow_run event](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run)
- [GitHub Actions - GITHUB_OUTPUT](https://docs.github.com/en/actions/using-workflows/workflow-commands-for-github-actions#setting-an-output-parameter)
- [docker/build-push-action](https://github.com/docker/build-push-action)
- [docker/login-action](https://github.com/docker/login-action)
- [gradle/actions/setup-gradle](https://github.com/gradle/actions/tree/main/setup-gradle)
- [Keel - Policies](https://keel.sh/docs/#policies)
