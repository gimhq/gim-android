pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gim-android"
include(":app")
include(":core:bridge")
include(":core:designsystem")
include(":feature:onboarding")
include(":feature:chatlist")
include(":feature:chat")
include(":feature:contacts")
include(":feature:devices")
include(":feature:linking")
