package app.keyholm.ctap

import app.keyholm.util.B64
import kotlinx.serialization.Serializable

@JvmInline
value class ClientDataHash(
    val bytes: ByteArray,
)

@JvmInline
value class CoseKey(
    val bytes: ByteArray,
)

@Serializable
@JvmInline
value class RpId(
    val value: String,
)

data class RelyingParty(
    val id: RpId,
    val name: String,
)

@Serializable
@JvmInline
value class UserHandle(
    val b64: String,
) {
    fun bytes(): ByteArray = B64.dec(b64)

    companion object {
        fun of(bytes: ByteArray) = UserHandle(B64.enc(bytes))
    }
}

data class CredentialUser(
    val handle: UserHandle,
    val name: String,
    val displayName: String,
)

@Serializable
@JvmInline
value class CredentialId(
    val b64: String,
) {
    fun bytes(): ByteArray = B64.dec(b64)

    companion object {
        fun of(bytes: ByteArray) = CredentialId(B64.enc(bytes))
    }
}
