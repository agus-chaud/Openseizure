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
- 62/62 tests unitarios verdes.

> ## 🛟 ANTES DE CADA PRUEBA — leer esto
>
> El reloj manda **datos reales por defecto**. El "modo validación" (números sintéticos `1,2,3,...`
> para probar el transporte) está **apagado** salvo que lo actives a propósito con
> `--ez validation_mode true` (solo funciona en builds debug; ver DEC-047).
>
> **No leas una "no-detección" como prueba de que todo anda** si no estás seguro del modo: si por
> error estuvieras en modo validación, OSD recibiría basura y nunca detectaría nada. Para una prueba
> de detección real, arrancá el monitoreo **sin** el extra de validación.

> ## 🩺 Si ves "⚠ MONITOREO DEGRADADO" en el reloj
>
> El watchdog (DEC-048) detectó que el pipeline se rompió. Significa una de dos cosas:
> 1. **El acelerómetro dejó de emitir** (no llegan muestras hace >10s), o
> 2. **El teléfono se desconectó** (no hay entrega exitosa hace >60s — Bluetooth caído, OSD cerrada,
>    teléfono fuera de rango).
>
> Justamente el bloqueante de campo ("silencio bidireccional") ahora se vería como DEGRADADO en vez
> de pasar desapercibido. Para diagnosticar: `adb logcat -s SeizureMonitorService:E` (busca
> "Pipeline DEGRADADO") y revisá la conexión reloj↔teléfono. **Una sesión que pasó por DEGRADADO no
> es una prueba de detección válida** — el reloj no estuvo mandando datos reales todo el tiempo.

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

> ## 🧭 ACTUALIZACIÓN (septiembre 2026) — causa raíz confirmada
>
> Todo lo de arriba es el **registro histórico** de la sesión de junio 2026 y queda intacto. Lo que
> cambió: la sospecha principal de esa sesión —"la versión de OSD instalada (V5.0.5) todavía no
> tenía terminado el soporte de reloj"— **quedó refutada**. Con OSD V5.0.8, V5.0.9 y la beta actual,
> todas con `SdDataSourceAw` completo, el fallo es **idéntico**.
>
> **Causa raíz real (confirmada, ver [DEC-050](DECISIONS.md)):** el Wear Data Layer solo entrega
> mensajes entre apps que comparten **package name Y certificado de firma** (el "AppKey" de Google).
> SeizureGuard (`com.seizureguard.wear`, debug key propia) y OSD (`uk.org.openseizuredetector`,
> release key de Graham) no comparten ninguno de los dos, así que Google Play Services descarta cada
> mensaje reloj→teléfono **antes** de que OSD lo vea (`Failed to deliver message to AppKey[...]` en
> el logcat del teléfono, emitido por `com.google.android.gms.persistent`). Es independiente de la
> versión de OSD. La app de reloj oficial de OSD (WearSD) funciona solo porque usa el mismo
> `applicationId` y la misma firma que la app de teléfono.
>
> **Sobre los "próximos pasos" de abajo:** apuntaban a las **herramientas de diagnóstico**, no a la
> causa. Conseguir el APK beta de OSD y conseguir el cable USB de datos habrían servido para *ver*
> el lado OSD, pero **ninguno de los dos arregla la conexión**. Quedan **superados** por DEC-050 y
> por la exploración SDD `watch-osd-message-delivery`
> (`openspec/changes/watch-osd-message-delivery/exploration.md`), donde se elige el fix (el usuario
> se inclina por que SeizureGuard adopte el `applicationId` de OSD + firma compartida, pendiente de
> consultar con Graham).

## 🔜 Próximos pasos (en orden de probabilidad) — SUPERADOS por DEC-050

> Estos pasos son de la sesión de junio 2026 y se conservan como registro. Los tres apuntaban a
> herramientas de diagnóstico, no a la causa raíz (ver la actualización de arriba). El trabajo real
> se sigue ahora en la exploración SDD `watch-osd-message-delivery`.

1. ~~**Probar el APK BETA de OSD**, no el 5.0.5 release.~~ Hecho: la beta (y V5.0.8 / V5.0.9) traen
   `SdDataSourceAw` completo y **fallan igual**. No era la causa.
2. **Coordinar con Graham** (GitHub discussion #69): pasarle el log del reloj (prueba que envía todo
   bien) y preguntar cómo el AW source espera conectarse. Sigue vigente, ahora enfocado en la firma
   y el `applicationId` compartidos (ver DEC-050).
3. ~~**Conseguir un cable de datos USB** para leer el logcat del teléfono~~ (`SdDataSourceAw:D`). El
   logcat del teléfono se consiguió y confirmó la causa (`Failed to deliver ... AppKey`). El cable
   era para *leer el logcat*, nunca fue el enlace reloj↔teléfono (ese siempre es Bluetooth vía Wear
   Data Layer; el Galaxy Watch 8 no tiene puerto de datos).

## Estado de conexión para retomar

- Reloj por WiFi: `adb connect 192.168.0.209:<puerto>` (el puerto cambia; verlo en Depuración
  inalámbrica). Build/install: ver `HARDWARE_RUNBOOK.md` y `BUILD_SETUP.md`.
- Teléfono: sin conexión adb (falta cable de datos).
