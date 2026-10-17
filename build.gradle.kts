plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Run build-logic unit tests (e.g. DEFAULT_HUB_URL resolution) as part of the root `check`.
tasks.register("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
}
