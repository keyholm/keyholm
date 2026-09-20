package app.keyholm.store

import app.keyholm.keystore.KeySecurityLevel
import app.keyholm.store.proto.KeySecurityLevelProto
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.CredentialUser
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RelyingParty
import app.keyholm.webauthn.RpId
import app.keyholm.webauthn.UserHandle
import app.keyholm.webauthn.WebAuthnAlgorithm
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import org.junit.Test
import org.junit.jupiter.api.assertThrows
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

private const val UNRECOGNIZED_PROTO_VALUE = 99

@RunWith(RobolectricTestRunner::class)
class PasskeyRecordMappingTest {
    private fun record(
        prfSecurityLevel: KeySecurityLevel? = null,
        lifecycle: RecordLifecycle = RecordLifecycle.Active,
    ) = PasskeyRecord(
        credentialId = CredentialId("cred-1"),
        rp = RelyingParty(id = RpId("example.com"), name = "Example"),
        user = CredentialUser(handle = UserHandle("user-1"), name = "alice", displayName = "Alice"),
        signCount = 7,
        callingPackage = PackageName("com.example.app"),
        createdAt = Instant.ofEpochMilli(1_000),
        keystore =
            PasskeyRecord.Keystore(
                coseAlgorithm = WebAuthnAlgorithm.ES256,
                securityLevel = KeySecurityLevel.StrongBox,
                publicKeySpki = ByteString.copyFrom(byteArrayOf(1, 2, 3, 4)),
                prfSecurityLevel = prfSecurityLevel,
            ),
        lifecycle = lifecycle,
        lastUsedAt = Instant.ofEpochMilli(2_000),
        likelyInvalid = true,
    )

    @Test
    fun `round-trips a full record`() {
        val original =
            record(
                prfSecurityLevel = KeySecurityLevel.TrustedEnvironment,
                lifecycle = RecordLifecycle.PendingDelete(Instant.ofEpochMilli(9_999)),
            )
        assertThat(original.toProto().toDomain()).isEqualTo(original)
    }

    @Test
    fun `round-trips a record with null optionals`() {
        val original = record(prfSecurityLevel = null, lifecycle = RecordLifecycle.Active)
        val restored = original.toProto().toDomain()
        assertThat(restored).isEqualTo(original)
        assertThat(restored.keystore.prfSecurityLevel).isNull()
        assertThat(restored.lifecycle).isEqualTo(RecordLifecycle.Active)
    }

    @Test
    fun `an active record writes no pending delete timestamp`() {
        val proto = record(lifecycle = RecordLifecycle.Active).toProto()
        assertThat(proto.hasPendingDeleteAt()).isFalse()

        val pending = record(lifecycle = RecordLifecycle.PendingDelete(Instant.ofEpochMilli(4_242))).toProto()
        assertThat(pending.hasPendingDeleteAt()).isTrue()
        assertThat(pending.pendingDeleteAt).isEqualTo(Instant.ofEpochMilli(4_242).toTimestamp())
    }

    @Test
    fun `round-trips a pending delete timestamp`() {
        val pendingDelete = RecordLifecycle.PendingDelete(Instant.ofEpochMilli(4_242))

        assertThat(record(lifecycle = pendingDelete).toProto().toDomain().lifecycle).isEqualTo(pendingDelete)
    }

    @Test
    fun `a record without prf writes no nested prf message`() {
        val keystore = record(prfSecurityLevel = null).toProto().keystore
        assertThat(keystore.hasPrf()).isFalse()

        val withPrf = record(prfSecurityLevel = KeySecurityLevel.StrongBox).toProto().keystore
        assertThat(withPrf.hasPrf()).isTrue()
        assertThat(withPrf.prf.securityLevel)
            .isEqualTo(KeySecurityLevelProto.KEY_SECURITY_LEVEL_PROTO_STRONGBOX)
    }

    @Test
    fun `an unrecognized stored enum is rejected`() {
        val stored = record().toProto()
        val proto =
            stored
                .toBuilder()
                .setKeystore(stored.keystore.toBuilder().setCoseAlgorithmValue(UNRECOGNIZED_PROTO_VALUE))
                .build()

        assertThrows<StoredRecordException> { proto.toDomain() }
    }

    @Test
    fun `round-trips the ed25519 algorithm`() {
        val original =
            record().copy(
                keystore =
                    PasskeyRecord.Keystore(
                        coseAlgorithm = WebAuthnAlgorithm.ED25519,
                        securityLevel = KeySecurityLevel.TrustedEnvironment,
                        publicKeySpki = ByteString.copyFrom(byteArrayOf(5, 6, 7, 8)),
                        prfSecurityLevel = null,
                    ),
            )
        assertThat(
            original
                .toProto()
                .toDomain()
                .keystore.coseAlgorithm,
        ).isEqualTo(WebAuthnAlgorithm.ED25519)
    }

    @Test
    fun `round-trips the ML-DSA-87 algorithm`() {
        val original =
            record().copy(
                keystore =
                    PasskeyRecord.Keystore(
                        coseAlgorithm = WebAuthnAlgorithm.ML_DSA_87,
                        securityLevel = KeySecurityLevel.TrustedEnvironment,
                        publicKeySpki = ByteString.copyFrom(byteArrayOf(5, 6, 7, 8)),
                        prfSecurityLevel = null,
                    ),
            )
        assertThat(
            original
                .toProto()
                .toDomain()
                .keystore.coseAlgorithm,
        ).isEqualTo(WebAuthnAlgorithm.ML_DSA_87)
    }
}
