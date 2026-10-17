package app.keyholm.store

import android.content.Context
import androidx.datastore.core.DataStore
import app.keyholm.Application
import app.keyholm.store.proto.DeniedNativeAppsProto
import app.keyholm.webauthn.AssetLinkStatement
import app.keyholm.webauthn.CertFingerprint
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RpId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant

internal const val DENIED_NATIVE_APPS_STORE_FILE = "denied_native_apps.pb"

class DeniedNativeAppRepository internal constructor(
    private val dataStore: DataStore<DeniedNativeAppsProto>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(context: Context) : this((context.applicationContext as Application).stores.deniedNativeApps)

    val nativeApps: Flow<DeniedNativeApps> =
        dataStore.data.map { it.toDomain() }.flowOn(dispatcher)

    val accepted: Flow<Map<RpId, List<AssetLinkStatement>>> = nativeApps.map { it.accepted }

    suspend fun record(
        rpId: RpId,
        packageName: PackageName,
        certFingerprints: Set<CertFingerprint>,
    ): Result<Unit> {
        val key = DeniedNativeAppKey(rpId, packageName, certFingerprints)
        return update { it.recordDenial(key, Instant.now()) }
    }

    suspend fun accept(key: DeniedNativeAppKey) = update { it.approve(key) }

    suspend fun revoke(key: DeniedNativeAppKey) = update { it.revoke(key, Instant.now()) }

    suspend fun dismiss(key: DeniedNativeAppKey) = update { it.dismiss(key) }

    suspend fun clearAccepted() = update { it.copy(accepted = emptyMap()) }

    private suspend fun update(transform: (DeniedNativeApps) -> DeniedNativeApps): Result<Unit> =
        try {
            dataStore.updateData { transform(it.toDomain()).toProto() }
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        }
}
