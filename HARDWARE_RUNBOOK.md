# HARDWARE RUNBOOK — Probar SeizureGuard con el reloj real (paso a paso, sin Android Studio)

> Esta guía la ejecutás **vos**, a mano, cuando tengas el **Samsung Galaxy Watch 8** y un
> **teléfono Android** con la app OpenSeizureDetector instalada. El asistente no puede hacer
> nada de acá porque necesita el hardware físico. Cuando un paso te salga bien, marcás esa fase
> como **lista** ("Field-Done") en el README.

---

## Diccionario rápido (leé esto primero)

Para no perderte, acá están todas las palabras raras que vas a ver, en criollo:

| Palabra | Qué significa, simple |
|---|---|
| **adb** | "Android Debug Bridge". Es un programa de tu PC que le da órdenes a un dispositivo Android (el reloj o el teléfono) desde la consola. Es como un control remoto por cable/WiFi. |
| **consola / terminal** | La ventana negra donde escribís comandos (PowerShell en Windows). |
| **APK** | El archivo instalable de una app Android. El equivalente a un `.exe` de Windows o un `.deb` de Linux. |
| **compilar / build** | Convertir el código fuente en una app lista para instalar (el APK). |
| **gradle** | La herramienta que compila el proyecto. La usás con `./gradlew.bat`. Pensalo como el `make` o el `pip build` de Android. |
| **SDK** | "Software Development Kit": el paquete de herramientas de Android que `gradle` necesita para compilar. Ya está instalado en `C:\Android`. |
| **logcat** | El registro de mensajes en vivo que escupe un dispositivo Android. Es como mirar los `print()` / logs de la app mientras corre. |
| **WiFi pairing / parear** | Darle permiso por única vez a tu PC para hablarle al reloj por WiFi, con un código de 6 dígitos (como emparejar unos auriculares Bluetooth). |
| **milli-g** | Unidad de aceleración. En reposo, la gravedad de la Tierra mide ~1000 milli-g. Sirve para chequear que el sensor mide bien. |
| **OSD** | OpenSeizureDetector: la app oficial (en el teléfono) que corre el modelo y dispara las alarmas. |

---

## 0. Pre-requisitos (instalar una sola vez)

> ✅ **Si en tu computadora ya tenés todo instalado** (Java 17, el SDK de Android y la
> config del proyecto) y ya lo configuraste, **saltá a la sección 2** para conectar el reloj.
> Esta sección queda por si lo armás en otra computadora (las rutas de ejemplo, como `C:\Android`, son las de la configuración del proyecto: ajustalas a las tuyas). El detalle completo está en `BUILD_SETUP.md`.

Hacen falta tres cosas: **Java 17** (el motor para compilar), el **SDK de Android** (las
herramientas), y **adb** (el control remoto). Todo se instala sin Android Studio. Ver `BUILD_SETUP.md`.

---

## 1. Correr los tests automáticos (sin reloj, en tu PC) ✅

Los **tests** son chequeos automáticos que verifican que el código del reloj no se rompió. Corren
en tu computadora en segundos, sin necesidad del reloj. En la consola, parado en la carpeta del
proyecto, escribí:

```powershell
# Esto le dice a Java 17 dónde está (necesario porque tu PC tiene otro Java por defecto):
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17-hotspot"
$env:ANDROID_HOME = "C:\Android"

.\gradlew.bat :wear:test
```

Si todo está bien, al final ves **`BUILD SUCCESSFUL`** ("compilación exitosa") y los tests en verde
.

---

## 2. Conectar el reloj a tu PC (por WiFi)

El Watch 8 **no tiene cable USB**, así que la única forma de conectarlo es por **WiFi**. El reloj
y tu PC tienen que estar en **la misma red WiFi de tu casa**.

### 2.1 Activar el "modo desarrollador" en el reloj
Es un modo escondido que permite que tu PC le hable al reloj. Para activarlo, en el reloj:
```
Ajustes → Acerca del reloj → Información de software
→ tocar "Número de compilación" 7 veces seguidas
  (aparece el cartel "Modo desarrollador activado")

Después: Ajustes → Opciones de desarrollador
→ activar "Depuración ADB"
→ activar "Depuración inalámbrica"
```
("Depuración" = permitir que una PC se conecte para inspeccionar/instalar. "Inalámbrica" = por WiFi.)

### 2.2 Parear el reloj (SOLO la primera vez)
"Parear" es darle permiso a tu PC para hablarle al reloj, una sola vez, con un código —igual que
cuando emparejás unos auriculares Bluetooth. **Wear OS lo exige antes de poder conectarte.**

En el reloj: **Depuración inalámbrica → Vincular nuevo dispositivo** ("Pair new device"). El reloj
te muestra dos cosas: una dirección tipo `192.168.1.42:37123` y un **código de 6 dígitos**.

> ⚠️ Ese número después de los dos puntos (`:37123`) es el **puerto de pareo**, y es **distinto**
> del puerto que vas a usar para conectarte después (`:5555`). No los mezcles.

En tu PC, en la consola:
```powershell
# Usá la dirección de PAREO que muestra el reloj:
C:\Android\platform-tools\adb.exe pair 192.168.1.42:37123
# Te va a pedir el código → escribís los 6 dígitos que muestra el reloj
# → si sale "Successfully paired", ya está pareado para siempre
```
> Nota: `adb` vive en `C:\Android\platform-tools\`. Por eso escribimos la ruta completa
> `C:\Android\platform-tools\adb.exe`. (Si agregás esa carpeta al "PATH" de Windows, podés
> escribir solo `adb`.)

### 2.3 Conectar (esto sí, cada vez que quieras usar el reloj)
En la misma pantalla del reloj ("Depuración inalámbrica") figura la **dirección IP** y el **puerto
de conexión** (normalmente `5555`):
```powershell
C:\Android\platform-tools\adb.exe connect 192.168.1.42:5555

# Verificar que quedó conectado:
C:\Android\platform-tools\adb.exe devices
# Tiene que aparecer el reloj en la lista, con la palabra "device" al lado.
```

---

## 3. Instalar la app del reloj

Primero **compilás** la app (la convertís en un archivo instalable, el APK), después la **instalás**
en el reloj.

> ⚠️ **Importante:** `gradlew.bat` solo funciona si la consola está **parada dentro de la carpeta
> del proyecto**. Si te dice "no se reconoce como comando", es que estás en otra carpeta. Entrá
> primero con `cd` y seteá Java/SDK (cada ventana nueva los pierde):
> ```powershell
> cd <carpeta-del-proyecto>
> $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17-hotspot"
> $env:ANDROID_HOME = "C:\Android"
> dir gradlew.bat   # si te lo lista, estás bien parado
> ```

```powershell
# Compilar la app del reloj (genera el APK):
.\gradlew.bat :wear:assembleCompanionDebug
# El archivo queda en: wear\build\outputs\apk\companion\debug\wear-companion-debug.apk
# (Desde el Batch 7. Si existe la carpeta vieja wear\build\outputs\apk\debug\, borrala:
#  ese APK es anterior y vibra ante fallas.)

# Instalarlo en el reloj (el "-r" = reinstalar si ya estaba):
C:\Android\platform-tools\adb.exe install -r wear\build\outputs\apk\companion\debug\wear-companion-debug.apk
```

---

## 4. Instalar y verificar el flujo de dos apps (reloj + companion + OSD)

> ⚠️ **Estado: NADA de esta sección se probó todavía en hardware real.** El sistema completo
> (reloj + companion + OSD) no fue verificado de punta a punta. El experimento de hardware GATE-0
> (`docs/EXPERIMENTO_WEARSD_OSD_BETA.md`) **no se corrió**: el dueño del proyecto decidió asumirlo como
> PASS (DEC-052). Las pruebas DV-1..DV-7 de abajo siguen **pendientes**. Mientras tanto, no dependas de
> esto solo para vigilar a nadie. Lo marcado **(sin verificar)** es una expectativa, no un hecho.

**Cómo funciona (DEC-050, DEC-051):** el reloj no puede hablarle directo a OSD. La Wear Data Layer solo
entrega mensajes entre apps con el **mismo package y el mismo certificado de firma**, y OSD tiene otros.
Por eso el teléfono corre **dos apps**:

```
Reloj (com.seizureguard.wear) ──Wear Data Layer──►  Companion en el teléfono (mismo package + firma)
                                                          │ HTTP local POST/GET 127.0.0.1:8080
                                                          ▼
Reloj ◄──/osd/alarm_state (Data Layer)──  Companion ◄──  OSD (fuente "Garmin", servidor web)
```

OSD detecta, hace sonar la alarma y manda el SMS. El companion es headless: **no tiene pantalla de
estado**. El camino viejo ("activar el Android Wear data source en OSD") **está roto** (DEC-050): ya no
lo uses.

### 4.1 Pre-requisitos

- Reloj conectado por adb (secciones 2 y 3) y un teléfono Android con **depuración USB** activada.
- **OSD V5.0 (rama beta)** instalado en el teléfono (ver sección 0/`docs/GUIA_CONECTAR_RELOJ_TELEFONO.md`;
  esa guía describe el mismo flujo de dos apps; no uses nunca la fuente "Android Wear").
- Un **segundo teléfono** (el del cuidador) para la prueba de SMS.
- El código de `:wear` y de `:phone` está en `main`. Compilá los dos desde el mismo checkout de `main`.
- Estado de las builds actuales, para saber qué esperar (ver 4.8): `main` ya incluye el Batch 7 (reloj),
  el Batch 5e y el Batch 8 (aviso de versión). Falta solo la verificación en hardware.

### 4.2 Orden de instalación y firma (lo que más falla)

Los dos APK **tienen que tener el mismo `applicationId` (`com.seizureguard.wear`) y estar firmados con
la misma clave**. Si no, el mensaje del reloj muere en Google Play Services, antes de llegar al
companion, y en el logcat del teléfono aparece `Failed to deliver message to AppKey[...]` (DEC-050).

- En esta máquina, `:wear` y `:phone` en debug se firman con el mismo `~/.android/debug.keystore`
  (compartido), así que si compilás **los dos en esta PC** coinciden. Si compilás uno en otra máquina,
  **no** coinciden.
- Para release, la clave compartida se lee de `keystore.properties` (gitignoreado; plantilla en
  `keystore.properties.template`).

```powershell
# 1) Companion (desde el checkout de main):
.\gradlew.bat :phone:assembleDebug
#    (ruta esperada del APK: phone\build\outputs\apk\debug\phone-debug.apk, sin verificar)
C:\Android\platform-tools\adb.exe -s <id_telefono> install -r phone\build\outputs\apk\debug\phone-debug.apk

# 2) Reloj (como en la sección 3):
.\gradlew.bat :wear:assembleCompanionDebug
C:\Android\platform-tools\adb.exe -s <ip_reloj>:5555 install -r wear\build\outputs\apk\companion\debug\wear-companion-debug.apk

# 3) Confirmar que los dos APK tienen la MISMA firma (los SHA-256 tienen que ser iguales):
C:\Android\build-tools\<version>\apksigner.bat verify --print-certs phone\build\outputs\apk\debug\phone-debug.apk
C:\Android\build-tools\<version>\apksigner.bat verify --print-certs wear\build\outputs\apk\companion\debug\wear-companion-debug.apk
```

Registrá: SHA-256 de cada APK. Si difieren, recompilá los dos en la misma máquina antes de seguir.

### 4.3 Configurar OSD en el teléfono

1. En OSD, ajuste **DataSource** = **"Garmin"** (en el onboarding de OSD figura como "Garmin Watch"; la
   etiqueta exacta en tu build: **sin verificar**). **NO** uses la fuente de Android Wear.
2. Servidor web de OSD **encendido** (`SdWebServer`, puerto 8080). No se encontró un ajuste para apagarlo;
   OSD debería tenerlo corriendo mientras el servicio anda (**sin verificar**). En la pestaña **System**
   de la pantalla principal de OSD figura "Access Server at: http://...:8080".
3. Apagar **"Enable Audible System FaultWarnings"** (clave `AudibleFaultWarning`; viene **activada** por
   defecto). Si queda activa, OSD suena por sus propias fallas y rompe la política de fallas
   silenciosas (DEC-057).
4. Configurar el número del cuidador para el SMS y **no** dejar activo **MUTE** ("Mute Alarms").
5. Dejar el teléfono **cargando** y con el **volumen de alarma** alto (la alarma de OSD usa el canal de
   alarma: suena con el timbre en silencio, pero **no** con volumen de alarma en 0 ni con No molestar en
   "silencio total"; sin verificar en tu equipo).

### 4.4 Iniciar el companion (SetupActivity)

Abrí **SeizureGuard Companion** en el teléfono. Es una pantalla de una sola vez, sin estado en vivo.
Botones reales: **"Allow running in the background"** (solo aparece si falta la exención de batería),
**"Start bridge"** y **"Stop bridge"**.

1. Tocar **"Allow running in the background"** y aceptar la exención de batería.
2. Tocar **"Start bridge"** y aceptar **Notificaciones** y **Nearby devices (Bluetooth)**. Sin Bluetooth
   aparece "Allow Nearby devices, or the bridge cannot start." y no arranca.
3. Debe aparecer el toast **"Bridge started"** y una notificación de baja importancia
   **"SeizureGuard bridge running"** ("Forwarding watch data to OpenSeizureDetector").
4. En el reloj: tocar **"Iniciar monitoreo"** (debe decir "Monitoreo activo").

### 4.5 Verificar que los datos llegan a OSD

1. Con el reloj andando ≥ 1 minuto, mirar OSD: la pestaña **System** debería mostrar datos recientes
   del reloj (**sin verificar** cómo se ve exactamente).
2. En el navegador del teléfono abrir `http://127.0.0.1:8080/data`: debe devolver JSON con un
   timestamp que **avanza** al recargar. Si devuelve un texto placeholder, OSD está en la fuente
   equivocada.
3. Logcat (tags reales): `OsdBridgeService`, `WearAlarmSender`, `BridgeNotifications`; y en OSD
   `SdDataSourceGarmin` y `WebServer`:
```powershell
C:\Android\platform-tools\adb.exe -s <id_telefono> logcat -s OsdBridgeService:D WearAlarmSender:D SdDataSourceGarmin:D WebServer:D
```
4. Si aparece `Failed to deliver message to AppKey` → problema de firma (4.2).

**Registrar:** hora, si `/data` avanzó, y si hay líneas de error.

### 4.6 Verificar que el estado de alarma vuelve al reloj

1. Agitar el reloj (rítmico y fuerte, 30 s) hasta que OSD pase a WARNING/ALARM.
2. Esperado: OSD suena, manda el SMS, y el reloj **vibra** (WARNING = pulso corto; ALARM = vibración
   fuerte y repetida). El refresco al reloj es cada 10 a 15 s, con techo de 8 s desde que OSD calcula el estado.
3. Estados 3 (FALL) y 5 (MANUAL) también deberían ser alarma. Qué hace el build actual está en 4.8.

**Registrar:** segundos entre la sacudida y la vibración, si sonó OSD, si llegó el SMS.

### 4.7 Checklist de verificación (DV-1..DV-7 y otros chequeos)

Numeración de DV según `docs/SAFETY_FINDINGS_WATCH_OSD.md` (sección 6). Ojo: `tasks.md` del openspec
numera distinto DV-4 y no tiene DV-7; se usa el registro de seguridad. Ninguno está hecho.

| ID | Cómo hacerlo | Qué deberías ver | Qué registrar |
|---|---|---|---|
| **DV-1** Bluetooth denegado | Desinstalar/reinstalar el companion (o quitar el permiso Nearby devices en Ajustes) y tocar **"Start bridge"**. | Sin crash. Notificación **silenciosa** "SeizureGuard: monitoring is not working" ("...allow the Nearby devices (Bluetooth) permission"). No suena ni vibra. | ¿Crash?, ¿se ve la notificación?, ¿sonó? Repetir con notificaciones también denegadas (¿no se ve nada?). |
| **DV-2** `connectedDevice` desde `BOOT_COMPLETED` | Con el puente andando, **reiniciar el teléfono** y esperar 2 min sin tocar nada. | Ya sea que el puente **se reanude**, o aparezca el aviso silencioso "The phone restarted and the bridge could not start by itself. Tap here, then tap Start bridge." | Cuál de los dos pasó, Android/One UI y tiempo. |
| **DV-3** `mDataFrequencyCheckEnabled` | Revisar las prefs de OSD (`DataFrequencyCheck`); en el código de OSD arranca en `true` y se pisa con esa pref. | Si está en `true`, la tolerancia de jitter de la cadencia de POST es más estricta. | Valor efectivo en tu OSD; si OSD descartó/rechazó datos por frecuencia. |
| **DV-4** Estado congelado / fuente equivocada | Con todo andando, poner OSD en **otra fuente de datos**. Aparte: cerrar OSD y repetir. | En el reloj aparece **DEGRADED dentro del techo firmado** (enlace 60 s; OSD congelado ≈ 75 s; **calculado, no medido**). La notificación del teléfono es **silenciosa** ("OSD is not accepting watch data..." u "OpenSeizureDetector is open but not analysing..."). | Segundos hasta DEGRADED en el reloj y hasta la notificación; si sonó o vibró algo. |
| **DV-5** Batería del keep-alive de 10 s | Noche o 8 h con teléfono al 100% sin cargador (solo para medir), vs. sin el companion. | Consumo razonable. | % de batería del teléfono al inicio y al final. |
| **DV-6** Noche completa de 8 h | Dormir con todo andando, teléfono cargando. | Sin cortes y resumen matutino "No interruptions last night." | Cortes, falsas alarmas, batería del reloj, resumen. |
| **DV-7** Inyección de fallas | Una por vez: (a) matar el listener, (b) matar OSD, (c) force-stop del companion, (d) No molestar, (e) notificaciones denegadas. | Cada una debe quedar como falla visible (DEGRADED en el reloj, notificación silenciosa) **sin sonido ni vibración**; (c) no genera aviso ni resumen. | Por cada una, tiempo hasta falla visible y si algo sonó. Dato clave para (a): el reintento del listener (Batch 5e, PR #28) está en `main` pero **sin probar en hardware**. |

Otros chequeos (todos pendientes):

| Chequeo | Cómo hacerlo | Qué deberías ver | Qué registrar |
|---|---|---|---|
| Notificación silenciosa en Doze / Samsung One UI | Provocar una falla (ej. cerrar OSD) con la pantalla apagada y el teléfono quieto ≥ 30 min. | La notificación aparece **sin asomarse (peek), sin sonido ni vibración**. | Si asomó, sonó o vibró. Si pasa, hay que bajar la importancia a nivel mínimo. |
| Resumen matutino a las 8:00 | Dejar el puente andando de noche y no tocar el teléfono. | Notificación silenciosa **"SeizureGuard: last night"** ("No interruptions last night." o "Monitoring was interrupted N times (total M min): ..."), cerca de las 8:00 (la alarma es inexacta, puede demorar). | Hora real de llegada; si sonó. |
| El resumen NO llega tras force-stop | Con el puente andando, hacer **force-stop** del companion a la noche. | **No** llega resumen a la mañana (límite conocido y aceptado, riesgo A1). | Confirmar que no llegó. |
| SMS en el teléfono del cuidador | Poner el teléfono del cuidador en **silencio** (y otra vez en No molestar). Disparar una alarma real (4.6). | El SMS **suena** solo si se configuró el contacto como prioritario. **No** lo controla el software. | Si sonó con silencio y con No molestar. |
| Caminos candidatos (sin verificar) | OSD en el teléfono del cuidador con fuente **"Network"** (`http://<IP>:8080/data` cada 2 s) para que suene por el canal de alarma. | **Candidato NO verificado de punta a punta**: misma red, corte del enlace (NETFAULT), y el servidor web de OSD queda **sin autenticación** en la LAN. **No** es una instrucción para el cuidador todavía. | Solo si decidís probarlo. |

### 4.8 Qué hacen los builds actuales y qué falta implementar

Todo lo de esta tabla ya está en `main` (código y tests). Lo único pendiente es probarlo en hardware real.

| Tema | Comportamiento en `main` | Dónde se ve en el código |
|---|---|---|
| Mapeo de estados en el reloj | 0 sin vibración, 1 pulso corto (WARNING), 2, 3 y 5 = alarma; 4, 7 y valores desconocidos = falla **silenciosa** (solo pantalla y log); 6 (MUTE) = sin vibración. | `AlarmStateManager.kt` (clasificación y `Severity`) |
| MUTE en la pantalla del reloj | Dice **"SILENCIADO, no avisa convulsiones"** (no "Monitoreo activo"). | `DisplayStatus.kt`, `strings.xml` (`label_status_muted`) |
| DEGRADED en el reloj | Solo visual: **"⚠ MONITOREO DEGRADADO"**, **sin vibración**. | `DisplayStatus.kt`; ya no existe `vibrateDegraded` |
| Watchdog del reloj | Intervalo 10 s, `DELIVERY_STALE_MS` 40 s, `ALARM_STATE_STALE_MS` 40 s, 2 chequeos seguidos (techos 60 s / ≈ 75 s, **calculados, no medidos**). | `SeizureMonitorService.kt` (constantes) |
| Sabores del reloj | `companion` (normal) y `osdDirect` (solo desarrollo, cartel "VERSIÓN DE PRUEBA"; su variante release está deshabilitada). | `wear/build.gradle.kts` |
| Versión del contrato | El reloj manda `contract_version` (1) con los ajustes; si el teléfono ve una versión distinta o ninguna, avisa **en silencio** ("SeizureGuard: update needed") y lo anota en el resumen matutino. **Sin probar en hardware.** | `WearDataLayerManager.kt`, `ContractVersionCheck.kt` |
| `sample_freq` en el companion | Exactamente 25, cualquier otro valor se descarta (Batch 5e, PR #27). | — |
| Chunks de acelerómetro planos/congelados | Se rechazan (125 muestras idénticas) (Batch 5e, PR #27). | — |
| Reintento del listener de `MessageClient` | Reintento con espera creciente (5 a 60 s) y re-registro si pasan 60 s sin mensajes válidos (Batch 5e, PR #28). | — |

Anotá la versión (commit) de cada APK en cada prueba.

### 4.9 Listo cuando...

Podés marcar esta fase como lista solo si: los datos llegan a OSD (4.5), el estado vuelve al reloj
(4.6) y **todos** los DV-1..DV-7 y los otros chequeos de la tabla están hechos y registrados. Hasta
entonces, el flujo sigue **sin verificar**.

---

## 5. Validación del transporte (el método de Graham) — EN ORDEN, no saltees

> ⚠️ **Nota (flujo de dos apps, sección 4):** con el companion, OSD usa la fuente **"Garmin"**, así que
> los comandos de esta sección ya usan el tag de logcat `SdDataSourceGarmin` (no `SdDataSourceAw`, que
> era del camino directo roto). El "modo de prueba" secuencial 1..750 del reloj **no está verificado**
> en este flujo de dos apps — puede necesitar un chequeo aparte con el companion andando.

Antes de confiar en la detección, hay que comprobar que los datos del reloj **llegan bien** al
teléfono. Dos chequeos.

### 5.1 Chequeo de ORDEN (números secuenciales)
En modo de prueba, el reloj manda números ordenados `1, 2, 3, ... 750` en vez de datos reales.
Vos mirás en el teléfono que lleguen **en ese orden**. Para "espiar" lo que recibe la app OSD,
usás el **logcat** (el registro de mensajes en vivo):

```powershell
# "-s SdDataSourceGarmin:D" = mostrá solo los mensajes de esa parte de la app OSD:
C:\Android\platform-tools\adb.exe -s <id_telefono> logcat -s SdDataSourceGarmin:D

# Qué deberías ver:
#   "1.0, 2.0, 3.0, ..."   → CORRECTO, llegan en orden ✅
#   "4.0, 3.0, 2.0, 1.0"   → MAL: los bytes llegan al revés (problema de orden de bytes)
#   "0.0, 1.4e-45, ..."    → MAL: números corruptos (problema de formato)
```
Solo si llegan en orden, pasás al 5.2.

### 5.2 Chequeo de UNIDADES (reloj quieto)
Dejá el reloj quieto sobre la mesa y cambiá a datos reales. Como está inmóvil, lo único que mide
es la **gravedad de la Tierra**, que vale ~1000 milli-g en un eje:

```powershell
C:\Android\platform-tools\adb.exe -s <id_telefono> logcat -s SdDataSourceGarmin:D

# Qué deberías ver con el reloj quieto:
#   un eje ≈ 1000 milli-g   → CORRECTO ✅ (está midiendo la gravedad bien)
#   todo ≈ 0                → MAL (estaría restando la gravedad, dato equivocado)
```

---

## 6. Prueba completa (simular una convulsión)

```powershell
# Mirar los mensajes del reloj Y del teléfono a la vez:
C:\Android\platform-tools\adb.exe logcat -s SeizureGuard:D SdDataSourceGarmin:D
```
1. App del reloj andando, app OSD del teléfono andando y recibiendo.
2. Agitá el reloj con la mano, con un movimiento **rítmico y fuerte** (1 a 3 sacudidas por segundo)
   durante unos 30 segundos. Eso imita el movimiento de una convulsión.
3. Tiene que pasar la cadena completa: OSD detecta → le avisa al reloj → **el reloj vibra**
   (un pulso corto = aviso; vibración fuerte y repetida = alarma) → la app OSD suena y manda un
   **SMS** (mensaje de texto) al cuidador.

---

## 7. Prueba nocturna real + ajuste de sensibilidad

1. Dormí con el reloj puesto y la app OSD andando toda la noche.
2. A la mañana, mirá en el historial de la app OSD cuántas **falsas alarmas** hubo (alarmas sin
   que hubiera convulsión, por ejemplo al darte vuelta en la cama).
3. Si hubo muchas falsas alarmas, se ajusta la **sensibilidad** (el "umbral") **en la configuración
   de la app OSD** — NO en este proyecto. Es una decisión médica delicada: ver `CLINICAL_SIGNOFF.md`.

---

## 8. Cómo me pasás los resultados

Copiá y pegá en el chat lo que viste: el texto del `logcat`, cuántas falsas alarmas hubo, o el
archivo de datos. Yo lo interpreto, te digo si el chequeo pasó, y recién ahí marcás esa fase como
**lista (Field-Done)** en el README.

| Paso | Está listo cuando... |
|------|----------------------|
| 5.1 orden | los números llegan ordenados a OSD |
| 5.2 unidades | un eje marca ~1000 milli-g con el reloj quieto |
| 6 prueba completa | la convulsión simulada dispara la alarma y el SMS |
| 7 nocturna | pasás una noche con pocas o ninguna falsa alarma |
