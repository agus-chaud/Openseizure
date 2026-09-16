// signing.gradle.kts — shared signing config source for :wear and :phone.
//
// Why this exists (design decision #1, watch-osd-message-delivery / Option F):
// the Wear Data Layer routes messages by AppKey = packageName + signing cert. :wear and the future
// :phone module must be signed with the SAME key for the companion bridge to receive the watch's
// messages at all. This script is the single source of truth for that shared keystore.
//
// This script only LOADS the keystore properties and exposes them via `rootProject.extra`. It does
// NOT configure any module's `android { signingConfigs { ... } } ` block — each module (:wear,
// later :phone) declares its own `signingConfigs` referencing these shared values in its own
// build.gradle.kts, added alongside that module's own batch of changes (see
// openspec/changes/watch-osd-message-delivery/tasks.md, T2.x / T7.x). Keeping this script
// side-effect-free on the android extension keeps this PR (Batch 1) config-only and low-risk.
//
// Expects a gitignored `keystore.properties` at the repo root — copy `keystore.properties.template`
// and fill in real values. Today both modules are debug-signed by the same shared
// `~/.android/debug.keystore`, so this only matters once a module wires a release signingConfig
// against `hasSharedSigningConfig`.

import java.io.FileInputStream
import java.util.Properties

val keystorePropertiesFile = file("keystore.properties")
val keystoreProperties = Properties()
val hasSharedSigningConfig = keystorePropertiesFile.exists()

if (hasSharedSigningConfig) {
    FileInputStream(keystorePropertiesFile).use { stream -> keystoreProperties.load(stream) }
}

extra["sharedKeystoreProperties"] = keystoreProperties
extra["hasSharedSigningConfig"] = hasSharedSigningConfig
