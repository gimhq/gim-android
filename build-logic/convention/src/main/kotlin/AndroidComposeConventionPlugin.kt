import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import im.g.apps.buildlogic.lib
import im.g.apps.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val android = extensions.findByType(LibraryExtension::class.java)
            ?: extensions.findByType(ApplicationExtension::class.java)
            ?: error("gim.android.compose requires an Android application or library plugin")
        (android as CommonExtension<*, *, *, *, *, *>).buildFeatures.compose = true

        dependencies {
            val bom = platform(libs.lib("androidx-compose-bom"))
            add("implementation", bom)
            add("implementation", libs.lib("androidx-compose-ui"))
            add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
            add("implementation", libs.lib("androidx-compose-material3"))
            add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
            add("debugImplementation", libs.lib("androidx-compose-ui-test-manifest"))

            add("testImplementation", bom)
            add("testImplementation", libs.lib("robolectric"))
            add("testImplementation", libs.lib("androidx-compose-ui-test-junit4"))
            add("testImplementation", libs.lib("androidx-test-core-ktx"))
            add("testImplementation", libs.lib("androidx-test-ext-junit"))

            add("androidTestImplementation", bom)
            add("androidTestImplementation", libs.lib("androidx-compose-ui-test-junit4"))
            add("androidTestImplementation", libs.lib("androidx-test-espresso-core"))
        }
    }
}
