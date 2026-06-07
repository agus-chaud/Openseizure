# Notas de prueba de campo — 2026-06-06/07

Primera validación en hardware real: **Samsung Galaxy Watch 8** + teléfono **Samsung A52** con
**OpenSeizureDetector V5.0.5**.

---

## ✅ Lo que funciona (lado RELOJ — validado en hardware)

El reloj está **completo y probado**. Logs reales lo confirman:

- Captura del acelerómetro a **25 Hz** (`TYPE_ACCELEROMETER`), sin crashear.
- Magnitud en reposo **~1000 milli-g** → **bench test de Graham (unidades) PASA**: confirma gravedad
  incluida, escala correcta para el modelo (DEC-023).
- Envío de chunks de **125 muestras cada ~5 s** por Wear Data Layer a `/osd/accel_data`,
  formato **JSON `{"samples":[...]}`** (el que OSD parsea primero).
- **Handshake de settings** implementado y funcionando:
  `Settings enviados a OSD: battery=75%, sample_freq=25Hz` (formato `{"battery":N,"sample_freq":25}`
  en `/osd/settings`, el que parsea `SdDataSourceAw.handleSettings`).
- 56/56 tests unitarios verdes.

## 🐛 Bugs encontrados y arreglados en campo

1. **Crash al iniciar el monitoreo** (Android 14): un foreground service tipo `health` exige
   `FOREGROUND_SERVICE_HEALTH` + al menos un permiso de sensores. Se agregó
   `HIGH_SAMPLING_RATE_SENSORS`. (commit `a96a115`)
2. **Notificación persistente invisible**: faltaba `POST_NOTIFICATIONS` (API 33+). Declarado en el
   manifest; otorgado por adb para probar. Pendiente: pedirlo en runtime desde `MainActivity`.

---

## ⚠️ El bloqueante actual (lado OSD)

OSD muestra **"Data source fault" (naranja)**, no muestra la batería del reloj, y el log del reloj
**NO** tiene `OSD pidió settings`. → **Silencio bidireccional**: el reloj manda accel + settings (GMS
los acepta), pero OSD no recibe; y OSD tampoco le manda el `start` al reloj.

`Enviado a A52 de Candu` solo significa que GMS aceptó la entrega al nodo, **no que OSD la haya
recibido**. OSD usa `NodeClient.getConnectedNodes` (no `CapabilityClient`), así que no es tema de
capability declarada.

**El problema NO está en el código del reloj** (todo validado). Está en que las dos apps no se
comunican por Wear Data Layer / del lado de OSD.

---

## 🔜 Próximos pasos (en orden de probabilidad)

1. **Probar el APK BETA de OSD**, no el 5.0.5 release. Graham dijo que el Android Wear data source
   está en la **rama `beta`** (escondido tras developer mode). Es posible que el release 5.0.5 no
   tenga el AW source completo/funcional. → Compilar/conseguir el beta de
   `github.com/OpenSeizureDetector/Android_Pebble_SD` (rama `beta`).
2. **Coordinar con Graham** (GitHub discussion #69): pasarle el log del reloj (prueba que envía todo
   bien) y preguntar cómo el AW source espera conectarse, y si 5.0.5 vs beta importa.
3. **Conseguir un cable de datos USB** para leer el logcat del teléfono (`SdDataSourceAw:D`) y ver el
   lado OSD. Los 3 cables probados eran "solo carga" (Windows no detectaba el teléfono). El pareo
   inalámbrico de adb falla con `protocol fault` (bug de adb 37 en Windows con este Samsung).

## Estado de conexión para retomar

- Reloj por WiFi: `adb connect 192.168.0.209:<puerto>` (el puerto cambia; verlo en Depuración
  inalámbrica). Build/install: ver `HARDWARE_RUNBOOK.md` y `BUILD_SETUP.md`.
- Teléfono: sin conexión adb (falta cable de datos).
