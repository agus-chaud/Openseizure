# Guía simple: conectar el reloj con la app del celular

> ⚠️ **Estado actual (octubre 2026): esto todavía no se probó en el hardware real.** Lo que
> sigue describe el flujo correcto tal como quedó diseñado y construido en el código (reloj y
> Companion ya están en la rama principal, `main`), pero nadie hizo todavía la prueba de punta a
> punta con el reloj físico. Seguí leyendo igual — es lo que hay
> que hacer cuando llegue el momento — pero no des por hecho que va a andar a la primera.

## Lo que pasó la última vez

En tu prueba de campo (junio 2026, quedó registrada en `FIELD_TEST_NOTES.md`), pasó esto:

- El reloj mandaba todo bien: acelerómetro, formato JSON correcto, settings — confirmado por logs reales.
- La app OSD del teléfono mostraba **"Data source fault"** (naranja) y nunca mostraba la batería del reloj.
- Diagnóstico de esa sesión: "silencio bidireccional" — el reloj hablaba, OSD no contestaba, y quedó anotado como sospecha principal que **la versión de OSD instalada (V5.0.5) no tenía terminado el soporte para reloj** — esa parte vivía en la rama `beta`, todavía sin fusionar al release público.

## La causa real, confirmada con los logs de los dos aparatos

> Esta sección se corrigió en **septiembre de 2026**. La versión anterior decía que la prueba
> falló "pura y exclusivamente" porque tenías una versión vieja de OSD. **Eso resultó ser falso.**
> Acá está lo que de verdad pasa, y por qué.

Cuando por fin se pudo mirar el `logcat` (el registro interno) del **teléfono** al mismo tiempo que
el del reloj, apareció la línea que lo explica todo:

```
Failed to deliver message to AppKey[<oculto#...>, <hash largo de la firma>]
```

Y quien la escribe no es OSD: es **Google Play Services** (`com.google.android.gms.persistent`), el
componente de Android que se encarga de pasar mensajes entre el reloj y el teléfono. O sea: el
mensaje del reloj **se descarta antes de que OSD llegue a verlo**.

### Por qué se descarta

El canal por el que se hablan el reloj y el teléfono (la "Wear Data Layer API") tiene una regla de
seguridad que no se puede desactivar: **solo entrega mensajes entre dos apps que compartan las dos
cosas a la vez**:

1. El mismo **nombre de paquete** (el identificador de la app: el del reloj es
   `com.seizureguard.wear`, el de OSD es `uk.org.openseizuredetector`).
2. El mismo **certificado de firma** (la "firma digital" con la que se empaqueta cada app).

Google llama a ese par (paquete + firma) el **"AppKey"**. SeizureGuard está firmada con una clave
de desarrollo propia; OSD está firmada con la clave privada de Graham (su autor). **No coinciden en
ninguna de las dos cosas**, así que Play Services tira todos los mensajes del reloj a la basura sin
avisar. Da igual la versión de OSD, da igual la configuración: mientras las dos apps no compartan
AppKey, no hay forma de que se comuniquen por ese canal.

La prueba está en la propia app de reloj **oficial** de OSD (se llama *WearSD*): funciona
**únicamente** porque usa el mismo nombre de paquete (`uk.org.openseizuredetector`) y la misma
firma que la app de teléfono. No hace nada más listo que SeizureGuard; simplemente comparte
identidad.

### Entonces, ¿la versión de OSD no importaba nada?

Importaba, pero como un problema **secundario**. Esto sigue siendo cierto:

| Evento | Fecha |
|---|---|
| Tu prueba de campo (falló con "Data source fault") | 6-7 de junio de 2026 |
| Lanzamiento de **OSD V5.0.8**, que fusiona la rama `beta` (soporte de reloj) al release público | **25 de junio de 2026** |

En junio tenías la **V5.0.5**, que efectivamente no traía terminado el `SdDataSourceAw.java` (el
archivo de OSD que recibe los datos del reloj). Si hubieras conectado todo perfecto, igual habrías
chocado con eso. Pero se probó después con **V5.0.8, V5.0.9 y la beta actual** —las tres con ese
archivo completo— y **el fallo es exactamente el mismo**. Así que actualizar OSD era necesario, pero
no es lo que arregla la conexión.

El detalle técnico completo está en [`DEC-050`](../DECISIONS.md).

---

## El arreglo elegido: una app puente en el medio

> Esta sección reemplaza lo que decía esta guía antes ("activar el Android Wear data source en
> OSD"). **Ese camino está roto y no se puede arreglar activando ningún toggle** — es justo lo que
> explica la sección de arriba. El arreglo real ya se decidió: es [`DEC-051`](../DECISIONS.md).

Como el reloj y OSD nunca van a poder compartir identidad (la clave privada de Graham no es algo
que podamos conseguir), la solución fue meter un **tercer actor** en el medio: una segunda app,
propia, que vive en el mismo teléfono y que **sí** comparte identidad con el reloj porque la
compilamos nosotros. Se llama **SeizureGuard Companion**.

El camino de los datos queda así:

```
Reloj (SeizureGuard)  →  Companion (mismo teléfono)  →  OSD (mismo teléfono)  →  alarma / SMS
        ▲                                                      │
        └──────────────── vuelve el estado de alarma ──────────┘
```

- **Reloj → Companion:** por el mismo canal de siempre (la Wear Data Layer), pero ahora sí entrega
  los mensajes, porque el Companion está firmado con la misma clave que el reloj — comparten
  AppKey.
- **Companion → OSD:** el Companion no analiza nada ni decide nada; solo agarra lo que le llega del
  reloj y se lo pasa a OSD por HTTP, hablándole a un servidor web que OSD ya trae adentro
  (`http://127.0.0.1:8080`, "127.0.0.1" es la forma de decir "el propio teléfono" en la jerga de
  redes). Es el mismo mecanismo que usa un reloj Garmin real para hablarle a OSD — por eso en OSD
  hay que elegir la fuente de datos **"Garmin"**, no "Android Wear".
- **OSD → alarma / SMS:** esa parte no cambia. OSD sigue siendo la app que detecta, hace sonar la
  alarma y manda el SMS al cuidador, igual que siempre.
- **De vuelta:** cuando OSD decide que hay una alarma, ese estado también viaja Companion → reloj,
  para que el reloj vibre.

El Companion es una app chica y sin pantalla de estado en vivo (ver más abajo): solo tiene una
pantalla para arrancarla o pararla, y trabaja en segundo plano. No hace inferencia, no decide
umbrales, no manda SMS — es puramente el "cable" entre el reloj y OSD.

Por decisión explícita del proyecto ([`DEC-057`](../DECISIONS.md)), si el Companion deja de recibir
datos del reloj o de poder hablarle a OSD, **no suena ni vibra nada** — se anota en silencio y se
te avisa con un resumen a la mañana (el resumen no está garantizado: ver `CAREGIVER_GUIDE.md`). Solo una alarma real de convulsión interrumpe. El detalle
completo de esa política está en `CAREGIVER_GUIDE.md`.

Esa política también rige en el **reloj**: una falla del sistema solo se muestra en la pantalla,
sin vibrar, y el aviso de "⚠ MONITOREO DEGRADADO" tampoco vibra. Solo vibra una alarma real. Si el
reloj y el teléfono tienen versiones distintas de SeizureGuard, el teléfono lo avisa en silencio con
una notificación **"SeizureGuard: update needed"** (y lo anota en el resumen de la mañana): actualizá
las dos apps a la misma versión.

---

## Paso a paso

### 1. Instalar las dos apps (reloj y Companion) con la misma firma

Esto es lo que más falla, así que va primero: el reloj y el Companion tienen que quedar **firmados
con exactamente la misma clave**, si no, volvemos al problema de "AppKey no coincide" de la
sección de arriba.

- Si compilás las dos apps (reloj y Companion) **en esta misma computadora**, se firman solas con
  la misma clave de desarrollo (`debug.keystore`) — no tenés que hacer nada extra.
- Si alguna se compiló en otra máquina, no van a coincidir y hay que volver a compilarlas juntas.

Los pasos exactos de compilación e instalación (comandos de `gradlew`, `adb`, cómo confirmar que
las dos firmas coinciden) están en **`HARDWARE_RUNBOOK.md`, sección 4.1 y 4.2** — no los repito acá
para no tener dos copias que se desactualicen distinto.

En resumen, son **dos APKs**: el del **reloj** (módulo `:wear`, sabor `companion` — el que se arma
con `.\gradlew.bat :wear:assembleCompanionDebug`) y el del **teléfono** (módulo `:phone`, el
Companion). Los dos salen de la rama `main`. El sabor `osdDirect` del reloj es solo para
desarrollo (le habla directo a OSD, muestra el cartel "VERSIÓN DE PRUEBA" y no sirve para esta
guía): no lo instales.

Instalá OSD también si todavía no lo tenés — OSD V5.0 (rama `beta`, la misma que usan el README y el runbook; el release V5.0.8 también trae el soporte de reloj) sirve; la versión ya no es la parte que traba la conexión.

### 2. Configurar OSD para que escuche al Companion, no al reloj directo

Abrí OSD en el teléfono y ajustá:

1. **Fuente de datos ("DataSource") = "Garmin"** (puede figurar como "Garmin Watch" en el menú de
   selección). **No** dejes activada "Android Wear" — ese es justo el camino roto.
2. El **servidor web de OSD** tiene que estar encendido (viene así por defecto). En la pestaña
   "System" de OSD figura como "Access Server at: http://...:8080" cuando está andando.
3. Apagá **"Enable Audible System FaultWarnings"** (en Ajustes de OSD). Esta opción viene
   **activada** por defecto y hace que OSD suene solo con sus propias fallas — justo lo contrario
   de la política de "fallas silenciosas" que eligió el proyecto. Si la dejás prendida, OSD va a
   sonar en momentos en los que no debería.
4. Cargá el número de teléfono del cuidador para el SMS, y confirmá que **MUTE** ("Mute Alarms")
   esté apagado.
5. Dejá el teléfono **cargando** durante la noche. Si se queda sin batería, OSD entra en falla y
   no puede sonar ninguna alarma.

### 3. Arrancar el puente (la app Companion)

Abrí **SeizureGuard Companion** en el teléfono. Es una sola pantalla, sin nada que mirar en vivo
mientras funciona — a propósito, para no tentarte a quedarte revisándola de noche.

1. Si aparece el botón **"Allow running in the background"**, tocalo y aceptá la excepción de
   batería (sin esto, Android puede llegar a matar el Companion mientras dormís).
2. Tocá **"Start bridge"**. Te va a pedir permiso de **notificaciones** y de **"Nearby devices"**
   (así le dice Android al permiso de Bluetooth). Sin el de Bluetooth el puente no puede arrancar.
3. Tiene que aparecer el mensaje **"Bridge started"** y quedar una notificación de baja
   importancia, **"SeizureGuard bridge running"**.

### 4. Confirmar que el reloj está monitoreando

Con la app SeizureGuard instalada en el reloj y el monitoreo arrancado:

- El reloj tiene que mostrar la notificación **"SeizureGuard activo"** (o "Monitoreo activo").
- Si ves **"⚠ MONITOREO DEGRADADO"**, el reloj no está mandando datos frescos — resolvé eso antes
  de seguir (ver `EXPLAINER_WEAR_DATA_LAYER.md`, sección del watchdog).

### 5. Verificar que los datos llegan de verdad a OSD

Con las tres piezas arrancadas (reloj, Companion, OSD), hay dos formas simples de confirmarlo:

- En OSD, la pestaña **"System"** debería mostrar datos recientes del reloj.
- Abrí el navegador **del propio teléfono** y entrá a `http://127.0.0.1:8080/data`: tiene que
  devolver un texto con un `timestamp` (marca de tiempo) que **avanza** cada vez que recargás la
  página. Si en cambio ves siempre el mismo texto de relleno, OSD está escuchando la fuente
  equivocada — volvé al paso 2.

Si en algún momento ves en el logcat del teléfono la línea `Failed to deliver message to
AppKey[...]`, es el problema de firma del paso 1: las dos apps no quedaron firmadas igual.

### 6. Probar que la alarma vuelve al reloj

Agitá el reloj con un movimiento rítmico y fuerte durante unos 30 segundos, hasta que OSD detecte
movimiento y pase a un estado de alarma. Tiene que pasar esto:

- OSD suena y manda el SMS al cuidador (como siempre).
- El reloj **vibra** para avisar que hay una alarma en curso — puede tardar hasta 10–15 segundos en
  enterarse, porque el estado viaja Companion → reloj cada tanto, no al instante.

Los pasos completos de esta prueba (incluyendo qué vibración corresponde a cada estado) están en
`HARDWARE_RUNBOOK.md`, sección 4.6, y la prueba nocturna completa en la sección 6/7 de ese mismo
documento.

---

## Si TODAVÍA no conecta

En orden de probabilidad (de más a menos común):

1. **Las dos apps del teléfono no quedaron firmadas igual** — la causa más común. Recompilá reloj y
   Companion en la misma computadora (paso 1) y confirmá con `apksigner verify --print-certs`
   (comando exacto en `HARDWARE_RUNBOOK.md` sección 4.2) que las dos firmas dan el mismo resultado.
2. **El reloj y el teléfono no están emparejados a nivel Bluetooth del sistema** (esto es distinto
   del `adb` para desarrollo — es el emparejamiento normal de reloj↔teléfono, vía la app Galaxy
   Wearable). Sin este emparejamiento base, ningún dato puede viajar. Confirmá en la app Galaxy
   Wearable del teléfono que el reloj figura como conectado.
3. **La app SeizureGuard del reloj no está corriendo el monitoreo** — revisá que la notificación
   "SeizureGuard activo" esté visible.
4. **OSD sigue en la fuente "Android Wear" en vez de "Garmin"** — volvé al paso 2. A veces se
   resetea al actualizar la app; volvé a chequearlo.
5. **El Companion no está corriendo** — abrí la app y confirmá que la notificación "SeizureGuard
   bridge running" siga ahí. Si no arrancó, revisá que le hayas dado permiso de Bluetooth
   ("Nearby devices").
6. **"Enable Audible System FaultWarnings" sigue activado en OSD** — no impide la conexión, pero
   hace que OSD suene por sus propias fallas; apagalo (paso 2).

---

## Para cuando funcione

Copiame lo que ves en pantalla (o un logcat si conseguiste cable) y lo interpretamos juntos — de ahí
seguís con `HARDWARE_RUNBOOK.md` secciones 4.7 en adelante (checklist de verificación, prueba
completa y prueba nocturna).

---

## Fuentes

- [`DEC-050`](../DECISIONS.md) (causa raíz confirmada: AppKey mismatch en el Wear Data Layer)
- [`DEC-051`](../DECISIONS.md) (arreglo elegido: app Companion en el teléfono que reenvía a OSD por HTTP, fuente "Garmin")
- [`DEC-057`](../DECISIONS.md) (política de fallas totalmente silenciosas, en el Companion y en el reloj desde el Batch 7)
- `FIELD_TEST_NOTES.md` (tu prueba de junio 2026, diagnóstico original + actualización de septiembre 2026)
- Release verificado: [OpenSeizureDetector V5.0.8](https://github.com/OpenSeizureDetector/Android_Pebble_SD/releases/tag/V5.0.8) (25/06/2026, merge de la rama `beta`)
- `WearSD` ([github.com/OpenSeizureDetector/WearSD](https://github.com/OpenSeizureDetector/WearSD)) — app de reloj oficial de OSD; funciona solo por compartir `applicationId` + firma con la app de teléfono
- `HARDWARE_RUNBOOK.md`, sección 4 — pasos exactos de compilación, instalación, firma y verificación del flujo de dos apps (comandos reales)
- `CAREGIVER_GUIDE.md` — la política completa de fallas silenciosas y qué hacer ante cada estado
- `EXPLAINER_WEAR_DATA_LAYER.md` — para entender qué significa cada paso mientras lo hacés
