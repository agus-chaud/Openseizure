# Guía simple de pruebas con el reloj real

> Para quien arma o revisa el sistema y **no es programador**. Cada prueba tiene pasos concretos, lo que
> tiene que pasar y qué anotar. Las pruebas se llaman **DV-1 a DV-9** (la misma numeración que usa
> `docs/SAFETY_FINDINGS_WATCH_OSD.md`, sección 6).

---

## ⚠️ Seguridad primero

- **Nunca hagas estas pruebas con la persona confiando en el sistema.** Hacelas **de día**, con la
  persona despierta y con los demás cuidados en marcha.
- Avisá a todos antes: la alarma suena fuerte y el **SMS sale de verdad**.
- Una sacudida del reloj es una **imitación**, no una convulsión real.
- Lo que dice `CAREGIVER_GUIDE.md` ("llamá a emergencias si dura más de 3 minutos", etc.) vale para
  convulsiones **reales**. Esta guía no lo cambia ni lo reemplaza.

## Por qué importan estas pruebas

El sistema (reloj + "SeizureGuard Companion" + OpenSeizureDetector) está construido y tiene tests
automáticos, pero **nunca se probó con el reloj y los teléfonos reales**. Mientras estas pruebas no pasen,
el sistema **NO está validado**: no lo uses como único cuidado de nadie. Las pruebas confirman dos cosas:
que la alarma llega **a tiempo**, y que las fallas **se ven sin hacer ruido**.

## Qué necesitás

- El **Galaxy Watch 8** con la app SeizureGuard.
- El **teléfono de la persona** (el que lleva "SeizureGuard Companion" y OpenSeizureDetector, "OSD").
- Un **segundo teléfono**: el del cuidador, que recibe el SMS.
- Un **cronómetro** (el del teléfono sirve, con botón de vuelta/lap) y, si podés, **otra persona** que anote
  tiempos o filme.
- **Cargadores** para el reloj y el teléfono de la persona.
- **Un día + una noche** (la noche completa es la última prueba).

## Preparación

La instalación y la conexión están explicadas paso a paso en `HARDWARE_RUNBOOK.md`: **secciones 2 a 4**
(conectar el reloj, instalar las dos apps con la **misma firma**, configurar OSD). No las repito acá.
Para entender el flujo también sirve `docs/GUIA_CONECTAR_RELOJ_TELEFONO.md`.

**Checklist "todo listo"** antes de cada tanda de pruebas:

- [ ] En OSD, la fuente de datos es **"Garmin"** (nunca "Android Wear").
- [ ] El **servidor web de OSD** está encendido (en la pestaña System figura "Access Server at: http://...:8080").
- [ ] En OSD, **"Enable Audible System FaultWarnings"** está **desactivada**.
- [ ] En OSD, **MUTE** está apagado y el **número del cuidador** para el SMS está cargado.
- [ ] En el teléfono de la persona, **volumen de alarma alto**, y el teléfono **cargando**.
- [ ] En "SeizureGuard Companion" tocaste **"Start bridge"** (debe aparecer el aviso "Bridge started" y la
      notificación "SeizureGuard bridge running").
- [ ] En el reloj tocaste **"Iniciar monitoreo"** y dice **"Monitoreo activo"**.
- [ ] Si reinstalaste "SeizureGuard Companion", tocá **"Start bridge"** de nuevo y después
      **reiniciá el monitoreo en el reloj** (parar y volver a iniciar). Hasta que el reloj mande sus
      ajustes, OSD recibe una batería de reloj de 100 por defecto y no te avisaría de una batería
      baja real.
- [ ] Esperaste **2 minutos** y la pantalla del reloj **no** dice "⚠ MONITOREO DEGRADADO".
- [ ] En el teléfono del cuidador, el contacto de la persona está configurado para que el SMS **suene**
      (ver "Tu teléfono" en `CAREGIVER_GUIDE.md`).

Anotá también la **versión (commit) de cada APK** que instalaste: sin eso los resultados no sirven.

---

## Antes de empezar: paso previo (no es un DV)

**Qué probás:** que los datos del reloj llegan a OSD.

**Cómo:**
1. Con todo andando, esperá 1 minuto.
2. En el navegador del teléfono de la persona abrí `http://127.0.0.1:8080/data`.
3. Recargá la página dos o tres veces.

**Qué tiene que pasar:** aparece un texto con un `timestamp` que **avanza** en cada recarga.

**Qué anotar:** hora y si el `timestamp` avanzó.

**Si falla:** si ves siempre el mismo texto, OSD está en la fuente equivocada (volvé a la preparación). Si
nada llega, casi siempre es la firma de las apps (`HARDWARE_RUNBOOK.md` §4.2). No sigas con el resto.

---

# A. Que llegue la alarma

## DV-3 — OSD no descarta datos por frecuencia

**Qué probás:** si OSD tiene activada la verificación de frecuencia de datos (más estricta con los tiempos).

**Cómo:**
1. En los ajustes de OSD buscá la opción de frecuencia de datos (la clave interna se llama
   `DataFrequencyCheck`; el nombre en pantalla puede variar, **sin verificar**).
2. Anotá si está activada.
3. Con el reloj andando 10 minutos, fijate si OSD muestra algún aviso de datos rechazados o fuera de tiempo.
4. Mientras tanto, **levantá la muñeca / mirá la pantalla del reloj varias veces**, y una de ellas **más de
   30 segundos**. Desde DEC-068 (PR #49) el reloj mantiene **exactamente 25 Hz** aunque el detector de
   "levantar la muñeca" de Samsung suba el sensor a 50 Hz.

**Cómo juzgar si hay FALLA:** mirá `alarmState` en el JSON de `/data` de OSD (o si la pantalla de OSD muestra
FAULT). **FALLA solo si `alarmState` == 4.** **No** te guíes por el texto de `faultCause`: OSD nunca lo
borra después de recuperarse, así que un texto viejo (por ejemplo "Data Source Fault" tras reinstalar la app
del reloj, o "Data arriving too quickly") puede quedar mientras OSD analiza con normalidad.

**Qué tiene que pasar:** el valor es el que esperabas, no aparece "Data arriving too quickly" y `alarmState`
nunca vale 4. Resultado de campo 2026-10-07: 204/204 paquetes, cada 4,97 a 5,06 s.

**Qué anotar:** valor de la opción; si OSD rechazó o descartó datos.

**Si falla:** si OSD rechaza datos por frecuencia, avisá a quien configuró el sistema.

## DV-8 — Latencia: relay (DV-8a) y techo clínico de 40 s (DV-8b)

Lo que aprendimos en la primera prueba de campo (2026-10-06/07, Galaxy Watch 8 + Galaxy A52 + OSD 5.0.9):

- La banda de análisis de OSD es de **3 a 8 Hz**. Sacudir 1 a 3 veces por segundo queda **debajo** de la banda
  y no dispara. Sacudí de forma **rítmica, 4 a 6 veces por segundo**.
- Sacudir con la mano **solo llegó a AVISO (WARNING), nunca a ALARMA**.
- El botón "Raise Alarm" de OSD (alarma manual, estado 5) **no sirve** para probar el relay: OSD pisa el
  estado con el siguiente paquete de datos (unos 5 s) salvo que su "latch" esté activado, y el celular lee el
  estado justo después de cada envío, así que el reloj nunca lo ve. **No lo uses para DV-8.**

Por eso DV-8 se divide en dos pruebas.

### DV-8a — Relay: el estado de OSD llega al reloj

**Qué probás:** que cualquier estado de detección de OSD (alcanza con AVISO / WARNING) llegue al reloj, y
cuánto tarda desde que OSD lo decide hasta que el reloj lo muestra. Esperado: unos pocos segundos (en campo
llegó en 1,7 s).

**Cómo:** sacudí el reloj **4 a 6 veces por segundo** hasta que OSD marque AVISO (WARNING) y cronometrá hasta
que el reloj lo muestre.

**Qué tiene que pasar:** el reloj refleja el estado de OSD a los pocos segundos (diseño: máximo 8 s).

**Qué anotar:** segundos entre la decisión de OSD y el reloj.

### DV-8b — Latencia clínica (techo de 40 s)

**No se puede verificar sacudiendo con la mano:** no llega a ALARMA. Hace falta una fuente de movimiento que
imite de verdad una convulsión, o repetir datos grabados. Mientras no exista, anotá DV-8b como **"no
verificable con sacudida a mano"** (no es PASA).

**Qué probás:** cuánto tarda en sonar la alarma desde que empieza la "convulsión". El techo firmado es
**40 segundos** (`CLINICAL_SIGNOFF.md`, `techo_latencia_clinica`). Este número **nunca se midió**: es el
dato más importante de toda la guía.

**Cómo:**
1. Todo listo (checklist). El reloj puesto en la muñeca, en una mesa o en la mano de quien va a sacudirlo.
2. Ideal: **dos personas**. Una sacude el reloj; la otra maneja el cronómetro. Si estás solo, filmá con un
   tercer aparato el reloj, el teléfono de la persona y el cronómetro juntos.
3. Pedí que el teléfono del cuidador esté a la vista.
4. Poné el cronómetro en cero. **Arrancalo en el mismo instante en que empieza la sacudida.**
5. Reproducí el movimiento de convulsión con la fuente que tengas (a mano, **4 a 6 sacudidas por segundo**,
   como en `HARDWARE_RUNBOOK.md` §6, solo llega a AVISO). **Seguí sin parar** hasta que suene la alarma del teléfono.
6. Apretá "vuelta" (lap) en el cronómetro en cada uno de estos momentos:
   - **(a)** el reloj vibra **fuerte y repetido** (alarma; un pulso corto solo es "aviso", no cuenta);
   - **(b)** suena la **alarma de OSD** en el teléfono de la persona;
   - **(c)** llega el **SMS** al teléfono del cuidador (después de (b) podés dejar de sacudir y seguir
     cronometrando hasta que llegue).
7. Si pasan **60 segundos** sin alarma, frená y anotá **"NO DISPARÓ"**.
8. Silenciá la alarma en OSD, sacá el reloj de la mesa y esperá **2 minutos quietos** hasta que el reloj
   vuelva a "Monitoreo activo" y OSD a normal. Repetí.
9. Hacé **al menos 5 corridas válidas** (que hayan disparado). Anotá cada una.

**Qué tiene que pasar:**
- En **cada** corrida, (a) y (b) ocurren en **40 segundos o menos** desde que empezó la sacudida.
- El reloj vibra fuerte, suena OSD, llega el SMS.

**Medición extra (opcional, sin código):** compará (a) con (b). El diseño dice que el estado de alarma llega
al reloj como máximo **8 s** después de que OSD lo calcula. Si en varias corridas el reloj vibra más de 8 s
**después** de que suena el teléfono, anotalo. Es aproximado (depende de tu pulso con el cronómetro): sirve
como pista, no como veredicto.

**Qué anotar (por corrida):** número de corrida, segundos hasta (a), (b) y (c), y si fue "NO DISPARÓ".

**Si falla:**
- **PASA** solo si (a) y (b) están en **≤ 40 s en todas** las corridas.
- Una sola corrida **> 40 s** = **no se cumple el techo firmado**. Avisá a quien configuró el sistema: el
  valor tiene que **revisarse y volver a firmarse** en `CLINICAL_SIGNOFF.md`. Nadie debe cambiarlo en
  silencio.
- "NO DISPARÓ" **no es éxito**. Una sacudida es una imitación y el modelo puede necesitar un patrón sostenido,
  así que pasa. Anotalo igual, probá con una sacudida más pareja y avisá si no dispara en la mayoría de las
  corridas.
- Si (c) llega mucho después de (b), anotalo y avisá aunque (a) y (b) estén bien.

---

# B. Que las fallas se vean sin sonar

**Regla de oro de todo este grupo:** una falla tiene que verse **sin ningún sonido ni vibración**, ni en el
teléfono ni en el reloj. Si algo suena o vibra, eso es lo que hay que anotar y avisar.

## DV-1 — Bluetooth denegado: sin crash y con aviso silencioso

**Qué probás:** que la app del teléfono no se rompe si falta el permiso de Bluetooth.

**Cómo:**
1. En el teléfono de la persona, quitá el permiso **"Dispositivos cercanos" (Nearby devices)** a
   "SeizureGuard Companion" (Ajustes → Aplicaciones; los nombres cambian según la marca). O desinstalá y
   reinstalá la app sin aceptarlo.
2. Abrí la app y tocá **"Start bridge"**.
3. Repetí con las **notificaciones también denegadas**.

**Qué tiene que pasar:** la app **no se cierra**. Verás el aviso **"Allow Nearby devices, or the bridge
cannot start."** al tocar el botón, y/o una notificación **silenciosa** "SeizureGuard: monitoring is not
working" con el texto "The bridge could not start. Open SeizureGuard and allow the Nearby devices
(Bluetooth) permission." No suena ni vibra. Con las notificaciones denegadas puede no verse nada.

**Qué anotar:** si se cerró la app, cuál de los textos viste, si algo sonó o vibró.

**Si falla:** un cierre inesperado o un sonido es grave: avisá a quien configuró el sistema. Después
devolvé el permiso.

## DV-4 — Estado congelado o fuente equivocada

**Qué probás:** que el reloj se entere (y lo muestre) si OSD deja de analizar.

**Cómo:**
1. Todo listo y andando.
2. **Parte 1:** en OSD cambiá la **fuente de datos** a otra (por ejemplo "Phone"). Arrancá el cronómetro.
3. Mirá la pantalla del reloj y la notificación del teléfono.
4. Devolvé la fuente a "Garmin". Esperá a que el reloj vuelva a "Monitoreo activo".
5. **Parte 2:** cerrá OSD del todo y repetí la observación.

**Qué tiene que pasar:** el reloj muestra **"⚠ MONITOREO DEGRADADO"** (ámbar) en unos **75 segundos** o
menos (60 s si se corta el enlace; el techo firmado para OSD congelado es ≈ 75 s, calculado, no medido). El
teléfono muestra una notificación **silenciosa**, por ejemplo "OSD is not accepting watch data. In
OpenSeizureDetector, set the data source to Garmin." u "OpenSeizureDetector is open but not analysing the
watch data. Open the app and check that its data source is Garmin." Nada suena ni vibra.

**Qué anotar:** segundos hasta DEGRADED en el reloj y hasta la notificación; si algo sonó o vibró.

**Si falla:** si el reloj sigue diciendo "Monitoreo activo" pasado ≈ 75 s, es grave (el sistema "parece
sano" y no lo está): avisá a quien configuró el sistema.

## DV-7 — Inyección de fallas (una por vez)

**Qué probás:** que cada falla se vea, sin ruido, y cuánto tarda.

**Cómo:** hacé cada una **por separado**. Entre una y otra, dejá todo andando de nuevo y esperá
"Monitoreo activo" en el reloj. Para cada una, arrancá el cronómetro al provocarla.
- **(a)** Matar el "oyente" de mensajes del puente (requiere ayuda de quien armó el sistema; si no se puede,
  dejala como pendiente).
- **(b)** Cerrar OSD.
- **(c)** **Forzar la detención** de "SeizureGuard Companion" (Ajustes → Aplicaciones → Forzar detención).
- **(d)** Activar **No molestar** en el teléfono de la persona.
- **(e)** Denegar las **notificaciones** de "SeizureGuard Companion".

**Qué tiene que pasar:** cada una queda como falla visible (el reloj dice "⚠ MONITOREO DEGRADADO",
notificación silenciosa en el teléfono) **sin sonido ni vibración**. En **(c)** no se espera ningún aviso ni
resumen a la mañana (límite conocido y aceptado).

**Qué anotar:** por cada letra, segundos hasta la falla visible y si algo sonó. En **(a)** anotá también si
el sistema **se recupera solo** (hay un reintento que nunca se probó en hardware).

**Si falla:** cualquier sonido o vibración ante una falla, o una falla que nunca se ve: avisá.

## DV-2 — Reinicio del teléfono

**Qué probás:** qué pasa con el puente cuando el teléfono se reinicia.

**Cómo:**
1. Con el puente andando, **reiniciá el teléfono de la persona**.
2. Esperá 2 minutos **sin tocar nada**.

**Qué tiene que pasar:** o el puente **se reanuda solo**, o aparece una notificación silenciosa
"SeizureGuard: monitoring is not working" con "The phone restarted and the bridge could not start by itself.
Tap here, then tap Start bridge."

**Qué anotar:** cuál de las dos pasó, el modelo del teléfono y la versión de Android, y el tiempo.

**Si falla:** si no pasa ninguna de las dos (el puente no vuelve y no hay aviso), avisá. Si aparece el aviso,
tocá "Start bridge" para recuperar.

---

# C. Lo que ve el cuidador

## DV-9 — Lo que ve el cuidador (cinco chequeos cortos)

Cada uno se hace por separado. Todos son avisos **silenciosos**: si suenan o vibran, anotalo.

**9a — Primer "MONITOREO DEGRADADO" (~80 s)**
1. En "SeizureGuard Companion" tocá **"Stop bridge"** (debe aparecer "Bridge stopped").
2. En el reloj tocá **"Iniciar monitoreo"** y arrancá el cronómetro.
3. **Qué tiene que pasar:** durante el primer minuto el reloj no juzga; a los **80 segundos más o menos**
   la pantalla dice **"⚠ MONITOREO DEGRADADO"** (ámbar) y la notificación del reloj repite ese título con
   "Sin datos o sin conexión al teléfono — revisá el reloj". Sin vibración.
4. **Anotar:** segundos hasta el cartel. **Si falla:** si pasan más de 90 s sin cartel o el reloj vibra,
   avisá. Después tocá "Start bridge" otra vez.

**9b — MUTE: "SILENCIADO"**
1. Con todo andando, activá **MUTE** en OSD (el nombre y lugar del botón dependen de tu versión de OSD,
   **sin verificar**).
2. Esperá hasta 15 s.
3. **Qué tiene que pasar:** el reloj dice **"SILENCIADO, no avisa convulsiones"** (celeste) y **no vibra**.
   Sacudí el reloj como en DV-8: **no** tiene que sonar nada ni llegar el SMS (la prueba muestra por qué
   MUTE es peligroso).
4. **Anotar:** si apareció el cartel y cuánto tardó; si algo vibró o sonó. **Importante:** apagá MUTE al
   terminar. **Si falla:** si el reloj sigue diciendo "Monitoreo activo" con MUTE puesto, avisá.

**9c — "SeizureGuard: update needed" (opcional)**
Solo si tenés a mano una versión del reloj distinta a la del teléfono (una compilación vieja).
1. Instalá en el reloj una versión distinta a la del Companion y, con el puente andando, tocá
   "Iniciar monitoreo" en el reloj.
2. **Qué tiene que pasar:** en el teléfono de la persona aparece, **en silencio**, **"SeizureGuard: update
   needed"** con el texto "SeizureGuard on the watch and on this phone are different versions. Update both to
   the same version." (o, si el reloj no informa su versión, "SeizureGuard on the watch looks out of date.
   Update it to the latest version."). El envío de datos **no se corta**.
3. **Anotar:** el texto exacto y si sonó algo. **Si no tenés una versión distinta, marcá "no probado".**

**9d — Reinicio del teléfono con el puente**
Es la misma prueba que **DV-2** (arriba). Si ya la hiciste, copiá el resultado acá.

**9e — Resumen de la mañana (silencioso, ~8:00)**
1. Se mira **la mañana siguiente a la noche de DV-6**, con el puente andando toda la noche.
2. **Qué tiene que pasar:** cerca de las **8:00** aparece en el teléfono de la persona una notificación
   silenciosa **"SeizureGuard: last night"** con "No interruptions last night." (o, si hubo cortes,
   "Monitoring was interrupted N times (total M min): ..."). Sin sonido ni vibración.
3. **Anotar:** hora real de llegada y el texto. **Si falla:** si no llega, la noche queda **sin confirmar**
   (lo dice `CAREGIVER_GUIDE.md`): avisá a quien configuró el sistema. Tampoco debe llegar si hiciste
   "Forzar detención" del Companion esa noche.

---

# D. La noche completa

Estas dos van **al final**: recién cuando A, B y C estén anotadas.

## DV-5 — Batería del teléfono (puede esperar)

**Qué probás:** cuánta batería gasta el puente en una noche.

**Cómo:** una noche (8 h), teléfono al **100% y sin cargador** (solo para esta medición, y solo si el
teléfono aguanta la noche sin riesgo). Anotá el porcentaje al empezar y al terminar. Comparalo con otra noche
sin el Companion.

**Qué tiene que pasar:** un consumo razonable.

**Qué anotar:** % inicial y final de cada noche.

**Si falla:** si gasta mucho, avisá: se puede relajar el refresco de 10 s. **Esta prueba puede esperar**
(`docs/SAFETY_FINDINGS_WATCH_OSD.md`). Para DV-6, volvé a poner el teléfono a cargar.

## DV-6 — Noche completa de 8 horas

**Qué probás:** que todo aguanta una noche entera.

**Cómo:**
1. Hacé la preparación (checklist) por la noche. Teléfono de la persona **cargando**, reloj **cargado
   (+80%)** y bien puesto, teléfono del cuidador cerca, con el SMS configurado para sonar.
2. Dormí con todo andando. **Mantené los otros cuidados habituales**: esta noche es una prueba.
3. A la mañana revisá: la pantalla del reloj, las notificaciones y el resumen de las ~8:00 (DV-9e).

**Qué tiene que pasar:** sin cortes, sin falsas alarmas (o muy pocas), el reloj con batería, y el resumen
**"No interruptions last night."**

**Qué anotar:** cortes, falsas alarmas (cuántas y a qué hora), batería final del reloj y del teléfono, texto
del resumen.

**Si falla:** muchos cortes o falsas alarmas: avisá a quien configuró el sistema. La sensibilidad se ajusta
en **OSD**, no acá (ver `CLINICAL_SIGNOFF.md`).

---

## Hoja de resultados

Copiá esta tabla y completala. Una fila por prueba y, en DV-8b, **una fila por corrida**.
Resultado: **PASS** o **FALLA** (o "no probado").

| Prueba | Fecha | Resultado | Tiempos (s) | Notas (qué viste, qué sonó) |
|---|---|---|---|---|
| Paso previo (`/data` avanza) | | | | |
| DV-3 Frecuencia de datos | | | | |
| DV-8a Relay (OSD a reloj) | | | __ s | |
| DV-8b corrida 1 | | | (a) __ (b) __ (c) __ | |
| DV-8b corrida 2 | | | (a) __ (b) __ (c) __ | |
| DV-8b corrida 3 | | | (a) __ (b) __ (c) __ | |
| DV-8b corrida 4 | | | (a) __ (b) __ (c) __ | |
| DV-8b corrida 5 | | | (a) __ (b) __ (c) __ | |
| DV-1 Bluetooth denegado | | | | |
| DV-4 Estado congelado (parte 1: fuente) | | | | |
| DV-4 Estado congelado (parte 2: OSD cerrado) | | | | |
| DV-7a Oyente | | | | |
| DV-7b OSD cerrado | | | | |
| DV-7c Forzar detención | | | | |
| DV-7d No molestar | | | | |
| DV-7e Notificaciones denegadas | | | | |
| DV-2 Reinicio del teléfono | | | | |
| DV-9a Degradado (~80 s) | | | | |
| DV-9b MUTE | | | | |
| DV-9c Update needed (opcional) | | | | |
| DV-9d Reinicio con el puente | | | | |
| DV-9e Resumen ~8:00 | | | | |
| DV-5 Batería (puede esperar) | | | | |
| DV-6 Noche completa | | | | |

Datos fijos para anotar arriba de la tabla: versión (commit) del APK del reloj y del Companion, modelo del
teléfono y versión de Android, versión de OSD.

## Cómo me pasás los resultados

Copiá y pegá la tabla completa, los tiempos y los textos que viste. Cómo se interpretan y cuándo se marca
cada fase como lista está en `HARDWARE_RUNBOOK.md`, **sección 8**. Se da por validado el sistema recién
cuando **DV-1 a DV-9 pasaron** (DV-5 puede esperar) y, en especial, **DV-8b cumple los 40 s**.
