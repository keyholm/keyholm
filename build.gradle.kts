plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("com.google.protobuf") version "0.10.0" apply false
    id("com.diffplug.spotless") version "8.10.3"
    id("dev.detekt") version "2.0.0-alpha.6" apply false
}

spotless {
    kotlin {
        target("app/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint("1.8.0")
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
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint("1.8.0")
            .editorConfigOverride(
                mapOf("ktlint_code_style" to "ktlint_official"),
            )
    }
}
