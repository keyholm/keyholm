import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import groovy.json.JsonSlurper
import java.io.RandomAccessFile
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import javax.inject.Inject

buildscript {
    repositories { google() }
    dependencies { classpath("com.android.tools.build:apksig:9.5.2") }
}

abstract class VerifyAssetLinks : DefaultTask() {
    @get:Input
    @get:Option(option = "apk", description = "APK to check, relative to the root project")
    abstract val apk: Property<String>

    @get:Inject
    abstract val layout: ProjectLayout

    @TaskAction
    fun verify() {
        val apkFile = layout.projectDirectory.file(apk.get()).asFile
        val result = ApkVerifier.Builder(apkFile).build().verify()
        check(result.isVerified) { "APK signature does not verify: ${result.errors}" }
        val packageName =
            RandomAccessFile(apkFile, "r").use {
                ApkUtils.getPackageNameFromBinaryAndroidManifest(ApkUtils.getAndroidManifest(DataSources.asDataSource(it)))
            }
        // Turn package id into domain
        val site = "https://" + packageName.split('.').reversed().joinToString(".")
        for (cert in result.signerCertificates) {
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
            val query =
                mapOf(
                    "source.web.site" to site,
                    "relation" to "delegate_permission/common.handle_all_urls",
                    "target.androidApp.packageName" to packageName,
                    "target.androidApp.certificate.sha256Fingerprint" to fingerprint,
                ).entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
            val response =
                HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI("https://digitalassetlinks.googleapis.com/v1/assetlinks:check?$query")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            check(response.statusCode() == 200) { "assetlinks:check returned ${response.statusCode()}: ${response.body()}" }
            val linked = (JsonSlurper().parseText(response.body()) as Map<*, *>)["linked"] == true
            check(linked) { "$fingerprint is not linked to $packageName by $site" }
            logger.lifecycle("OK: $fingerprint is linked to $packageName by $site")
        }
    }
}

tasks.register<VerifyAssetLinks>("verifyAssetLinks") {
    description = "Checks that every signer of --apk is linked from its package domain's assetlinks.json"
}
