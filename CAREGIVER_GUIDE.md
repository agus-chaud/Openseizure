# Guía para el cuidador — SeizureGuard

> Esta guía está escrita para que la entiendas rápido, incluso medio dormido a las 3 de la mañana.
> **Leela completa una vez, con calma, antes de la primera noche.** Imprimila si podés.

---

## ⚠️ Lo más importante, antes que nada

**SeizureGuard es una ayuda, NO una garantía.** Es un proyecto libre y gratuito, **no es un aparato
médico aprobado**. Puede avisar de una convulsión, pero **puede fallar**: a veces no detecta una
convulsión real, y a veces suena sin que pase nada. **No reemplaza el cuidado médico ni tu propia
atención.**

Hablá siempre con el médico de la persona sobre qué hacer ante una convulsión. Si las indicaciones
de esta guía difieren de las de tu médico, **hacé caso al médico**.

### ⚠️ Este sistema todavía NO fue probado completo con el equipo real

El sistema completo (reloj + aplicación del teléfono + OpenSeizureDetector) **todavía no se probó
de punta a punta con el reloj y los teléfonos reales**. Las pruebas en el equipo real están
pendientes: el reloj y la aplicación del teléfono ya están construidos, pero nadie los probó
todavía con el equipo real. Por eso:

- **No confíes solo en este sistema.** Mantené cualquier otro método de control o de aviso que ya
  uses (escucharla, dormir cerca, otro aparato, lo que te indique el médico).
- Esta guía describe cómo **debe** funcionar. Hasta que se pruebe en la vida real, tomalo con cautela.

---

## ¿Qué es este sistema? (en un párrafo)

Son **tres piezas** del lado de la persona que duerme. **1)** Un **reloj inteligente** en su
muñeca, que siente los movimientos. **2)** Una **aplicación en su teléfono** (se llama
"SeizureGuard Companion") que le pasa esos movimientos a la tercera pieza. **3)** Otra aplicación
en ese mismo teléfono, **OpenSeizureDetector**, que es la que decide si parece una convulsión,
hace sonar la alarma y **manda un mensaje de texto (SMS)** a **tu** teléfono. Vos usás un teléfono
**distinto**: el que recibe el mensaje.

```
Persona durmiendo con el reloj  →  su teléfono (2 aplicaciones)
        →  si parece una convulsión  →  alarma en SU teléfono + SMS a TU teléfono
```

---

## Qué vas a notar, y qué significa

Solo hay dos cosas que deben interrumpirte, y las dos son por una **emergencia**:

- **En TU teléfono:** llega un **SMS** avisando de una posible convulsión.
- **En el teléfono de la persona:** suena una **alarma fuerte** (si está cerca tuyo, la vas a oír).

Además, **en el reloj de la persona**:
- **Una vibración corta y suave** = *aviso* (puede no ser nada; el sistema está "prestando atención").
- **Una vibración fuerte y repetida** = *alarma* (el sistema cree que hay una convulsión).

**Nada más debería avisar.** Ni las fallas, ni los problemas, ni los cortes. Esto es a propósito
(ver más abajo). Vale para el **teléfono** (la app "SeizureGuard Companion") y para el **reloj**:
una falla no suena ni vibra en ninguno de los dos.

> **Dónde se ve una falla en el reloj:** solo en la pantalla, sin ruido ni vibración. Si abrís la
> app SeizureGuard en el reloj y dice **"⚠ MONITOREO DEGRADADO"** (en ámbar), el sistema no está
> protegiendo bien. La notificación del reloj dice lo mismo. Si el reloj muestra la carátula,
> **no hay ningún aviso**: hay que abrir la app o mirar las notificaciones.
>
> Si la pantalla dice **"ALARMA"** con un texto chico "⚠ Monitoreo degradado" debajo, la alarma es
> real: actuá igual y arreglá la falla después.

**Un aviso más, también silencioso, en el teléfono de la persona:** si el reloj y la aplicación del
teléfono ("SeizureGuard Companion") tienen **versiones distintas**, el teléfono muestra una
notificación que dice **"SeizureGuard: update needed"** (está en inglés), sin sonido ni vibración.
El envío de datos **no se corta**, pero con versiones distintas **no está garantizado que la
detección funcione bien**. No es una emergencia: arreglalo **de día**, no a las 3 de la mañana.

> 👉 El SMS solo hace ruido en tu teléfono **si vos lo dejaste configurado para eso**. Mirá la
> sección "Tu teléfono" más abajo. Es lo más fácil de olvidar.

---

## Qué hacer cuando suena la alarma o llega el SMS

Mantené la calma e **andá a ver a la persona.** Pautas generales de primeros auxilios para una
convulsión (confirmá estas con tu médico):

1. **Quedate con la persona** y mirá la hora (cuánto dura la convulsión es un dato importante).
2. **Ponela de costado** (de lado) con cuidado, para que respire mejor y no se ahogue con saliva.
3. **Apartá objetos duros** que tenga cerca para que no se golpee. Aflojá ropa apretada en el cuello.
4. **NO** la sujetes a la fuerza para frenar los movimientos.
5. **NO** le pongas nada en la boca (ni dedos, ni cuchara, ni agua).
6. Hablale tranquilo. La mayoría de las convulsiones pasan solas en 1–2 minutos.
7. **Llamá a emergencias** si: la convulsión **dura más de 3 minutos**, se repite una tras otra, la
   persona no respira o no se despierta después, se lastimó, o si tu médico te indicó llamar siempre.

> Estos son lineamientos generales y ampliamente aceptados, pero **cada persona es distinta**:
> seguí el plan que te haya dado el neurólogo o médico tratante.

### Si fue una falsa alarma

Va a pasar a veces (un movimiento brusco al dormir puede dispararla). No te enojes con el sistema:
**es preferible una falsa alarma de más que una convulsión sin avisar.** Si hay demasiadas falsas
alarmas y te agotan, avisale a quien configuró el sistema: se puede ajustar la sensibilidad.

---

## Las fallas son silenciosas: por qué y qué significa para vos

**Decisión tomada a propósito:** si el sistema falla (se cae una aplicación, se corta la conexión
del reloj, etc.), **no suena ni vibra nada**, ni en el teléfono ni en el reloj. Lo único que debe
interrumpirte es una emergencia real. Así se evita despertarte por problemas técnicos.

**La consecuencia, dicha sin vueltas: un sistema roto se ve igual que una noche tranquila.**
Si algo se cae, nadie se entera en el momento. Por eso hacen falta **dos hábitos**:

1. **Antes de dormir:** el checklist de abajo (2 minutos).
2. **A la mañana:** leer el resumen de la noche (ver más abajo).

---

## Cuándo el sistema NO te protege

Estas situaciones dejan al sistema sin funcionar, **y no avisan**:

- **OpenSeizureDetector** (la aplicación del teléfono de la persona) está **cerrada** o configurada
  en una fuente de datos equivocada.
- La aplicación **"SeizureGuard Companion"** está **detenida** o se cerró a la fuerza.
- El **teléfono de la persona** está **apagado**, sin batería, o con el **volumen de alarma en
  cero** o en modo **No molestar** que tapa la alarma.
- **Batería baja en el teléfono de la persona:** OpenSeizureDetector pasa a "falla" y, con una
  falla activa, **una convulsión puede NO generar alarma**. Por eso el teléfono duerme **cargando**.
- El **reloj** está **fuera de la muñeca**, sin batería, o **sin conexión** con el teléfono.
- El modo **MUTE** (silencio) de OpenSeizureDetector está activo: **tapa las alarmas y cancela el
  SMS** mientras dure.
- Al iniciar la aplicación del teléfono se **negó un permiso** (Bluetooth): no arranca, y **tampoco
  avisa fuerte**.
- **El teléfono se reinició** (por ejemplo, se quedó sin batería y se volvió a prender, o se
  actualizó solo) y la aplicación "SeizureGuard Companion" **no arrancó sola**. Queda una
  notificación silenciosa que dice **"SeizureGuard: monitoring is not working"** y, abajo, "The
  phone restarted and the bridge could not start by itself. Tap here, then tap Start bridge."
  **Hasta que toques "Start bridge", el sistema NO protege y no suena nada.** Revisá esa
  notificación siempre que el teléfono se haya reiniciado.
- **Tu teléfono** está en silencio o en No molestar sin la excepción para este contacto.
- El **reloj** y la aplicación **"SeizureGuard Companion"** del teléfono tienen **versiones
  distintas**: el sistema sigue mandando datos, pero **no está garantizado que se entiendan bien**.
  Avisa con la notificación **"SeizureGuard: update needed"**, sin sonido.
- Convulsiones **sin mucho movimiento** (ausencias, algunas focales): **puede no detectarlas**.
  Detecta sobre todo las de **movimiento fuerte** (tónico-clónicas).

Además: **no llama solo a una ambulancia** (te avisa a vos y vos decidís) y **no es un
diagnóstico médico** (solo detecta un patrón de movimiento).

---

## Si el teléfono dice "SeizureGuard: update needed"

| Dice | Qué significa |
|---|---|
| "SeizureGuard on the watch and on this phone are different versions. Update both to the same version." | El reloj y el teléfono tienen versiones distintas de SeizureGuard. |
| "SeizureGuard on the watch looks out of date. Update it to the latest version." | El reloj no informa su versión: casi seguro tiene una versión vieja. Hay que actualizar el reloj. |

1. **Avisá a quien configuró el sistema** para instalar la **misma versión** en el reloj y en el
   teléfono. Hacelo **de día**, nunca con la persona durmiendo: mientras se actualiza, el sistema no
   protege.
2. **Después de actualizar**, con "SeizureGuard Companion" iniciada (**"Start bridge"**),
   **detené y volvé a iniciar el monitoreo en el reloj** ("Iniciar monitoreo"). El aviso se borra
   **solo** cuando el reloj vuelve a mandar su versión y coincide. Si actualizaste solo el teléfono,
   también hay que reiniciar el monitoreo del reloj.
3. **Puede que se pueda deslizar** (según la versión de Android); si desapareció pero no
   actualizaste los dos, volvé a revisar las versiones. Si sigue ahí después del paso 2, las
   versiones todavía no coinciden o el reloj no reenvió su versión: avisá a quien configuró el
   sistema.
4. Mientras el aviso esté puesto, tratá la noche como **no confirmada**: extremá los otros cuidados
   y repetí **la prueba de día**.
5. Si tocás **"Stop bridge"**, el aviso **queda puesto** a propósito, hasta que las versiones
   coincidan.

---

## Checklist antes de dormir (2 minutos)

### En el teléfono de la persona

- [ ] **OpenSeizureDetector** está **abierta**, con la fuente de datos en **"Garmin"**.
- [ ] En los ajustes de OpenSeizureDetector, la opción **"Enable Audible System FaultWarnings"**
      está **DESACTIVADA** (si no, la aplicación pita sola por sus fallas y rompe el silencio
      acordado).
- [ ] **"SeizureGuard Companion"** está iniciada: se abre la aplicación y se toca **"Start bridge"**
      (la primera vez pide permisos: aceptalos, y también **"Allow running in the background"**).
- [ ] El teléfono está **cargando** durante toda la noche.
- [ ] El **volumen de alarma** está alto y **No molestar** no está tapando la alarma.
- [ ] **MUTE** no está activo en OpenSeizureDetector.
- [ ] **No** hay una notificación **"SeizureGuard: monitoring is not working"**. Puede decir, por
      ejemplo, "The bridge could not start. Open SeizureGuard and allow the Nearby devices
      (Bluetooth) permission." o "The phone restarted and the bridge could not start by itself. Tap
      here, then tap Start bridge." También hay avisos parecidos de OpenSeizureDetector, como
      "OSD is not accepting watch data. In OpenSeizureDetector, set the data source to Garmin."
      Si hay alguno, arreglalo **antes** de dormir.
- [ ] **No** hay una notificación **"SeizureGuard: update needed"**. Si está, el reloj y el teléfono
      tienen versiones distintas: arreglalo **antes** de dormir (ver la sección con ese nombre).

### En el reloj

- [ ] Está **cargado** (idealmente +80%) y **bien puesto** en la muñeca.
- [ ] La aplicación **SeizureGuard** está **andando** (dice "Monitoreo activo"; si dice
      "Monitoreo inactivo", tocá "Iniciar monitoreo"). "Monitoreo activo" solo vale si **no**
      aparece "⚠ MONITOREO DEGRADADO".
- [ ] Después de tocar "Iniciar monitoreo", **esperá 2 minutos** y **mirá la pantalla de nuevo**
      antes de dormir. El reloj no juzga durante el primer minuto, así que un problema recién
      aparece como "⚠ MONITOREO DEGRADADO" a los 80 segundos más o menos.
- [ ] La pantalla **no** dice **"⚠ MONITOREO DEGRADADO"** (ámbar). Si lo dice, algo falló (sensor,
      conexión con el teléfono, u OpenSeizureDetector detenido o congelado): revisá el reloj y el
      teléfono. Puede tardar hasta 1 minuto y medio en aparecer.
- [ ] Si dice **"Falla del sistema (OSD) — sin vibración"** (violeta claro), OpenSeizureDetector informa
      una falla propia. No es una convulsión y el reloj no vibra. Revisá el teléfono: mientras dure
      esa falla, una convulsión podría no generar alarma.
- [ ] Si la pantalla dice **"SILENCIADO, no avisa convulsiones"** (celeste), OpenSeizureDetector
      está en **MUTE**: ante una convulsión **nada va a avisar** (ni el reloj, ni el teléfono, ni el
      SMS). Sacá MUTE en OSD.
- [ ] Si arriba de la pantalla dice **"VERSIÓN DE PRUEBA"**, ese reloj tiene la versión de
      desarrollo, no la normal: instalá la versión normal.

### En TU teléfono (el del cuidador)

- [ ] Está **cargado** y **cerca tuyo**.
- [ ] Los mensajes del número del teléfono de la persona **suenan aunque el teléfono esté en
      silencio o en No molestar** (ver "Tu teléfono").
- [ ] Hiciste **la prueba** al menos una vez (ver más abajo).

---

## Tu teléfono: que el SMS te despierte

El sistema **no puede** cambiar cómo suena tu teléfono. Eso lo tenés que dejar armado vos:

1. Guardá el número del teléfono de la persona como **contacto**.
2. Configurá ese contacto como **prioritario / favorito** y permití que **suene aunque el teléfono
   esté en silencio o en "No molestar"** (en Android suele estar en Ajustes → Notificaciones →
   No molestar → Excepciones / Personas). Los nombres cambian según la marca del teléfono.
3. **Probalo:** poné tu teléfono en silencio (o No molestar) y pedí que te manden un SMS desde el
   teléfono de la persona. **Si no sonó fuerte, no está bien configurado.**
4. Dejá el teléfono **cargando y con la pantalla apagada** al lado de la cama.

---

## A la mañana: el resumen de la noche

Cada día, **a las 8:00 (aprox.)**, el **teléfono de la persona** muestra una notificación
**silenciosa** (sin sonido ni vibración) que se llama **"SeizureGuard: last night"**. Está en inglés.
Se lee así:

| Dice... | Significa... |
|---|---|
| **"No interruptions last night."** | La noche no tuvo cortes. Buena señal (no es garantía). |
| **"Monitoring was interrupted N times (total M min): ..."** | Hubo **N cortes** y en total **M minutos sin protección**. Lo que sigue explica la causa. |

Causas que puede mencionar:

| Dice | Qué pasó, en criollo |
|---|---|
| "watch not sending data" | El reloj dejó de mandar datos (sacado de la muñeca, apagado, lejos o sin batería). |
| "OSD not reachable" | OpenSeizureDetector no estaba recibiendo (cerrada o trabada). |
| "OSD wrong data source" | OpenSeizureDetector estaba en la fuente de datos equivocada. |
| "OSD rejected data" | OpenSeizureDetector rechazó los datos del reloj. |
| "OSD not analysing" | OpenSeizureDetector estaba abierta pero sin analizar. |
| "phone stopped" | La aplicación del teléfono se detuvo un rato. |

Puede aparecer además **en una línea aparte**: **"Note: the watch and phone apps were on different
versions. Update both."** Significa que durante la noche el reloj y el teléfono tenían versiones
distintas de SeizureGuard. **No cuenta como corte** (por eso puede aparecer junto a "No
interruptions last night."), pero la noche **no está confirmada**: seguí los pasos de "Si el
teléfono dice 'SeizureGuard: update needed'". En la notificación cerrada esa línea puede quedar
oculta: **abrila (expandila)** para leerla completa.

**Si el teléfono se reinició durante la noche** y la aplicación no arrancó sola, puede que no haya
resumen y que lo único que veas sea la notificación **"SeizureGuard: monitoring is not working"**
("The phone restarted and the bridge could not start by itself. Tap here, then tap Start bridge.").
Tocá "Start bridge" y tratá esa noche como **sin protección**.

**⚠️ El resumen NO está garantizado.** No llega si la aplicación "SeizureGuard Companion" se cerró
a la fuerza, si el teléfono estaba apagado, si alguien tocó **"Stop bridge"**, o si las
notificaciones están bloqueadas. Además puede ser que solo avise de una parte de los cortes.

### Qué hacer a la mañana

- [ ] **Leé el resumen** en el teléfono de la persona.
- [ ] **Si dice "No interruptions last night."** → seguí normal.
- [ ] **Si muestra cortes** → hubo minutos sin protección. No hace falta asustarse, pero:
  revisá la causa (tabla de arriba), arreglala, y **avisá a quien configuró el sistema**. Esa
  noche, extremá los otros cuidados.
- [ ] **Si NO hay resumen** → **no sabés cómo fue la noche.** Tratalo como una noche **sin
  confirmar**: abrí "SeizureGuard Companion" y tocá **"Start bridge"**, revisá OpenSeizureDetector
  y el reloj, y avisá a quien configuró el sistema.
- [ ] **Si viste "⚠ MONITOREO DEGRADADO" en la pantalla o en las notificaciones del reloj** → algo
  falló: mismo procedimiento.
- [ ] **Si el resumen trae la línea "Note: ... different versions"** → versiones distintas:
  actualizá reloj y teléfono de día (ver "Si el teléfono dice 'SeizureGuard: update needed'") y
  avisá a quien configuró el sistema.

---

## Si tenés dudas

**Si no estás seguro de que el sistema esté funcionando, asumí que NO lo está** y usá tus otros
métodos de cuidado esa noche.

Quien configuró el sistema (a quien avisar ante cualquier corte o duda):

**Nombre / teléfono:** ______________________________________

---

## Hacé una prueba de día antes de confiar de noche

Con quien configuró el sistema, **de día**: que alguien agite el reloj con movimiento fuerte y
rítmico unos 30 segundos y verificá que **suena la alarma en el teléfono de la persona** y que
**el SMS te llega a vos, con tu teléfono en silencio**. Avisá a todos antes de la prueba: el SMS
sale de verdad. Mejor descubrir un problema un martes a la tarde que un sábado a las 3 de la mañana.

Gracias por cuidar. Este sistema existe para darte una mano, pero **la persona más importante de
todo el sistema sos vos.**
