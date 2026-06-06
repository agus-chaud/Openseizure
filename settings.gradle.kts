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
// :phone retirado (2026-06-05): la inferencia y las alarmas las hace la app OSD V5.0.
// Este repo solo aporta el lado reloj. Ver engram architecture/seizureguard-executorch-api.
