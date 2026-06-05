# DECISION RECORD — Plantilla (un registro por PR del agente)

> Cada PR que abre el agente DEBE venir acompañado de un decision record con esta estructura.
> Su propósito: que el humano pueda **aprobar o rechazar el merge con criterio**, no a ciegas
> (explainability — Ahmad cap. 12). Copiar esta plantilla al cuerpo del PR.

---

## PR: {título} — Fase {N.M}

### Qué hice
{Descripción concisa del cambio. Qué archivos, qué comportamiento nuevo.}

### Por qué
{Qué tarea del checklist resuelve. Referencia a spec/design.}

### Qué cubre (tests)
- {Test 1 — escenario de la spec que valida}
- {Test 2}
- Resultado: {tests verdes / link al run}

### Qué NO cubre
{Límites explícitos. Qué queda fuera de scope, qué necesita validación de hardware (Field-Done).}

### Criticidad (del Safety Reviewer)
- Veredicto: {PASS / BLOCK}
- Clasificación: {alarm-path / detection-logic / peripheral}
- Constantes clínicas detectadas: {ninguna / lista → CLINICAL_SIGNOFF.md}

### Riesgo residual
{Qué podría salir mal aun con esto mergeado. Sé honesto. En lenguaje vida/muerte si toca la alarma.}

### Estado tras merge
- [ ] Agent-Done (tests verdes + Safety PASS + este PR aprobado)
- [ ] Field-Done (pendiente — requiere validación humana en hardware)
