plugins {
    alias(libs.plugins.gim.android.feature)
}

android {
    namespace = "im.g.apps.feature.chat"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
