plugins {
    alias(libs.plugins.gim.android.library)
    alias(libs.plugins.gim.android.compose)
}

android {
    namespace = "im.g.apps.core.designsystem"
}

dependencies {
    implementation(libs.coil.compose)
}
