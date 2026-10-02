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
    }
}
rootProject.name = "Keyholm"
include(":app")
include(":publicsuffixlist")
