package app.keyholm.webauthn

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

private const val FINGERPRINT =
    "14:6D:E9:83:C5:73:06:50:D8:EE:B9:95:2F:34:FC:64:16:A0:83:42:E6:1D:BE:A8:8A:04:96:B2:3F:CF:44:E5"

private fun statementJson(
    relations: List<String> = listOf("delegate_permission/common.get_login_creds"),
    namespace: String = "android_app",
    packageName: String = "app.example",
    fingerprints: List<String> = listOf(FINGERPRINT),
): String =
    """
    {
        "relation": ${relations.joinToString(",", "[", "]") { "\"$it\"" }},
        "target": {
            "namespace": "$namespace",
            "package_name": "$packageName",
            "sha256_cert_fingerprints": ${fingerprints.joinToString(",", "[", "]") { "\"$it\"" }}
        }
    }
    """.trimIndent()

private val expectedStatement =
    AssetLinkStatement(PackageName("app.example"), CertFingerprint.of(setOf(FINGERPRINT)))

class AssetLinksTest {
    @Test
    fun `parseAssetLinkStatements keeps a valid get_login_creds android_app statement`() {
        val parsed = parseAssetLinkStatements("[${statementJson()}]")

        assertThat(parsed).containsExactly(expectedStatement)
    }

    @Test
    fun `parseAssetLinkStatements drops a statement without get_login_creds relation`() {
        val json = statementJson(relations = listOf("delegate_permission/common.handle_all_urls"))

        assertThat(parseAssetLinkStatements("[$json]")).isEmpty()
    }

    @Test
    fun `parseAssetLinkStatements drops a statement with a non-android_app namespace`() {
        val json = statementJson(namespace = "web")

        assertThat(parseAssetLinkStatements("[$json]")).isEmpty()
    }

    @Test
    fun `parseAssetLinkStatements throws on malformed json`() {
        assertThrows<SerializationException> { parseAssetLinkStatements("not json at all") }
    }

    @Test
    fun `parseAssetLinkStatements keeps only the matching statement among several`() {
        val json = "[${statementJson(namespace = "web")},${statementJson()}]"

        assertThat(parseAssetLinkStatements(json)).containsExactly(expectedStatement)
    }

    @Test
    fun `parseCommunityAssetLinks keys each domain's statements by rpId`() {
        val json =
            """
            {
              "one.example": [${statementJson()}],
              "two.example": [${statementJson(packageName = "app.other")}]
            }
            """.trimIndent()

        val parsed = parseCommunityAssetLinks(json)

        assertThat(parsed).containsExactly(
            RpId("one.example"),
            listOf(expectedStatement),
            RpId("two.example"),
            listOf(AssetLinkStatement(PackageName("app.other"), CertFingerprint.of(setOf(FINGERPRINT)))),
        )
    }

    @Test
    fun `parseCommunityAssetLinks keeps a domain whose statements all get dropped`() {
        val json = """{"one.example": [${statementJson(namespace = "web")}]}"""

        val parsed = parseCommunityAssetLinks(json)

        assertThat(parsed).containsExactly(RpId("one.example"), emptyList<AssetLinkStatement>())
    }

    @Test
    fun `parseCommunityAssetLinks throws on malformed json`() {
        assertThrows<SerializationException> { parseCommunityAssetLinks("[]") }
    }
}
