# El modelo que detecta convulsiones

> Este documento asume que sabés programar (Python, arrays, funciones) pero no sabés qué es un tensor, un softmax, ni cómo funciona una red neuronal.
> Compañero de este doc: `EXPLAINER_WEAR_DATA_LAYER.md` (cómo llegan los datos hasta acá).
> **Marcadores:** `🔍 Detalle opcional` = no bloquea entender el resto, podes saltearlo .
> `❓ Antes de seguir` = pregunta corta para chequear que quedó adentro
---

## El protagonista de este documento: el mismo número de siempre

Si venís de `EXPLAINER_WEAR_DATA_LAYER.md`, ya conocés a **`1150`** (milli-g de magnitud del acelerómetro). Ahí lo dejamos llegando al teléfono. Acá seguimos su viaje: qué le hace el modelo a `1150` y a sus 749 compañeros de ventana hasta convertirse en un **sí/no de alarma**.

Si arrancás directo por este doc: `1150` es simplemente un número de aceleración que salió de tu reloj hace un instante. Alcanza con eso.

---

## El diagrama maestro (volvé acá cada vez que te pierdas)

```
①TENSOR        ②CONVOLUCIÓN×14        ③POOLING         ④DENSAS          ⑤SOFTMAX        ⑥ALARMA
(1,1,750)      Conv1d+BatchNorm+ReLU  GlobalAvgPool    64→64→32→16→2   logits→probs    p > umbral
   │                    │                   │                │               │             │
   └───750 muestras─────┴───────────────────┴────────────────┴───────────────┴─────────────┘
              (Parte 1)         (Parte 3)          (Parte 4.1)      (Parte 4.2)   (Parte 4.3-4.4)
```

---

## Parte 1 — Qué es un tensor

### 1.1 — El problema: los 750 números necesitan una forma de viajar juntos

Un modelo no recibe "un número por vez" — recibe **todos los 750 juntos, organizados en una forma exacta**. Esa forma tiene nombre: **tensor**.

### 1.2 — Tensor = array de NumPy

Es exactamente lo que ya conocés como array de NumPy o `DataFrame.values`. La única diferencia real es que las librerías de deep learning necesitan que ese array sepa además: en qué dispositivo vive (CPU/GPU) y si hay que calcular gradientes (para entrenar). Para vos, hoy: `tensor ≈ np.array`.

```python
import numpy as np
datos = np.array([998.2, 1150.0, 995.8, 1002.1, 999.0])
datos.shape   # (5,)
```

### 1.3 — El shape es lo único que te tenés que grabar

El 90% de los errores de deep learning son "shape mismatch". El shape del modelo de este proyecto es `(1, 1, 750)`:

| Posición | Valor | Qué significa |
|---|---|---|
| dim 0 | `1` | **batch**: cuántas ventanas proceso a la vez (acá: 1 reloj) |
| dim 1 | `1` | **canales**: cuántas señales por instante (acá: 1, ya redujimos x/y/z a una magnitud) |
| dim 2 | `750` | **tiempo**: 750 muestras = 30 segundos a 25Hz  |

```python
ventana = np.zeros((1, 1, 750))
ventana[0, 0, 384] = 1150.0   # nuestro número, en algún lugar de la ventana de 30s
```

### 1.4 — 🔍 Detalle opcional: por qué NO es `(1, 750, 1)`

TensorFlow/Keras suele usar `(batch, tiempo, canales)` = `(1, 750, 1)`. **PyTorch usa `(batch, canales, tiempo)`** = `(1, 1, 750)`. Este proyecto usa PyTorch/ExecuTorch. Confundir esto rompe todo en silencio (el modelo corre, pero interpreta mal los datos), exactamente el tipo de bug que hay que verificar contra código real, no memorizar de un README.

### 1.5 — 🔍 Detalle opcional: PyTorch vs TensorFlow — la diferencia real, sin tener que elegir

Ojo con una confusión común : `(1, 3, 750)` **no sería "una matriz de 3
dimensiones" en el sentido de tener MÁS dimensiones** — seguiría teniendo exactamente 3 dimensiones, igual que `(1, 1, 750)`. Lo único que cambiaría es el TAMAÑO de la dimensión del medio (3 en vez de 1). Es como la diferencia entre un Excel de 1 columna y uno de 3 columnas — ambos son "una tabla
2D", ninguno tiene "más dimensiones" que el otro por tener más columnas. **Rango** (cuántas dimensiones tiene) y **tamaño de cada dimensión** (cuánto mide cada una) son dos cosas distintas y mezclarlas es el error más común al empezar con tensores.

Ahora sí, la pregunta de fondo: **PyTorch y TensorFlow son dos librerías distintas que hacen básicamente lo mismo** —> ambas te dejan definir y entrenar redes neuronales. La diferencia no es "una puede hacer cosas que la otra no", es de **filosofía y ergonomía**:

| | PyTorch | TensorFlow (con Keras) |
|---|---|---|
| Cómo se define el cálculo  
"Define por ejecución": escribís código Python normal (`if`, loops) y el cálculo se arma a medida que corre | Históricamente armaba un "grafo" completo de antemano, antes de correr nada (TF2 ya se comporta más parecido a PyTorch por defecto) |
| Dónde se usa más  
Investigación/papers — la mayoría de los papers nuevos de IA publican código en PyTorch (como el paper de este proyecto, Parte 5) | Producción/mobile — TFLite (el "hermano" de ExecuTorch para TensorFlow) tiene años más de uso en apps |
| Se siente como 
Python puro, con clases (`nn.Module`) que vos escribís a mano | Una API más "de alto nivel" (Keras), con menos código pero menos control línea por línea |

**Por qué este proyecto usa PyTorch (vía ExecuTorch) y no TensorFlow (vía TFLite):** el paper que
define la arquitectura (Spahr et al., Parte 5) publicó su código en PyTorch — el equipo de OSD lo
adoptó tal cual para no reimplementar ni arriesgarse a introducir diferencias sutiles al traducir
de una librería a otra. **No es que TensorFlow sea peor para este caso** — de hecho, en el mismo
repo de entrenamiento existe `deepEpiCnnModel.py` (versión TensorFlow/Keras de la misma red,
mencionada en la Parte 5.1) como alternativa. Es una decisión de "usar lo que ya está probado y
funciona", no una limitación técnica de una librería sobre la otra.

**❓ Antes de seguir:** si el shape fuera `(1, 3, 750)` en vez de `(1, 1, 750)`, ¿qué habría
cambiado en cómo llega el dato del acelerómetro (no en cuántas dimensiones tiene el tensor — en
QUÉ significa cada una)? *(pista: repasá Parte 1.4 del doc de Wear — x/y/z, y la Parte 1.4 de este
doc sobre invariancia a la rotación)*

---

## Parte 2 — Qué es una red neuronal, cuándo sirve, y cómo aprende

### 2.1 — Qué es una red neuronal?

Una red neuronal es **una cadena de transformaciones** que convierte tu input (750 números) en un
output (2 números, uno por clase). Cada "capa" es una transformación.

```python
x = ventana_de_750                # shape (1, 1, 750)
x = transformacion_1(x)           # capa 1
# ... 12 capas más ...
x = transformacion_14(x)          # capa 14
output = x                        # shape (1, 2)
```

Las dos transformaciones que vas a ver una y otra vez: **convolución** y **capa densa**.

### 2.2 — ¿Cuándo conviene usar una red neuronal (y cuándo NO)?

**Conviene cuando:**
- El patrón que buscás es **demasiado complejo o sutil para describirlo con reglas escritas a
  mano**. Nadie puede escribir "si la aceleración supera X Y tiene forma Y Z entonces es
  convulsión" con reglas simples — el patrón real es demasiado variable entre personas y episodios.
  Una red neuronal, en cambio, puede APRENDER ese patrón de miles de ejemplos reales, sin que nadie
  tenga que formularlo explícitamente.
- Tenés **suficientes datos etiquetados** (ejemplos ya clasificados como "convulsión" / "no
  convulsión") para que la red tenga de dónde aprender. Sin datos, no hay nada que aprender.

**NO conviene (o es overkill) cuando:**
- La relación es simple y lineal — ahí una regresión lineal o logística  hace lo
  mismo, más rápido, con menos datos, y mucho más fácil de explicar por qué decidió lo que decidió.
- Tenés pocos datos — una red neuronal grande con pocos datos tiende a "memorizar" los ejemplos en
  vez de aprender el patrón general (esto se llama *overfitting*), y falla con casos nuevos.
- Necesitás poder explicar EXACTAMENTE por qué el modelo decidió algo, caso por caso — una red
  neuronal de 14 capas es difícil de auditar línea por línea).

Este caso concreto (seizure detection desde acelerómetro) cae del lado de "conviene": el patrón es
complejo, variable entre personas, y el proyecto tiene acceso a un dataset real (Open Seizure
Database) con miles de episodios etiquetados por médicos/usuarios.

### 2.3 — Cómo aprende una red sus pesos (y qué está tratando de optimizar)

Cuando arranca el entrenamiento, TODOS los números de TODAS las capas (los kernels de convolución
de la Parte 3, los pesos de las capas densas de la Parte 4.2) empiezan en **valores aleatorios**.
La red, al principio, literalmente no sabe nada — sus predicciones son básicamente random.

El proceso de entrenamiento, resumido en 4 pasos que se repiten miles de veces:

1. **Predicción (forward pass):** le mostrás a la red un ejemplo real del dataset (750 muestras de
   un episodio YA etiquetado como "convulsión" o "no convulsión") y la red da su respuesta actual
   (probablemente mal, al principio).
2. **Medir el error (loss function):** comparás la respuesta de la red contra la etiqueta real. Si
   la red dijo "20% probabilidad de convulsión" y en realidad SÍ era una convulsión, eso es un error
   grande. Ese error se resume en un solo número: la **loss** (pérdida). Cuanto más lejos está la
   predicción de la verdad, más alta la loss.
3. **Calcular qué ajustar (backpropagation):** acá está la matemática "mágica" de las redes — hay
   una forma de calcular, para CADA uno de los millones de números de la red, "si subiera este
   número un poquito, ¿la loss bajaría o subiría?". Eso es literalmente un gradiente (derivada) — la
   dirección en la que hay que mover cada peso para reducir el error.
4. **Ajustar un poquito (gradient descent):** se mueve cada peso un poquito en la dirección que
   reduce la loss (ni de golpe — un pasito chico, controlado por un parámetro llamado *learning
   rate*). Se repite con el próximo ejemplo, y con el próximo, miles de veces.

**Qué está tratando de optimizar, en criollo:** la red busca los valores de todos sus pesos que
hacen que, en promedio sobre TODOS los ejemplos de entrenamiento, sus predicciones se parezcan lo
más posible a las etiquetas reales — sin memorizar los ejemplos puntuales (para que funcione
también con episodios nuevos que nunca vio). Ese balance —aprender el patrón general, no memorizar—
es todo un tema aparte (se llama *generalización*), y es la razón por la que existe el `dropout`
que viste mencionado en el código de la Parte 5.

**Dato de este proyecto:** el entrenamiento real corre en `nnTrainer.py` (repo
`OpenSeizureDatabase`), sobre datos reales del Open Seizure Database — no en este repo, y tampoco en
el teléfono. El teléfono SOLO usa los pesos ya entrenados (el archivo `.pte`) para predecir , nunca
aprende ni ajusta nada en producción.

---

## Parte 3 — Convolución 1D: cómo la red "mira" a `1150` y su contexto

### 3.1 — ❓ El problema: `1150` solo, sin contexto, no dice nada

Un pico de `1150` puede ser un movimiento normal o el inicio de una convulsión — la diferencia está
en el PATRÓN alrededor de ese número (¿vino solo? ¿siguió temblando rítmicamente después?). La
convolución es la herramienta para que la red mire "ventanas" de contexto, no números sueltos.

### 3.2 — La idea con NumPy puro, antes de PyTorch

Una convolución 1D desliza una ventanita de pesos (el **kernel**) sobre la señal, calculando un
producto punto en cada posición — una media móvil ponderada, pero con pesos que la red **aprende**.

```python
import numpy as np

señal = np.array([998, 1001, 1150, 1400, 1200, 1000, 999])   # nuestro 1150 en contexto
kernel = np.array([0.2, 0.2, 0.2, 0.2, 0.2])                  # kernel de tamaño 5 (promedio simple)

salida = []
for i in range(len(señal) - len(kernel) + 1):
    salida.append(np.dot(señal[i:i+len(kernel)], kernel))

print(salida)   # señal MÁS CORTA (7-5+1=3) — el kernel "resume" cada ventana
```

**¿Por qué un producto punto, específicamente, y no otra operación?** Porque el producto punto es,
matemáticamente, una forma de medir **cuánto se parece** una cosa a otra. Cuando multiplicás cada
elemento del kernel por el elemento correspondiente de la señal y sumás todo, el resultado da MÁS
ALTO cuanto más "coincide" la forma del kernel con la forma de ese pedazo de señal — y da bajo (o
negativo) cuando no se parecen. Es literalmente una pregunta matemática: "¿este pedacito de señal
se parece al patrón que busco?", repetida en cada posición de la ventana.

El kernel actúa como un "detector de forma" — y la red, durante el entrenamiento (Parte 2.3), ajusta
los números del kernel hasta que ese detector se vuelve bueno para encontrar la forma que de verdad
distingue una convulsión de un movimiento normal.

La diferencia con nuestro ejemplo de `[0.2]*5`: ese kernel fijo solo promedia (detecta "algo estuvo
presente en general"). **El kernel real no es `[0.2]*5` fijo — son 5 números que la red AJUSTA
durante el entrenamiento** para volverse un detector de una forma específica (ej. "pico brusco
seguido de oscilación rítmica" = firma de convulsión), no un simple promedio.

### 3.3 — 🔍 Detalle opcional: por qué "1D" y no "2D" como en imágenes

En imágenes la convolución es 2D (alto × ancho de píxeles). Acá la señal es una sola dimensión
temporal — por eso `Conv1d`, no `Conv2d`. Mismo concepto, una dimensión menos.

### 3.4 — El vocabulario que vas a ver en el código real

| Parámetro | Qué significa | Analogía |
|---|---|---|
| `kernel_size` | Ancho de la ventanita | Tamaño de ventana en `rolling().mean()` de pandas |
| `stride` | Cuánto salta el kernel entre pasada y pasada | `stride=2` = salta de a 2, reduce la señal a la mitad |
| `padding='valid'` (=0) | No rellena bordes, la salida es más corta | Como `mode='valid'` en `np.convolve` |

Cada capa de este modelo usa `kernel_size=5`; la mayoría `stride=1`, salvo cada 5ta capa (`stride=2`).

### 3.5 — 🔍 Por qué hay que ir "resumiendo" la señal (y por qué justo cada 5ta capa)

Pensalo en términos de qué tan lejos "mira" cada capa. La primera capa, con `kernel_size=5`, mira
ventanas de apenas 5 muestras (0.2 segundos) — puede notar cosas MUY locales, como "¿hubo un salto
brusco acá?". Pero una convulsión no se define por un instante de 0.2s — se define por un PATRÓN
que persiste varios segundos (temblor rítmico sostenido). Para que una capa pueda "ver" eso, necesita
mirar una ventana de tiempo mucho más ancha que 5 muestras.

Hay dos formas de lograr eso: (a) usar un kernel gigante desde el principio (carísimo
computacionalmente, y en la práctica no funciona tan bien), o (b) **apilar capas progresivamente,
reduciendo la resolución temporal a medida que avanzás** — así cada capa nueva, aunque siga mirando
solo 5 posiciones de SU entrada, esas 5 posiciones ya representan un tramo más ancho de la señal
ORIGINAL (porque las capas anteriores ya comprimieron el tiempo). Es la opción (b) la que usa este
modelo — por eso hace falta "resumir": cada `stride=2` duplica efectivamente cuánto tiempo real
representa cada punto que le queda a la señal.

**¿Por qué justo cada 5ta capa y no cada 2da o cada 10ma?** Es una decisión de diseño del paper
(Spahr et al.) — un balance entre "reducir muy rápido" (perdés detalle fino demasiado pronto) y
"reducir muy lento" (las capas finales siguen mirando ventanas muy angostas, sin captar el patrón
completo de varios segundos). Cada 5 capas es lo que a los autores les funcionó mejor en sus
experimentos con el dataset real — no hay una fórmula matemática única que diga "tiene que ser 5",
es un hiperparámetro ajustado empíricamente.

### 3.6 — Después de cada convolución: `BatchNorm` + `ReLU`

Se repite 14 veces — dos piezas, cada una resuelve un problema distinto:

**`ReLU`** — `max(0, x)`: apaga negativos, deja pasar positivos.

```python
def relu(x): return np.maximum(0, x)
```
Sin esto, apilar capas seguiría siendo matemáticamente equivalente a UNA sola capa lineal — apilar
14 no ganaría nada, porque combinar transformaciones puramente lineales sigue siendo lineal. `ReLU`
rompe esa linealidad, y es justamente esa "no-linealidad" lo que le permite a la red aprender
patrones complejos (curvas, combinaciones, excepciones) en vez de solo relaciones tipo "más de esto
= más de aquello".

**`BatchNorm`** — reescala los números a media~0/varianza~1 entre capas. Es como cuando varias
personas cantan juntas y una grita mucho más fuerte que las demás — un director de coro las nivela
para que ninguna voz tape al resto y se pueda escuchar la mezcla completa. `BatchNorm` hace eso con
los números entre una capa y la siguiente.

**¿Qué significa "no converger" (lo que pasaría sin `BatchNorm`)?** Entrenar una red es ir bajando
la loss (Parte 2.3) de a poquito, capa tras capa, ejemplo tras ejemplo. **"Converger"** significa
que ese proceso llega a asentarse en un buen valor bajo y estable — el modelo "aprendió". **"No
converger"** significa que la loss NUNCA se estabiliza: puede quedar dando vueltas sin bajar nunca,
o directamente crecer descontroladamente (a esto se lo llama a veces que el entrenamiento
"explota"). Con 14 capas apiladas, si los números de una capa salen muy grandes o muy chicos, ese
efecto se multiplica capa tras capa (como una voz que grita cada vez más fuerte en cada repetición)
hasta volverse un caos numérico que ningún ajuste de pesos logra estabilizar. `BatchNorm` evita ese
efecto bola de nieve manteniendo los números en un rango razonable en cada paso.

**Contrafactual, resumido:**

| Con `BatchNorm` | Sin `BatchNorm` |
|---|---|
| Los números se mantienen en un rango parecido capa tras capa | Un valor grande en la capa 3 se amplifica en la 4, más en la 5... hasta la 14 |
| La loss (Parte 2.3) baja de forma estable durante el entrenamiento | La loss oscila sin bajar, o crece sin control ("explota") |
| El modelo converge en un tiempo razonable | El entrenamiento puede no converger nunca, sin importar cuánto tiempo lo dejes corriendo |

**❓ Antes de seguir:** si `stride=1` en todas las capas (nunca `stride=2`), ¿qué le pasaría al
tamaño de la señal a medida que avanza por las 14 capas, comparado con lo que pasa hoy?
*(pista: `kernel_size=5, padding=0` ya la achica un poco cada vez, incluso sin stride 2)*

---

## Parte 4 — De 750 números a "sí/no convulsión"

### 4.1 — Global Average Pooling: comprimir todo en un vector

**Con números reales, para que no quede abstracto:** arrancás con 750 muestras y 1 canal. Cada
convolución (kernel=5, sin padding) recorta 4 muestras de la señal aunque no tenga `stride=2`; y
cada 5ta capa además la parte al medio (`stride=2`). Haciendo la cuenta capa por capa, al llegar a
la capa 14 la dimensión temporal bajó de **750 a aproximadamente 76** — pero mientras tanto la
cantidad de CANALES subió de **1 a 64** (según la lista `filters` de la Parte 3.4/5.2). Es un
intercambio a propósito: **cambiaste "muchos instantes de tiempo, poca información por instante"
por "pocos instantes, pero cada uno resumiendo muchísima información" (64 features aprendidas en
vez de 1 magnitud cruda).**

El **Global Average Pooling** da el paso final de esa idea: promedia, para CADA uno de los 64
canales, esos ~76 valores que quedan — colapsa el tiempo a 1 solo número por canal.

```python
canales = np.random.rand(64, 12)     # 64 canales, 12 pasos de tiempo restantes
vector_final = canales.mean(axis=1)  # shape (64,) — un promedio por canal
```

Es como preguntarle a cada persona de un grupo "del 1 al 10, ¿cuánto te gustó la película?" y
quedarte con el promedio de cada persona a lo largo de toda la charla — no importa en qué momento
exacto dijo su opinión más alta, solo te queda un número resumen por persona. Acá "cada persona" es
un canal, y el resumen es cuánto se activó ese canal en promedio durante los 30 segundos.

### 4.2 — Capas densas: "multiplicación de matriz + bias", a mano

Vamos con números chiquitos para que quede clarísimo, antes de volver a los 64/32/16 reales.
Supongamos que tu vector de entrada tiene solo 2 números: `x = [3, 5]`, y la capa densa lo tiene
que convertir en un vector de 1 número.

**Bias primero, porque es lo más simple:** el bias es exactamente lo mismo que el "+ b" (ordenada
al origen) de una recta `y = mx + b` que ya viste en cualquier curso de estadística — un número
fijo que se SUMA al final, independiente del input. Le permite a la capa decir "aunque todos los
inputs den 0, la salida no tiene por qué ser 0" — más libertad para ajustar.

**Ahora la multiplicación de matriz — es simplemente hacer VARIAS combinaciones lineales a la vez.**
Una combinación lineal es lo que ya conocés: `3*w1 + 5*w2` (cada input multiplicado por un peso, y
sumado). Una capa densa hace ESO, pero varias veces en paralelo, una por cada neurona de salida —
cada neurona tiene SU PROPIO juego de pesos:

```
Input:  x = [3, 5]
Pesos de la neurona A:  wA = [0.5, 0.2]   →  salidaA = 3*0.5 + 5*0.2 = 2.5
Pesos de la neurona B:  wB = [-0.1, 0.9]  →  salidaB = 3*(-0.1) + 5*0.9 = 4.2
Pesos de la neurona C:  wC = [0.0, 0.3]   →  salidaC = 3*0.0 + 5*0.3 = 1.5

Salida = [2.5, 4.2, 1.5] + bias  ← esto es 2 inputs → 3 outputs
```

`x @ W` (la "multiplicación de matriz") es exactamente hacer esas 3 combinaciones lineales de una
sola vez, acomodando todos los pesos (`wA`, `wB`, `wC`) como columnas de una matriz `W`. No hay
ninguna operación nueva ahí — es la misma cuenta de `3*w1 + 5*w2` que ya sabías hacer, aplicada
varias veces en paralelo, con álgebra lineal como taquigrafía para no escribir 64 líneas sueltas.

```python
def capa_densa(x, W, b): return x @ W + b   # x:(64,) W:(64,32) → (32,)
```

Este modelo encadena `64 → 64 → 32 → 16 → 2` — en cada flecha, un `x @ W + b` distinto (con su
propia matriz `W` y bias `b`, ambos APRENDIDOS durante el entrenamiento de la Parte 2.3), seguido de
BatchNorm + ReLU, igual que en las convoluciones.

### 4.3 — Softmax: de "2 números cualquiera" a "2 porcentajes que suman 100%"

**El problema, antes de la fórmula:** la última capa densa devuelve 2 números crudos (**logits**) —
por ejemplo `[0.3, 2.1]`. Estos NO son probabilidades todavía: podrían ser negativos, podrían sumar
cualquier cosa (acá suman 2.4, no 1.0), y no hay forma de leerlos como "tanto por ciento de
confianza". Necesitamos convertirlos en algo interpretable: dos porcentajes que sumen exactamente
100% (o 1.0) — "70% de que NO es convulsión, 30% de que SÍ" tiene sentido; "0.3 de que no y 2.1 de
que sí" no significa nada por sí solo.

**La idea más simple que se te podría ocurrir** sería dividir cada logit por la suma de todos:
`0.3 / 2.4 = 0.125` y `2.1 / 2.4 = 0.875`. Suma 1, ¡ya está! Pero esto tiene un problema: si un
logit fuera negativo (ej. `[-1.0, 2.1]`), dividir por la suma directamente puede dar resultados sin
sentido (porcentajes negativos). Necesitamos algo que primero garantice que todo sea positivo, Y
que además "decida con confianza" cuando la diferencia entre los dos números es grande. Ahí entra
la exponencial.

```python
import numpy as np
logits = np.array([0.3, 2.1])   # [score "no convulsión", score "convulsión"]

def softmax(logits):
    exp = np.exp(logits - np.max(logits))   # -max: estabilidad numérica, no cambia el resultado
    return exp / exp.sum()

print(softmax(logits))   # ej: [0.14, 0.86] → suman 1.0
```

**Paso a paso de qué hace esta función:**
1. `np.exp(x)` (exponencial) convierte CUALQUIER número, incluso negativo, en un número positivo —
   y cuanto más grande era el original, exponencialmente más grande queda (`exp(2.1)` es mucho más
   grande que `exp(0.3)`, no solo un poquito más).
2. Dividir cada uno por la suma de todos los exponenciales fuerza a que el resultado sume
   exactamente 1 — ahora sí son probabilidades válidas.

**Por qué exponencial y no otra función que también dé positivos:** la exponencial **amplifica
diferencias** — si un logit es bastante más alto que el otro, softmax lo empuja mucho más cerca de
1, no lo deja en un tibio 55%. Por eso el modelo da respuestas "decididas" (ej. 86%, no 51%) cuando
la señal es clara, en vez de porcentajes tibios cerca del 50/50 todo el tiempo.

Código real (`SdAlgMl.java`, lado OSD):

```java
private static float softmaxProb(float[] scores, int index) {
    float max = Math.max(scores[0], scores[1]);
    double e0 = Math.exp(scores[0] - max);
    double e1 = Math.exp(scores[1] - max);
    return (index == 1) ? (float)(e1/(e0+e1)) : (float)(e0/(e0+e1));
}
```

Es la misma función de NumPy de arriba, a mano en Java (ExecuTorch en Android no trae softmax de
librería lista para un array crudo).

### 4.4 — El umbral: de probabilidad a decisión (¿y de dónde sale el 0.5?)

```python
p_seizure = probs[1]   # ej. 0.86
alarma = p_seizure > 0.5   # umbral configurable, NO hardcodeado
```

**Qué análisis debería hacerse (la teoría):** con un modelo de clasificación binaria como este, el
umbral es un dial que mueve un trade-off — subirlo reduce falsas alarmas pero puede dejar pasar
convulsiones reales; bajarlo atrapa más convulsiones reales pero genera más falsas alarmas. Las
herramientas estándar para elegir un umbral "bueno" con evidencia (no a ojo) son:
- **Curva ROC**: grafica, para CADA umbral posible, cuántos verdaderos positivos atrapás (TPR /
  sensibilidad) contra cuántos falsos positivos generás (FPR). Te deja elegir el umbral que da el
  mejor punto de esa curva para tu caso de uso.
- **Estadístico J de Youden** (`TPR - FPR`): un solo número que resume "qué tan bueno" es un umbral,
  buscando el punto de mejor balance.
- **F-beta score**: parecido, pero podés pesarlo para priorizar sensibilidad (atrapar convulsiones)
  por sobre precisión (evitar falsas alarmas) — que es justo lo que uno querría en este dominio,
  porque perder una convulsión real es mucho más grave que una falsa alarma de más.

**Lo que encontré en el código real** (`OpenSeizureDatabase/user_tools/nnTraining2/`): el proyecto
SÍ usa exactamente estas herramientas durante el **entrenamiento**. `nnTrainer.py` tiene un sistema
de selección de checkpoints (`MODEL_SELECTION_GUIDE.md`) con métricas configurables como `"youden"`
(Youden's J) o `"f_beta"` (con `fBeta=2.0` para priorizar sensibilidad) — decide **cuál versión
entrenada de los pesos conservar**, rechazando candidatos con FPR por encima de un límite
configurable.

**El hallazgo más interesante :** buscando cómo ese análisis
de entrenamiento llega (o no) hasta el teléfono, encontré que **el modelo SÍ puede traer su propio
umbral recomendado, y la app lo aplica automáticamente.** En `MlModelManager.java` (líneas 277-315),
la función `applyRecommendedThresholdsFromModel()` lee un campo `seizure_probability_threshold_pct`
de los metadatos que vienen empaquetados junto con el modelo al descargarlo, y si está presente,
**reemplaza el `0.5` genérico por ese valor específico** — pero SOLO si el cuidador no fijó un valor
manual antes (`PREF_ML_SEIZURE_PROB_THRESHOLD_USER_SET`).

**Verificado con tests reales** (`MlModelManagerTest.java`):
- `testApplyRecommendedThresholdsFromModel_appliesWhenUserNotSet` (línea 161): un modelo con
  metadata `{"seizure_probability_threshold_pct": 65}` hace que el umbral efectivo pase a ser 65%,
  no el 50% genérico — ESTO es lo que conectaría con el análisis Youden/F-beta de arriba, si el
  entrenamiento exportó ese número calculado en los metadatos del modelo.
- `testApplyRecommendedThresholdsFromModel_skipsWhenUserSet` (línea 180): si el cuidador ya ajustó
  el umbral a mano, el modelo NO lo pisa — la decisión humana manda por sobre la recomendación
  automática.

**Lo que sigue sin poder verificar:** si los metadatos concretos de `deepEpiCnn_2026_01_24_Run24`
efectivamente incluyen un `seizure_probability_threshold_pct` calculado con Youden/F-beta, o si ese
campo viene vacío y por eso cae al `DEFAULT_ML_SEIZURE_PROB_THRESHOLD_PCT = "50"` genérico — esos
metadatos no están en ningún repo que clonamos (probablemente viven en el servidor de distribución
de modelos, fuera de nuestro alcance).

**Conclusión honesta, actualizada:** existe una cadena completa y real —Youden/F-beta en
entrenamiento → posible exportación a metadata → aplicación automática en el teléfono— que SÍ
conecta el análisis estadístico con el umbral de producción. Lo que no pude confirmar es si esa
cadena se usó de punta a punta para el modelo Run24 específico, o si por ahora el `50` genérico
sigue activo porque la metadata no trae el campo. Es una pregunta puntual y verificable: bastaría
con mirar el JSON de metadata que se descargó junto al `.pte` real en tu instalación de OSD.

---

## Parte 5 — La arquitectura real completa: DeepEpiCnn, 14 capas

### 5.1 — Fuente académica real

```
Spahr A., Bernini A., Ducouret P., et al.
"Deep learning–based detection of generalized convulsive seizures
using a wrist-worn accelerometer", Epilepsia, 2025.
DOI: https://doi.org/10.1111/epi.18406
```

Código en `OpenSeizureDetector/OpenSeizureDatabase`, `user_tools/nnTraining2/deepEpiCnnModel_torch.py`
— clonado en una carpeta local (`<carpeta-del-clon-de-OpenSeizureDatabase>`) para citar código real, no de memoria.

### 5.2 — La arquitectura completa, en palabras

Ya tenés todas las piezas de las Partes 3 y 4 — acá está cómo se ensamblan, sin código, como una
receta de pasos:

1. **Entra el tensor** `(1, 1, 750)` — 30 segundos de magnitud de aceleración (Parte 1).
2. **14 veces seguidas**, se repite el mismo trío: *convolución* (un detector de forma que mira una
   ventanita, Parte 3.2) → *BatchNorm* (nivela los números, Parte 3.6) → *ReLU* (apaga negativos,
   Parte 3.6). Cada una de esas 14 repeticiones tiene SU PROPIO kernel aprendido — no se repite el
   mismo detector, cada capa aprende a buscar algo distinto (las primeras, formas simples y locales;
   las últimas, patrones más amplios y abstractos, construidos sobre lo que detectaron las
   anteriores).
3. La cantidad de "detectores en paralelo" por capa (los **filtros**) crece a medida que avanzás:
   arranca en 16, sube a 32 durante 11 capas seguidas, termina en 64. Al mismo tiempo, **cada 5ta
   capa reduce a la mitad la resolución temporal** (Parte 3.5) — el clásico intercambio de las CNN:
   menos puntos en el tiempo, pero cada uno cargando más información resumida.
4. Al terminar las 14 capas, el **Global Average Pooling** (Parte 4.1) colapsa lo que queda de
   tiempo en un solo número por canal — te quedan 64 números en total, uno por cada "detector"
   final.
5. Esos 64 números atraviesan **4 capas densas** (Parte 4.2) que los van combinando y reduciendo:
   64 → 64 → 32 → 16 → **2**. Los últimos 2 números son los logits.
6. **Softmax** (Parte 4.3) convierte esos 2 logits en 2 probabilidades que suman 100%.
7. El **umbral** (Parte 4.4) convierte esa probabilidad en una decisión concreta: alarma o no.



### 5.3 — 🔍 Detalle opcional: de PyTorch a ExecuTorch — por qué hace falta este paso extra

**El problema:** el modelo se entrena con PyTorch "completo" — una librería pesada, pensada para
correr en una computadora con GPU, que además necesita todo un intérprete de Python funcionando
alrededor. Un teléfono Android no tiene Python instalado, y cargar una librería de decenas de MB
solo para correr un modelo ya entrenado sería un desperdicio enorme de espacio y batería — como
llevarte toda la cocina de un restaurante a upa para calentar un solo plato ya preparado en tu casa.

**La solución — separar "entrenar" de "usar lo ya entrenado":** una vez que el modelo terminó de
aprender sus pesos (Parte 2.3) en una compu con GPU, esos pesos quedan FIJOS — ya no van a cambiar
nunca más en el teléfono. Para simplemente APLICAR un modelo ya entrenado (sin entrenar nada nuevo),
no hace falta la maquinaria completa de PyTorch — alcanza con un programa mucho más chico que sepa
hacer una sola cosa: tomar el tensor de entrada y multiplicar/sumar según los pesos ya fijos, capa
por capa, hasta llegar al resultado.

**ExecuTorch** es exactamente ese programa chico: un *runtime* (programa que ejecuta algo, sin
necesitar el entorno completo donde se creó) pensado para mobile. El proceso es: entrenás en
PyTorch normal → "exportás"/comprimís el modelo ya entrenado a un archivo `.pte` (10-20MB, mucho
más liviano que la librería completa) → ese archivo es lo único que viaja al teléfono → ExecuTorch
(una librería mucho más chica, sin Python) lo lee y lo ejecuta.

En el código real de OSD, esto se ve así: se carga el archivo `.pte` una sola vez al arrancar la
app, y después cada inferencia es solo "meterle el tensor de 750 números y leer los 2 logits que
devuelve" — toda la complejidad de las 14 capas ya quedó empaquetada adentro del archivo `.pte`, el
teléfono no necesita saber nada de convoluciones ni de BatchNorm para poder USAR el modelo, solo
necesita poder ejecutarlo.

### 5.4 — 🔍 Detalle opcional: lo que "Run24" probablemente significa

`deepEpiCnn_2026_01_24_Run24.pte`. La arquitectura (`DeepEpiCnn`) es pública y la verificamos.
"Run24" no aparece en ningún repo público que encontramos — probablemente un run id de experiment
tracking (ej. "la corrida 24 de entrenamiento", común en MLflow/Weights&Biases). **Honestidad: no
lo pude verificar contra fuente pública** — si te importa qué distingue a esa corrida específica,
habría que preguntarle al equipo de OpenSeizureDetector.

**❓ Antes de seguir:** ¿por qué el `AdaptiveAvgPool1d(1)` hace que el modelo funcione igual aunque
la señal de entrada tenga un largo temporal levemente distinto después de las convoluciones,
mientras que la cabeza densa (`Linear`) necesita un tamaño de entrada FIJO? *(pista: promedio vs
multiplicación de matriz con forma exacta)*

---

## El viaje completo de `1150`, de una — resumen final

```
① `1150` es uno de los 750 números del tensor (1,1,750) que llegó del reloj
② Atraviesa 14 capas Conv1d+BatchNorm+ReLU — cada una lo mezcla con sus vecinos
   temporales y lo reduce, buscando el patrón "convulsión" (stride=2 cada 5 capas)
③ Global Average Pooling lo funde en un promedio junto a los demás — ya no es
   "un número", es parte de un vector de 64
④ Las 4 capas densas (64→64→32→16→2) lo transforman hasta 2 logits
⑤ Softmax convierte esos logits en [p_no_seizure, p_seizure], ej. [0.14, 0.86]
⑥ p_seizure > 0.5 (umbral configurable) → decisión de alarma, vuelve al reloj
```

---

## Ejercicio de cierre

**Sin volver a leer el documento:** explicá con tus palabras qué pasaría si alguien reemplazara el
`softmax` final por simplemente devolver los 2 logits crudos como si fueran probabilidades (sin
convertirlos). Dale un ejemplo numérico concreto de logits que rompería esa lógica.

*(Si te trabaste, la respuesta está en la Parte 4.3 — pero intentalo primero de memoria, ahí es
donde queda fijado.)*

---

## Glosario rápido

| Término | Qué es |
|---|---|
| Tensor | Un array de NumPy con superpoderes (device, gradientes) — hoy, `≈ np.array` |
| Rango vs tamaño | Rango = cuántas dimensiones tiene un tensor; tamaño = cuánto mide cada una. `(1,1,750)` y `(1,3,750)` tienen el mismo rango (3) |
| Loss function | Un número que mide qué tan mal predijo la red — el entrenamiento busca bajarlo |
| Backpropagation / gradient descent | El método para calcular cómo ajustar cada peso, y moverlo un poquito, para bajar la loss |
| Overfitting | Cuando la red memoriza los ejemplos de entrenamiento en vez de aprender el patrón general |
| Bias | El "+ b" de una capa densa — un número fijo que se suma, como la ordenada al origen de una recta |
| ROC / Youden's J / F-beta | Herramientas para elegir un umbral de decisión con evidencia, balanceando sensibilidad vs falsas alarmas |
| Shape | Dimensiones de un tensor, ej. `(1, 1, 750)` = batch × canales × tiempo |
| Convolución 1D | Ventanita de pesos APRENDIDOS deslizándose sobre una señal temporal |
| Kernel | Tamaño de esa ventanita (acá: 5) |
| Stride | Cuánto salta el kernel entre pasada y pasada |
| ReLU | `max(0, x)` |
| BatchNorm | Reescala valores entre capas, como un `StandardScaler` interno |
| Global Average Pooling | Promedia toda la dimensión temporal, colapsa a un vector fijo |
| Capa densa (`Linear`) | Multiplicación de matriz + bias — regresión lineal encadenada |
| Logits | Números crudos de salida, antes de softmax — no son probabilidades |
| Softmax | Convierte logits en probabilidades que suman 1, amplificando la mayor |
| Umbral | Corte (ej. 0.5) que convierte una probabilidad en decisión sí/no |
| ExecuTorch / `.pte` | Runtime liviano de PyTorch para mobile |

---

## Respuestas a las preguntas del documento

**Parte 1.4 — si el shape fuera `(1, 3, 750)` en vez de `(1, 1, 750)`, ¿qué habría cambiado?**
Habría significado que el modelo recibe los 3 ejes crudos (x, y, z) por separado, sin reducirlos a
una sola magnitud — 3 canales de 750 muestras cada uno, en vez de 1 canal. Este proyecto SÍ reduce
a magnitud (Parte 1.4 del doc de Wear: `√(x²+y²+z²)`), por eso el canal es 1. Un modelo con 3
canales existiría, pero necesitaría haber sido entrenado con esa forma de entrada — no es
intercambiable con este.

**Parte 3.5 — si `stride=1` en TODAS las capas (nunca `stride=2`), ¿qué le pasaría al tamaño de la
señal a lo largo de las 14 capas, comparado con hoy?**
La señal se seguiría achicando un poco en cada capa igual (por `kernel_size=5, padding=0`: una
convolución "valid" siempre pierde algunos valores en los bordes), pero MUCHO más lento que hoy —
hoy, cada 5ta capa además la reduce a la mitad de un salto (`stride=2`). Sin esos saltos, al llegar
a la capa 14 quedaría una señal bastante más larga que la que realmente queda con el diseño actual,
y el modelo tendría muchos más cálculos que hacer por cada inferencia (más lento, más batería).

**Parte 5.4 — ¿por qué `AdaptiveAvgPool1d(1)` tolera un largo variable pero `Linear` necesita un
tamaño fijo?**
`AdaptiveAvgPool1d(1)` simplemente PROMEDIA todos los valores que le lleguen — no importa si son 10
o 15 valores, el promedio siempre da un solo número por canal. `Linear`, en cambio, es una
multiplicación de matriz (`x @ W + b`): la matriz `W` tiene un tamaño FIJO de filas/columnas
definido de antemano, así que necesita que `x` tenga exactamente el tamaño que esa matriz espera —
no podés multiplicar una matriz de 64×64 por un vector de 70 elementos.

**Ejercicio de cierre — ¿qué pasaría sin softmax, devolviendo los logits crudos como si fueran
probabilidades?**
Ejemplo: logits `[-3.0, 8.5]`. Sin softmax, alguien podría leer "8.5" como si fuera una probabilidad
— pero las probabilidades tienen que estar entre 0 y 1, y sumar 1 entre todas las clases. `8.5` no
es una probabilidad válida de nada. Con softmax, esos mismos logits se convierten en algo como
`[0.00003, 0.99997]` — ahí sí tenés un número interpretable ("99.997% de confianza en la clase
convulsión") y comparable directamente contra un umbral como 0.5.

---

## Fuentes primarias

- Spahr A. et al., ["Deep learning–based detection of generalized convulsive seizures using a wrist-worn accelerometer"](https://doi.org/10.1111/epi.18406), Epilepsia, 2025
- PyTorch, [`torch.nn.Conv1d` docs](https://docs.pytorch.org/docs/stable/generated/torch.nn.Conv1d.html)
- PyTorch, [ExecuTorch overview](https://docs.pytorch.org/executorch/stable/index.html)
- Código real: `user_tools/nnTraining2/deepEpiCnnModel_torch.py` (repo OpenSeizureDatabase), `app/src/main/java/uk/org/openseizuredetector/alg/SdAlgMl.java` (repo OSD, rama `beta`)

---

*¿Algo no te cerró? Volvé a preguntar señalando la sección exacta.*
