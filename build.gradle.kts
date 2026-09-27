buildscript {
    repositories { google() }
    dependencies { classpath("com.android.tools:r8:9.6.0-alpha14") }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("com.google.protobuf") version "0.10.1" apply false
    id("com.diffplug.spotless") version "8.11.0"
    id("dev.detekt") version "2.0.0-alpha.6" apply false
}

spotless {
    kotlin {
        target("app/src/**/*.kt", "pxp/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint()
            .editorConfigOverride(
                mapOf(
                    "ktlint_code_style" to "ktlint_official",
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                ),
            )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts", "publicsuffixlist/*.gradle.kts", "pxp/*.gradle.kts")
        ktlint()
            .editorConfigOverride(
                mapOf("ktlint_code_style" to "ktlint_official"),
            )
    }
}
