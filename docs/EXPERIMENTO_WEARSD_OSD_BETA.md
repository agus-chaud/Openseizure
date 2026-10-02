# Experimento: WearSD + OSD beta — probar que la cadena reloj → teléfono → OSD funciona

> Esto es un **experimento de validación descartable**, no la arquitectura final. Sirve para
> contestar una sola pregunta ("¿puede este hardware mover datos del reloj a OSD de punta a
> punta?") y después se tira. La arquitectura que sí queremos construir es la **Opción F** (una
> app companion propia en el teléfono que reenvía a OSD por HTTP, igual que hace Garmin), y vive
> en `openspec/changes/watch-osd-message-delivery/`.

> ⚠️ **Este experimento todavía NO se corrió.** El proyecto lo tenía como requisito ("GATE-0")
> antes de empezar a escribir código de la Opción F, pero el dueño del proyecto no tuvo acceso al
> reloj físico durante alrededor de una semana. En vez de frenar todo el trabajo a la espera del
> reloj, tomó la decisión explícita e informada de **asumir GATE-0 como PASS y seguir adelante**
> con la implementación (`DEC-052`, "override de GATE-0"). No es un descuido ni algo que se haya
> olvidado hacer: fue una aceptación de riesgo consciente, documentada en `DECISIONS.md`
> (`DEC-052`) y en `HARDWARE_RUNBOOK.md` sección 4.
>
> Lo que sigue sin confirmarse en hardware real es específicamente si dos apps SeizureGuard
> firmadas igual pueden intercambiar mensajes por la Wear Data Layer **en este Galaxy Watch 8**
> (Graham, el autor de OSD, solo lo probó en un Watch 7). Ese riesgo puntual se
> arrastró hasta el lote de trabajo del lado del reloj ("Batch 7"), que **ya se escribió y está en
> `main`** sin que este experimento se corriera: la confirmación en hardware sigue pendiente (DV-1..DV-7
> en `HARDWARE_RUNBOOK.md`). Los pasos de abajo siguen siendo los correctos para cuando tengas el reloj
> a mano.

---

## Qué es este experimento y por qué lo hacemos

Cuando le mandamos el reporte del bloqueante de campo a **Graham Jones** (el que mantiene
OpenSeizureDetector, que de acá en más llamamos **OSD**), su respuesta fue: antes de invertir en
arreglar el envío propio de SeizureGuard, corré **mi** setup y comprobá que la cadena funciona.
Ese setup es:

- **WearSD**: la app de reloj liviana y oficial de OSD (un proyecto aparte de SeizureGuard).
- **OSD beta**: la app de OSD en el teléfono, compilada desde su rama de pruebas.

Una **rama** (branch) es una línea de desarrollo paralela dentro de un repositorio git: `beta` es
donde OSD prueba features nuevas antes de pasarlas a la versión pública. WearSD habla con OSD por
la rama `beta` porque ahí está el soporte de reloj más al día.

**El objetivo es uno solo:** demostrar que en *este* hardware (reloj + teléfono) los datos del
acelerómetro pueden viajar del reloj a OSD y que OSD los procesa. Si funciona, confirma que el
**único** problema de SeizureGuard es la **identidad de la app** (nombre de paquete + certificado
de firma), y no algo más profundo en el transporte o en el reloj. Si ni siquiera funciona con
WearSD, hay algo más en juego y hay que volver a escribirle a Graham.

Graham corre esto en un **Samsung Galaxy Watch 7**; vos tenés un **Galaxy Watch 8**. Son
parecidos pero no idénticos, así que el paso 0 chequea la compatibilidad antes de empezar.

Comentario de referencia (la discusión donde Graham lo propuso):
<https://github.com/orgs/OpenSeizureDetector/discussions/69#discussioncomment-18117824>

---

## La idea clave que hace que esto funcione

La **Wear Data Layer** —el canal por el que se hablan una app de reloj y una app de teléfono—
tiene una regla de seguridad que no se puede desactivar: **solo entrega mensajes entre dos apps
que compartan las dos cosas a la vez**:

1. El mismo **nombre de paquete** (el identificador único de la app).
2. El mismo **certificado de firma**.

Google llama a ese par (paquete + firma) el **"AppKey"**. Es exactamente lo que hizo fallar la
prueba de campo de junio 2026 (ver `DECISIONS.md`, **DEC-050**): SeizureGuard
(`com.seizureguard.wear`, firmado con una clave propia) y OSD (`uk.org.openseizuredetector`,
firmado con la clave privada de Graham) no comparten ninguna de las dos, así que Google Play
Services tira todos los mensajes del reloj a la basura antes de que OSD los vea.

WearSD ya resuelve la mitad del problema: **usa el mismo nombre de paquete que OSD**
(`uk.org.openseizuredetector`). Lo único que falta es la **misma clave de firma**. Y acá está el
truco del experimento:

> **Firmar** una app es sellarla con una firma digital (un certificado criptográfico) que prueba
> quién la construyó; Android no instala ninguna app sin firma. La primera vez que compilaste
> *cualquier* app en esta computadora, Android Studio generó solo una firma de desarrollo —la
> **debug key** ("clave de depuración")— y la guardó en un archivo:
> `%USERPROFILE%\.android\debug.keystore`. Desde entonces, **todo** lo que compilás en esta
> misma máquina se firma automáticamente con esa misma debug key, sin que tengas que hacer nada.

Por eso este experimento **compila las dos apps desde el código fuente**, en la **misma Android
Studio** y en la **misma computadora**: así las dos quedan firmadas con la misma debug key, y con
eso más el nombre de paquete compartido, el AppKey coincide y los mensajes pasan.

Si en cambio te bajaras el APK de release de OSD ya hecho de GitHub, ese archivo está firmado con
la clave **privada** de Graham —que no tenemos— y el AppKey **no** coincidiría. Descargar el APK
listo no sirve para este experimento.

---

## Términos que vas a ver (referencia rápida)

| Palabra | Qué significa, simple |
|---|---|
| **rama / branch** | Línea de desarrollo paralela dentro de un repo git. Acá usamos la rama `beta` de OSD y la `main` de WearSD. |
| **compilar / build** | Convertir el código fuente en un archivo `.apk` instalable (el `.apk` es el equivalente Android de un `.exe`). |
| **firmar** | Sellar el `.apk` con un certificado digital que dice quién lo construyó. Android no instala nada sin firma. |
| **debug keystore** | El archivo `%USERPROFILE%\.android\debug.keystore` con la firma de desarrollo que Android Studio generó solo la primera vez que compilaste algo. Todo lo que compilás en esta máquina se firma con ella. |
| **ADB** | "Android Debug Bridge": un programa de la PC que le da órdenes a un dispositivo Android desde la consola. Al Watch 8 le habla por WiFi (no tiene puerto USB). |
| **sideload** | Instalar una app sin pasar por la tienda oficial (Play Store). Compilar y correr desde Android Studio es una forma de sideload. |
| **foreground service** | "Servicio en primer plano": un proceso que Android mantiene vivo mientras muestra una notificación permanente, para que el sistema no lo mate con la pantalla apagada. |
| **logcat** | El registro de mensajes en vivo que escupe un dispositivo Android. Es como mirar los `print()` / logs de una app mientras corre. |
| **data source** | En OSD, la "fuente de datos": el origen desde el que OSD lee el movimiento. Puede ser un reloj Garmin, un Pebble, un reloj Wear OS, etc. |

---

## Paso a paso

### 0. Chequeo previo de compatibilidad

WearSD declara `minSdk 36` (Android 16 / Wear OS 6). El Galaxy Watch 8 corre Wear OS 6, así que
**en principio** es compatible — pero confirmalo antes de invertir tiempo en compilar. En el
reloj:

```
Ajustes → Acerca del reloj → Información de software
```

Anotá la versión de **Wear OS / One UI Watch** que figura ahí. Si fuera anterior a **Wear OS 6**,
WearSD no va a instalar (Android lo rechaza por `minSdk`), y hay que avisarle a Graham antes de
seguir.

### 1. Requisitos

- **Android Studio** instalado en la PC (la misma instalación se usa para las dos apps — es
  clave, ver la sección de arriba).
- El **Samsung Galaxy Watch 8**.
- Un **teléfono Android**.
- Reloj y teléfono **emparejados** por la app **Galaxy Wearable** del teléfono (el emparejamiento
  normal de reloj↔teléfono, el mismo que usás para que lleguen las notificaciones).
- **ADB Wireless** habilitado en el reloj. El Watch 8 no tiene puerto USB, así que la PC le habla
  por WiFi. No repito los pasos acá: están completos en **`HARDWARE_RUNBOOK.md`**, sección 2
  ("Conectar el reloj a tu PC (por WiFi)") — modo desarrollador, depuración inalámbrica, pareo y
  `adb connect`.

### 2. Traer el código de OSD (rama `beta`)

El código de OSD vive en el repo `Android_Pebble_SD`. Para bajarlo y pararte en la rama `beta`:

```powershell
git clone https://github.com/OpenSeizureDetector/Android_Pebble_SD
cd Android_Pebble_SD
git checkout beta
```

> **Nota importante:** si ya tenés un clon local de OSD
> (`<carpeta-del-clon-de-OSD>`), parado en la rama `beta`, en vez de clonar de
> nuevo reusá ese y traé los últimos cambios:
> ```powershell
> cd <carpeta-del-clon-de-OSD>
> git checkout beta
> git pull
> ```

### 3. Compilar e instalar OSD en el teléfono desde Android Studio

1. Abrí Android Studio y abrí el proyecto de OSD (la carpeta del paso 2).
2. Esperá a que **Gradle sincronice** (la barra de progreso de abajo; Gradle es la herramienta
   que arma el proyecto). La primera vez puede tardar varios minutos porque descarga
   dependencias.
3. Conectá el **teléfono** y seleccionalo como destino en la barra de arriba.
4. Apretá el botón **Run** (el triángulo verde).

**Compilar** convierte el código fuente en un `.apk` instalable, y Android Studio lo **firma solo
con la debug key** de esta máquina — que es justo lo que necesitamos para que el AppKey coincida
con WearSD.

> No compiles OSD por línea de comandos. Regla del proyecto: el build lo hacés siempre desde
> Android Studio, con el botón Run. La línea de comandos se usa acá solo para `git` y, más
> adelante, para leer el `logcat`.

### 4. Traer y compilar WearSD en el reloj

El código de WearSD vive en su propio repo, rama `main` (la rama principal):

```powershell
git clone https://github.com/OpenSeizureDetector/WearSD
```

Abrí ese proyecto en Android Studio. **Puede ser otra ventana**, pero **tiene que ser la misma
instalación de Android Studio en la misma computadora** que usaste para OSD en el paso 3. Ese es
el punto crítico del experimento: solo así las dos apps se firman con la misma debug key
(`%USERPROFILE%\.android\debug.keystore`) y el AppKey termina coincidiendo.

1. Esperá la sincronización de Gradle.
2. Seleccioná el **reloj** como destino (aparece vía ADB Wireless, si seguiste el paso 1).
3. Apretá **Run**. Android Studio compila WearSD, lo firma con la debug key e instala en el reloj
   (esto es un **sideload**: instalar sin pasar por la Play Store).

### 5. Confirmar el Bluetooth

Abrí **Galaxy Wearable** en el teléfono y verificá que el reloj figure como **conectado**.

El reloj y el teléfono se hablan **siempre por Bluetooth**, nunca por cable (el Watch 8 ni
siquiera tiene puerto de datos). La **Wear Data Layer** —el canal de mensajes entre las dos
apps— viaja *por adentro* de ese emparejamiento Bluetooth. Si el reloj no figura conectado en
Galaxy Wearable, ningún dato va a llegar, por más bien compiladas que estén las apps.

### 6. Configurar OSD en el teléfono

1. Abrí OSD en el teléfono.
2. Entrá al **modo desarrollador**: tocá el número de versión de la app varias veces seguidas
   (igual que se activa el modo desarrollador de Android tocando "Número de compilación").
3. Activá el **data source "Android Wear"** (solo para este experimento, que prueba el camino directo; SeizureGuard en uso real usa **"Garmin"**).

Un **data source** en OSD es el origen desde el que lee el movimiento; "Android Wear" le dice a
OSD "esperá los datos de un reloj Wear OS por la Data Layer".

### 7. Arrancar WearSD en el reloj

1. Abrí la app WearSD en el reloj.
2. Concedé los permisos que pida: **sensores corporales / frecuencia cardíaca** y
   **notificaciones**.
3. Confirmá que aparece la **notificación permanente** del servicio. Esa notificación es la señal
   de que arrancó un **foreground service**: un proceso que Android mantiene vivo con la pantalla
   apagada justamente porque tiene esa notificación visible. Sin ella, el sistema mataría el
   monitoreo apenas se apaga la pantalla.

### 8. Verificar que conectó

Mirá la pantalla de OSD en el teléfono:

- ❌ Sigue mostrando **"Data source fault"** → todavía no conectó. Andá al paso 10.
- ✅ Deja de mostrar "Data source fault" y **empieza a mostrar el porcentaje de batería del
  reloj** → **conectó**. Ese número de batería es la prueba de que el handshake entre las dos
  apps se completó: OSD no podría saber la batería del reloj si los mensajes no estuvieran
  llegando.

### 9. Verificar que llegan datos de verdad

Que muestre la batería confirma el handshake, pero falta ver que fluye el acelerómetro. Agarrá el
reloj y **agitalo con un movimiento rítmico** unos segundos. Mirá si OSD reacciona: la curva de
aceleración en pantalla se mueve, o cambia el estado que muestra. Si reacciona, la cadena
completa **reloj → teléfono → OSD** funciona.

### 10. Si NO funciona — diagnóstico

Leé el **logcat** del teléfono filtrando por la parte de OSD que recibe los datos del reloj. El
`logcat` es el registro de mensajes en vivo del dispositivo; con `adb` conectado al teléfono (ver
`HARDWARE_RUNBOOK.md` sección 5):

```powershell
C:\Android\platform-tools\adb.exe -s <id_telefono> logcat -s SdDataSourceAw:D
```

Qué buscar:

- **Aparece `onMessageReceived` con un path tipo `/osd/...`** → los mensajes **LLEGAN**. Éxito
  del experimento: la cadena funciona y el único problema de SeizureGuard es la identidad de app
  (nombre de paquete + firma), nada más.
- **Aparece `Failed to deliver message to AppKey[...]` desde `com.google.android.gms.persistent`**
  → las firmas **NO coinciden**. Causa típica: OSD se instaló desde un APK de release en vez de
  compilarlo, o las dos apps se compilaron en máquinas / instalaciones de Android Studio
  distintas. Recompilá OSD desde el código (paso 3) en la **misma** Android Studio que WearSD.
- **No aparece ninguna de las dos** → revisá el checklist de siempre:
  - Reloj y teléfono emparejados en Galaxy Wearable (paso 5).
  - Data source **"Android Wear"** activado en OSD (paso 6; solo para este experimento: SeizureGuard usa **"Garmin"**).
  - Permisos concedidos en WearSD (paso 7).
  - WearSD efectivamente **corriendo** — la notificación permanente tiene que estar visible en el
    reloj.

### 11. Límites del experimento

- **Autonomía corta.** Con WearSD la batería del reloj dura **~6 horas** (dato de Graham, en la
  discusión enlazada arriba). No alcanza para una prueba nocturna completa de 8 horas. Este
  experimento sirve **solo** para confirmar que la cadena de datos funciona, no para evaluar una
  noche real.
- **Sin watchdog.** WearSD no tiene el watchdog de SeizureGuard (la alerta local en el reloj si
  el flujo de datos se corta). Si el envío se cae en medio de la prueba, WearSD no te va a avisar
  — lo vas a notar recién al mirar OSD.

### 12. Qué hacemos con el resultado

> Nota de contexto (octubre 2026): la implementación de la Opción F ya se escribió completa y está
> en `main`, incluido el lote del reloj ("Batch 7"), sin esperar este experimento (ver la nota de
> GATE-0 al principio de este documento). Así que el resultado de acá ya no decide si se arranca o
> no — eso ya pasó — sino si se puede confirmar en hardware real lo que se venía asumiendo.

- **Funciona** (paso 9 OK, o `onMessageReceived` en el logcat) → confirma en hardware real, y en
  este Galaxy Watch 8 específico, que la cadena de transporte es sana y que la **Opción F**
  (companion app propia en el teléfono, bajo nuestra identidad, que reenvía a OSD por HTTP como
  hace Garmin) era la apuesta correcta. Con esto confirmado, lo que ya está en `main`
  (incluida la actualización del reloj, "Batch 7") queda respaldado por una prueba real.
- **No funciona ni con WearSD** → hay algo más en juego además del AppKey, algo que no se detectó
  al asumir GATE-0 como PASS. Frená antes de tocar más código del reloj y volvé a escribirle a
  Graham con el `logcat` completo del teléfono adjunto, describiendo en qué paso se cortó.

---

## Fuentes

- **WearSD** — repo de la app de reloj oficial de OSD:
  <https://github.com/OpenSeizureDetector/WearSD> (rama `main`).
- **OSD, rama `beta`** — <https://github.com/OpenSeizureDetector/Android_Pebble_SD> (`git checkout
  beta`); si ya tenés un clon local, usá ese.
- **Comentario de Graham** que propone este experimento:
  <https://github.com/orgs/OpenSeizureDetector/discussions/69#discussioncomment-18117824>
- [`DECISIONS.md`](../DECISIONS.md), **DEC-050** — causa raíz confirmada del fallo de entrega
  reloj→OSD: AppKey mismatch (package name + certificado de firma) en el Wear Data Layer.
- [`docs/GUIA_CONECTAR_RELOJ_TELEFONO.md`](GUIA_CONECTAR_RELOJ_TELEFONO.md) — explicación en
  criollo del mismo bloqueante y del flujo reloj↔teléfono↔OSD.
- [`HARDWARE_RUNBOOK.md`](../HARDWARE_RUNBOOK.md) — pasos de ADB Wireless (sección 2), instalación
  de OSD en el teléfono (sección 4) y lectura de `logcat` (sección 5).
- Arquitectura final propuesta (no este experimento): **Opción F**, en
  `openspec/changes/watch-osd-message-delivery/`.
