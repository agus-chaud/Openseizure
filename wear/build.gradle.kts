plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.seizureguard.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.seizureguard.wear"
        minSdk = 30          // Wear OS 3+ (Galaxy Watch 4+)
        targetSdk = 34       // Wear OS 4 (Galaxy Watch 8)
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
