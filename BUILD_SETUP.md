# BUILD_SETUP — Correr los tests del reloj SIN Android Studio (Windows)

> Receta de 5 pasos para preparar esta máquina y poder correr `./gradlew :wear:test`.
> Los pasos 1–4 son **una sola vez**. Después, verificar tu código es solo el paso 5.

Analogía: para cocinar necesitás **cocina (Java)**, **ingredientes (Android SDK)**,
**la receta apuntada (local.properties)** y **el robot de cocina (gradle wrapper)**.

---

## Paso 1 — Java 17 (la cocina)

El proyecto necesita Java **17** (AGP 8.5 no garantiza el 21).

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
```
Cerrá y reabrí la terminal, luego:
```powershell
java -version      # debe decir 17.x
```
Si sigue saliendo otra versión, apuntá `JAVA_HOME` al 17:
```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-17", "User")
```
(ajustá la ruta a donde se instaló).

---

## Paso 2 — Android SDK (los ingredientes), sin Android Studio

1. Bajá "Command line tools only": https://developer.android.com/studio#command-line-tools-only
2. Descomprimí en `C:\Android\cmdline-tools\latest\`
   (adentro de `latest\` deben quedar las carpetas `bin\`, `lib\`, etc.)
3. Apuntá la variable de entorno:
```powershell
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Android", "User")
```
4. Cerrá y reabrí la terminal. Instalá las piezas y aceptá licencias:
```powershell
& "C:\Android\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
& "C:\Android\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
```

---

## Paso 3 — local.properties (dónde están los ingredientes)

En la raíz del proyecto, creá un archivo `local.properties` con una línea:
```
sdk.dir=C:\\Android
```
> Doble barra `\\`. Este archivo es local — NO se sube a git.

---

## Paso 4 — Gradle wrapper (el robot de cocina)

Faltan `gradlew.bat` y `gradle-wrapper.jar`. Opciones:

**A) Si están en el repo remoto** (probá primero):
```powershell
git status        # ¿aparecen como faltantes?
git checkout -- gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar
```

**B) Si no están, regenerá el wrapper** (necesitás Gradle una vez):
```powershell
winget install Gradle.Gradle
gradle wrapper --gradle-version 8.9
```

---

## Paso 5 — Correr los tests

Parado en la carpeta del proyecto:
```powershell
.\gradlew.bat :wear:test
```
La primera vez tarda un par de minutos (baja dependencias). Esperado: `BUILD SUCCESSFUL`
con todos los tests del reloj en verde (`CircularBuffer`, `WearDataLayerManager`,
`SeizureMonitorService`, `CsvLogger`, etc.).

Para ver el reporte detallado si algo falla:
```
wear\build\reports\tests\testDebugUnitTest\index.html
```

---

## Resumen

| Paso | Qué | ¿Cuántas veces? |
|------|-----|-----------------|
| 1 | Java 17 | una vez |
| 2 | Android SDK | una vez |
| 3 | local.properties | una vez |
| 4 | gradle wrapper | una vez |
| 5 | `gradlew :wear:test` | cada vez que querés verificar |
