package im.g.apps.buildlogic

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.HasUnitTestBuilder
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal const val COMPILE_SDK = 35
internal const val MIN_SDK = 26
internal const val TARGET_SDK = 35

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        compileSdk = COMPILE_SDK

        defaultConfig {
            minSdk = MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            // Device tests read the gim-hub URL from this argument; override with
            // -Pandroid.testInstrumentationRunnerArguments.gimServerUrl=<url>. 10.0.2.2 is the emulator's host loopback.
            testInstrumentationRunnerArguments["gimServerUrl"] = "http://10.0.2.2:8080"
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        testOptions {
            unitTests.isIncludeAndroidResources = true
            unitTests.isReturnDefaultValues = true
        }

        lint {
            abortOnError = true
            error += "HardcodedText"
            // Every translated locale must cover all strings and its plural quantities.
            error += "MissingTranslation"
            error += "MissingQuantity"
        }
    }

    dependencies {
        add("androidTestImplementation", libs.lib("junit"))
        add("androidTestImplementation", libs.lib("androidx-test-core-ktx"))
        add("androidTestImplementation", libs.lib("androidx-test-ext-junit"))
        add("androidTestImplementation", libs.lib("androidx-test-runner"))
        add("androidTestImplementation", libs.lib("androidx-test-rules"))
    }

    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

// Unit tests target the debug variant (test manifest, demo mode); skip them for release so `check`
// can run alongside assembleRelease.
internal fun Project.disableReleaseUnitTests() {
    val androidComponents = extensions.getByName("androidComponents") as AndroidComponentsExtension<*, *, *>
    androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { variant ->
        (variant as? HasUnitTestBuilder)?.enableUnitTest = false
    }
}
