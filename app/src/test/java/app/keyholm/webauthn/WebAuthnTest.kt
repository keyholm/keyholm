package app.keyholm.webauthn

import com.google.common.truth.Truth.assertThat
import com.upokecenter.cbor.CBORObject
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class WebAuthnTest {
    @Test
    fun `attestationObjectNone encodes fmt, empty attStmt, and the given authData`() {
        val authData = AuthenticatorData(byteArrayOf(1, 2, 3, 4))

        val decoded = CBORObject.DecodeFromBytes(AttestationObjects.attestationObjectNone(authData).bytes)

        assertThat(decoded.get("fmt").AsString()).isEqualTo("none")
        assertThat(decoded.get("attStmt").size()).isEqualTo(0)
        assertThat(decoded.get("authData").GetByteString()).isEqualTo(authData.bytes)
    }

    @Test
    fun `attestationObjectAndroidKey encodes fmt, alg, sig, x5c, and authData`() {
        val authData = AuthenticatorData(byteArrayOf(5, 6, 7))
        val signature = DerSignature(byteArrayOf(9, 9, 9))
        val leaf = byteArrayOf(1)
        val intermediate = byteArrayOf(2)

        val decoded =
            CBORObject.DecodeFromBytes(
                AttestationObjects
                    .attestationObjectAndroidKey(
                        authData,
                        signature,
                        listOf(leaf, intermediate),
                        WebAuthn.ALG_ES256,
                    ).bytes,
            )
        val attStmt = decoded.get("attStmt")

        assertThat(decoded.get("fmt").AsString()).isEqualTo("android-key")
        assertThat(attStmt.get("alg").AsInt32Value()).isEqualTo(WebAuthn.ALG_ES256)
        assertThat(attStmt.get("sig").GetByteString()).isEqualTo(signature.bytes)
        assertThat(attStmt.get("x5c").size()).isEqualTo(2)
        assertThat(attStmt.get("x5c").get(0).GetByteString()).isEqualTo(leaf)
        assertThat(attStmt.get("x5c").get(1).GetByteString()).isEqualTo(intermediate)
        assertThat(decoded.get("authData").GetByteString()).isEqualTo(authData.bytes)
    }

    @Test
    fun `assertion auth data lays out rpId hash, user flags, and big-endian sign count`() {
        val rpId = RpId("keyholm.app")
        val expectedRpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.value.toByteArray())

        val data = WebAuthn.assertionAuthData(rpId, signCount = 1)

        assertThat(data.bytes.size).isEqualTo(37)
        assertThat(data.bytes.copyOfRange(0, 32)).isEqualTo(expectedRpIdHash)
        assertThat(data.bytes[32]).isEqualTo(0x05.toByte())
        assertThat(data.bytes.copyOfRange(33, 37)).isEqualTo(byteArrayOf(0, 0, 0, 1))
    }

    private fun sha256Of(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    @Test
    fun `clientData signs the caller's hash and returns a placeholder for a privileged client`() {
        val provided = ClientDataHash(byteArrayOf(7, 7, 7))

        val cd = WebAuthn.clientData(ClientDataType.Get, "Y2hhbA", Caller.Privileged.Client(provided))

        assertThat(cd.hash.bytes).isEqualTo(provided.bytes)
        assertThat(cd.json.bytes).isEqualTo(WebAuthn.PLACEHOLDER_CLIENT_DATA_JSON.bytes)
    }

    @Test
    fun `clientData assembles the json for a privileged caller that supplied only an origin`() {
        val cd =
            WebAuthn.clientData(
                ClientDataType.Get,
                "Y2hhbA",
                Caller.Privileged.Origin("https://example.com"),
            )

        val json = cd.json.bytes.toString(Charsets.UTF_8)
        assertThat(json).contains("\"origin\":\"https://example.com\"")
        assertThat(json).contains("\"type\":\"webauthn.get\"")
        assertThat(json).contains("\"challenge\":\"Y2hhbA\"")
        assertThat(cd.hash.bytes).isEqualTo(sha256Of(cd.json.bytes))
    }

    @Test
    fun `clientData assembles the json from the derived origin for a normal caller`() {
        val cd =
            WebAuthn.clientData(
                ClientDataType.Create,
                "Y2hhbA",
                Caller.Normal("android:apk-key-hash:abc"),
            )

        val json = cd.json.bytes.toString(Charsets.UTF_8)
        assertThat(json).contains("\"origin\":\"android:apk-key-hash:abc\"")
        assertThat(json).contains("\"type\":\"webauthn.create\"")
        assertThat(cd.hash.bytes).isEqualTo(sha256Of(cd.json.bytes))
    }

    @Test
    fun `clientData never signs a hash that disagrees with the json it returns`() {
        val assembling =
            listOf(
                Caller.Privileged.Origin("https://example.com"),
                Caller.Normal("android:apk-key-hash:abc"),
            )

        for (caller in assembling) {
            val cd = WebAuthn.clientData(ClientDataType.Get, "Y2hhbA", caller)

            assertThat(cd.hash.bytes).isEqualTo(sha256Of(cd.json.bytes))
        }
    }
}
