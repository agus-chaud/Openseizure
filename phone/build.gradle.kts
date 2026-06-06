plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)               // KSP para Room (Fase 3.5)
}

android {
    namespace = "com.seizureguard.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.seizureguard.phone"
        minSdk = 26
        targetSdk = 34
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Necesario para que el modelo ExecuTorch (.pte) no se comprima dentro del APK
    // (la inferencia corre en el teléfono con ExecuTorch — ver architecture/seizureguard-inference-location)
    aaptOptions {
        noCompress += "pte"
    }

    // Requerido para que Robolectric pueda leer src/test/assets/ (loader test)
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.tooling)
    implementation(libs.androidx.compose.material3)

    // Wear Data Layer (recibir mensajes del watch)
    implementation(libs.play.services.wearable)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Lifecycle — ViewModel para AlarmActivity y configuración (Fase 3.2, 3.4)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)

    // Room — historial de eventos SeizureEvent (Fase 3.5)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)      // KSP genera los DAOs en compile-time

    // ExecuTorch (PyTorch Edge) — la inferencia corre acá, en el teléfono.
    // Modelo: deepEpiCnn_2026_01_24_Run24.pte. Coordenada Maven PENDIENTE DE VERIFICAR
    // (ExecuTorch Android no está en el version catalog todavía — ver nota en CLINICAL_SIGNOFF / handoff).
    // implementation(libs.executorch)   // <-- descomentar cuando se confirme la coordenada

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)  // loader test lee src/test/assets/ en JVM
    androidTestImplementation(libs.androidx.junit)
}
