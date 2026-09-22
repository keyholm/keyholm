package app.keyholm.provider

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.domerrors.EncodingError
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import app.keyholm.R
import app.keyholm.keystore.SecureKeyManager
import app.keyholm.settings.Settings
import app.keyholm.settings.SettingsRepository
import app.keyholm.store.PasskeyRepository
import app.keyholm.store.PasskeySummary
import app.keyholm.store.StoredRecordException
import app.keyholm.util.logger
import app.keyholm.webauthn.AlgorithmFamily
import app.keyholm.webauthn.AlgorithmNegotiation
import app.keyholm.webauthn.CreationOptions
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.MlDsaSupport
import app.keyholm.webauthn.NativeAppTrust
import app.keyholm.webauthn.WebAuthnAlgorithm
import app.keyholm.webauthn.attestationRequested
import app.keyholm.webauthn.parseCreationOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException

private const val REQUEST_CODE = 0
private const val SCHEME = "keyholm"
private const val CREDENTIALS = "credentials"
private const val UNLOCK_ENTRY_TITLE = "Keyholm"
private const val EXTRA_PREFER_RP_NAME = "app.keyholm.extra.PREFER_RP_NAME"
private const val EXTRA_NATIVE_APP_TRUST = "app.keyholm.extra.NATIVE_APP_TRUST"

internal fun credentialUri(credentialId: CredentialId): Uri =
    Uri
        .Builder()
        .scheme(SCHEME)
        .authority(CREDENTIALS)
        .appendPath(credentialId.b64)
        .build()

internal fun Intent.putPreferRpName(prefer: Boolean): Intent = putExtra(EXTRA_PREFER_RP_NAME, prefer)

internal fun Intent.preferRpName(): Boolean {
    check(hasExtra(EXTRA_PREFER_RP_NAME)) { "the intent carries no RP name preference" }
    return getBooleanExtra(EXTRA_PREFER_RP_NAME, false)
}

internal fun Intent.putNativeAppTrust(trust: NativeAppTrust): Intent = putExtra(EXTRA_NATIVE_APP_TRUST, trust.mode().name)

internal suspend fun Intent.nativeAppTrust(context: Context): NativeAppTrust {
    val stored = checkNotNull(getStringExtra(EXTRA_NATIVE_APP_TRUST)) { "the intent carries no native app trust" }
    return named(EXTRA_NATIVE_APP_TRUST, stored, TrustMode.entries).toTrust(context)
}

internal fun Intent.credentialId(): CredentialId =
    CredentialId(
        checkNotNull(data?.lastPathSegment) {
            "the sign-in intent carries no credential URI"
        },
    )

class Service internal constructor(
    dispatcher: CoroutineDispatcher,
) : CredentialProviderService() {
    constructor() : this(Dispatchers.IO)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val passkeyRepo by lazy { PasskeyRepository(applicationContext) }
    private val getEntries by lazy { GetCredentialEntries(applicationContext) }
    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val log = logger()

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            callback.onError(CreateCredentialNoCreateOptionException())
            return
        }

        val job =
            scope.launch {
                try {
                    val settings = settingsRepo.settings.first()
                    val options =
                        try {
                            parseCreationOptions(request.requestJson)
                        } catch (e: SerializationException) {
                            log.e(e) { "couldn't parse the request JSON" }
                            callback.onResult(BeginCreateCredentialResponse(emptyList()))
                            return@launch
                        }
                    val algorithms = offeredAlgorithms(options, settings)
                    if (algorithms.isEmpty()) {
                        callback.onResult(BeginCreateCredentialResponse(emptyList()))
                        return@launch
                    }
                    val offer =
                        CreationOffer(
                            algorithms = algorithms,
                            attestation = offeredAttestation(options),
                            identity = settings.identityPreference,
                            authenticators = settings.createAuthenticators,
                            invalidateOnBiometricEnrollment = settings.invalidateOnBiometricEnrollment,
                            nativeAppTrust = settings.nativeAppTrust,
                        )
                    val entry =
                        buildCreateEntry(
                            offer,
                            settings.preferRpName,
                            passkeyRepo.summary(),
                        )
                    callback.onResult(BeginCreateCredentialResponse(listOf(entry)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    log.e(e) { "couldn't read the store" }
                    callback.onResult(BeginCreateCredentialResponse(emptyList()))
                } catch (e: StoredRecordException) {
                    log.e(e) { "couldn't read the store" }
                    callback.onResult(BeginCreateCredentialResponse(emptyList()))
                }
            }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private fun offeredAlgorithms(
        options: CreationOptions,
        settings: Settings,
    ): List<WebAuthnAlgorithm> =
        AlgorithmNegotiation.offeredAlgorithms(
            creationOptions = options,
            enabled = settings.enabledFamilies.filterTo(mutableSetOf(), ::isFamilyAvailable),
            preferred = settings.preferredAlgorithm,
            fallback = settings.fallbackAlgorithm,
            mlDsa = if (isFamilyAvailable(AlgorithmFamily.ML_DSA)) settings.mlDsaSupport else MlDsaSupport.OFF,
        )

    private fun isFamilyAvailable(family: AlgorithmFamily): Boolean =
        family.algorithms.any {
            SecureKeyManager.isAlgorithmAvailable(applicationContext, it)
        }

    private fun offeredAttestation(options: CreationOptions): AttestationOffer =
        if (attestationRequested(options.attestation)) AttestationOffer.Ask else AttestationOffer.NotRequested

    private fun buildCreateEntry(
        offer: CreationOffer,
        preferRpName: Boolean,
        summary: PasskeySummary,
    ): CreateEntry {
        val algorithm = offer.algorithms.singleOrNull()
        val algorithmName =
            if (algorithm != null) {
                "${algorithm.displayName} device-bound" +
                    (if (algorithm == WebAuthnAlgorithm.ES256) " (dedicated secure hardware)" else "")
            } else {
                "Device-bound passkey"
            }
        return CreateEntry(
            accountName = algorithmName,
            description = "Keyholm generates passkeys that never leave the device.",
            pendingIntent =
                pendingIntent(app.keyholm.ui.createpasskey.Activity::class.java, offer.toUri()) {
                    putPreferRpName(preferRpName)
                },
            icon = Icon.createWithResource(applicationContext, R.mipmap.ic_launcher),
            isAutoSelectAllowed = true,
            publicKeyCredentialCount = summary.count,
            totalCredentialCount = summary.count,
            lastUsedTime = summary.lastUsedTime,
        )
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val job =
            scope.launch {
                try {
                    if (settingsRepo.settings.first().requireUnlock) {
                        callback.onResult(
                            BeginGetCredentialResponse(
                                authenticationActions =
                                    listOf(
                                        AuthenticationAction(UNLOCK_ENTRY_TITLE, unlockPendingIntent(applicationContext)),
                                    ),
                            ),
                        )
                        return@launch
                    }
                    val entries = getEntries.of(request)
                    if (entries == null) {
                        callback.onError(
                            GetPublicKeyCredentialDomException(
                                EncodingError(),
                                "requestJson is not valid request options",
                            ),
                        )
                        return@launch
                    }
                    callback.onResult(BeginGetCredentialResponse(entries))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    log.e(e) { "couldn't read the passkey store" }
                    callback.onError(GetCredentialInterruptedException())
                } catch (e: StoredRecordException) {
                    log.e(e) { "couldn't read the passkey store" }
                    callback.onError(GetCredentialInterruptedException())
                }
            }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }

    private fun pendingIntent(
        target: Class<*>,
        data: Uri,
        extras: Intent.() -> Unit = {},
    ): PendingIntent =
        PendingIntent.getActivity(
            applicationContext,
            REQUEST_CODE,
            Intent(applicationContext, target).setData(data).apply(extras),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
