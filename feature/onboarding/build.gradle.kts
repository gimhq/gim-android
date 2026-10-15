plugins {
    alias(libs.plugins.gim.android.feature)
}

android {
    namespace = "im.g.apps.feature.onboarding"
}

dependencies {
    implementation(libs.zxing.core)
}
