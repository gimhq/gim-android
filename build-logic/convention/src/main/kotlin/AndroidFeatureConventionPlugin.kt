import im.g.apps.buildlogic.lib
import im.g.apps.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("gim.android.library")
        pluginManager.apply("gim.android.compose")

        dependencies {
            add("implementation", project(":core:bridge"))
            add("implementation", project(":core:designsystem"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("kotlinx-collections-immutable"))
            add("implementation", libs.lib("coil-compose"))
        }
    }
}
