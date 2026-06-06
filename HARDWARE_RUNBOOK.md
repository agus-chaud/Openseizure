# HARDWARE RUNBOOK — Pruebas reales SIN Android Studio

> Todo por línea de comandos: `gradle` (wrapper, ya está en el repo) + `adb`. No necesitás
> Android Studio. Este runbook lo ejecutás VOS cuando tengas el Samsung Galaxy Watch 8 + un
> teléfono Android con la app OpenSeizureDetector V5.0 instalada. El agente no puede correr nada
> de acá (necesita el hardware). Tras cada paso exitoso, marcás la fase como **Field-Done** en el README.

---

## 0. Pre-requisitos (instalar una sola vez, sin Android Studio)

Necesitás 3 cosas: **JDK 17**, el **Android SDK command-line tools**, y **adb**.

### 0.1 JDK 17
```bash
# Verificar si ya lo tenés:
java -version      # debe decir 17.x

# Si no, instalá Temurin 17 (https://adoptium.net) o por gestor de paquetes:
# Windows:  winget install EclipseAdoptium.Temurin.17.JDK
```

### 0.2 Android SDK command-line tools (sin IDE)
1. Descargá "Command line tools only" de https://developer.android.com/studio#command-line-tools-only
2. Descomprimí en, por ejemplo, `C:\Android\cmdline-tools\latest\`
3. Seteá la variable de entorno:
```powershell
# PowerShell (Windows)
$env:ANDROID_HOME = "C:\Android"
# (agregalo permanente en Variables de entorno del sistema)
```
4. Instalá las piezas que el build necesita y aceptá licencias:
```bash
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
sdkmanager --licenses        # aceptá todo (y)
```
5. `platform-tools` trae **adb**. Verificá:
```bash
adb version
```

### 0.3 Crear local.properties (le dice a Gradle dónde está el SDK)
En la raíz del repo, creá un archivo `local.properties` con:
```properties
sdk.dir=C:\\Android
```
> Este archivo NO se commitea (es local de tu máquina).

---

## 1. Correr los tests unitarios (sin reloj, sin teléfono)

Esto verifica que el código del reloj no tiene regresiones. Corre en tu PC en segundos:
```bash
./gradlew :wear:test
```
Esperado: `BUILD SUCCESSFUL` con todos los tests de `CircularBuffer`, `WearDataLayerManager`,
`SeizureMonitorService`, etc. en verde.

---

## 2. Conectar el reloj (ADB Wireless — el Watch 8 no tiene USB)

### 2.1 Habilitar developer mode en el reloj
```
Ajustes → Acerca del reloj → Información de software
→ tocar "Número de compilación" 7 veces
Ajustes → Opciones de desarrollador → Depuración ADB: ON → Depuración inalámbrica: ON
→ anotá la IP y puerto (ej: 192.168.1.42:5555)
```

### 2.2 Conectar
```bash
./scripts/connect_watch.sh 192.168.1.42      # script helper del repo
# o a mano:
adb connect 192.168.1.42:5555
adb devices                                   # debe listar el reloj
```

---

## 3. Compilar e instalar la app del reloj

```bash
# Compilar el APK debug:
./gradlew :wear:assembleDebug
# El APK queda en: wear/build/outputs/apk/debug/wear-debug.apk

# Instalar en el reloj:
adb install -r wear/build/outputs/apk/debug/wear-debug.apk
# o usá el script del repo:
./scripts/deploy_wear.sh
```

---

## 4. Instalar la app OSD V5.0 en el teléfono (la que corre el modelo)

La inferencia la hace OpenSeizureDetector V5.0 (rama beta), NO este repo.

1. Conseguir el APK de OSD V5.0 beta (preguntar a Graham por un APK pre-build, o compilar la
   rama `beta` de https://github.com/OpenSeizureDetector/Android_Pebble_SD con el mismo `gradlew`).
2. Instalar en el teléfono:
```bash
adb -s <id_del_telefono> install -r OpenSeizureDetector-v5-beta.apk
```
3. En la app OSD: habilitar **developer mode** y activar el **Android Wear data source**
   (está oculto detrás de developer mode para que usuarios normales no lo toquen).

---

## 5. Protocolo de validación de Graham (en orden — NO saltear)

### 5.1 Validación secuencial (verificar ORDEN de llegada)
El reloj manda `[1.0, 2.0, ..., 750.0]` en vez de datos reales (modo debug en builds debug).
En el teléfono, mirá el log de OSD:
```bash
adb -s <id_telefono> logcat -s SdDataSourceAw:D
# CORRECTO:   "1.0, 2.0, 3.0, ..."  (en orden)
# Byte swap:  "4.0, 3.0, 2.0, 1.0"  → bug de endianness
# Fragmentado:"0.0, 1.4e-45, ..."   → bug de ByteOrder
```
✅ Solo si llega en orden, pasás al 5.2.

### 5.2 Bench test (verificar UNIDADES)
Reloj quieto sobre la mesa, modo datos reales:
```bash
adb -s <id_telefono> logcat -s SdDataSourceAw:D
# CORRECTO:   un eje ≈ 1000 milli-g (gravedad) → TYPE_ACCELEROMETER OK
# INCORRECTO: magnitud ≈ 0 → estaría usando LINEAR_ACCELERATION (mal)
```

---

## 6. Test end-to-end (simular convulsión)

```bash
# Logs de ambos lados a la vez:
adb logcat -s SeizureGuard:D SdDataSourceAw:D
```
1. App del reloj activa, OSD activo y recibiendo.
2. Sacudí el reloj con movimiento rítmico 1-3 Hz por ~30 segundos.
3. Verificá la cadena: OSD detecta → OSD manda `/osd/alarm_state` → el reloj vibra
   (WARNING corto / ALARM 3×500ms) → OSD dispara su alarma + SMS al cuidador.

---

## 7. Test nocturno + ajuste de sensibilidad

1. Dormir con el reloj puesto, OSD corriendo toda la noche.
2. A la mañana, contar falsas alarmas en el historial de OSD.
3. Si hay muchas falsas: **ajustar el umbral en la configuración de la app OSD** (NO en este
   repo — el umbral vive en OSD). Esto es una decisión clínica → ver `CLINICAL_SIGNOFF.md`.

---

## 8. Cómo pegarle resultados al agente

Copiá en el chat: la salida de `logcat`, la cantidad de falsas alarmas, o el CSV. El agente
lo interpreta, te dice si el criterio se cumple, y SOLO entonces marcás **Field-Done** en el README.

| Paso | Field-Done cuando... |
|------|----------------------|
| 5.1 secuencial | los números llegan en orden a OSD |
| 5.2 bench | un eje ≈ 1000 milli-g en OSD |
| 6 end-to-end | la convulsión simulada dispara alarma + SMS |
| 7 nocturno | una noche con tasa de falsas alarmas aceptable |
