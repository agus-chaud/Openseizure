// Root build.gradle.kts — declara plugins, no los aplica
// KSP es necesario para Room (code generation en Fase 3.5)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}
