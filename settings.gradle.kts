pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SeizureGuard"
include(":wear")
include(":phone")
// :phone reincorporado (watch-osd-message-delivery / Option F, Batch 2): companion bridge que
// traduce los mensajes DEC-046 del reloj al ingreso HTTP Garmin de OSD (127.0.0.1:8080). La
// inferencia y las alarmas siguen corriendo en la app OSD V5.0 — este módulo no hace ML.
// Ver engram architecture/seizureguard-executorch-api y
// openspec/changes/watch-osd-message-delivery/design.md.
