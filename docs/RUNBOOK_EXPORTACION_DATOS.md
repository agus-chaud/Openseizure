# Runbook: registrar convulsiones y exportar datos de salud

Guía operativa para juntar, mes a mes, los datos que después permitan buscar patrones
entre el sueño, el estrés y las convulsiones.

> **Este documento no es de programación.** No hay que escribir código para seguirlo.
> El código viene al final, y recién cuando haya datos que valga la pena mirar.

---

## Antes de empezar: dónde estás parado hoy

Hay que decirlo claro porque cambia todo lo demás:

**Hoy tenés cero eventos registrados.** OSD nunca llegó a funcionar con el reloj, así que no
hay nada que exportar del lado de las convulsiones.

Eso significa que **este proyecto hoy no es de análisis: es de acumulación.** El objetivo de
los próximos meses no es encontrar un patrón. Es juntar filas. Cualquier conclusión sacada
antes de tiempo va a ser ruido disfrazado de hallazgo, y en un producto de seguridad de vida
eso es peligroso, no solo incorrecto.

La buena noticia: el lado del sueño **sí** tiene historia. Samsung Health viene registrando
desde que usás el reloj. Ese cuaderno ya está escrito — el que falta es el otro.

---

## El mapa completo

```
  FASE 0            FASE 1              FASE 2            FASE 3           FASE 4
  ──────            ──────              ──────            ──────           ──────
  Que el reloj  →   Configurar      →   Exportar      →   Rutina       →   Notebook
  hable con         Data Sharing        Samsung           mensual          y análisis
  el teléfono       y marcar            Health
                    eventos

  ↑ ESTÁS ACÁ       arranca el          se puede          durante          cuando haya
                    mismo día           hacer ya          meses            suficientes "SÍ"
```

Las fases 0 y 1 son bloqueantes: sin ellas no se registra ni un evento. La fase 2 se puede
hacer hoy mismo aunque las otras no estén listas.

---

## FASE 0 — Que el reloj hable con el teléfono

**Estado: bloqueante. Es lo único que importa hasta que esté resuelto.**

No repito acá lo que ya está escrito. Seguí **[`GUIA_CONECTAR_RELOJ_TELEFONO.md`](GUIA_CONECTAR_RELOJ_TELEFONO.md)**
de punta a punta.

El resumen de por qué falló la vez pasada: el reloj y OSD no comparten *AppKey* (nombre de
paquete + certificado de firma), así que Google Play Services descarta los mensajes del reloj
antes de que OSD los vea. Es independiente de la versión de OSD (se comprobó con V5.0.8, V5.0.9 y
la beta). No configuraste nada mal. El arreglo ya se eligió (`DEC-051`): una segunda app propia,
**SeizureGuard Companion**, en el mismo teléfono, que le reenvía a OSD lo que recibe del reloj. El
detalle completo está en [`DEC-050`](../DECISIONS.md), [`DEC-051`](../DECISIONS.md) y en
`GUIA_CONECTAR_RELOJ_TELEFONO.md`.

**Cómo sabés que la fase 0 está cerrada:** la pantalla de OSD deja de decir *"Data source
fault"* y la pestaña "System" empieza a mostrar datos recientes del reloj. Eso es la prueba
de que las tres piezas (reloj, Companion y OSD) se están hablando.

- [ ] Reloj y Companion instalados con la **misma firma** (mismo `applicationId` y misma clave)
- [ ] OSD configurado con la fuente de datos **"Garmin"** (no "Android Wear") y "Enable Audible System FaultWarnings" apagado
- [ ] App del reloj corriendo, notificación "SeizureGuard activo" visible
- [ ] App **SeizureGuard Companion** corriendo en el teléfono ("Start bridge" tocado, notificación "Bridge running" visible)
- [ ] OSD deja de decir "Data source fault" y muestra datos recientes del reloj

---

## FASE 1 — Configurar Data Sharing y empezar a marcar eventos

**Estado: hacelo el mismo día que cierres la fase 0. Sin esto, los eventos que se registren
no van a servir para el análisis.**

### Por qué esta fase existe

OSD detecta alrededor del **76% de las convulsiones reportadas** con su configuración por
defecto, y además genera falsas alarmas por movimientos repetitivos. Eso rompe los datos por
los dos lados a la vez:

| Problema | Qué pasa | Efecto en la tabla |
|---|---|---|
| Falsa alarma | Sonó la alarma pero no hubo convulsión | Una fila marcada "SÍ" que en realidad es "no" |
| Convulsión no detectada | Hubo convulsión y la alarma no sonó | Una fila marcada "no" que en realidad es "SÍ" |

El segundo es el más traicionero, porque contamina justamente el grupo contra el que vas a
comparar. Es como medir el efecto de un remedio con un grupo de control que también lo tomó.

**La solución existe y ya está adentro de OSD:** cada evento se puede marcar como convulsión
genuina o como falsa alarma. Esa marca —no la alarma cruda— es la que después se usa como
columna de resultado.

Pero solo existe si la venís poniendo. Por eso esta fase arranca el día uno y no cuando
tengas ganas de analizar.

### Pasos

1. **Crear una cuenta en la base de datos de OSD** (la Open Seizure Database). Se registra
   desde la app o desde el sitio del proyecto.
2. **Loguear la app OSD del teléfono contra esa cuenta.** Sin login, no se generan Events.
3. **Activar el registro de eventos a archivo.** En OSD: menú → *settings* →
   **"General – General Preferences"** → activar **"Log Alarm events to SD Card"**.
   (Dejá "Log Data to SD Card" apagado por ahora: genera muchísimo volumen y no lo necesitás
   para esto.)
4. **Y la parte que no es técnica y es la más importante:** cada vez que suene una alarma,
   entrar a OSD y **marcar el evento** como convulsión real o falsa alarma. Idealmente el
   mismo día, mientras la memoria está fresca.

> **Este paso 4 es el proyecto.** Todo lo demás es infraestructura alrededor de que alguien
> se acuerde de marcar. Si se abandona a los dos meses, no hay análisis posible después.
> Vale la pena acordarlo explícitamente con el cuidador, que es quien se despierta.

- [ ] Cuenta creada en la base de OSD
- [ ] App OSD logueada
- [ ] "Log Alarm events to SD Card" activado
- [ ] Acordado con el cuidador quién marca los eventos y cuándo

### Qué hacer con una convulsión que OSD no detectó

Si hubo una convulsión y la alarma nunca sonó, **igual hay que anotarla**. OSD permite
reportar un evento manualmente. Si por algún motivo no se puede, anotalo aparte con fecha y
hora aproximada: esa fila es información valiosa, no un hueco.

---

## FASE 2 — Exportar los datos de Samsung Health

**Estado: se puede hacer hoy, sin esperar a las fases 0 y 1.**

Esta es la parte que ya tiene historia acumulada. Vale la pena hacer una exportación ahora
mismo, aunque no la uses todavía, para confirmar que funciona y ver qué trae.

### Por qué esta ruta y no Health Connect

Se evaluaron las dos. Samsung Health entrega **archivos CSV listos para usar**. Health Connect
entrega un `.zip` con una **base de datos SQLite** adentro — se puede leer, pero hay que
consultarla, y esa función está pensada para mudar datos de un teléfono a otro, no para
analizarlos. Además exige Android 14 o superior.

Para lo que necesitamos, Samsung Health es el camino corto.

### Aclaración importante sobre el estrés

Circula la idea de que el dato de estrés de Samsung está bloqueado. **Es cierto solo a
medias:** hay un permiso de socio que Samsung exige, pero eso aplica a *una app que quiere
leer el dato en vivo*. No aplica a vos bajándote tus propios datos.

La descarga personal incluye estrés, variabilidad del pulso (HRV), fases del sueño, pulso,
oxígeno en sangre, temperatura de piel y respiración.

### Pasos

1. Abrí **Samsung Health** en el teléfono.
2. Andá a **My page** (Mi página).
3. Tocá el **menú de tres puntos** arriba a la derecha → **Settings**.
4. Bajá hasta **Download personal data** y tocá **Download**.
5. Verificá con tu cuenta Samsung (huella o contraseña).
6. Esperá a que termine. Puede tardar.

### Dónde queda el archivo

```
/sdcard/Download/Samsung Health/samsunghealth_<tucuenta>_<fecha>/
```

Para pasarlo a la computadora, cualquiera de las dos:

- Por cable USB, copiando la carpeta a mano.
- Por consola:

```bash
adb pull "/sdcard/Download/Samsung Health" .
```

### Qué esperar adentro

**Preparate para el volumen:** una exportación reportada pesaba unos 236 MB repartidos en
~23.000 archivos. No te asustes.

La estructura es:

- Un puñado de **CSV de resumen**, uno por métrica. Se nombran con el patrón
  `com.samsung.health.<metrica>.csv`. **Estos son los que importan.**
- Una carpeta enorme de **JSON crudo**. Ignorala por completo para este trabajo.

De los CSV, buscá los que correspondan a: **sueño (con fases), estrés, HRV y pulso**. Con esos
cuatro alcanza y sobra para arrancar.

- [ ] Primera exportación hecha
- [ ] Carpeta copiada a la computadora
- [ ] Identificados los CSV de sueño, estrés y HRV
- [ ] Confirmado que las fechas cubren el período que te interesa

---

## FASE 3 — La rutina mensual

**Estado: durante meses. Es la fase más larga y la más aburrida, y es la que decide si esto
funciona o no.**

Una vez por mes, el mismo día (elegí uno y anotalo):

1. Exportar Samsung Health (Fase 2, pasos 1 a 6).
2. Exportar o copiar el log de eventos de OSD.
3. Guardar ambos en una carpeta con la fecha:
   `datos/2026-09/`, `datos/2026-10/`, y así.
4. **Revisar que los eventos del mes estén todos marcados** como genuinos o falsos.

Y una anotación honesta que conviene llevar: **cuántos eventos confirmados llevás
acumulados**. Ese número —no la cantidad de noches— es el que dice si ya se puede analizar
algo.

```
Mes        Eventos confirmados    Acumulado
2026-09            0                  0
2026-10            .                  .
```

---

## FASE 4 — El notebook

**Estado: recién cuando el acumulado lo justifique. No antes.**

La tabla final tiene una fila por noche y se ve así:

| fecha | dormí_hs | sueño_profundo_hs | estrés_prom | hubo_convulsión |
|---|---|---|---|---|
| 2026-09-20 | 7.2 | 1.4 | 32 | no |
| 2026-09-21 | 5.1 | 0.8 | 58 | **SÍ** |
| 2026-09-22 | 6.8 | 1.2 | 30 | no |

El cruce es por **fecha**, y nada más. Esa es toda la integración: no hace falta programar
nada en Android, ni tiempo real, ni que las dos apps se hablen. Los dos cuadernos se
encuentran en tu computadora y en ninguna otra parte.

### La forma del problema

El cuaderno de sueño escribe **todas** las noches. El de convulsiones escribe **muy de vez en
cuando**. Así que la tabla va a ser casi toda "no", y toda la información vive en las pocas
filas que dicen "SÍ".

Consecuencia directa: el número que manda **no es cuántas noches tenés** —vas a tener cientos
en dos meses— sino **cuántos eventos confirmados tenés**.

---

## La regla de parada (escribila ANTES de mirar los datos)

Esto es lo que te protege del modo de falla principal de todo el proyecto.

El día que tengas tres eventos y uno haya caído después de una mala noche, tu cabeza va a
encontrar el patrón sola. Es lo que hacen las cabezas. La única defensa que funciona es
haberte comprometido de antemano.

**Referencias para elegir tu número:**

- Para una comparación simple (horas de sueño en noches con convulsión vs sin convulsión):
  del orden de **20 a 30 eventos confirmados**.
- Para mirar dos o tres variables juntas (sueño + estrés): del orden de **30 a 60**.

Completá esto y no lo toques después:

```
Mi regla de parada
──────────────────
No voy a sacar ninguna conclusión hasta tener ___ eventos confirmados.
Firmado: ____________   Fecha: __________
```

### Y cuando llegues a ese número

Aun ahí, lo que tengas es una correlación en una muestra chica de una sola persona. Habilita
decir *"en mis datos, estas dos cosas aparecieron juntas N veces"*. **No** habilita decir
*"esto me causa las convulsiones"*. Esa distinción no es un tecnicismo: es la diferencia
entre información útil y una decisión médica tomada sobre ruido.

---

## Qué falta verificar

Honestidad sobre los límites de este documento. Lo siguiente **no** está confirmado contra
documentación oficial, porque la documentación pública de OSD está atrasada respecto de la
versión que vas a correr:

- Las **columnas exactas** del CSV de eventos de OSD.
- La **ruta exacta** del archivo en un Android moderno. La documentación dice "SD card", que
  es lenguaje de hace diez años; hoy casi seguro va a almacenamiento interno.
- Si la función **"Export Data"** de la pantalla de Data Sharing (agregada en v4.1) exporta
  eventos o datos crudos.
- Los **nombres exactos** de los CSV de Samsung Health para sueño y estrés.

Las cuatro se resuelven abriendo las apps una vez y mirando. Cuando lo hagas, actualizá esta
sección con lo que encontraste — el próximo que lea esto (probablemente vos, en seis meses)
te lo va a agradecer.

---

## Resumen en cinco líneas

1. Arreglar la conexión reloj-teléfono. Sin eso no hay nada.
2. Configurar Data Sharing y **marcar cada evento** como real o falso. Todos los meses.
3. Exportar Samsung Health una vez por mes.
4. Guardar todo por fecha y llevar la cuenta de eventos confirmados.
5. No analizar nada hasta llegar al número que escribiste de antemano.

---

## Fuentes

- [Descargar datos personales de Samsung Health — Samsung](https://www.samsung.com/ae/support/apps-services/how-can-i-retrieve-my-personal-data-from-samsung-health)
- [Contenido y ubicación del export de Samsung Health](https://bhived.ai/lessons/export-samsung-health-data-without-root)
- [Backup y restauración de Health Connect — Ayuda de Android](https://support.google.com/android/answer/15323271?hl=en)
- [Health Connect — Android Developers](https://developer.android.com/health-and-fitness/health-connect)
- [Acceso e interpretación de archivos de log — OpenSeizureDetector](https://www.openseizuredetector.org.uk/?page_id=801)
- [Data Sharing y la Open Seizure Database — OpenSeizureDetector](https://www.openseizuredetector.org.uk/static/osd_pages/pages-user/data-sharing/index.html)

## Documentos relacionados

- [`GUIA_CONECTAR_RELOJ_TELEFONO.md`](GUIA_CONECTAR_RELOJ_TELEFONO.md) — Fase 0, paso a paso
- [`../HARDWARE_RUNBOOK.md`](../HARDWARE_RUNBOOK.md) — instalación de las apps y validación del transporte
- [`../FIELD_TEST_NOTES.md`](../FIELD_TEST_NOTES.md) — qué pasó en la prueba de campo de junio 2026
- [`EXPLAINER_WEAR_DATA_LAYER.md`](EXPLAINER_WEAR_DATA_LAYER.md) — cómo se hablan el reloj y el teléfono
- [`../openspec/changes/context-logging/exploration.md`](../openspec/changes/context-logging/exploration.md) — por qué se eligió este camino y qué se descartó
