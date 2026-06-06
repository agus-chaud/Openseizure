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

> ✅ **En esta máquina ya está todo instalado** (Java 17, el SDK de Android en `C:\Android`, y la
> config del proyecto). Si ya lo configuraste, **saltá a la sección 2** para conectar el reloj.
> Esta sección queda por si lo armás en otra computadora. El detalle completo está en `BUILD_SETUP.md`.

Hacen falta tres cosas: **Java 17** (el motor para compilar), el **SDK de Android** (las
herramientas), y **adb** (el control remoto). Todo se instala sin Android Studio. Ver `BUILD_SETUP.md`.

---

## 1. Correr los tests automáticos (sin reloj, en tu PC) ✅

Los **tests** son chequeos automáticos que verifican que el código del reloj no se rompió. Corren
en tu computadora en segundos, sin necesidad del reloj. En la consola, parado en la carpeta del
proyecto, escribí:

```powershell
# Esto le dice a Java 17 dónde está (necesario porque tu PC tiene otro Java por defecto):
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "C:\Android"

.\gradlew.bat :wear:test
```

Si todo está bien, al final ves **`BUILD SUCCESSFUL`** ("compilación exitosa") y 32 tests en verde.

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
> cd C:\Users\Dell\Agus\OpenSeizure
> $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
> $env:ANDROID_HOME = "C:\Android"
> dir gradlew.bat   # si te lo lista, estás bien parado
> ```

```powershell
# Compilar la app del reloj (genera el APK):
.\gradlew.bat :wear:assembleDebug
# El archivo queda en: wear\build\outputs\apk\debug\wear-debug.apk

# Instalarlo en el reloj (el "-r" = reinstalar si ya estaba):
C:\Android\platform-tools\adb.exe install -r wear\build\outputs\apk\debug\wear-debug.apk
```

---

## 4. Instalar la app OSD en el teléfono (la que detecta)

La detección de convulsiones la hace la app **OpenSeizureDetector V5.0** en el teléfono, NO este proyecto. Tu reloj solo le manda los datos.

1. Conseguir el archivo instalable (APK) de OSD V5.0: pedírselo a Graham (el creador), o compilarlo
   desde su código (rama `beta` de `github.com/OpenSeizureDetector/Android_Pebble_SD`).
2. Instalarlo en el teléfono. Como vas a tener DOS dispositivos conectados (reloj + teléfono), hay
   que decirle a `adb` a cuál instalar, con `-s` y el identificador del teléfono:
```powershell
# Primero ver los identificadores de los dispositivos conectados:
C:\Android\platform-tools\adb.exe devices
# Después instalar en el teléfono (reemplazá <id_telefono> por el que viste arriba):
C:\Android\platform-tools\adb.exe -s <id_telefono> install -r OpenSeizureDetector-v5-beta.apk
```
3. En la app OSD del teléfono: activar su "modo desarrollador" y prender el **Android Wear data
   source** (la fuente de datos del reloj). Está escondido para que un usuario normal no lo toque.

---

## 5. Validación del transporte (el método de Graham) — EN ORDEN, no saltees

Antes de confiar en la detección, hay que comprobar que los datos del reloj **llegan bien** al
teléfono. Dos chequeos.

### 5.1 Chequeo de ORDEN (números secuenciales)
En modo de prueba, el reloj manda números ordenados `1, 2, 3, ... 750` en vez de datos reales.
Vos mirás en el teléfono que lleguen **en ese orden**. Para "espiar" lo que recibe la app OSD,
usás el **logcat** (el registro de mensajes en vivo):

```powershell
# "-s SdDataSourceAw:D" = mostrá solo los mensajes de esa parte de la app OSD:
C:\Android\platform-tools\adb.exe -s <id_telefono> logcat -s SdDataSourceAw:D

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
C:\Android\platform-tools\adb.exe -s <id_telefono> logcat -s SdDataSourceAw:D

# Qué deberías ver con el reloj quieto:
#   un eje ≈ 1000 milli-g   → CORRECTO ✅ (está midiendo la gravedad bien)
#   todo ≈ 0                → MAL (estaría restando la gravedad, dato equivocado)
```

---

## 6. Prueba completa (simular una convulsión)

```powershell
# Mirar los mensajes del reloj Y del teléfono a la vez:
C:\Android\platform-tools\adb.exe logcat -s SeizureGuard:D SdDataSourceAw:D
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
