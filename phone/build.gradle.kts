// phone/build.gradle.kts — companion bridge module (watch-osd-message-delivery / Option F).
//
// Shares `applicationId` + signing key with `:wear` on purpose: the Wear Data Layer routes
// messages by AppKey = packageName + signing cert, so both modules must match exactly for the
// watch's DEC-046 messages to reach this module at all (design decision #1). `namespace` stays
// distinct (`com.seizureguard.phone`) to avoid `R`/`BuildConfig` collisions between modules.
//
// Zero new dependencies: reuses `libs.versions.toml` entries already used by `:wear`
// (core-ktx, play-services-wearable, coroutines, lifecycle). HTTP to OSD is done via
// `HttpURLConnection` (no OkHttp/Retrofit) — see design "Architecture Decision #1".
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// signing.gradle.kts (root, Batch 1) only loads keystore.properties and exposes it via
// rootProject.extra — it does not configure any module's signingConfigs. This module declares
// its own signingConfigs block referencing those shared values (see signing.gradle.kts header
// comment for the rationale).
@Suppress("UNCHECKED_CAST")
val sharedKeystoreProperties = rootProject.extra["sharedKeystoreProperties"] as Properties
val hasSharedSigningConfig = rootProject.extra["hasSharedSigningConfig"] as Boolean

android {
    namespace = "com.seizureguard.phone"
    compileSdk = 34

    defaultConfig {
        // Intentionally the SAME applicationId as :wear — see file header + design decision #1.
        applicationId = "com.seizureguard.wear"
        minSdk = 26          // Android 8.0+ — companion phone, not Wear OS
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasSharedSigningConfig) {
            create("release") {
                storeFile = file(sharedKeystoreProperties.getProperty("storeFile"))
                storePassword = sharedKeystoreProperties.getProperty("storePassword")
                keyAlias = sharedKeystoreProperties.getProperty("keyAlias")
                keyPassword = sharedKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No minification yet — this batch is a scaffold with no code to shrink/obfuscate.
            // Revisit once the bridge logic (Batch 3+) lands.
            isMinifyEnabled = false
            if (hasSharedSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Requerido para que Robolectric pueda leer src/test/assets/ (mismo patrón que :wear)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Wear Data Layer (recibe los mensajes DEC-046 del reloj)
    implementation(libs.play.services.wearable)

    // Coroutines — imprescindible para el foreground service de la Fase 5 (OsdBridgeService)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Lifecycle — serviceScope ligado al ciclo de vida del Service
    implementation(libs.androidx.lifecycle.runtime)

    // Unit tests (JVM, sin dispositivo — Robolectric). Mismo setup que :wear.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Instrumented tests (en el teléfono físico)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
