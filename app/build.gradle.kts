plugins {
    alias(libs.plugins.gim.android.application)
}

android {
    namespace = "im.g.apps"

    defaultConfig {
        applicationId = "im.g.apps"
        versionCode = 1
        versionName = "0.1.0"
        // Select the gim-core UniFFI client instead of the fake (debug builds can opt into demo mode at
        // runtime, see AppContainer.PREF_DEMO_MODE).
        buildConfigField("boolean", "USE_REAL_GIM_CORE", "true")
        // Prefilled onboarding server URL: -P/gradle.properties `gim.defaultHubUrl`, else env GIM_DEFAULT_HUB_URL.
        val defaultHubUrl = providers.gradleProperty("gim.defaultHubUrl").orNull?.takeIf { it.isNotBlank() }
            ?: providers.environmentVariable("GIM_DEFAULT_HUB_URL").orNull.orEmpty()
        val hubUrlLiteral = defaultHubUrl.trim().replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "DEFAULT_HUB_URL", "\"$hubUrlLiteral\"")
        // libgim_core.so ships for these ABIs only; drops JNA's other ABIs from the APK.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Release keys and store publishing are out of scope for M2; sign with the debug key.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":core:bridge"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:chatlist"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:contacts"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    androidTestImplementation(libs.androidx.test.espresso.intents)
}
