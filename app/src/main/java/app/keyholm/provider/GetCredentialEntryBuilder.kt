package app.keyholm.provider

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricPrompt
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.BiometricPromptData
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import app.keyholm.R
import app.keyholm.keystore.AuthenticatorPolicy
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.store.PasskeyRecord
import app.keyholm.store.PasskeyRepository
import app.keyholm.util.logger
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.NativeAppTrust
import app.keyholm.webauthn.RequestOptions
import app.keyholm.webauthn.RpId
import java.security.GeneralSecurityException
import java.security.Signature

internal class GetCredentialEntryBuilder(
    private val context: Context,
    private val keyManager: SecureKeyManager,
    private val passkeyRepo: PasskeyRepository,
    private val pendingIntent: (CredentialId, preferRpName: Boolean, trust: NativeAppTrust) -> PendingIntent,
) {
    private val log = logger()

    fun matchingRecords(
        records: List<PasskeyRecord>,
        requestOptions: RequestOptions,
    ): List<PasskeyRecord> {
        val forRp = records.filter { it.rp.id == RpId(requestOptions.rpId) }
        val allowedIds = requestOptions.allowCredentials.map { it.id }.toSet()
        return if (allowedIds.isEmpty()) forRp else forRp.filter { it.credentialId.b64 in allowedIds }
    }

    suspend fun buildEntries(
        option: BeginGetPublicKeyCredentialOption,
        records: List<PasskeyRecord>,
        allowSingleTap: Boolean,
        preferRpName: Boolean,
        trust: NativeAppTrust,
    ): List<CredentialEntry>? =
        try {
            records.map { buildEntry(option, it, allowSingleTap, preferRpName, trust) }
        } catch (e: IllegalArgumentException) {
            log.e(e) { "couldn't build a credential entry" }
            null
        }

    private suspend fun buildEntry(
        option: BeginGetPublicKeyCredentialOption,
        record: PasskeyRecord,
        allowSingleTap: Boolean,
        preferRpName: Boolean,
        trust: NativeAppTrust,
    ): CredentialEntry {
        val material =
            if (allowSingleTap) {
                singleTapSignature(record)?.also { PendingSignatures.put(record.credentialId, it.signature) }
            } else {
                null
            }
        return PublicKeyCredentialEntry(
            context = context,
            username = record.user.name,
            pendingIntent =
                pendingIntent(
                    record.credentialId,
                    preferRpName,
                    trust,
                ),
            beginGetPublicKeyCredentialOption = option,
            displayName = record.user.displayName.ifBlank { record.user.name },
            lastUsedTime = record.lastUsedAt,
            icon = Icon.createWithResource(context, R.mipmap.ic_launcher),
            isAutoSelectAllowed = true,
            biometricPromptData =
                material?.let {
                    BiometricPromptData
                        .Builder()
                        .setCryptoObject(BiometricPrompt.CryptoObject(it.signature))
                        .setAllowedAuthenticators(it.allowedAuthenticators.promptMask)
                        .build()
                },
        )
    }

    private data class SingleTapSignature(
        val signature: Signature,
        val allowedAuthenticators: AuthenticatorPolicy,
    )

    private suspend fun singleTapSignature(record: PasskeyRecord): SingleTapSignature? =
        try {
            val algorithm = record.keystore.coseAlgorithm
            val allowedAuthenticators = keyManager.allowedAuthenticatorsFor(record.keyAlias, algorithm)
            SingleTapSignature(keyManager.signatureFor(record.keyAlias, algorithm), allowedAuthenticators)
        } catch (e: KeyPermanentlyInvalidatedException) {
            log.e(e) { "key invalidated for ${record.keyAlias.value}" }
            markLikelyInvalid(record)
            null
        } catch (e: GeneralSecurityException) {
            log.e(e) { "couldn't prepare signature for ${record.keyAlias.value}" }
            null
        }

    private suspend fun markLikelyInvalid(record: PasskeyRecord) {
        if (record.likelyInvalid) return
        passkeyRepo.update(record.copy(likelyInvalid = true)).onFailure {
            log.e(it) { "markLikelyInvalid failed for ${record.keyAlias.value}" }
        }
    }
}
