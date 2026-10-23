plugins {
    alias(libs.plugins.gim.android.feature)
}

android {
    namespace = "im.g.apps.feature.settings"
}

dependencies {
    implementation(libs.zxing.core)
}
