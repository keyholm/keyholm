package app.keyholm.store

import app.keyholm.keystore.KeySecurityLevel
import app.keyholm.store.proto.CoseAlgorithmProto
import app.keyholm.store.proto.KeySecurityLevelProto
import app.keyholm.store.proto.KeystoreProto
import app.keyholm.store.proto.PasskeyRecordProto
import app.keyholm.store.proto.PrfProto
import app.keyholm.store.proto.RelyingPartyProto
import app.keyholm.store.proto.UserProto
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.CredentialUser
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RelyingParty
import app.keyholm.webauthn.RpId
import app.keyholm.webauthn.UserHandle
import app.keyholm.webauthn.WebAuthnAlgorithm

private fun CoseAlgorithmProto.toWebAuthnAlgorithm(): WebAuthnAlgorithm =
    when (this) {
        CoseAlgorithmProto.COSE_ALGORITHM_PROTO_ES256 -> {
            WebAuthnAlgorithm.ES256
        }

        CoseAlgorithmProto.COSE_ALGORITHM_PROTO_ED25519 -> {
            WebAuthnAlgorithm.ED25519
        }

        CoseAlgorithmProto.COSE_ALGORITHM_PROTO_ML_DSA_65 -> {
            WebAuthnAlgorithm.ML_DSA_65
        }

        CoseAlgorithmProto.COSE_ALGORITHM_PROTO_ML_DSA_87 -> {
            WebAuthnAlgorithm.ML_DSA_87
        }

        CoseAlgorithmProto.UNRECOGNIZED -> {
            throw StoredRecordException("unrecognized COSE algorithm in stored passkey")
        }
    }

private fun WebAuthnAlgorithm.toCoseAlgorithmProto(): CoseAlgorithmProto =
    when (this) {
        WebAuthnAlgorithm.ES256 -> CoseAlgorithmProto.COSE_ALGORITHM_PROTO_ES256
        WebAuthnAlgorithm.ED25519 -> CoseAlgorithmProto.COSE_ALGORITHM_PROTO_ED25519
        WebAuthnAlgorithm.ML_DSA_65 -> CoseAlgorithmProto.COSE_ALGORITHM_PROTO_ML_DSA_65
        WebAuthnAlgorithm.ML_DSA_87 -> CoseAlgorithmProto.COSE_ALGORITHM_PROTO_ML_DSA_87
    }

private fun KeySecurityLevelProto.toKeySecurityLevel(): KeySecurityLevel =
    when (this) {
        KeySecurityLevelProto.KEY_SECURITY_LEVEL_PROTO_STRONGBOX -> {
            KeySecurityLevel.StrongBox
        }

        KeySecurityLevelProto.KEY_SECURITY_LEVEL_PROTO_TRUSTED_ENVIRONMENT -> {
            KeySecurityLevel.TrustedEnvironment
        }

        KeySecurityLevelProto.UNRECOGNIZED -> {
            throw StoredRecordException("unrecognized security level in stored passkey")
        }
    }

private fun KeySecurityLevel.toProto(): KeySecurityLevelProto =
    when (this) {
        KeySecurityLevel.StrongBox -> {
            KeySecurityLevelProto.KEY_SECURITY_LEVEL_PROTO_STRONGBOX
        }

        KeySecurityLevel.TrustedEnvironment -> {
            KeySecurityLevelProto.KEY_SECURITY_LEVEL_PROTO_TRUSTED_ENVIRONMENT
        }
    }

internal fun PasskeyRecordProto.toDomain(): PasskeyRecord =
    PasskeyRecord(
        credentialId = CredentialId(credentialId),
        rp = RelyingParty(id = RpId(rp.id), name = rp.name),
        user =
            CredentialUser(
                handle = UserHandle(user.handle),
                name = user.name,
                displayName = user.displayName,
            ),
        signCount = signCount,
        callingPackage = PackageName(callingPackage),
        createdAt = createdAt.toInstant(),
        keystore =
            PasskeyRecord.Keystore(
                coseAlgorithm = keystore.coseAlgorithm.toWebAuthnAlgorithm(),
                securityLevel = keystore.securityLevel.toKeySecurityLevel(),
                publicKeySpki = keystore.publicKeySpki,
                prfSecurityLevel =
                    if (keystore.hasPrf()) keystore.prf.securityLevel.toKeySecurityLevel() else null,
            ),
        lifecycle =
            if (hasPendingDeleteAt()) {
                RecordLifecycle.PendingDelete(pendingDeleteAt.toInstant())
            } else {
                RecordLifecycle.Active
            },
        lastUsedAt = lastUsedAt.toInstant(),
        likelyInvalid = likelyInvalid,
    )

internal fun PasskeyRecord.toProto(): PasskeyRecordProto {
    val keystoreBuilder =
        KeystoreProto
            .newBuilder()
            .setCoseAlgorithm(keystore.coseAlgorithm.toCoseAlgorithmProto())
            .setSecurityLevel(keystore.securityLevel.toProto())
            .setPublicKeySpki(keystore.publicKeySpki)
    keystore.prfSecurityLevel?.let {
        keystoreBuilder.prf = PrfProto.newBuilder().setSecurityLevel(it.toProto()).build()
    }

    val builder =
        PasskeyRecordProto
            .newBuilder()
            .setCredentialId(credentialId.b64)
            .setRp(RelyingPartyProto.newBuilder().setId(rp.id.value).setName(rp.name))
            .setUser(
                UserProto
                    .newBuilder()
                    .setHandle(user.handle.b64)
                    .setName(user.name)
                    .setDisplayName(user.displayName),
            ).setSignCount(signCount)
            .setCallingPackage(callingPackage.value)
            .setCreatedAt(createdAt.toTimestamp())
            .setKeystore(keystoreBuilder)
            .setLastUsedAt(lastUsedAt.toTimestamp())
            .setLikelyInvalid(likelyInvalid)
    if (lifecycle is RecordLifecycle.PendingDelete) builder.pendingDeleteAt = lifecycle.at.toTimestamp()
    return builder.build()
}
