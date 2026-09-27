import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("com.android.library")
    id("dev.detekt")
}

abstract class BuildPxpNative : DefaultTask() {
    @get:Internal
    abstract val crateDirectory: DirectoryProperty

    @get:InputFiles
    abstract val crateSources: ConfigurableFileCollection

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Internal
    abstract val cargoHome: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun run() {
        val output = outputDirectory.get().asFile
        output.mkdirs()
        exec.exec {
            workingDir = crateDirectory.get().asFile
            environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
            environment("RUSTFLAGS", "--remap-path-prefix=${cargoHome.get()}=/cargo")
            commandLine(
                "cargo",
                "ndk",
                "-t",
                "arm64-v8a",
                "-o",
                output.absolutePath,
                "build",
                "--release",
                "--locked",
            )
        }
    }
}

val pxpCrate = layout.projectDirectory.dir("pxp-crypto")

val buildPxpNative =
    tasks.register<BuildPxpNative>("buildPxpNative") {
        crateDirectory.set(pxpCrate)
        crateSources.setFrom(
            pxpCrate.file("Cargo.toml"),
            pxpCrate.file("Cargo.lock"),
            fileTree(pxpCrate.dir("src")),
        )
        ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
        cargoHome.set(
            providers
                .environmentVariable("CARGO_HOME")
                .orElse(providers.environmentVariable("HOME").map { "$it/.cargo" }),
        )
        outputDirectory.set(layout.buildDirectory.dir("generated/jniLibs"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildPxpNative,
            BuildPxpNative::outputDirectory,
        )
    }
}

android {
    namespace = "app.keyholm.pxp"
    ndkVersion = libs.versions.ndk.get()
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 2
            }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 35
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        // Only releases fail on newer dependency versions
        if (!providers.gradleProperty("checkDependencyVersions").isPresent) {
            disable += "GradleDependency"
        }
        warningsAsErrors = true
        abortOnError = true
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Robolectric shadows reference hidden framework classes that only exist inside its sandbox
    exclude("**/shadows/**")
    testLogging {
        events("passed", "skipped", "failed")
    }
    // Robolectric reflects into JDK internals that JPMS keeps closed by default.
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
    )
}

detekt {
    source.setFrom("src/main/java")
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
    buildUponDefaultConfig = true
    parallel = true
    ignoredBuildTypes.addAll("debug")
}

val testPxpNative =
    tasks.register<Exec>("testPxpNative") {
        workingDir = pxpCrate.asFile
        commandLine("cargo", "test", "--locked")
    }

val lintPxpNative =
    tasks.register<Exec>("lintPxpNative") {
        workingDir = pxpCrate.asFile
        commandLine("cargo", "clippy", "--locked", "--all-targets", "--", "-D", "warnings")
    }

tasks.named("check") {
    dependsOn("detektMain", testPxpNative, lintPxpNative)
}

dependencies {
    implementation("androidx.annotation:annotation:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.upokecenter:cbor:4.5.6")
    implementation("co.touchlab:kermit:2.2.0")
    implementation("com.squareup.okio:okio:3.18.2")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.truth:truth:1.4.5")

    // Robolectric tests run on JUnit 4 via the vintage engine, alongside the Jupiter tests.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("org.robolectric:robolectric:4.17")
}
