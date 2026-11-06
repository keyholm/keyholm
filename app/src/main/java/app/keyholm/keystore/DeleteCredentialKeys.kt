package app.keyholm.keystore

import app.keyholm.ctap.CredentialId

fun deleteCredentialKeys(
    credentialId: CredentialId,
    keyManager: SecureKeyManager,
    hmacKeyManager: HmacKeyManager,
) {
    keyManager.deleteKey(credentialId)
    hmacKeyManager.deleteKey(credentialId)
}
