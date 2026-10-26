import im.g.apps.buildlogic.resolveDefaultHubUrl
import im.g.apps.buildlogic.resolveKeepServerUrl

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
        // Shown on Help and about: the first line of the root GIM_CORE_VERSION file (e.g. 0.1.0+c1b7503).
        val gimCoreVersion = rootProject.file("GIM_CORE_VERSION").readLines().first().trim()
        buildConfigField("String", "GIM_CORE_VERSION", "\"$gimCoreVersion\"")
        // Prefilled onboarding hub: -P/gradle.properties `gim.defaultHubUrl`, else env GIM_DEFAULT_HUB_URL, else the
        // built-in hub (see resolveDefaultHubUrl).
        val defaultHubUrl = resolveDefaultHubUrl(
            property = providers.gradleProperty("gim.defaultHubUrl").orNull,
            env = providers.environmentVariable("GIM_DEFAULT_HUB_URL").orNull,
        )
        buildConfigField("String", "DEFAULT_HUB_URL", "\"${defaultHubUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        // Default keep server (settings backup, user reports): -P/gradle.properties `gim.keepServerUrl`, else env
        // GIM_KEEP_SERVER_URL, else the built-in one; an empty or `off` override turns it off (see resolveKeepServerUrl).
        // The user's Settings choice overrides it at runtime.
        val keepServerUrl = resolveKeepServerUrl(
            property = providers.gradleProperty("gim.keepServerUrl").orNull,
            env = providers.environmentVariable("GIM_KEEP_SERVER_URL").orNull,
        )
        buildConfigField("String", "KEEP_SERVER_URL", "\"${keepServerUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        // libgim_core.so ships for these ABIs only; drops JNA's other ABIs from the APK.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures {
        buildConfig = true
    }

    // The manifest localeConfig (per-app language list on API 33+) comes from the values-<locale> folders;
    // res/resources.properties names the locale of the unqualified values/.
    androidResources {
        generateLocaleConfig = true
    }

    buildTypes {
        debug {
            // gim-push-mock base URL MockPushProvider polls when no debug setting overrides it: -P/gradle.properties
            // `gim.pushMockUrl`, else env GIM_PUSH_MOCK_URL; empty (default) leaves push off. Debug builds only.
            val pushMockUrl = providers.gradleProperty("gim.pushMockUrl").orNull?.takeIf { it.isNotBlank() }
                ?: providers.environmentVariable("GIM_PUSH_MOCK_URL").orNull.orEmpty()
            val pushMockUrlLiteral = pushMockUrl.trim().replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", "PUSH_MOCK_URL", "\"$pushMockUrlLiteral\"")
        }
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
    implementation(project(":feature:devices"))
    implementation(project(":feature:hubs"))
    implementation(project(":feature:linking"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    androidTestImplementation(libs.androidx.test.espresso.intents)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
