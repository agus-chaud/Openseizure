# Cómo se hablan el reloj y el teléfono 

> Este documento asume que no sabés nada de Android. Si ya programaste algo (Python, backend,
> lo que sea), vas a reconocer los patrones , solo cambia el vocabulario.
>
> Compañero de este doc: `EXPLAINER_MODELO_ML.md` (qué hace OSD con los datos que le mandamos).
>
> **Marcadores que vas a ver:** `🔍 Detalle opcional` = no bloquea entender el resto
 `❓ Antes de seguir` = una pregunta corta para chequear que quedó
---

## El protagonista de este documento: un número

Son las 3:14am. Tu brazo se mueve un poco entre sueños. 40 milisegundos más tarde, ese movimiento
se convirtió en un número: **`1150`** (milli-g de magnitud). Ese número va a atravesar TODO este
documento — sensor → buffer → JSON → Bluetooth → teléfono → alarma. Cada vez que aparezca así,
**`1150`**, es el mismo número, un paso más adelante en su viaje. Al final del doc vas a poder
explicar los 6 pasos que dio sin mirar atrás.

---

## El diagrama maestro (volvé acá cada vez que te pierdas)

Este es EL mapa completo del viaje de `1150`. Cada sección de este doc explcia un tramo distinto.

```
  ①SENSOR    ②BUFFER    ③TRANSPORTE       ④DATA LAYER      ⑤TELÉFONO      ⑥VUELTA
 acelerómetro ring buffer  JSON {"samples"} MessageClient    OSD recibe    alarm_state
   25Hz       125 (reloj)  chunk de 125     path /osd/...    y junta 750   al reloj
    │             │              │                │               │            │
    └──1150───────┴──────────────┴────────────────┴───────────────┴────────────┘
                              (Parte 1)        (Parte 2)      (Parte 3)    (Parte 3.2)
```
## Parte 1 — Los bloques de Android

### 1.1 — ❓ Antes de arrancar: ¿por qué "correr una app" en Android no es como correr un script?

En Python, `python main.py` arranca de arriba hacia abajo y vos tenés el control. En Android, el
**sistema operativo** decide cuándo activa cada pieza de tu app  y peor: **puede matar tu proceso
sin avisar** si cree que está consumiendo batería en background sin que nadie lo vea. Para un
reloj que tiene que seguir despierto toda la noche vigilando convulsiones, esto es un problema real. 

### 1.2 — Foreground Service: el "proceso que Android promete no matar"

**Analogía:** pensá en un guardia de seguridad nocturno. Si el jefe pasa y no lo ve haciendo nada
visible, puede mandarlo a su casa antes de que termine el turno, pensando que no hace falta. Para
evitar eso, el guardia cuelga un cartel bien visible: "ESTOY DE GUARDIA". Mientras el cartel esté
puesto, nadie lo manda a casa. Un **Foreground Service** es ese cartel, le mostrás al usuario una
notificación persistente ("SeizureGuard activo") a cambio de que Android prometa no apagarte
arbitrariamente.

```
Service normal (background)          Foreground Service
───────────────────────────          ──────────────────
Sistema puede matarlo cuando         Sistema NO lo mata (salvo
quiera, sin avisar                   memoria crítica extrema)
No hay notificación                  Notificación obligatoria:
                                      "SeizureGuard activo"
```

En este repo: `SeizureMonitorService.kt`. Esa notificación NO es una decisión de UX, es
el precio que Android cobra por dejarte correr toda la noche sin que te maten el proceso.

### 1.3 — 🔍 Detalle opcional: WakeLock (mantener el CPU despierto con la pantalla apagada)

Si la pantalla se apaga, el CPU entra en modo ahorro (`Doze mode`). Un **WakeLock** es pedirle
explícitamente al sistema "no dejes que esto se duerma", como cuando alguien tiene que vigilar
toda la noche y le pide a un compañero "no me dejes dormirme, aunque cierre los ojos un rato,
despertame". El sistema tiene permiso para "cerrar los ojos" (apagar la pantalla) pero no para
"dormirse del todo" (apagar el CPU que sigue leyendo el sensor).

```kotlin
val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SeizureGuard::Monitoring")
wakeLock.acquire(10 * 60 * 60 * 1000L /* 10 horas, timeout de seguridad */)
```

Tiene timeout — por eso el watchdog (Parte 4) lo renueva cada 30s, para que no se apague solo a
mitad de una noche larga de monitoreo.

### 1.4 — SensorManager: acá nace nuestro protagonista

**Analogía:** es como el timbre de tu casa. No te quedás mirando la puerta todo el día por si viene
alguien, vivís tu vida normal, y cuando alguien toca el timbre, ESO te avisa. `registerListener` es
"conectar el timbre"; cada vez que el sensor detecta movimiento, te suena el timbre (`onSensorChanged`)
con el dato nuevo. Vos no preguntás "¿hay algo nuevo?" a cada rato — te avisan solos.

```kotlin
sensorManager.registerListener(listener, accelerometer, 40_000 /* microsegundos = 40ms = 25Hz */)
```

Cada lectura trae `(x, y, z)`. Este proyecto los reduce a **una sola magnitud** con Pitágoras 3D:

```python
import numpy as np
magnitude = np.sqrt(x**2 + y**2 + z**2)   # esto es exactamente cómo nace nuestro 1150
```

**¿Por qué tirar 2 de los 3 números? La razón real es la orientación del reloj.**

Pensalo así: `x`, `y`, `z` miden aceleración en tres direcciones fijas RESPECTO AL RELOJ (adelante,
costado, arriba). Pero un reloj en la muñeca puede quedar girado de mil formas distintas — con la
pantalla mirando para arriba, para el costado, con la muñeca doblada, etc. **El mismo movimiento del
brazo va a dar valores de `(x, y, z)` completamente distintos según cómo esté girado el reloj en ese
momento** — y el reloj puede rotar solo, sin que la persona haga nada especial, con cada giro de
muñeca al dormir.

La magnitud `√(x²+y²+z²)` resuelve esto: **no importa cómo esté rotado el reloj, la magnitud del
movimiento da el mismo número.** Es una propiedad matemática (el largo de un vector no cambia
aunque rotes el sistema de ejes) — a esto se le llama que la magnitud es **invariante a la
rotación**. Para un modelo que tiene que reconocer "hubo temblor rítmico" sin importar en qué
posición durmió la persona esa noche, esto es mucho más confiable que confiar en `x`, `y`, `z` por
separado.

En reposo, con el reloj quieto, la magnitud da **~1000 milli-g** (1g = gravedad terrestre) —
**sin importar la orientación**, que es justo la prueba de que funciona: probá inclinar el reloj en
distintos ángulos estando quieto, y vas a seguir viendo ~1000. Nuestro `1150` es un poco más alto —
el brazo se movió. Sanity check de campo: si ves ~1000 en reposo (en cualquier posición), el sensor
anda bien.

### 1.5 — 🔍 Detalle opcional: ring buffer

**Contrafactual — ¿qué pasaría sin esto?** Un `MutableList` que crece infinito, agregando una
muestra cada 40ms sin límite, revienta la memoria de un reloj con 300 mAh en cuestión de horas —
nadie reinicia el reloj a mitad de la noche para "vaciar la lista". Un **ring buffer** resuelve esto
con tamaño fijo: al llenarse, lo nuevo pisa a lo viejo, así que la memoria usada nunca crece más
allá de un límite conocido de antemano.

```python
from collections import deque
buffer = deque(maxlen=125)   # ver corrección de tamaño real más abajo
buffer.append(nueva_magnitud)   # si está lleno, la más vieja se descarta sola
```

**Corrección importante (verificada contra el código real, no contra una versión anterior de este
doc):** el `CircularBuffer.kt` del reloj tiene capacidad **125**, no 750 — coincide exactamente con
`TRANSPORT_CHUNK_SIZE` (`SeizureMonitorService.kt:908-909`: `BUFFER_CAPACITY = TRANSPORT_CHUNK_SIZE
= 125`). El buffer de 750 que arma la ventana completa para el modelo vive del OTRO lado (dentro de
`SdAlgMl.java`, en el teléfono) — el reloj solo necesita juntar 125 muestras (~5s) para disparar un
envío, no las 750 completas. Lo vas a ver en la Parte 3.1.

**Esto no es solo teoría — hay tests reales que lo verifican** (`CircularBufferTest.kt`):
- `buffer_startsEmpty` (línea 55) y `buffer_afterAddingExactCapacity_isFull` (línea 88): el buffer
  arranca vacío y se marca lleno en exactamente 125 muestras, ni una más ni una menos.
- `buffer_afterAddingLessThanCapacity_isNotFull` (línea 72): prueba el caso borde de 124 muestras —
  el más peligroso, porque si fallara acá el modelo recibiría ventanas incompletas en producción.
- `buffer_snapshot_returnsElementsInChronologicalOrder` (línea 113): confirma que el orden se
  preserva — si esto fallara, sería como leer una señal de electrocardiograma al revés, un patrón
  que el modelo nunca vio en su entrenamiento.

**❓ Antes de seguir:** ¿por qué un `FULL_WAKE_LOCK` (que mantiene la pantalla prendida) sería mala
idea en un reloj que monitorea toda la noche? TE CONSUME TODA LA BATERIA Y LA PANTALLA TE GENERA UNA LUZ MOLESTA PARA DORMIR

---

## Parte 2 — Wear Data Layer API: cómo dos dispositivos Android se hablan

### 2.0 — ❓ Antes que nada: si es Bluetooth/WiFi, ¿por qué hablamos de una "API"?

Es una duda súper razonable, y la respuesta corta es: **Bluetooth/WiFi y la Wear Data Layer API no
son alternativas — son dos capas distintas, una arriba de la otra.**

Primero, qué es una API en general (esto te va a servir para SIEMPRE, no solo para este proyecto):
**API** = "Application Programming Interface" = un conjunto de funciones/reglas que alguien más
programó, para que vos las uses SIN tener que saber cómo funcionan por dentro. Cuando usás
`pandas.read_csv()`, no sabés (ni te importa) cómo pandas abre el archivo byte por byte — la API te
da una función lista, vos la llamás, y confiás en que hace lo que promete.

Ahora, las capas de este caso concreto:

```
CAPA 4 (lo que vos programás):     WearDataLayerManager.sendAccelData(samples)
CAPA 3 (la API que usás):          Wear Data Layer API (MessageClient, NodeClient...)
CAPA 2 (lo que la API maneja       Google Play Services: empareja los dispositivos,
        por vos, sin que la veas):  reconecta si se corta, reintenta, etc.
CAPA 1 (el cable invisible real):  Bluetooth y/o WiFi — la señal de radio física
                                    que efectivamente viaja entre el reloj y el teléfono
```

Vos escribís código contra la **Capa 3** (`messageClient.sendMessage(...)`). Nunca tocás Bluetooth
directamente — ni abrís un socket, ni manejás el emparejamiento, ni te preocupás si en un momento
dado la conexión usa Bluetooth o WiFi (el sistema elige por vos, y hasta puede cambiar de uno a
otro sin que tu código se entere). Eso es EXACTAMENTE lo que hace útil una API: te da un contrato
simple ("mandá esto a este path") y esconde toda la complejidad de radio, reconexión y bajo nivel
detrás.

**Por qué esto importa para lo que estás aprendiendo:** cuando alguien te diga "esta app usa la API
de Stripe" o "este servicio expone una API REST", es la misma idea — una capa de funciones
prometidas, arriba de algo más complejo que no necesitás tocar.

### 2.1 — ❓ El problema: `1150` ya es un número. ¿Cómo cruza de un dispositivo físico a otro?

El reloj y el teléfono son **dos dispositivos físicos distintos**. No comparten memoria — no hay
forma de que uno "llame a una función" del otro directamente, como sí harías entre dos funciones
dentro del mismo programa. Necesitan mandarse mensajes a través del canal de radio (Capa 1) usando
la API (Capa 3) como intermediaria.

**Analogía directa:** pensalo como **el correo postal entre dos casas**. Vos no salís a caminar la
ciudad entregando cartas a mano (eso sería manejar Bluetooth vos mismo) — hay un servicio de correo
(la API, respaldada por Google Play Services) que ya sabe cómo llegar de una casa a la otra. Vos
solo escribís la carta, le ponés una dirección (el **path**, ej. `/osd/accel_data`) y la mandás. El
correo se encarga de la ruta, el transporte físico y de qué hacer si hay que reintentar.

### 2.2 — Los tres actores

| Concepto Android | Analogía |
|---|---|
| `Node` | Una de las dos casas (el reloj es una casa, el teléfono es la otra) |
| `MessageClient` | El cartero: entrega cartas a una dirección (`path`) específica |
| `NodeClient` | La lista de casas que están "habitadas ahora" — "¿hay alguien para recibir esta carta?" |

```kotlin
// WearDataLayerManager.kt :
val nodes = Wearable.getNodeClient(context).connectedNodes.await()
if (nodes.isEmpty()) return false   // nadie escuchando → no hay teléfono

nodes.forEach { node -> messageClient.sendMessage(node.id, path, data).await() }
```
### 2.3 — 🔍 Detalle opcional: por qué el listener nunca crashea

```kotlin
val listener = MessageClient.OnMessageReceivedListener { event ->
    if (event.path == PATH_ALARM_STATE) { /* parsear event.data */ }
}
```

Es como una persona que recibe cartas todos los días: si un día le llega un sobre roto e ilegible,
lo tira a la basura y sigue esperando la carta de mañana — no se muda de casa ni deja de recibir
correo para siempre por una carta rota. Por eso este código loguea y sigue en vez de lanzar
excepción.

**Contrafactual — ¿qué pasaría si esto lanzara una excepción sin capturarla?** Un mensaje corrupto
tumbaría el `listener` entero. El reloj dejaría de recibir `/osd/alarm_state` PARA SIEMPRE hasta que
alguien reinicie manualmente el servicio — silencioso, sin ningún aviso. En software de seguridad
de vida, "dejar de escuchar del todo" por un solo mensaje malformado es mucho peor que "perder ese
mensaje puntual y seguir funcionando".

**Verificado con un test real** (`WearDataLayerManagerTest.kt:107`,
`` `parseAlarmState returns null on malformed payload without crashing` ``): le pasa un payload
basura a propósito y confirma que la función devuelve `null` en vez de lanzar una excepción — es
justo el comportamiento del contrafactual de arriba, probado en código, no solo prometido en un
comentario.

**❓ Antes de seguir:** si mandás un mensaje y no hay ningún `Node` conectado (`nodes.isEmpty()`),
¿qué pasa con `1150`? *(pista: mirá el `return false` de arriba — nadie lo recibe, y quien llamó se
entera del fallo)*  --> SE REGISTRA Q NO LLEGÓ, PERO SIEMPRE SE SIGUE ESCUCHANDO

---

## Parte 3 — El contrato real: `1150` sale del reloj (DEC-046)

Cuatro paths, cada uno con dirección y formato fijo. Fuente de verdad: `DECISIONS.md` → DEC-046
(reemplaza intentos previos que mandaban floats binarios y no funcionaban — el parser de OSD
esperaba JSON, no bytes crudos).

```
RELOJ (:wear)                                          TELÉFONO (app OSD V5.0)
──────────────                                         ───────────────────────
                    ← /osd/send_settings "start" ←      "Necesito tus settings"
    /osd/settings
    {"battery":85,"sample_freq":25}          →

    /osd/accel_data
    {"samples":[..., 1150, ...]}             →          Acumula hasta 750 → infiere

                    ← /osd/alarm_state ←                {"alarm_state":0,"alarm_phrase":"OK"}
```

### 3.1 — `/osd/accel_data`: acá viaja nuestro `1150`

```json
{"samples": [998.2, 1150.0, 995.8, ...]}
```

125 valores por mensaje (`TRANSPORT_CHUNK_SIZE`) = 5 segundos a 25Hz. El teléfono acumula 6
mensajes de 125 = 750 (30 segundos) antes de correr el modelo.

**Código que lo empaqueta** (`WearDataLayerManager.kt`):

```kotlin
fun samplesToJsonBytes(samples: FloatArray): ByteArray {
    val arr = JSONArray()
    for (s in samples) arr.put(s.toDouble())
    return JSONObject().put("samples", arr).toString().toByteArray(Charsets.UTF_8)
}
```

**Código que lo recibe** (`SdDataSourceAw.java`, repo OSD):

```java
if (json.has("samples")) {
    org.json.JSONArray samples = json.getJSONArray("samples");
    for (int i = 0; i < samples.length(); i++) {
        if (nRawData < MAX_RAW_DATA) { rawData[nRawData] = samples.getDouble(i); nRawData++; }
        else { processAccelData(); nRawData = 0; }   // dispara el análisis ML
    }
}
```

`json.has("samples")` es lo que hace que OSD reconozca el formato. Mandar `{"data":[...]}` en vez de `{"samples":[...]}` no tira error: OSD silenciosamente cae al parser binario (que espera otra cosa) y `1150` se pierde sin que nadie se entere.

**Contrafactual — esto no es hipotético, ya pasó en este proyecto:**

| Enfoque | Qué pasaba |
|---|---|
| DEC-034/039 (viejo): floats binarios little-endian | OSD los interpretaba con su parser de respaldo (`int16`, no `float32`) → basura numérica silenciosa, sin ningún error visible |
| DEC-046 (actual): JSON `{"samples":[...]}` | `json.has("samples")` matchea, OSD lo reconoce, los datos llegan bien |

**Verificado con tests reales** (`WearDataLayerManagerTest.kt`):
- `samplesToJson produces samples array with correct count` (línea 36) y `samplesToJson preserves values in order` (línea 49): confirman formato y orden.
- `samplesToJson sequential validation pattern matches Graham protocol` (línea 61): este es el test que respalda el modo debug de la Parte 3.4 — verifica que los números secuenciales `[1,2,3,...]` se serializan tal cual, para poder detectar si algo los desordena en el camino.
- `accelDataPath matches OSD protocol` (línea 143): confirma que el path literal es `/osd/accel_data`, carácter por carácter — un typo acá rompería todo en silencio, igual que la clave `"samples"` mal escrita.

### 3.2 — La vuelta: `/osd/alarm_state`

```json
{"alarm_state": 0, "alarm_phrase": "OK"}
```

`0`=OK, `1`=WARNING, `2+`=ALARM. Si el payload no es el JSON esperado, `parseAlarmState()` devuelve
`null` — se loguea y se ignora, no crashea (mismo principio que 2.3).

### 3.3 — 🔍 Detalle opcional: el handshake (`/osd/settings` + `/osd/send_settings`)

si el teléfono pide settings antes de que el reloj esté escuchando, o viceversa, alguien se pierde el mensaje.

```
1. OSD manda "/osd/send_settings" con "start"  → "decime tus settings"
2. El reloj responde "/osd/settings" {"battery":85,"sample_freq":25}
3. El reloj TAMBIÉN lo manda proactivamente al arrancar, por si el "start"
   de OSD llegó antes de que el listener del reloj estuviera registrado
```

**Contrafactual — sin este intercambio:** OSD reporta **"Data source fault"** indefinidamente, sin
saber si el reloj está vivo. Es como llamar por teléfono y decir "¿hola? ¿me escuchás?" antes de
empezar a hablar en serio: si nadie confirma que está del otro lado, seguís hablando solo sin
saberlo — y en este caso, sin el handshake, el reloj podría estar mandando `accel_data`
perfectamente bien, pero OSD nunca lo tomaría en serio porque nunca confirmó tener settings.

**Verificado con tests reales** (`WearDataLayerManagerTest.kt`):
`settingsToJson produces the format OSD handleSettings expects` (línea 118) y `sendSettingsPath
matches OSD protocol` (línea 126) — confirman, respectivamente, que el JSON de respuesta tiene
exactamente las claves que `SdDataSourceAw.handleSettings()` espera, y que el path de escucha
coincide carácter por carácter con lo que OSD manda al arrancar.

### 3.4 — 🔍 Detalle opcional: lo que encontré hoy y no estaba documentado (keep-alive)

Comparando contra el código real de OSD (commit del 13/08/2026), encontré que ahora manda
`/osd/alarm_state` cada 20s proactivamente, aunque no haya `accel_data` procesándose — evita que el
reloj muestre "NO PHONE" en ratos sin inferencia. Mejora del lado OSD, posterior a la última
auditoría de este repo.

**❓ Antes de seguir:** si mandaras `{"samples": [1150]}` pero con la clave escrita `"Samples"`
(mayúscula), ¿qué pasaría del lado OSD? *(pista: `json.has("samples")` es case-sensitive — mismo
mecanismo silencioso de la Parte 3.1)*

---

## Parte 4 — El watchdog: qué pasa si `1150` deja de llegar (DEC-048)

### 4.1 — El problema

El reloj puede *creer* que monitorea cuando en realidad nada llega al teléfono: sensor muerto,
Bluetooth caído, teléfono apagado. Sin nada que lo detecte, la UI sigue mostrando "todo bien" , la
peor mentira posible en software de seguridad de vida.

### 4.2 — La solución: heartbeat cada 30s

**Analogía:** un padre que cada 30 minutos se asoma al cuarto del bebé para chequear que sigue
respirando y moviéndose. No se queda mirando fijo toda la noche . Si en el último chequeo no vio señales de vida durante demasiado tiempo, se preocupa y actúa.

```kotlin
// Función PURA — testeable sin Android ni tiempo real:
fun evaluateHealth(now: Long, lastSample: Long, lastDelivery: Long, started: Long): Health {
    if (now - lastSample > SAMPLE_STALE_MS)     return Health.DEGRADED   // sensor mudo >10s
    if (now - lastDelivery > DELIVERY_STALE_MS) return Health.DEGRADED   // sin entregas OK >60s
    return Health.OK
}
```

Si `1150` (o cualquier muestra) deja de generarse por >10s, o deja de **entregarse con éxito** al
teléfono por >60s → estado **DEGRADADO**: notificación "⚠ MONITOREO DEGRADADO", vibración distinta.

**Contrafactual — sin watchdog:** el reloj sigue mostrando "SeizureGuard activo" con la misma cara
de siempre, aunque el sensor esté muerto o el Bluetooth cortado. La UI miente por omisión — no dice
"todo mal" explícitamente, simplemente deja de decir nada nuevo, y eso se ve idéntico a "todo bien"
para quien lo mira de reojo a las 3am.

**Verificado con tests reales** (`SeizureMonitorServiceTest.kt`, sección de `evaluateHealth`, línea
767 en adelante): son ~7 tests que le dan a la función pura distintos combos de
`(now, lastSample, lastDelivery)` — sensor fresco pero entrega vieja, entrega fresca pero sensor
viejo, ambos frescos, ambos viejos, justo en el borde del umbral — y verifican que devuelve
`DEGRADED` u `OK` según corresponda. Como es una función pura (sin `delay()` real), estos tests
corren en milisegundos y cubren los casos borde exactos sin depender del reloj del sistema.

**Por qué "función pura" no es un tecnicismo:** un test que dependa de `delay()` real es lento . Separando la lógica (pura, testeable con inputs fijos) del loop (`delay(30_000)`, que sí
depende del tiempo real y no se testea), los tests corren en milisegundos y son determinísticos.

### 4.3 — 🔍 Detalle opcional: la cadena completa sensor → watchdog

```kotlin
private suspend fun sendToAllNodes(path: String, data: ByteArray): Boolean {
    // ...
    return anyDelivered   // true SOLO si se entregó a al menos un nodo
}
```

`lastDeliveryOkAtMs` (que usa el watchdog) solo avanza cuando esto devuelve `true`. Si el teléfono
se desconecta, las entregas fallan, `lastDeliveryOkAtMs` se congela, y a los 60s el watchdog lo
detecta. Es la cadena completa: `1150` nace → se transporta → si deja de llegar, se nota.

---

## El viaje completo de `1150`, de una — resumen final

```
① Nace en el acelerómetro (25Hz) como magnitud √(x²+y²+z²) = 1150 milli-g
② Entra al ring buffer del reloj (junto a otras 124 muestras de ese chunk)
③ Se serializa a JSON: {"samples":[..., 1150, ...]}
④ Viaja por MessageClient al path /osd/accel_data (Wear Data Layer API)
⑤ OSD lo recibe, lo guarda en rawData[], y cuando junta 750 corre el modelo
⑥ El resultado (alarma sí/no) vuelve al reloj por /osd/alarm_state
   Si en cualquier punto de ①→④ algo se corta, el watchdog lo nota en ≤60s
```

---

## Ejercicio de cierre

**Sin volver a leer el documento:** explicá por qué el reloj manda los datos en bloques de 125 y no
manda directamente los 750 que necesita el modelo de una sola vez. Dale dos razones distintas —
una tiene que ver con el tiempo (¿cuánto tardarías en juntar 750 muestras a 25Hz?), la otra con qué
pasaría si el mensaje de 750 se perdiera en el camino.

*(Si te trabaste, la respuesta está en la Parte 1.4 + Parte 3.1 — pero intentalo primero de
memoria, ahí es donde queda fijado.)*

---

## Glosario rápido

| Término | Qué es |
|---|---|
| API | Conjunto de funciones/reglas listas para usar, que esconden la complejidad de lo que hacen por dentro |
| Foreground Service | Proceso en background con notificación obligatoria; Android no lo mata |
| WakeLock | Permiso explícito para que el CPU no se duerma con la pantalla apagada |
| SensorManager | API pub/sub para leer sensores de hardware |
| milli-g | Unidad de aceleración; 1000 milli-g = 1g = gravedad terrestre en reposo |
| Ring buffer | Buffer de tamaño fijo; al llenarse, lo nuevo pisa a lo viejo |
| Node | Un dispositivo Android pareado en la Wear Data Layer API |
| MessageClient | Cliente pub/sub para mandar/escuchar mensajes por `path` entre Nodes |
| Path | String tipo `/osd/accel_data` — el "topic" del mensaje |
| Handshake | Intercambio inicial para confirmar que ambos lados están escuchando |
| Watchdog | Chequea periódicamente "señales de vida" y alerta si faltan |
| Keep-alive | Mensaje proactivo para mantener viva la percepción de conexión |

---

## Respuestas a las preguntas del documento

**Parte 1.5 — ¿por qué `FULL_WAKE_LOCK` sería mala idea en un reloj toda la noche?**
Un `FULL_WAKE_LOCK` mantiene la PANTALLA prendida además del CPU. En un reloj sobre la muñeca de
alguien durmiendo, eso significa luz encendida toda la noche (molesta para dormir) y batería
gastándose mucho más rápido para nada — no necesitás ver la pantalla para seguir midiendo el
acelerómetro. `PARTIAL_WAKE_LOCK` deja el CPU despierto pero la pantalla se apaga normal.

**Parte 2.3 — si no hay ningún `Node` conectado, ¿qué pasa con `1150`?**
`sendToAllNodes` devuelve `false` (mirá el `if (nodes.isEmpty()) return false` del código) — el
mensaje ni siquiera se intenta mandar, porque no hay "casa" del otro lado para entregarlo. `1150`
se pierde, PERO quien llamó a esta función se entera (recibe `false`, no una excepción silenciosa)
y eso es justo lo que alimenta al watchdog de la Parte 4 para detectar la desconexión.

**Parte 3.4 — ¿qué pasaría si mandaras `"Samples"` con mayúscula en vez de `"samples"`?**
`json.has("samples")` es case-sensitive — comparación de texto exacta, no ignora mayúsculas. Con
`"Samples"`, esa condición da `false`, OSD no reconoce el formato JSON esperado, y cae al parser
binario de respaldo (que interpreta los bytes como enteros de 16 bits, no como texto JSON) —
resultado: basura numérica sin ningún error visible. Mismo mecanismo silencioso que romper el
formato del payload.

**Ejercicio de cierre — ¿por qué bloques de 125 y no 750 de una sola vez?**
Primera razón (tiempo): a 25Hz, juntar 750 muestras tarda 30 segundos reales. Si el reloj esperara
los 750 antes de mandar el primer mensaje, OSD estaría 30 segundos sin recibir NADA al arrancar el
monitoreo — el handshake y la primera detección de "¿está vivo el reloj?" tardarían muchísimo más.
Mandando de a 125 (5 segundos), OSD empieza a ver actividad casi de inmediato.
Segunda razón (robustez): si un solo mensaje de 750 se perdiera en el camino (Bluetooth con un bache
momentáneo), se perderían 30 segundos completos de datos de una sola vez. Perdiendo un mensaje de
125, se pierden solo 5 segundos — mucho más fácil de tolerar, y el watchdog lo detecta rápido si se
repite.

---

## Puente al hardware real: qué vas a estar viendo en cada paso del runbook

Todo lo de arriba es teoría hasta que agarrás el reloj de verdad. `HARDWARE_RUNBOOK.md` (Fase D)
tiene 5 pasos manuales — acá te digo, para cada uno, EXACTAMENTE qué concepto de este documento
estás poniendo a prueba, para que no sean dos mundos separados.

**D.1 — Instalar OSD + activar la fuente Android Wear.**
Lo que estás activando es el `SdDataSourceAw` del lado teléfono (Parte 2 y 3) y el handshake de la
Parte 3.3. Si después de este paso ves "Data source fault" en OSD, es exactamente el escenario que
describe esa sección — el handshake `/osd/send_settings` ↔ `/osd/settings` no se completó. Revisá
logcat del reloj buscando si mandó `/osd/settings` al arrancar.

**D.2 — Validación secuencial `[1.0, 2.0, 3.0, ...]`.**
Esto activa el modo debug de la Parte 3.4/DEC-047 (`EXTRA_VALIDATION_MODE`) — el reloj manda
números secuenciales en vez del acelerómetro real, específicamente para poder verificar que el
ORDEN de las muestras se preserva de punta a punta (que no se mezclan ni se pierden en el camino
por Bluetooth). Si en OSD ves los números fuera de orden o con saltos, el problema está en el
transporte de la Parte 3.1 (bloques de 125), no en el sensor.

**D.3 — Reloj quieto → un eje da ~1000 milli-g.**
Este es el sanity check exacto de la Parte 1.4: la magnitud `√(x²+y²+z²)` en reposo tiene que dar
~1000 sin importar en qué ángulo tengas el reloj (por la invariancia a la rotación que explicamos
ahí). Si ves un valor bien distinto de 1000 con el reloj quieto, sospechá del `SensorManager` o de
la conversión de unidades, no del transporte.

**D.4 — End-to-end: simular convulsión → alarma + SMS.**
Acá se prueba la cadena COMPLETA de este doc: sensor (Parte 1.4) → buffer (Parte 1.5) → JSON
(Parte 3.1) → Wear Data Layer (Parte 2) → OSD acumula y corre el modelo (esto ya es
`EXPLAINER_MODELO_ML.md`) → `/osd/alarm_state` vuelve (Parte 3.2) → vibración en el reloj. Si la
alarma no llega, andá partiendo la cadena: ¿el reloj mandó los datos? (logcat) → ¿OSD los recibió?
(logs de OSD) → ¿el modelo corrió? → ¿la respuesta volvió?

**D.5 — Test nocturno + ajuste de umbral.**
Este paso toca directo la Parte 4.4 de `EXPLAINER_MODELO_ML.md` — el umbral de decisión (default
0.5, ajustable desde la config de OSD). Como esa sección explica con evidencia real del repo de
entrenamiento, no está verificado que ese 0.5 haya salido de un análisis clínico específico para
este modelo — así que si en el test nocturno ves demasiadas falsas alarmas (o, peor, ves que no
detecta algo que debería), **eso es información real para decidir si mover el umbral**, no un
síntoma de que algo esté roto en el transporte.

**Y el watchdog (Parte 4 completa) corre en segundo plano durante TODO el runbook** — si en
cualquier paso ves "⚠ MONITOREO DEGRADADO" en vez de una falla silenciosa, es exactamente el
mecanismo de DEC-048 haciendo su trabajo: avisándote que algo se cortó, en vez de dejarte creer que
todo está bien cuando no lo está.

---

## Fuentes primarias

- Google, [Wearable Data Layer API — MessageClient](https://developer.android.com/training/wearables/data/messages)
- Google, [Foreground Services en Android](https://developer.android.com/develop/background-work/services/foreground-services)
- Código real: `wear/src/main/java/com/seizureguard/wear/data/WearDataLayerManager.kt`, `wear/src/main/java/com/seizureguard/wear/service/SeizureMonitorService.kt`
- Código OSD (clonado en `C:\Users\Dell\Agus\OpenSeizureDetector-OSD`, rama `beta`): `app/src/main/java/uk/org/openseizuredetector/datasource/SdDataSourceAw.java`
- `DECISIONS.md` → DEC-046 (contrato), DEC-047 (modo validación), DEC-048 (watchdog)

---

*¿Algo no te cerró? Volvé a preguntar señalando la sección exacta.*
