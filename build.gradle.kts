// Root build.gradle.kts — declara plugins, no los aplica
// KSP es necesario para Room (code generation en Fase 3.5)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}

// Shared signing config source for :wear and :phone — see signing.gradle.kts for rationale.
// Loads (or no-ops if absent) the gitignored root keystore.properties; per-module signingConfigs
// blocks that reference `rootProject.extra["sharedKeystoreProperties"]` land with each module's own
// batch (watch-osd-message-delivery tasks.md T2.x / T7.x).
apply(from = "signing.gradle.kts")
