# Agent Team Prompts - WhoopDavidAPI

## Agentes Disponibles

| Agente | Archivo | Rol | Model |
|--------|---------|-----|-------|
| Spring Boot Mentor | `spring-boot-mentor.md` | Ensenanza de conceptos Spring Boot, no codifica | sonnet |
| Code Reviewer | `code-reviewer.md` | Revision de codigo con categorizacion de hallazgos | sonnet |
| Backend Architect | `backend-architect.md` | Decisiones arquitectonicas y trade-offs | sonnet |
| Security Specialist | `security-specialist.md` | Seguridad, OAuth2, proteccion de tokens y APIs | sonnet |

---

## Prompt 1: Investigacion de Arquitectura BFF

**Caso de uso**: Antes de implementar una nueva capa o tomar una decision arquitectonica importante.

```
Necesito que investiguen la mejor forma de implementar [TEMA] en WhoopDavidAPI.

@backend-architect: Analiza las opciones arquitectonicas para [TEMA]. Evalua los trade-offs considerando que este es un BFF para un solo usuario (David) con sincronizacion cada 30 min desde Whoop API v2 y consumo desde Power BI. Documenta pros/contras de cada opcion.

@spring-boot-mentor: Explica como funciona [TEMA] en Spring Boot 4.x. Busca en la documentacion oficial si hay cambios respecto a 3.x. Proporciona enlaces directos a las secciones relevantes. NO escribas codigo, explica el concepto.

@security-specialist: Evalua las implicaciones de seguridad de cada opcion para [TEMA]. Considera el modelo de doble autenticacion (OAuth2 Whoop + Basic Auth Power BI) y la persistencia de tokens.

Contexto adicional: [DETALLES ESPECIFICOS]
```

### Ejemplos concretos:
- `[TEMA]` = "la decision WebFlux vs WebMVC"
- `[TEMA]` = "la estrategia de sincronizacion incremental vs full"
- `[TEMA]` = "la persistencia y refresh de OAuth2 tokens"
- `[TEMA]` = "la estructura de entidades JPA para datos de Whoop"

---

## Prompt 2: Revision Completa del Proyecto

**Caso de uso**: Despues de implementar una funcionalidad completa o antes de un deploy.

```
Revisen el estado actual del proyecto WhoopDavidAPI de forma exhaustiva.

@code-reviewer: Revisa TODO el codigo Kotlin del proyecto. Categoriza los hallazgos como CRITICO/IMPORTANTE/MENOR/POSITIVO. Presta especial atencion a: uso correcto de RestClient, manejo de paginacion nextToken de Whoop, compatibilidad del JSON con Power BI (plano, max 2 niveles), y manejo de errores.

@security-specialist: Ejecuta tu checklist de seguridad completo. Verifica: tokens no expuestos en logs, SecurityFilterChain stateless, Basic Auth configurado, secrets en env vars, CORS, CSRF deshabilitado, validacion de inputs. Reporta cada item como PASS/FAIL con detalle.

@backend-architect: Evalua si la arquitectura implementada sigue el patron BFF correctamente. Verifica la separacion entre flujo de sincronizacion y flujo de consumo. Identifica violaciones de los principios arquitectonicos definidos.

Enfoque especial en: [AREA A REVISAR O "todo el proyecto"]
```

---

## Prompt 3: Investigacion Whoop API + Power BI Integration

**Caso de uso**: Cuando se necesita entender como mapear datos de Whoop a lo que Power BI necesita.

```
Investiguen como integrar los datos de [RECURSO WHOOP] para consumo desde Power BI.

@spring-boot-mentor: Explica como funciona el endpoint [RECURSO] de Whoop API v2. Consulta la especificacion OpenAPI en https://api.prod.whoop.com/developer/doc/openapi.json. Describe la estructura de la respuesta, paginacion con nextToken, y campos disponibles. NO escribas codigo.

@backend-architect: Disena el modelo de datos (entidad JPA + DTO) para [RECURSO]. El DTO debe ser JSON plano para Power BI: max 2 niveles de anidacion, timestamps ISO 8601 UTC, esquema fijo (null, no omitir). Explica las decisiones de mapeo.

@code-reviewer: Si ya hay implementacion de [RECURSO], revisala. Verifica que el mapeo Whoop response -> Entity -> DTO es correcto y que no se pierden datos. Verifica que la paginacion Power BI (page/pageSize) funciona correctamente.

Recurso Whoop: [RECURSO = cycles | recovery | sleep | workouts | profile]
```

---

## Prompt 4: Depuracion con Hipotesis Competidoras

**Caso de uso**: Cuando algo no funciona y necesitas investigar la causa raiz.

```
Hay un problema en WhoopDavidAPI: [DESCRIPCION DEL PROBLEMA]

Cada agente debe investigar desde su perspectiva y proponer hipotesis INDEPENDIENTES. NO se copien entre si.

@code-reviewer: Analiza el codigo relacionado con [AREA]. Busca bugs logicos, errores de tipo, manejo incorrecto de null, o problemas de concurrencia. Formula tu hipotesis con la linea de codigo exacta que sospechas.

@security-specialist: Investiga si el problema esta relacionado con autenticacion, autorizacion, o configuracion de Spring Security. Verifica tokens, SecurityFilterChain, CORS, y headers. Formula tu hipotesis.

@backend-architect: Evalua si el problema es arquitectonico: flujo de datos incorrecto, dependencias circulares, configuracion de beans, o problemas de integracion entre capas. Formula tu hipotesis.

@spring-boot-mentor: Busca en la documentacion oficial de Spring Boot 4.x si hay comportamientos conocidos, breaking changes vs 3.x, o configuraciones por defecto que puedan causar [PROBLEMA]. Proporciona enlaces.

Sintomas: [DETALLES, LOGS, ERRORES]
Reproduccion: [PASOS PARA REPRODUCIR]
```

---

## Workflow Rules

### Al inicio de cada sesion de agente
```bash
# Siempre sincronizar antes de trabajar
git pull --rebase origin main
```

### Orden de operaciones recomendado
1. `git pull` para tener la ultima version
2. Leer CLAUDE.md para contexto actualizado
3. Ejecutar la tarea asignada
4. Reportar hallazgos con formato estructurado

### Convenciones de reporte
- Cada agente reporta con su nombre como header: `## [Nombre del Agente]`
- Hallazgos con referencia a archivo y linea: `archivo.kt:42`
- Links a documentacion oficial siempre que sea posible
- Si un agente no encuentra problemas en su area, lo dice explicitamente

---

## Atajos Utiles

| Atajo | Descripcion |
|-------|-------------|
| `Ctrl+J` | Abrir panel de agentes (si soportado) |
| `@agente` | Mencionar agente en prompt |
| `/agents` | Listar agentes disponibles |

---

## Notas

- **MODO APRENDIZAJE**: El Spring Boot Mentor NO debe codificar. Solo explica y ensena.
- **Spring Boot 4.0.2**: Verificar siempre contra docs oficiales. Puede haber breaking changes vs 3.x.
- **Conflicto WebFlux vs WebMVC**: Decision pendiente. Los agentes deben considerarlo en sus analisis.
- **Un solo usuario**: Este sistema es para David. No sobredisenar para multi-tenancy.
