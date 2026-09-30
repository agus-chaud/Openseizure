import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// signing.gradle.kts (root, Batch 1) only loads keystore.properties and exposes it via
// rootProject.extra; this module declares its own signingConfigs from those shared values so
// :wear and :phone are signed with the SAME key (Wear Data Layer AppKey = package + cert).
@Suppress("UNCHECKED_CAST")
val sharedKeystoreProperties = rootProject.extra["sharedKeystoreProperties"] as Properties
val hasSharedSigningConfig = rootProject.extra["hasSharedSigningConfig"] as Boolean

android {
    namespace = "com.seizureguard.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.seizureguard.wear"
        minSdk = 30          // Wear OS 3+ (Galaxy Watch 4+)
        targetSdk = 34       // Wear OS 4 (Galaxy Watch 8)
        versionCode = 1
        versionName = "0.1.0"
        // Default transport: the SeizureGuard phone companion (flavor `companion`).
        // Overridden to true ONLY by the `osdDirect` flavor below.
        buildConfigField("boolean", "OSD_DIRECT_MODE", "false")
    }

    // Transport flavor dimension (watch-osd-message-delivery, design decision #7).
    // Both flavors compile the SAME sources (no flavor source sets): the wire format
    // (/osd/* paths + DEC-046 JSON) is identical, and the only real difference is the
    // applicationId, which the Wear Data Layer uses (with the signing cert) to route messages.
    //   companion (first-declared = default): applicationId com.seizureguard.wear ->
    //     messages reach the :phone companion, which shares that package + cert.
    //   osdDirect: applicationId uk.org.openseizuredetector -> messages reach OSD directly
    //     (the pre-companion behaviour, retained behind OSD_DIRECT_MODE, not deleted).
    flavorDimensions += "transport"
    productFlavors {
        create("companion") {
            dimension = "transport"
        }
        create("osdDirect") {
            dimension = "transport"
            applicationId = "uk.org.openseizuredetector"
            buildConfigField("boolean", "OSD_DIRECT_MODE", "true")
        }
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
            isMinifyEnabled = true
            if (hasSharedSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true   // genera BuildConfig (BuildConfig.DEBUG) — AGP 8 lo apaga por defecto
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Requerido para que Robolectric pueda leer src/test/assets/
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Safety finding F7: the osdDirect flavor is a developer-only escape hatch. It must never ship as a
// release artifact, so the osdDirectRelease variant is not created at all (osdDirectDebug remains).
androidComponents {
    beforeVariants(
        selector().withFlavor("transport" to "osdDirect").withBuildType("release")
    ) { variantBuilder ->
        variantBuilder.enable = false
    }
}

// With a flavor dimension AGP no longer creates the flavor-less `testDebugUnitTest` / `lintDebug`
// tasks (Gradle reports them as ambiguous), which would silently break the documented commands and
// CI (`:wear:testDebugUnitTest`, `:wear:lintDebug`). These aliases keep those names working and
// cover BOTH transport flavors, so the retained direct-to-OSD flavor cannot rot untested.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs debug unit tests for every transport flavor (companion + osdDirect)."
    dependsOn("testCompanionDebugUnitTest", "testOsdDirectDebugUnitTest")
}
tasks.register("lintDebug") {
    group = "verification"
    description = "Runs lint for the debug variant of every transport flavor."
    dependsOn("lintCompanionDebug", "lintOsdDirectDebug")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM — controla versiones
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.tooling)

    // Wear OS Compose
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)

    // Wear Data Layer (comunicación con la app OSD que corre la inferencia)
    implementation(libs.play.services.wearable)

    // NOTA: sin TFLite/ExecuTorch en el reloj. La inferencia corre en la app OSD V5.0
    // (ver engram architecture/seizureguard-executorch-api). El reloj solo captura y transmite.

    // Coroutines — imprescindible para el ForegroundService (Fase 1.1)
    implementation(libs.kotlinx.coroutines.android)

    // Coroutines extensions para GMS Tasks — Task.await() en WearDataLayerManager (Fase 2.1)
    implementation(libs.kotlinx.coroutines.play.services)

    // Lifecycle — serviceScope ligado al ciclo de vida del Service (Fase 1.2)
    implementation(libs.androidx.lifecycle.runtime)

    // Unit tests (JVM, sin dispositivo — Robolectric)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)   // ApplicationProvider en tests Robolectric

    // Instrumented tests (en el watch físico — Fase 0.4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
