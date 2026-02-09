# 14. CI/CD with GitHub Actions

## Index

1. [What is CI/CD](#1-what-is-cicd)
2. [Where it is used in our project](#2-where-is-it-used-in-our-project)
3. [The 3 workflows and how they are chained](#3-the-3-workflows-and-how-they-are-chained-together)
4. [Workflow 1: CI (Continuous Integration)](#4-workflow-1-ci-continuous-integration)
5. [Workflow 2: CD (Continuous Deployment)](#5-workflow-2-cd-continuous-deployment)
6. [Workflow 3: Update API Documentation](#6-workflow-3-update-api-documentation)
7. [Complete pipeline flow](#7-complete-pipeline-flow)
8. [GitHub Actions concepts explained](#8-github-actions-concepts-explained)
9. [Official documentation](#9-official-documentation)

---

## 1. What is CI/CD?

**CI (Continuous Integration)**: every time someone pushes or opens a pull request, the tests and the build run automatically. If something fails, the team knows immediately.

**CD (Continuous Deployment)**: after CI passes, the Docker image is built and pushed to [Docker Hub](https://hub.docker.com/). Then, Keel detects the new image and automatically updates the Kubernetes cluster.

**The goal**: that every change in the code reaches production (or the dev environment) without manual intervention, but with the guarantee that it has passed the tests.

---

## 2. Where is it used in our project?

The [workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax) are in `.github/workflows/`:

```
.github/workflows/
├── ci.yml                  # Compilacion y tests
├── cd.yml                  # Build Docker + push a Docker Hub
└── update-api-docs.yml     # Actualiza OpenAPI spec y coleccion Postman
```

---

## 3. The 3 workflows and how they are chained together

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

**Important**: CI and CD run **in parallel** when there is a push to `dev` or `main`. They do not depend on each other. Update API Docs runs **after** CD completes successfully.

---

## 4. Workflow 1: CI (Continuous Integration)

**File**: `.github/workflows/ci.yml`

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

### Line-by-line explanation

#### Triggers (`on`)

```yaml
on:
  pull_request:
    branches: [main, dev]
  push:
    branches: [dev]
```

- **[`pull_request`](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request)**: `branches: [main, dev]` -- runs when a PR is created or updated toward `main` or `dev`. This allows verifying the code BEFORE merging it.
- **[`push`](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#push)**: `branches: [dev]` -- runs when there is a direct push to `dev` (includes PR merges).
- **There is no `push: branches: [main]`**: pushes to `main` are only done via PR (which already runs CI in the PR). CD does run on pushes to `main`.

#### Permissions

```yaml
permissions:
  contents: read
```

- The workflow only needs to **read** the repository (code checkout). It doesn’t need to write.
- **Principle of least privilege**: if the workflow is compromised, it cannot modify the repository.

#### Steps

**Step 1: Checkout**

```yaml
- name: Checkout
  uses: actions/checkout@v4
```

Download the code from the repository onto the runner. Without this, the runner is empty.

**Step 2: Setup Java**

```yaml
- name: Setup Java 24
  uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: 24
```

Install Java 24 (Eclipse Temurin) on the runner. The [GitHub Actions](https://docs.github.com/en/actions) runners come with Java but not necessarily the correct version.

**Step 3: Setup Gradle**

```yaml
- name: Setup Gradle
  uses: gradle/actions/setup-gradle@v4
```

Configure Gradle with automatic caching. Downloaded dependencies are cached between runs to speed up builds.

**Step 4: Build and test**

```yaml
- name: Build and test
  run: ./gradlew build
```

`./gradlew build` does everything: compiles Kotlin, runs kapt (MapStruct), compiles tests, runs tests, and generates reports. If any test fails, the step fails and the workflow is marked as failed.

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

- **`if: always()`**: it ALWAYS runs, even if the build failed. This way you can see the test reports to diagnose failures.
- **`actions/upload-artifact@v4`**: uploads the HTML test reports as an artifact downloadable from the GitHub Actions UI.
- **`retention-days: 7`**: artifacts are deleted after 7 days to avoid consuming storage.

---

## 5. Workflow 2: CD (Continuous Deployment)

**File**: `.github/workflows/cd.yml`

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

### Line-by-line explanation

#### Trigger

```yaml
on:
  push:
    branches: [main, dev]
```

It only runs on pushes to `main` or `dev`. It does not run on PRs (we don’t want to publish Docker images from unmerged code).

#### JAR build (without tests)

```yaml
- name: Build (skip tests - already passed in CI)
  run: ./gradlew bootJar -x test
```

- `bootJar` generates the Spring Boot fat JAR.
- `-x test` skips the tests. They were already run in the CI workflow (which runs in parallel).
- **Why not reuse the CI JAR**: each workflow runs on a different runner. They do not share files.

#### Docker Hub login

```yaml
- name: Login to Docker Hub
  uses: docker/login-action@v3
  with:
    username: ${{ secrets.DOCKERHUB_USERNAME }}
    password: ${{ secrets.DOCKERHUB_TOKEN }}
```

- **`${{ secrets.DOCKERHUB_USERNAME }}`** and **`${{ secrets.DOCKERHUB_TOKEN }}`**: [secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets) configured in GitHub (Settings > Secrets and variables > Actions).
- A Docker Hub Access Token is used, not the direct password.

#### Docker Buildx

```yaml
- name: Set up Docker Buildx
  uses: docker/setup-buildx-action@v3
```

Buildx is a Docker extension that enables advanced builds: caching in GitHub Actions, multi-platform builds, etc.

#### Tag strategy

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

| Branch | Generated tags | Keel detects | Deploy in |
|------|---------------|--------------|--------------|
| `main` | `latest` + `<sha-completo>` | `latest` | Production (`apptolast-whoop-david-api-prod`) |
| `dev` | `develop` + `dev-<sha-completo>` | `develop` | Dev (`apptolast-whoop-david-api-dev`) |

**Why 2 tags**:

- The mutable tag (`latest` / `develop`) is the one Keel monitors for auto-deploy.
- The tag with SHA (`abc123...` / `dev-abc123...`) is immutable and allows rolling back to an exact version.

**`$GITHUB_OUTPUT`**: it is the way to pass values between steps in GitHub Actions. Step `tags` writes the value; step `Build and push` reads it with `${{ steps.tags.outputs.tags }}`.

#### Build and push of the Docker image

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

- **`context: .`**: use the repo root directory as the Docker context (where `Dockerfile` is).
- **`push: true`**: upload the image to Docker Hub after building it.
- **`tags`**: the tags calculated in the previous step.
- **`cache-from: type=gha`** and **`cache-to: type=gha,mode=max`**: use the GitHub Actions [cache for Docker layers](https://docs.docker.com/build/cache/). This greatly speeds up builds (dependency layers are not rebuilt if they haven’t changed).

**`mode=max`**: caches all intermediate layers, not just those of the final result. Useful for multi-stage builds because it also caches the builder layers.

---

## 6. Workflow 3: Update API Documentation

**File**: `.github/workflows/update-api-docs.yml`

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

- **[`workflow_run`](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run)**: it runs AFTER another workflow finishes.
- **`workflows: ["CD"]`**: triggers when the workflow named "CD" completes.
- **`types: [completed]`**: it runs both if CD succeeded and if it failed. The job condition `if` filters only the successful ones.
- **`branches: [main, dev]`**: only if the CD was for these branches.

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

- **`workflow_dispatch`**: allows you to run the workflow manually from the GitHub Actions UI.
- Define an `environment` input with an options selector.

### Write permissions

```yaml
permissions:
  contents: write
```

This workflow needs **write** access in the repository because it commits the generated files (OpenAPI spec and Postman collection).

### Job: update-docs

```yaml
jobs:
  update-docs:
    name: Update OpenAPI & Postman Collection
    runs-on: ubuntu-latest
    if: ${{ github.event.workflow_run.conclusion == 'success' || github.event_name == 'workflow_dispatch' }}
```

- **`if: ... conclusion == 'success'`**: only runs if the CD workflow was successful. If CD failed, it makes no sense to try to update the documentation.
- **`|| github.event_name == 'workflow_dispatch'`**: it also runs if it was launched manually.

### Explained steps

**Step 1: Checkout**

```yaml
- name: Checkout repository
  uses: actions/checkout@v4
  with:
    token: ${{ secrets.GITHUB_TOKEN }}
    ref: ${{ github.event.workflow_run.head_branch || github.ref }}
```

- **`ref`**: checks out the branch that triggered the CD workflow, not the default branch. That way the commit is made on the correct branch.

**Step 2-3: Set up Node.js + Install openapi2postmanv2**

```yaml
- name: Setup Node.js
  uses: actions/setup-node@v4
  with:
    node-version: '20'

- name: Install openapi-to-postmanv2
  run: npm install -g openapi-to-postmanv2
```

The `openapi2postmanv2` tool converts an OpenAPI spec into a Postman collection. It is a Node.js tool.

**Step 4: Determine API URL**

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

| Branch | URL |
|------|-----|
| `main` | `https://david-whoop.apptolast.com` |
| `dev` | `https://david-whoop-dev.apptolast.com` |

**Step 5: Wait for the deploy**

```yaml
- name: Wait for API deployment
  run: |
    echo "Esperando 60 segundos para que el deployment complete..."
    sleep 60
```

Keel takes ~1 minute to detect the new image and deploy. This sleep gives time for the new pod to start up.

**Step 6: Download OpenAPI spec**

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

- Makes up to 3 attempts to download `/v3/api-docs` (springdoc-openapi endpoint).
- If the first attempt fails (the API may not be ready), wait 30 seconds and retry.
- Format the JSON with `jq` to make diffs in Git easier.

**Step 7: Generate Postman collection**

```yaml
- name: Generate Postman Collection
  run: |
    openapi2postmanv2 \
      -s openapi.json \
      -o WhoopDavidAPI_Collection_NEW.postman_collection.json \
      -p \
      -O folderStrategy=Tags,includeAuthInfoInExample=true
```

- `-s`: source file (OpenAPI spec).
- `-o`: output file.
- `-p`: pretty print.
- `-O folderStrategy=Tags`: organizes requests by tags (instead of by paths).
- `includeAuthInfoInExample=true`: include authentication in the examples.

**Step 8: Detect changes**

```yaml
- name: Check for changes
  id: changes
  run: |
    git add openapi.json WhoopDavidAPI_Collection.postman_collection.json
    git diff --staged --quiet || echo "changed=true" >> $GITHUB_OUTPUT
```

- **`git add`**: adds the files to the staging area. This is important because they can be **new** files (which `git diff` without `--staged` would not detect).
- **`git diff --staged --quiet`**: returns exit code 0 if there are no changes, 1 if there are changes.
- **`|| echo "changed=true"`**: if there are changes, set the variable `changed` for the next step.

**Why `git add` + `git diff --staged` instead of `git diff --quiet`**: `git diff --quiet` only detects changes in files that are already tracked. If `openapi.json` is a new file (the first time it is generated), `git diff --quiet` would not detect it. `git add` first and `git diff --staged` afterwards detects both new and modified files.

**Step 9: Commit and push**

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

- It only runs if there were changes (`if: steps.changes.outputs.changed == 'true'`).
- Configure the Git user as `github-actions[bot]` so that commits appear as automatic.
- Commit and push directly to the branch.

**Step 10: Summary**

```yaml
- name: Summary
  run: |
    echo "## API Documentation Update" >> $GITHUB_STEP_SUMMARY
    echo "" >> $GITHUB_STEP_SUMMARY
    echo "| Item | Status |" >> $GITHUB_STEP_SUMMARY
    ...
```

- **`$GITHUB_STEP_SUMMARY`**: generates a visible summary on the workflow page in GitHub. It is a markdown table with the execution result.

---

## 7. Complete pipeline flow

### When a PR is created toward `dev` or `main`

```
1. Developer crea/actualiza PR
2. CI workflow se ejecuta:
   - Compila el proyecto
   - Ejecuta los 9 tests
   - Sube reportes de test como artefacto
3. GitHub muestra check verde/rojo en el PR
4. Si es verde, el PR se puede mergear
```

### When a PR is merged into `dev`

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

### When a PR is merged into `main`

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

### Visual summary

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

## 8. GitHub Actions Concepts Explained

### Workflows, Jobs and Steps

```
Workflow (.yml file)
  └── Job (runs on a runner)
       └── Step 1 (action o comando)
       └── Step 2
       └── Step 3
```

- **Workflow**: a YAML file in `.github/workflows/`. Defines when and what runs.
- **Job**: a group of steps that run on the same runner (virtual machine). Different jobs can run in parallel.
- **Step**: an individual action (run a command, use an action).

### `actions/...` (Reusable actions)

Actions are reusable blocks of functionality, published by the community or by GitHub:

| Action | Version | Function |
|--------|---------|---------|
| [`actions/checkout@v4`](https://github.com/actions/checkout) | v4 | Download the code from the repo |
| [`actions/setup-java@v4`](https://github.com/actions/setup-java) | v4 | Install a version of Java |
| `gradle/actions/setup-gradle@v4` | v4 | Configure Gradle with cache |
| `actions/upload-artifact@v4` | v4 | Upload files as artifacts |
| `docker/login-action@v3` | v3 | Log in to a Docker registry |
| `docker/setup-buildx-action@v3` | v3 | Set up Docker Buildx |
| [`docker/build-push-action@v6`](https://github.com/docker/build-push-action) | v6 | Build + push of a Docker image |
| `actions/setup-node@v4` | v4 | Install Node.js |

### `secrets` (Secrets)

Secrets are configured in GitHub (Settings > Secrets and variables > Actions) and are referenced as `${{ secrets.NOMBRE }}`.

Secrets used in our project:

| Secret | Used in | Purpose |
|---------|----------|-----------|
| `DOCKERHUB_USERNAME` | CD | Docker Hub user |
| `DOCKERHUB_TOKEN` | CD | Docker Hub Access Token |
| `GITHUB_TOKEN` | Update API Docs | GitHub automatic token to push |

**[`GITHUB_TOKEN`](https://docs.github.com/en/actions/tutorials/authenticate-with-github_token)** is special: GitHub generates it automatically for each workflow run. You don’t need to configure it manually.

### `permissions`

Check what the workflow can do with the permissions of `GITHUB_TOKEN`:

```yaml
permissions:
  contents: read    # CI y CD: solo leer el repo
```

```yaml
permissions:
  contents: write   # Update API Docs: necesita escribir (commit + push)
```

### `$GITHUB_OUTPUT` (communication between steps)

```yaml
# Step 1: escribe
echo "tags=apptolast/whoop-david-api:latest" >> $GITHUB_OUTPUT

# Step 2: lee
tags: ${{ steps.tags.outputs.tags }}
```

`$GITHUB_OUTPUT` is a special file where a step can write key=value pairs. Other steps in the same job can read those values with `${{ steps.<id>.outputs.<clave> }}`.

### `$GITHUB_STEP_SUMMARY`

```yaml
echo "## Titulo" >> $GITHUB_STEP_SUMMARY
echo "| Col1 | Col2 |" >> $GITHUB_STEP_SUMMARY
```

Generate a summary in markdown visible on the workflow page in GitHub. Useful for showing summarized information about the result.

### `if: always()` and `if: steps.X.outputs.Y == 'true'`

```yaml
- name: Upload test reports
  if: always()  # Se ejecuta incluso si steps anteriores fallaron
```

```yaml
- name: Commit and push
  if: steps.changes.outputs.changed == 'true'  # Solo si hubo cambios
```

Steps can be conditioned with expressions. `always()` guarantees execution even if the workflow is failing.

### Docker layer caching with `type=gha`

```yaml
cache-from: type=gha
cache-to: type=gha,mode=max
```

- **`type=gha`**: use the GitHub Actions cache (not Docker Hub or an external registry).
- **`cache-from`**: when building, it looks for cached layers on GitHub.
- **`cache-to`**: after building, upload the new layers to the cache.
- **`mode=max`**: caches ALL intermediate layers (including those from the builder stage in a multi-stage build).

**Result**: if I only change the source code (not the dependencies), Docker reuses the `./gradlew dependencies` layer from the cache, saving several minutes.

### `workflow_run` (chain workflows)

```yaml
on:
  workflow_run:
    workflows: ["CD"]
    types: [completed]
    branches: [main, dev]
```

It’s the way to run a workflow AFTER another one finishes. Unlike putting everything in a single workflow, it allows:

- That each workflow has different permissions (`read` vs `write`).
- Workflows should be independent and reusable.
- Have the third workflow run only if the second (CD) was successful.

**Attention**: `types: [completed]` runs both if it was successful and if it failed. That’s why the job has `if: github.event.workflow_run.conclusion == 'success'`.

---

## 9. Official documentation

- [GitHub Actions - Documentation](https://docs.github.com/en/actions)
- [GitHub Actions - Workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
- [GitHub Actions - Events that trigger workflows](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)
- [GitHub Actions - Use secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
- [GitHub Actions - workflow_run event](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run)
- [GitHub Actions - GITHUB_OUTPUT](https://docs.github.com/en/actions/using-workflows/workflow-commands-for-github-actions#setting-an-output-parameter)
- [docker/build-push-action](https://github.com/docker/build-push-action)
- [docker/login-action](https://github.com/docker/login-action)
- [gradle/actions/setup-gradle](https://github.com/gradle/actions/tree/main/setup-gradle)
- [Keel - Policies](https://keel.sh/docs/#policies)
