/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.squareup.okio:okio:3.18.2")

    compileOnly("com.android.tools.build:gradle:9.4.1")
}

gradlePlugin {
    plugins.register("mozac.PublicSuffixListPlugin") {
        id = "mozac.PublicSuffixListPlugin"
        implementationClass = "mozilla.components.gradle.plugins.PublicSuffixListPlugin"
    }
}
