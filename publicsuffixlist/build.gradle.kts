plugins {
    id("com.android.library")
    id("mozac.PublicSuffixListPlugin")
}

android {
    namespace = "mozilla.components.lib.publicsuffixlist"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 1
            }
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}

publicSuffixList {
    sourceFile = file("effective_tld_names.dat")
}

dependencies {
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
