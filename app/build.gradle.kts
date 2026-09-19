import com.android.build.api.variant.DeviceTestBuilder
import com.android.build.api.variant.HostTestBuilder

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.protobuf")
    id("dev.detekt")
}

dependencyLocking {
    lockMode.set(LockMode.STRICT)
}

configurations.configureEach {
    // staging is a release-like build with debug signing essentially
    if (isCanBeResolved &&
        !name.startsWith("staging") &&
        (name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath"))
    ) {
        resolutionStrategy.activateDependencyLocking()
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("staging")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
        variant.deviceTests[DeviceTestBuilder.ANDROID_TEST_TYPE]?.enable = false
        variant.enableLint = false
    }
    onVariants { variant ->
        val apkName =
            if (variant.buildType == "release") "keyholm-unsigned.apk" else "keyholm.apk"
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName
                ?.set(apkName)
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.2"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

android {
    namespace = "app.keyholm"
    compileSdk = 37

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "app.keyholm"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = null
        }
        create("staging") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Robolectric needs the merged manifest and resources to host Compose content.
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        jniLibs {
            // AGP's bundled NDK strip tool chokes on this prebuilt native lib
            // (transitive via androidx.core-ktx/Compose UI's PathIterator support).
            keepDebugSymbols.add("**/libandroidx.graphics.path.so")
            // Prebuilt and already stripped; ships via androidx.datastore:datastore.
            keepDebugSymbols.add("**/libdatastore_shared_counter.so")
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        // Compat drawable loading exists for pre-API-21 vector and theme handling.
        disable += "UseCompatLoadingForDrawables"
        warningsAsErrors = true
        abortOnError = true
    }
}

// Robolectric reflects into JDK internals that JPMS keeps closed by default.
tasks.withType<Test>().configureEach {
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

composeCompiler {
    // com.google.protobuf.ByteString is immutable but the compiler can't tell (external
    // abstract class). This makes classes holding it infer as stable at the class level.
    // It does NOT fix lambda-capture identity comparison - those types still need an
    // explicit @Immutable/@Stable (see PasskeyRecord).
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
}

detekt {
    toolVersion = "2.0.0-alpha.6"
    source.setFrom("src/main/java")
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
    buildUponDefaultConfig = true
    parallel = true
    ignoredBuildTypes.addAll("debug", "staging")
}

tasks.named("check") {
    dependsOn("detektMain")
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.datastore:datastore:1.2.1")
    implementation("com.google.protobuf:protobuf-javalite:4.36.2")
    implementation("androidx.biometric:biometric:1.4.0-alpha07")
    implementation("androidx.biometric:biometric-compose:1.4.0-alpha07")
    implementation("com.upokecenter:cbor:4.5.6")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.navigation3:navigation3-runtime:1.1.7")
    implementation("androidx.navigation3:navigation3-ui:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("co.touchlab:kermit:2.2.0")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.google.truth:truth:1.4.5")

    // Robolectric tests (anything touching a real Context) run on JUnit 4 via the
    // vintage engine, alongside the JUnit 5/Jupiter tests above on the same platform runner.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
