plugins {
    alias(libs.plugins.gim.android.feature)
}

android {
    namespace = "im.g.apps.feature.linking"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
}
