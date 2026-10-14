plugins {
    alias(libs.plugins.gim.android.library)
}

android {
    namespace = "im.g.apps.core.bridge"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.collections.immutable)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
}
