pluginManagement {
    includeBuild("plugins/publicsuffixlist") {
        name = "publicsuffixlist-plugin"
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://androidx.dev/snapshots/builds/16801255/artifacts/repository") {
            content { includeGroup("androidx.biometric") }
        }
    }
}
rootProject.name = "Keyholm"
include(":app")
include(":publicsuffixlist")
