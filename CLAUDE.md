# SeizureGuard — Project Instructions

App de detección de convulsiones nocturnas para Samsung Galaxy Watch 8.
Stack: Kotlin 2.0.21 + Wear OS 4. Módulo único: `:wear`.

ARQUITECTURA (2026-06-05): el reloj es un *data source Android Wear* compatible con
`SdDataSourceAw` que alimenta la app **OpenSeizureDetector V5.0** (rama beta). La inferencia
(modelo deepEpiCnn Run24 + ExecuTorch), el umbral y las alarmas/SMS corren en la app OSD, NO en
este repo. Este repo NO tiene runtime de ML. Ver engram `architecture/seizureguard-executorch-api`.

---

## Reglas de desarrollo

- **Nunca buildear** después de cambios — el usuario testea en Android Studio
- **Nunca usar** cat/grep/find/sed/ls — usar bat/rg/fd/eza
- **Room usa KSP** — nunca kapt (Kotlin 2.0, kapt está deprecated)
- **Al terminar cada fase**: actualizar checklist en README.md y guardar en engram

---

## gstack — Skills disponibles para este proyecto

### /browse — Investigación y documentación
**Usar siempre** para cualquier navegación web. NUNCA usar `mcp__claude-in-chrome__*` directamente.

```
/browse <url>
```

Cuándo usarlo en SeizureGuard:
- Buscar docs de Wear OS SDK, TFLite, Samsung Health SDK
- Explorar issues en GitHub de OpenSeizureDetector
- Verificar compatibilidades entre versiones de dependencias
- Leer changelogs de AGP, Kotlin, Compose

---

### /careful — Protección antes de comandos destructivos
Activa guardrails que advierten antes de ejecutar comandos irreversibles.

```
/careful
```

Cuándo usarlo en SeizureGuard:
- Antes de `adb uninstall com.seizureguard.wear` (borra la app del watch)
- Antes de `git reset --hard` que descarte trabajo no commiteado
- Antes de limpiar archivos de log del watch (`rm raw_accel_*.csv`)
- Antes de reformatear o resetear el Samsung Watch 8

---

### /freeze — Aislar edits a un módulo
Restringe todos los edits a un directorio específico. Evita tocar código que no corresponde a la fase actual.

```
/freeze wear/        ← solo editar el módulo watch (Fases 1 y 2)
/freeze phone/       ← solo editar el módulo phone (Fase 3)
/unfreeze            ← levantar la restricción
```

Cuándo usarlo en SeizureGuard:
- Fases 1.x y 2.x: `/freeze wear/` para no tocar accidentalmente el módulo phone
- Fase 3.x: `/freeze phone/` para no romper el módulo wear ya estable
- Fase 4 (integración): `/unfreeze` porque necesitás editar ambos

---

### /guard — /careful + /freeze combinados
Máxima seguridad cuando estás tocando lógica crítica.

```
/guard wear/src/main/java/com/seizureguard/wear/
```

Cuándo usarlo en SeizureGuard:
- Al editar `SeizureStateMachine.kt` (la lógica OK → WARNING → ALARM)
- Al modificar `TFLiteInferenceEngine.kt` (pipeline de inferencia)
- Al tocar `SeizureMonitorService.kt` (el foreground service nocturno)
- En general: cualquier archivo cuyo bug no vas a ver hasta las 3am

---

### /investigate — Debugging sistemático con root cause
Debugging estructurado en 4 fases: investigar → analizar → hipótesis → fix.
Regla de hierro: nunca fixear sin encontrar la causa raíz primero.

```
/investigate
```

Cuándo usarlo en SeizureGuard:
- El foreground service muere a las 2am y no sabés por qué
- TFLite devuelve probabilidades siempre iguales (modelo no cargó bien)
- El acelerómetro deja de emitir datos después de X minutos
- Falsa alarma inesperada — qué ventana de datos la causó
- ADB se desconecta del watch durante el debug

---

### /plan-eng-review — Revisión técnica del plan de cada fase
Revisión como engineering manager: arquitectura, data flow, edge cases, performance, test coverage.

```
/plan-eng-review
```

Cuándo usarlo en SeizureGuard:
- **Antes de Fase 1**: revisar el diseño del ForegroundService y el ring buffer
- **Antes de Fase 2**: revisar el pipeline TFLite y la máquina de estados
- **Antes de Fase 3**: revisar el protocolo Wear Data Layer y la AlarmActivity
- **Antes de Fase 4**: revisar la estrategia de testing nocturno

Preguntas típicas que genera:
- "¿Qué pasa si TFLite tarda más de 5s en inferir? ¿Hay timeout?"
- "¿El ring buffer es thread-safe?"
- "¿Cómo manejás el caso de watch sacado de la muñeca a las 3am?"

---

### /plan-ceo-review — Revisión del producto (decisiones y supuestos)
Cuestiona supuestos del producto antes de que estén hardcodeados en el código.

```
/plan-ceo-review
```

Cuándo usarlo en SeizureGuard:
- **Antes de Fase 2**: "¿El umbral 0.5 es correcto o arbitrario?"
- **Antes de Fase 3**: "¿SMS es suficiente o el cuidador necesita notificación push?"
- **Después de Fase 4**: "¿8h nocturnas es el caso de uso real o hay escenarios de siesta?"
- Cada vez que estés a punto de hardcodear una constante clínica importante

---

### /retro — Retrospectiva de commits y progreso
Analiza el historial de git: velocidad, patterns, qué frenó, qué fluyó.

```
/retro
```

Cuándo usarlo en SeizureGuard:
- Al terminar cada fase principal (Fase 0, 1, 2, 3, 4)
- Si sentís que el avance se frenó — para identificar dónde y por qué
- Para celebrar hitos: "¿cuánto código escribimos para tener la primera detección?"

Requisito: necesitás al menos algunos commits en el repo para que sea útil.

---

### /review — Revisión de código antes de cerrar una fase
Analiza el diff de lo que escribiste y busca bugs, problemas de arquitectura, y code smells.

```
/review
```

Cuándo usarlo en SeizureGuard:
- Al terminar cada sub-fase antes de marcarla como ✅ en el README
- Específicamente útil después de escribir:
  - `CircularBuffer.kt` (Fase 1.5) — verificar que no haya off-by-one errors
  - `SeizureStateMachine.kt` (Fase 2.4) — verificar la lógica de transiciones
  - `DataLayerListenerService.kt` (Fase 3.1) — verificar el parsing de mensajes

---

### /design-shotgun — Variantes de UI en Compose
Genera múltiples variantes de una pantalla para comparar antes de elegir.
**Adaptado**: en lugar de HTML/CSS, genera múltiples `@Composable` con `@Preview`.

```
/design-shotgun
```

Cuándo usarlo en SeizureGuard:
- Fase 3.2: generar 3 variantes de la AlarmActivity full-screen del phone
- Fase 1.1: generar variantes de la notificación persistente del ForegroundService
- Fase 4.5: variantes del Tile de Wear OS para quick-start/stop

El agente genera las variantes en Compose con `@Preview(device = WearDevices.SMALL_ROUND)`
y vos elegís la que más te gusta antes de implementar la versión final.

---

### /gstack-upgrade — Actualizar gstack
```
/gstack-upgrade
```
Correr cuando haya una versión nueva de gstack disponible.

---

## Skills que NO aplican a este proyecto

| Skill | Por qué no |
|-------|-----------|
| `/qa`, `/qa-only` | Testean apps web vía browser — tu app es Android nativa |
| `/benchmark` | Mide Core Web Vitals — tus métricas son batería/latencia TFLite |
| `/canary` | Monitorea deploys web — no tenés servidor |
| `/ship`, `/land-and-deploy` | Para repos con PRs y pipeline de deploy web |
| `/design-review` | Audita UI web con browser — tu UI es Compose en Wear OS |
| `/design-consultation` | Sistema de diseño para web/branding |
| `/connect-chrome`, `/setup-browser-cookies` | QA de apps web autenticadas |
| `/setup-deploy` | Configura Fly.io, Vercel, Render, etc. |
| `/document-release` | Actualiza docs post-ship en proyectos web |
| `/office-hours` | Para founders de startups |
| `/autoplan` | Corre CEO + design + eng review — overkill para este proyecto |
| `/codex` | Wrapper de OpenAI Codex CLI |
| `/cso` | Audita infraestructura web y CI/CD pipelines |

---

## Stack completo

| Capa | Tecnología | Versión |
|------|-----------|---------|
| Lenguaje | Kotlin | 2.0.21 |
| Build | Gradle + AGP | 8.9 + 8.5.2 |
| Code gen | KSP | 2.0.21-1.0.28 |
| Wear OS SDK | API 30–34 | target: Galaxy Watch 8 |
| UI | Jetpack Compose for Wear OS | BOM 2024.05.00 |
| ML (inferencia) | **Corre en la app OSD, no en este repo** | ExecuTorch + `deepEpiCnn_2026_01_24_Run24.pte`, tensor `(1,1,750)` |
| Sensores | SensorManager (25Hz) | + Samsung SDK opcional |
| Comunicación | Wear Data Layer API (JSON, DEC-046) | — |
| Coroutines | kotlinx-coroutines | 1.8.1 |

> Nota: este repo (módulo único `:wear`) **no tiene runtime de ML**. No hay TFLite ni Room: la
> inferencia, el umbral y las alarmas son de la app OpenSeizureDetector V5.0 en el teléfono.
