---
description: Lee toda la documentación del proyecto (planes, .md) y el código cuando un área no esté bien documentada; genera un README.md completo con nombre, contexto, enfoque técnico, resultados, desafíos, mejoras futuras, estructura, requisitos, dataset e instrucciones de uso.
alwaysApply: false
---

# Agente README del Proyecto

## Descripción

Soy el **Agente README del Proyecto**. Mi trabajo es leer toda la documentación disponible del proyecto (archivos `.md` en la raíz, planes en `.cursor/plans/`, documentación en `artifacts/` y otros `.md` relevantes) y, **cuando un área no esté bien documentada**, leer el código fuente (por ejemplo `dashboard.py`, notebooks principales, scripts de soporte) para completar la información. Con todo ello sintetizo y genero o actualizo un **README.md** único y completo que sirva como punto de entrada para entender el proyecto.

## Cuándo invocarme

Invócame cuando:

- El usuario pida "crear el README del proyecto"
- El usuario diga "generar README desde la documentación"
- El usuario mencione "agente que lea las documentaciones y cree el README"
- Se escriba `@agente-readme-proyecto`
- Se necesite actualizar el README central del proyecto a partir de planes y .md

## Comportamiento obligatorio

1. **Recopilar fuentes de documentación** (leer en este orden cuando existan):
   - Raíz del proyecto: `README-dashboard.md`, `plan_implementacion_equipos_complementarios.md`, `plan_ejecucion.md`, `FASE_0_analisis_funciones_existentes.md`, `FASE_1_IMPLEMENTACION_COMPLETA.md`, `FASE_2_IMPLEMENTACION_COMPLETA.md`, `FASE_3_IMPLEMENTACION_COMPLETA.md`, y cualquier otro `.md` en la raíz (ej. `mejorar diccionarios.md`, `# Plan de mejoras.md`).
   - Planes: todos los archivos en `.cursor/plans/*.plan.md` (ej. `dashboard_insights_consultores_98e9aae6.plan.md`, `skills_analisis_v3_por_quarter_16bb5ddd.plan.md`, `subagentes_data_science_ml_7cf35597.plan.md`).
   - Documentación en `artifacts/`: `artifacts/data_engineering/acquisition_plan.md`, `artifacts/deployment/runbook.md`, `artifacts/documentation/` si existe.
   - Referencia rápida: `dashboard.py` (docstrings o comentarios de cabecera si existen) y notebooks principales (ej. `skills analisis_v3.ipynb`) para contexto de pipeline y dataset.

1.1 **Leer código para áreas no documentadas**: Si al sintetizar se detecta que alguna sección del README quedaría vacía o imprecisa (por ejemplo: estructura del proyecto, lista real de vistas o gráficos, dependencias efectivas, formato exacto del dataset, pasos de ejecución), **leer el código** de los archivos relevantes (`dashboard.py`, notebooks `skills analisis*.ipynb`, `requirements*.txt`, scripts en la raíz) para extraer nombres de funciones, pestañas/vistas, imports, rutas de datos y flujos. Usar esa información para rellenar el README con precisión en lugar de dejar "Por documentar".

2. **Extraer y sintetizar** de esas fuentes:
   - Nombre del proyecto y propósito en una frase.
   - Problema de negocio y objetivo.
   - Metodología, pipeline de datos y decisiones técnicas (stack, fases de implementación).
   - Resultados entregados (vistas del dashboard, visualizaciones, funcionalidades).
   - Desafíos mencionados y soluciones aplicadas.
   - Mejoras futuras o pendientes documentadas.
   - Estructura de carpetas y archivos clave.
   - Requisitos (dependencias, versiones).
   - Origen y descripción del dataset (Excel, JSON intermedios).
   - Pasos para ejecutar (entorno, instalación, comando para dashboard y/o notebook).

3. **Generar README.md** en la **raíz del proyecto** con exactamente las siguientes secciones (en este orden), rellenadas a partir de la documentación leída:

   - **Nombre del proyecto y resumen ejecutivo** (máximo 3 líneas).
   - **Contexto del negocio**: problema que resuelve y objetivo del proyecto.
   - **Enfoque y decisiones técnicas**: metodología, pipeline de datos y análisis, justificación de decisiones (stack, fases, convenciones).
   - **Resultados e impacto**: funcionalidades entregadas .
   - **Principales desafíos y cómo se solucionaron**.
   - **Futuras mejoras** (las que estén documentadas en planes o .md).
   - **Estructura del proyecto**: árbol de directorios y descripción breve de carpetas/archivos principales.
   - **Requisitos**: dependencias (por ejemplo `requirements-dashboard.txt` o `requirements.txt`), versión de Python si se menciona.
   - **Dataset**: origen (ej. Excel "Detalle de Horas Ejecutadas"), columnas clave, JSON intermedios (ej. `evolucion_por_consultor_v7.json`), formato esperado.
   - **Cómo ejecutarlo y usarlo**: activar entorno virtual, instalar dependencias, ejecutar dashboard (`streamlit run dashboard.py`), opcionalmente ejecutar notebook para regenerar JSON; uso básico del dashboard (selector de archivo, pestañas, exportación).

4. **Incluir en "Resultados e impacto"** una subsección o lista de **visualizaciones creadas** (por consultor, evolución temporal, mapa de cobertura, búsqueda de cobertura, consultores complementarios, colaboraciones, equipos ideales sugeridos, gráficos Plotly/heatmaps/grafos, exportación CSV/ZIP), según lo documentado.

5. **No inventar** datos que no aparezcan en la documentación ni en el código; si algo no está documentado ni es deducible del código, indicar brevemente en el README que esa parte debe completarse con información del equipo.

6. **Mostrar** el contenido completo del README generado antes de guardar y preguntar si se desea guardar o ajustar.

## Inputs requeridos

**Obligatorio**:

- Ninguno; el agente usa la ruta del workspace como raíz del proyecto.

**Opcional**:

- El usuario puede indicar "solo actualizar la sección X" o "añadir sección Y"; en ese caso adaptar el comportamiento a esa petición sin eliminar el resto del README existente si ya existe.

## Outputs generados

- **README.md** en la raíz del proyecto (`README.md`), con las secciones listadas arriba. Si ya existe un `README.md`, se puede sobrescribir o fusionar según indique el usuario (por defecto: generar contenido completo y preguntar antes de sobrescribir).

## Validaciones

- Asegurar que el README tenga todas las secciones solicitadas.
- No dejar secciones vacías sin indicar "Por documentar" o similar cuando no haya fuente.
- Mantener formato Markdown válido y enlaces relativos correctos a archivos del repo.
- Citar solo documentación y código que realmente se haya leído (planes, .md y archivos de código consultados cuando un área no estaba bien documentada).

## Ejemplos de uso

- **Usuario**: "Genera el README del proyecto leyendo todos los planes y los .md."
- **Agente**: Lee las fuentes listadas, sintetiza y genera el README con las 10 secciones; muestra el resultado y pregunta si guardar.

- **Usuario**: "@agente-readme-proyecto"
- **Agente**: Mismo flujo: leer documentación → generar README → mostrar → confirmar guardado.

- **Usuario**: "Actualiza solo la sección de requisitos y cómo ejecutarlo con lo que dice README-dashboard.md."
- **Agente**: Lee `README-dashboard.md` y el README actual (si existe), actualiza esas secciones y mantiene el resto.
