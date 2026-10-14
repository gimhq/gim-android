plugins {
    alias(libs.plugins.gim.android.library)
}

android {
    namespace = "im.g.apps.core.bridge"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        // Vendored UniFFI bindings (scripts/sync-gim-core.sh) are generated code; never edited here.
        lintConfig = file("lint.xml")
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.collections.immutable)
    implementation(libs.jna) { artifact { type = "aar" } }

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
}
