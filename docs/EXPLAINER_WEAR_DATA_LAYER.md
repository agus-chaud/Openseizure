# Cómo se hablan el reloj y el teléfono 

> Este documento asume que no sabés nada de Android. Si ya programaste algo  vas a reconocer los patrones , solo cambia el vocabulario.
>
> Compañero de este doc: `EXPLAINER_MODELO_ML.md` (qué hace OSD con los datos que le mandamos).
>
> **Marcadores que vas a ver:** `🔍 Detalle opcional` = no bloquea entender el resto
 `❓ Antes de seguir` = una pregunta corta para chequear que quedó
>
> **Actualización de arquitectura (DEC-050/DEC-051):** hasta hace poco, "el teléfono" en este doc
> era simplemente "la app OSD". Eso cambió: el reloj y OSD no pueden hablarse directo (Parte 2 lo
> explica), así que ahora hay una **tercera pieza** en el medio, una app puente que corre en el
> mismo teléfono que OSD: la **companion app** (módulo `:phone` de este mismo repo). El reloj le
> sigue mandando exactamente los mismos mensajes de siempre (mismo formato, mismos "paths" — ver
> Parte 3) a esa companion, y la companion se los reenvía a OSD por otro medio (HTTP local, Parte
> 3.5). Este doc ya incorpora esa pieza nueva.
---

## El protagonista de este documento: un número

Son las 3:14am. Tu brazo se mueve un poco entre sueños. 40 milisegundos más tarde, ese movimiento
se convirtió en un número: **`1150`** (milli-g de magnitud). Ese número va a atravesar TODO este
documento — sensor → buffer → JSON → Bluetooth → companion en el teléfono → HTTP local → OSD →
alarma. Cada vez que aparezca así, **`1150`**, es el mismo número, un paso más adelante en su viaje. Al final del doc vas a poder explicar los pasos que dio sin mirar atrás.

---

## El diagrama maestro (volvé acá cada vez que te pierdas)

Este es EL mapa completo del viaje de `1150`. Cada sección de este doc explica un tramo distinto.

```
①SENSOR      ②BUFFER     ③TRANSPORTE      ④DATA LAYER      ⑤COMPANION       ⑥HTTP LOCAL        ⑦VUELTA
acelerómetro  ring buffer JSON{"samples"}  MessageClient    :phone recibe   companion→OSD       alarm_state
  25Hz        125(reloj)  chunk de 125     path /osd/...    (mismo AppKey    junta 750, infiere  companion→reloj
                                                             que el reloj)   POST/GET :8080
   │              │             │                │                │               │                  │
   └────1150──────┴─────────────┴────────────────┴────────────────┴───────────────┴──────────────────┘
                            (Parte 1)         (Parte 2)        (Parte 3)        (Parte 3.5)        (Parte 3.2)
```

**Por qué "companion" y no directamente "OSD":** son DOS apps Android distintas instaladas en el
MISMO teléfono físico. La companion (`:phone`) es la única que puede recibir mensajes del reloj
(comparte identidad de firma con él — Parte 2 explica por qué eso importa); OSD nunca ve al reloj
directamente, solo recibe una petición HTTP local de la companion, como si fuera cualquier otro
servidor web en `localhost`.

---

## Parte 1 — Los bloques de Android

### 1.1 — ❓ Antes de arrancar: ¿por qué "correr una app" en Android no es como correr un script?

En Python, `python main.py` arranca de arriba hacia abajo y vos tenés el control. En Android, el **sistema operativo** decide cuándo activa cada pieza de tu app  y peor: **puede matar tu proceso sin avisar** si cree que está consumiendo batería en background sin que nadie lo vea. Para un reloj que tiene que seguir despierto toda la noche vigilando convulsiones, esto es un problema real. 

### 1.2 — Foreground Service: el "proceso que Android promete no matar"

**Analogía:** pensá en un guardia de seguridad nocturno. Si el jefe pasa y no lo ve haciendo nada visible, puede mandarlo a su casa antes de que termine el turno, pensando que no hace falta. Para evitar eso, el guardia cuelga un cartel bien visible: "ESTOY DE GUARDIA". Mientras el cartel esté
puesto, nadie lo manda a casa. Un **Foreground Service** es ese cartel, le mostrás al usuario un notificación persistente ("SeizureGuard activo") a cambio de que Android prometa no apagarte arbitrariamente.

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

Tiene timeout — por eso el watchdog (Parte 4) lo renueva cada 10s, para que no se apague solo a
mitad de una noche larga de monitoreo.

### 1.4 — SensorManager: acá nace nuestro protagonista

**Analogía:** es como el timbre de tu casa. No te quedás mirando la puerta todo el día por si viene alguien, vivís tu vida normal, y cuando alguien toca el timbre, ESO te avisa. `registerListener` es "conectar el timbre"; cada vez que el sensor detecta movimiento, te suena el timbre (`onSensorChanged`)
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

Pensalo así: `x`, `y`, `z` miden aceleración en tres direcciones fijas RESPECTO AL RELOJ (adelante, costado, arriba). Pero un reloj en la muñeca puede quedar girado de mil formas distintas — con la pantalla mirando para arriba, para el costado, con la muñeca doblada, etc. **El mismo movimiento del brazo va a dar valores de `(x, y, z)` completamente distintos según cómo esté girado el reloj en ese momento** — y el reloj puede rotar solo, sin que la persona haga nada especial, con cada giro de muñeca al dormir.

La magnitud `√(x²+y²+z²)` resuelve esto: **no importa cómo esté rotado el reloj, la magnitud del movimiento da el mismo número.** Es una propiedad matemática (el largo de un vector no cambia aunque rotes el sistema de ejes) , a esto se le llama que la magnitud es **invariante a la rotación**. Para un modelo que tiene que reconocer "hubo temblor rítmico" sin importar en qué posición durmió la persona esa noche, esto es mucho más confiable que confiar en `x`, `y`, `z` por separado.

En reposo, con el reloj quieto, la magnitud da **~1000 milli-g** (1g = gravedad terrestre) **sin importar la orientación**, que es justo la prueba de que funciona: probá inclinar el reloj en distintos ángulos estando quieto, y vas a seguir viendo ~1000. Nuestro `1150` es un poco más alto  el brazo se movió. Sanity check de campo: si ves ~1000 en reposo (en cualquier posición), el sensor anda bien.

### 1.5 — 🔍 Detalle opcional: ring buffer

**Contrafactual — ¿qué pasaría sin esto?** Un `MutableList` que crece infinito, agregando una muestra cada 40ms sin límite, revienta la memoria de un reloj con 300 mAh en cuestión de horas — nadie reinicia el reloj a mitad de la noche para "vaciar la lista". Un **ring buffer** resuelve esto con tamaño fijo: al llenarse, lo nuevo pisa a lo viejo, así que la memoria usada nunca crece más allá de un límite conocido.

```python
from collections import deque
buffer = deque(maxlen=125)   # ver corrección de tamaño real más abajo
buffer.append(nueva_magnitud)   # si está lleno, la más vieja se descarta sola
```

**Corrección importante (verificada contra el código real):** el `CircularBuffer.kt` del reloj tiene capacidad **125**, no 750 — coincide exactamente con
`TRANSPORT_CHUNK_SIZE` (`SeizureMonitorService.kt:908-909`: `BUFFER_CAPACITY = TRANSPORT_CHUNK_SIZE= 125`). El buffer de 750 que arma la ventana completa para el modelo vive del OTRO lado (dentro de `SdAlgMl.java`, en el teléfono) — el reloj solo necesita juntar 125 muestras (~5s) para disparar un
envío, no las 750 completas. Lo vas a ver en la Parte 3.1.

**Esto no es solo teoría — hay tests reales que lo verifican** (`CircularBufferTest.kt`)
- `buffer_startsEmpty` (línea 55) y `buffer_afterAddingExactCapacity_isFull` (línea 88)el buffer arranca vacío y se marca lleno en exactamente 125 muestras, ni una más ni una menos.
- `buffer_afterAddingLessThanCapacity_isNotFull` (línea 72): prueba el caso borde de 124 muestras , el más peligroso, porque si fallara acá el modelo recibiría ventanas incompletas en producción.
- `buffer_snapshot_returnsElementsInChronologicalOrder` (línea 113): confirma que el orden se preserva — si esto fallara, sería como leer una señal de electrocardiograma al revés, un patrón que el modelo nunca vio en su entrenamiento.

**❓ Antes de seguir:** ¿por qué un `FULL_WAKE_LOCK` (que mantiene la pantalla prendida) sería mala idea en un reloj que monitorea toda la noche? TE CONSUME TODA LA BATERIA Y LA PANTALLA TE GENERA UNA LUZ MOLESTA PARA DORMIR

---

## Parte 2 — Wear Data Layer API: cómo dos dispositivos Android se hablan

### 2.0 — ❓ Antes que nada: si es Bluetooth/WiFi, ¿por qué hablamos de una "API"?

**Bluetooth/WiFi y la Wear Data Layer API no son alternativas — son dos capas distintas, una arriba de la otra.**

**API** = "Application Programming Interface" = un conjunto de funciones/reglas que alguien más programó, para que vos las uses SIN tener que saber cómo funcionan por dentro.
Ahora, las capas de este caso concreto:

```
CAPA 4 (lo que vos programás):     WearDataLayerManager.sendAccelData(samples)
CAPA 3 (la API que usás):          Wear Data Layer API (MessageClient, NodeClient...)
CAPA 2 (lo que la API maneja       Google Play Services: empareja los dispositivos,
        por vos):                  reconecta si se corta, reintenta, etc.
CAPA 1 (el cable invisible real):  Bluetooth y/o WiFi — la señal que
                                     efectivamente viaja entre el reloj y el teléfono
```

Vos escribís código contra la **Capa 3** (`messageClient.sendMessage(...)`). Nunca tocás Bluetooth directamente — ni abrís un socket, ni manejás el emparejamiento, ni te preocupás si en un momento dado la conexión usa Bluetooth o WiFi (el sistema elige por vos, y hasta puede cambiar de uno a otro sin que tu código se entere). Eso es EXACTAMENTE lo que hace útil una API: te da un contrato simple ("mandá esto a este path") y esconde toda la complejidad de radio, reconexión y bajo nivel detrás.


### 2.1 — ❓ El problema: `1150` ya es un número. ¿Cómo cruza de un dispositivo físico a otro?

El reloj y el teléfono son **dos dispositivos físicos distintos**. No comparten memoria — no hay forma de que uno "llame a una función" del otro directamente, como sí harías entre dos funciones dentro del mismo programa. Necesitan mandarse mensajes a través de la Capa 1 usando la API (Capa 3) como intermediaria.


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

Es como una persona que recibe cartas todos los días: si un día le llega un sobre roto e ilegible, lo tira a la basura y sigue esperando la carta de mañana — no se muda de casa ni deja de recibir correo para siempre por una carta rota. Por eso este código loguea y sigue en vez de lanzar excepción.

**Contrafactual — ¿qué pasaría si esto lanzara una excepción sin capturarla?** Un mensaje corrupto mataría el `listener` entero. El reloj dejaría de recibir `/osd/alarm_state` PARA SIEMPRE hasta que
alguien reinicie manualmente el servicio — silencioso, sin ningún aviso. En software de seguridad de salud, "dejar de escuchar del todo" por un solo mensaje malformado es mucho peor que "perder ese mensaje puntual y seguir funcionando".

**Verificado con un test real** (`WearDataLayerManagerTest.kt:107`,
`` `parseAlarmState returns null on malformed payload without crashing` ``): le pasa un payload basura a propósito y confirma que la función devuelve `null` en vez de lanzar una excepción — es justo el comportamiento de arriba, probado en código.

**❓ Antes de seguir:** si mandás un mensaje y no hay ningún `Node` conectado (`nodes.isEmpty()`),
¿qué pasa con `1150`? *(pista: mirá el `return false` de arriba — nadie lo recibe, y quien llamó se entera del fallo)*  --> SE REGISTRA Q NO LLEGÓ, PERO SIEMPRE SE SIGUE ESCUCHANDO

---

## Parte 3 — El contrato real: `1150` sale del reloj (DEC-046), y de ahí a OSD (DEC-051)

Son dos tramos, no uno. **Tramo A** (reloj ↔ companion, `:phone`): cuatro paths de Wear Data Layer,
formato fijo, **sin cambios desde DEC-046** — el reloj no se enteró de que ahora habla con una app
distinta. **Tramo B** (companion → OSD): nuevo, HTTP local dentro del mismo teléfono (Parte 3.5).
Fuente de verdad: `DECISIONS.md` → DEC-046 (contrato del Tramo A), DEC-050 (por qué hace falta un
Tramo B) y DEC-051 (diseño elegido para el Tramo B).

```
RELOJ (:wear)                    COMPANION (:phone, en el teléfono)          OSD (misma app, mismo teléfono)
──────────────                   ───────────────────────────────────         ───────────────────────────────
                    ← /osd/send_settings "start" ←      pide settings
    /osd/settings
    {"battery":85,"sample_freq":25}          →          valida y reenvía →   POST /settings (HTTP local)

    /osd/accel_data
    {"samples":[..., 1150, ...]}             →          valida y reenvía →   POST /data → junta 750 → infiere

                    ← /osd/alarm_state ←                lee con GET /data ←  responde alarmState
```

El reloj sigue mandando y esperando exactamente lo mismo que en DEC-046 — el Tramo A no cambió una
coma. Lo que cambió es que del otro lado del Data Layer, quien recibe esos mensajes ya no es OSD,
es la companion (Parte 2 explica por qué OSD no puede recibirlos directo). La companion valida cada
mensaje y, si es válido, se lo reenvía a OSD por HTTP (Parte 3.5) — si el reloj mandara algo raro,
la companion lo descarta ahí mismo y OSD nunca se entera de ese mensaje.

### 3.1 — `/osd/accel_data`: acá viaja nuestro `1150` (Tramo A, sin cambios)

```json
{"samples": [998.2, 1150.0, 995.8, ...]}
```

125 valores por mensaje (`TRANSPORT_CHUNK_SIZE` en el reloj / `ACCEL_CHUNK_SAMPLES` en la
companion) = 5 segundos a 25Hz. OSD acumula 6 chunks de 125 = 750 (30 segundos) antes de correr el
modelo — eso sigue pasando adentro de OSD, sin cambios (`EXPLAINER_MODELO_ML.md`).

**Código que lo empaqueta, sin cambios desde DEC-046** (`wear/.../WearDataLayerManager.kt`):

```kotlin
fun samplesToJsonBytes(samples: FloatArray): ByteArray {
    val arr = JSONArray()
    for (s in samples) arr.put(s.toDouble())
    return JSONObject().put("samples", arr).toString().toByteArray(Charsets.UTF_8)
}
```

**Código que lo recibe hoy — la companion, no OSD** (`phone/.../WatchMessageParser.kt`):

```kotlin
fun parseAccel(bytes: ByteArray?): DoubleArray? = parse(bytes) { o ->
    val arr = o.optJSONArray("samples")
    if (arr == null || arr.length() != ACCEL_CHUNK_SAMPLES) return@parse null
    // ...valida que cada valor sea un número finito y razonable (< 100_000 milli-g)...
    // Chunk "congelado" (las 125 muestras exactamente iguales) también se rechaza — Parte 3.5.
}
```

`json.has("samples")` sigue siendo la clave que hace que el mensaje se reconozca — solo que ahora
quien la busca es la companion, no `SdDataSourceAw.java` de OSD (esa clase de OSD sigue existiendo
en el código de OSD, pero ya no participa: la fuente de datos activa en OSD pasó a ser **"Garmin"**,
no **"Android Wear"** — Parte 3.5 y el paso D.1 de la Fase D del `README.md`).

**Contrafactual — esto no es hipotético, ya pasó en este proyecto:**

| Enfoque | Qué pasaba |
|---|---|
| DEC-034/039 (viejo): floats binarios little-endian | Quien recibía (entonces, OSD) los interpretaba con su parser de respaldo (`int16`, no `float32`) → basura numérica silenciosa, sin ningún error visible |
| DEC-046 (Tramo A actual): JSON `{"samples":[...]}` | El receptor reconoce `json.has("samples")`, los datos llegan bien |
| DEC-050 (bloqueante real, no de formato): el receptor de DEC-046 era OSD directo | El Data Layer de Android descarta el mensaje ANTES de que cualquier JSON se parsee — Parte 2 explica por qué |

**Verificado con tests reales** (`WearDataLayerManagerTest.kt`, lado reloj):
- `samplesToJson produces samples array with correct count` y `samplesToJson preserves values in order`: confirman formato y orden.
- `samplesToJson sequential validation pattern matches Graham protocol`: respalda el modo debug de la Parte 3.4 — verifica que los números secuenciales `[1,2,3,...]` se serializan tal cual, para detectar si algo los desordena en el camino.
- `accelDataPath matches OSD protocol`: confirma que el path literal es `/osd/accel_data`, carácter por carácter — un typo acá rompería todo en silencio, igual que la clave `"samples"` mal escrita.

**Y del lado companion** (`WatchMessageParserTest.kt`, repo `:phone`): tests que verifican que un
chunk con `sample_freq` distinto de 25, o con las 125 muestras idénticas ("sensor congelado"), se
rechaza y NO se reenvía a OSD (DEC-059/DEC-060) — ver Parte 3.5.

### 3.2 — La vuelta: `/osd/alarm_state` (Tramo A, sin cambios de formato)

```json
{"alarm_state": 0, "alarm_phrase": "OK"}
```

`0`=OK, `1`=WARNING, `2+`=ALARM. Si el payload no es el JSON esperado, `parseAlarmState()` devuelve
`null` — se loguea y se ignora, no crashea (mismo principio que 2.3). El reloj sigue recibiendo esto
del mismo path de siempre — solo que ahora quien lo arma y lo manda es la companion, con el dato que
acaba de leerle a OSD por HTTP (Parte 3.5), no OSD directamente.

### 3.3 — 🔍 Detalle opcional: el handshake (`/osd/settings` + `/osd/send_settings`)

Si la companion pide settings antes de que el reloj esté escuchando, o viceversa, alguien se pierde el mensaje.

```
1. La companion manda "/osd/send_settings" con "start"  → "decime tus settings"
2. El reloj responde "/osd/settings" {"battery":85,"sample_freq":25}
3. El reloj TAMBIÉN lo manda proactivamente al arrancar, por si el "start"
   de la companion llegó antes de que el listener del reloj estuviera registrado
```

**Contrafactual — sin este intercambio:** la companion (y por lo tanto OSD, que depende de que la companion le reenvíe algo) se queda sin saber si el reloj está vivo. Es como llamar por teléfono y decir "¿hola? ¿me escuchás?" antes de empezar a hablar en serio: si nadie confirma que está del otro lado, seguís hablando solo sin saberlo.

**Verificado con tests reales** (`WearDataLayerManagerTest.kt`, lado reloj):
`settingsToJson produces the format OSD handleSettings expects` y `sendSettingsPath matches OSD
protocol` — confirman que el JSON de respuesta tiene las claves esperadas y que el path coincide
carácter por carácter. (Los nombres de estos tests todavía dicen "OSD" porque se escribieron en la
era DEC-046/pre-companion — el contrato de bytes que verifican no cambió, solo cambió quién los lee.)

### 3.4 — 🔍 Detalle opcional: el keep-alive del `alarm_state` — HOY vive en la companion, no en OSD

Versión vieja de este documento decía que OSD mandaba `/osd/alarm_state` cada 20s proactivamente.
Eso describía el comportamiento (real, pero de la era pre-companion) de OSD hablando directo con un
reloj Wear Data Layer nativo. **Hoy eso ya no aplica:** OSD no le manda nada al reloj — el que hace
keep-alive es `AlarmStateRelay` en la companion, cada **10 segundos** (`ALARM_KEEP_ALIVE_MS`,
constante firmada en DEC-059, no 20s), y con una regla más fina que "cada tanto, sin más": un estado
`0` (OK) solo se reenvía al reloj si la companion está sana Y el dato de OSD es reciente; en cambio,
cualquier estado que NO sea OK (alarma real) se reenvía **siempre**, sin esa condición — para que un
puente enfermo nunca pueda tapar una alarma real detrás de un falso "todo OK" (asimetría "fail-loud",
hallazgo F1 del registro de seguridad).

**❓ Antes de seguir:** si mandaras `{"samples": [1150]}` pero con la clave escrita `"Samples"`
(mayúscula), ¿qué pasaría del lado de la companion? *(pista: `optJSONArray("samples")` es
case-sensitive — mismo mecanismo silencioso de la Parte 3.1, solo que ahora quien lo detecta es la
companion, no OSD)*

### 3.5 — La segunda pata del viaje: companion → OSD por HTTP local (DEC-051, nuevo desde el último EXPLAINER)

Esta parte no existía en la versión anterior de este documento porque no existía en el proyecto.
Una vez que la companion validó un mensaje del reloj (Parte 3.1), lo **re-empaqueta a un formato
distinto** y lo manda por HTTP a `http://127.0.0.1:8080` — el mismo teléfono hablándose a sí mismo
por loopback, no por red. Ese puerto lo abre OSD internamente (`SdWebServer`, pensado originalmente
para que un reloj Garmin le mande datos por HTTP) — por eso en la configuración de OSD la fuente de
datos activa pasa a ser **"Garmin"**, no "Android Wear" (el tag `SdDataSourceAw` de OSD sigue
existiendo en su código, pero deja de usarse).

```
Companion arma (OsdPayloadCodec.kt)                          OSD recibe (SdDataSourceGarmin, vía SdWebServer)
────────────────────────────────────                          ──────────────────────────────────────────────
{"dataType":"raw","data":[998.2, 1150.0, ...]}     → POST /data      (form-encoded: dataObj=<json urlencoded>)
{"dataType":"settings","analysisPeriod":5,
 "sampleFreq":25,"battery":85, ...}                → POST /settings
                                                    ← GET  /data      {"alarmState":0,"alarmPhrase":"OK", ...}
```

Notá que el formato JSON de este tramo es **distinto** del de la Parte 3.1 (`dataType`/`data` en vez
de `samples`; `alarmState` en camelCase en vez de `alarm_state`) — es el formato que espera el
servidor embebido de OSD, no el de DEC-046. La companion es, literalmente, un traductor entre los dos
dialectos: le habla al reloj en el dialecto DEC-046 y a OSD en el dialecto Garmin/`SdWebServer`, y
ninguno de los dos lados sabe que el otro dialecto existe.

**🔍 Detalle opcional — por qué esto no es "más frágil" por tener una pata más:** cada POST es
"mandar y listo" (`no retry/queue/batching by design`, comentario real del código): si un envío
falla (OSD no responde, el puerto 8080 no está escuchando), esa ventana de 125 muestras se pierde,
pero la siguiente lo vuelve a intentar 5 segundos después — mismo principio de "perder poquito y
seguir" que ya viste en la Parte 1.5 con el buffer de 125. El estado de alarma se lee con `GET
/data` en un poll separado, no atado al envío de datos — si ese GET falla, simplemente no hay nada
nuevo que mandarle al reloj hasta el próximo intento.

---

## Parte 4 — El watchdog: qué pasa si `1150` deja de llegar (DEC-048)

### 4.1 — El problema

El reloj puede *creer* que monitorea cuando en realidad nada llega al teléfono: sensor muerto, Bluetooth caído, teléfono apagado. Sin nada que lo detecte, la UI sigue mostrando "todo bien" , la peor mentira posible .

### 4.2 — La solución: heartbeat cada 10s

**Analogía:** un padre que cada tanto se asoma al cuarto del bebé para chequear que sigue respirando y moviéndose. No se queda mirando fijo toda la noche . Si en el último chequeo no vio señales de vida durante demasiado tiempo, se preocupa y actúa.

```kotlin
// Función PURA — testeable sin Android ni tiempo real:
fun evaluateHealth(now: Long, lastSample: Long, lastDelivery: Long, lastAlarmState: Long, started: Long): Health {
    if (now - started < WATCHDOG_WARMUP_MS)           return Health.HEALTHY    // primer minuto: gracia
    if (now - lastSample > SAMPLE_STALE_MS)           return Health.DEGRADED   // sensor mudo >10s
    if (now - lastDelivery > DELIVERY_STALE_MS)       return Health.DEGRADED   // sin entregas OK >40s
    if (now - lastAlarmState > ALARM_STATE_STALE_MS)  return Health.DEGRADED   // sin alarm_state de la companion >40s
    return Health.HEALTHY
}
```

Si `1150` (o cualquier muestra) deja de generarse por >10s, o deja de **entregarse con éxito** al teléfono por >40s, o la companion deja de mandar el estado de alarma por >40s → estado **DEGRADADO** (después de 2 chequeos seguidos): notificación y pantalla "⚠ MONITOREO DEGRADADO", **sin vibración** (DEC-057).

**Contrafactual — sin watchdog:** el reloj sigue mostrando "SeizureGuard activo" con la misma cara de siempre, aunque el sensor esté muerto o el Bluetooth cortado. La UI miente por omisión — no dice "todo mal" explícitamente, simplemente deja de decir nada nuevo, y eso se ve idéntico a "todo bien" para quien lo mira de reojo a las 3am.

**Verificado con tests reales** (`SeizureMonitorServiceTest.kt`, sección de `evaluateHealth`): son varios tests que le dan a la función pura distintos combos de `(now, lastSample, lastDelivery)` — sensor fresco pero entrega vieja, entrega fresca pero sensor viejo, ambos frescos, ambos viejos, justo en el borde del umbral — y verifican que devuelve `DEGRADED` u `OK` según corresponda. Como es una función pura (sin `delay()` real), estos tests corren en milisegundos y cubren los casos borde exactos sin depender del reloj del sistema.

**Por qué "función pura" no es un tecnicismo:** un test que dependa de `delay()` real es lento . Separando la lógica (pura, testeable con inputs fijos) del loop (`delay(WATCHDOG_INTERVAL_MS)`, que sí depende del tiempo real y no se testea), los tests corren en milisegundos y son determinísticos.

### 4.3 — 🔍 Detalle opcional: la cadena completa sensor → watchdog

```kotlin
private suspend fun sendToAllNodes(path: String, data: ByteArray): Boolean {
    // ...
    return anyDelivered   // true SOLO si se entregó a al menos un nodo
}
```

`lastDeliveryOkAtMs` (que usa el watchdog) solo avanza cuando esto devuelve `true`. Si el teléfono
se desconecta, las entregas fallan, `lastDeliveryOkAtMs` se congela, y a los 40s sin entregas (más dos chequeos de 10s) el watchdog lo
detecta, unos 60s en total. Es la cadena completa: `1150` nace → se transporta → si deja de llegar, se nota.

---

## El viaje completo de `1150`, de una — resumen final

```
① Nace en el acelerómetro (25Hz) como magnitud √(x²+y²+z²) = 1150 milli-g
② Entra al ring buffer del reloj (junto a otras 124 muestras de ese chunk)
③ Se serializa a JSON: {"samples":[..., 1150, ...]}
④ Viaja por MessageClient al path /osd/accel_data (Wear Data Layer API)
⑤ La companion (:phone, misma firma que el reloj) lo recibe y lo valida
⑥ La companion lo reempaqueta a {"dataType":"raw","data":[...]} y hace
   POST /data a OSD por HTTP local (127.0.0.1:8080) — OSD junta 750 e infiere
⑦ La companion lee el resultado con GET /data y se lo manda al reloj por
   /osd/alarm_state — mismo formato de siempre, {"alarm_state":N,...}
   Si en cualquier punto de ①→⑥ algo se corta, el watchdog lo nota en 60 s (enlace) / ≈75 s (OSD congelado), techos calculados, no medidos en el reloj real
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
| Companion app | App puente (`:phone`) que corre en el mismo teléfono que OSD, con la misma identidad de firma que el reloj — recibe del reloj lo que OSD no puede recibir directo, y se lo reenvía a OSD por HTTP |
| AppKey | `packageName` + hash del certificado de firma; el Data Layer solo entrega mensajes entre apps con el mismo AppKey (Parte 2) |
| HTTP local / loopback | Petición HTTP que nunca sale del propio teléfono (`127.0.0.1`) — dos apps del mismo dispositivo hablándose como si una fuera un servidor web |

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
`optJSONArray("samples")` en la companion es case-sensitive — comparación de texto exacta, no
ignora mayúsculas. Con `"Samples"`, `optJSONArray` devuelve `null`, `parseAccel()` también devuelve
`null`, y la companion descarta el mensaje entero **sin reenviarlo a OSD** — ni siquiera llega a
existir un intento fallido del lado de OSD, muere un paso antes. Es el mismo mecanismo silencioso de
siempre (una clave mal escrita no rompe nada visiblemente), solo que ahora quien lo detecta —y quien
podría, en teoría, loguearlo— es la companion, no OSD.

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

Todo lo de arriba es teoría hasta que agarrás el reloj de verdad. la Fase D del `README.md` (checklist D.1 a D.5; los pasos detallados están en `HARDWARE_RUNBOOK.md`)
tiene 5 pasos manuales — acá te digo, para cada uno, EXACTAMENTE qué concepto de este documento
estás poniendo a prueba, para que no sean dos mundos separados.

**D.1 — Instalar el reloj + la companion + OSD, y activar la fuente Garmin en OSD (no "Android Wear").**
Esto es una corrección sobre la versión anterior de este doc: por DEC-050 (Parte 2), OSD **no puede**
recibir mensajes del reloj directo, así que la fuente "Android Wear"/`SdDataSourceAw` de OSD se queda
sin datos por diseño, no por un bug de configuración. Lo que hay que instalar son TRES cosas — el
reloj, la companion (`:phone`, firmada igual que el reloj) y OSD — y en OSD activar la fuente
**"Garmin"** (Parte 3.5). Si después de este paso ves "Data source fault" en OSD, revisá el handshake
de la Parte 3.3 (`/osd/send_settings` ↔ `/osd/settings`, ahora entre reloj y companion) por logcat del
reloj, y por separado si la companion está efectivamente reenviando a `127.0.0.1:8080` (logcat de la
companion).

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
(Parte 3.1) → Wear Data Layer, reloj→companion (Parte 2) → companion valida y reenvía por HTTP local
(Parte 3.5) → OSD acumula y corre el modelo (esto ya es `EXPLAINER_MODELO_ML.md`) → la companion lee
el resultado con `GET /data` y arma `/osd/alarm_state` (Parte 3.2) → vibración en el reloj. Si la
alarma no llega, ahora hay un eslabón más para partir: ¿el reloj mandó los datos? (logcat del reloj)
→ ¿la companion los recibió y los validó? (logcat de la companion, o `FaultLog`/resumen matutino si
ya está el Batch 5d) → ¿OSD los recibió por HTTP? (logs de OSD) → ¿el modelo corrió? → ¿la respuesta
volvió por la companion?

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
- Código real, reloj (`:wear`, Tramo A, contrato de bytes sin cambios desde DEC-046): `wear/src/main/java/com/seizureguard/wear/data/WearDataLayerManager.kt`, `wear/src/main/java/com/seizureguard/wear/service/SeizureMonitorService.kt`
- Código real, companion (`:phone`, Tramo A recibido + Tramo B armado): `phone/src/main/java/com/seizureguard/phone/bridge/WatchMessageParser.kt` (valida lo que manda el reloj), `phone/src/main/java/com/seizureguard/phone/bridge/OsdPayloadCodec.kt` y `OsdHttpForwarder.kt` (arman y mandan el HTTP a OSD), `phone/src/main/java/com/seizureguard/phone/bridge/AlarmStateRelay.kt` (lee de OSD y arma la vuelta al reloj)
- Código OSD (repo OSD, rama `beta`): `app/src/main/java/uk/org/openseizuredetector/datasource/SdDataSourceAw.java` (fuente "Android Wear", ya no usada — Parte 3.5)
- `DECISIONS.md` → DEC-046 (contrato reloj↔companion), DEC-047 (modo validación), DEC-048 (watchdog), DEC-050 (por qué OSD no puede recibir del reloj directo), DEC-051 (diseño de la companion y el HTTP local), DEC-059 (constantes firmadas, incluido el keep-alive de 10s)

---

**Nota sobre el estado de este cambio (actualizada 2026-10-01):** el reloj ya fue retargeteado y está en `main` (Batches 7 y 8, `DECISIONS.md`): tiene dos sabores de build (`companion` y `osdDirect`), el watchdog corre cada 10s con los límites de 40s de la Parte 4, y las fallas del sistema no vibran. Además el reloj manda un número de versión del contrato (`contract_version`) junto con los ajustes, para que la companion avise en silencio si reloj y teléfono tienen versiones distintas. El **contrato de bytes** de la Parte 3.1–3.4 (paths, claves JSON) sigue idéntico al de siempre. Lo que **no** se hizo todavía es probar todo esto con el reloj y el teléfono reales (DV-1..DV-7 en `HARDWARE_RUNBOOK.md`).

---

*¿Algo no te cerró? Volvé a preguntar señalando la sección exacta.*
