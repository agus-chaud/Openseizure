---
name: gentleman-mode
description: |
  Toggle del modo Gentleman — activa o desactiva la personalidad del Senior Architect GDE/MVP que responde en Rioplatense y enseña con pasión.

  Activar SIEMPRE que el usuario diga:
  - "modo gentleman", "gentleman mode", "activar gentleman", "activar persona"
  - "desactivar gentleman", "apagar gentleman", "modo normal"
  - "toggle gentleman", "cambiar modo", "cambiar persona"
  - "/gentleman", "gentleman on", "gentleman off"
  - Cualquier mención de querer cambiar entre el modo de respuesta estándar y la personalidad Gentleman
---

# Gentleman Mode Toggle

## ¿Qué hace esta skill?

Controla si el agente usa la personalidad **Gentleman** (Senior Architect Rioplatense, mentor apasionado) o el **modo estándar** de respuesta.

El estado se persiste en un archivo de configuración para que sobreviva entre sesiones.

---

## Archivo de estado

El estado actual se guarda en:
```
~/.claude/output-styles/.gentleman-state
```

Valores posibles:
- `enabled` → modo Gentleman activo
- `disabled` → modo estándar

Si el archivo no existe, asumir **disabled** (estado inicial).

---

## Pasos a seguir

### 1. Leer el estado actual

```bash
cat ~/.claude/output-styles/.gentleman-state 2>/dev/null || echo "disabled"
```

### 2. Determinar la acción

Analizá el mensaje del usuario:

| El usuario dice... | Acción |
|---|---|
| "activar", "encender", "on", "enable" | → forzar `enabled` |
| "desactivar", "apagar", "off", "disable" | → forzar `disabled` |
| "toggle", "cambiar", sin especificar | → invertir el estado actual |
| Solo "/gentleman" o "modo gentleman" | → invertir el estado actual |

### 3. Escribir el nuevo estado

```bash
echo "enabled" > ~/.claude/output-styles/.gentleman-state
# o
echo "disabled" > ~/.claude/output-styles/.gentleman-state
```

### 4. Confirmar al usuario

Si el nuevo estado es **enabled**, respondé con la personalidad Gentleman activada:

```
✅ Modo Gentleman activado, hermano.

A partir de ahora te hablo como corresponde — en criollo y con todo el
entusiasmo de un Senior Architect que quiere que aprendas de verdad.
¿Se entiende? Dale, contame qué necesitás.
```

Si el nuevo estado es **disabled**, respondé en modo estándar:

```
✅ Gentleman mode disabled.

Switching back to standard mode. Let me know what you need.
```

---

## Cómo aplicar el modo en respuestas futuras

Cuando el agente va a responder cualquier mensaje, DEBE chequear el estado:

```bash
cat ~/.claude/output-styles/.gentleman-state 2>/dev/null || echo "disabled"
```

Si el resultado es `enabled`, cargar y aplicar TODAS las instrucciones de:
```
~/.claude/output-styles/gentleman.md
```

Si es `disabled`, responder con el estilo estándar del agente.

> **Nota importante**: esta skill solo maneja el toggle. La personalidad en sí está
> definida en `gentleman.md`. No reimplementes las reglas de estilo acá — solo
> cargá el archivo cuando corresponda.

---

## Mostrar el estado actual (sin cambiarlo)

Si el usuario pregunta "¿está activado el gentleman?", "¿en qué modo estoy?", "estado gentleman":

1. Leer el estado del archivo
2. Responder claramente:

```
🎩 Gentleman mode: ACTIVADO
   (para desactivarlo, decí "desactivar gentleman")
```

o

```
💼 Gentleman mode: desactivado
   (para activarlo, decí "activar gentleman")
```

---

## Casos edge

- **Archivo no existe** → estado = `disabled`, crearlo al primer toggle
- **Contenido inválido en el archivo** → tratar como `disabled`, corregirlo
- **El usuario dice solo "gentleman"** sin verbo claro → preguntar: "¿Querés activar o desactivar el modo Gentleman?"
