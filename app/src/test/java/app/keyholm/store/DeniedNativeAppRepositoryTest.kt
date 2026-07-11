package app.keyholm.store

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import app.keyholm.store.proto.DeniedNativeAppsProto
import app.keyholm.webauthn.AssetLinkStatement
import app.keyholm.webauthn.CertFingerprint
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RpId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

private fun key(
    rpId: String,
    packageName: String,
    fingerprints: Set<String>,
) = DeniedNativeAppKey(RpId(rpId), PackageName(packageName), CertFingerprint.of(fingerprints))

@RunWith(RobolectricTestRunner::class)
class DeniedNativeAppRepositoryTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() =
        runBlocking<Unit> {
            scopes.forEach { it.cancel() }
            scopes.forEach { it.coroutineContext.job.join() }
        }

    private fun freshFile() = File(context.cacheDir, "denied_native_apps_${System.nanoTime()}.pb")

    private fun newScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes.add(it) }

    private fun dataStore(
        file: File = freshFile(),
        scope: CoroutineScope = newScope(),
    ): DataStore<DeniedNativeAppsProto> =
        DataStoreFactory.create(
            serializer = DeniedNativeAppsSerializer,
            scope = scope,
            produceFile = { file },
        )

    @Test
    fun `record persists a denied entry, normalising the fingerprint`() =
        runBlocking<Unit> {
            val repo = DeniedNativeAppRepository(dataStore())

            repo.record(RpId("example.com"), PackageName("com.example.app"), CertFingerprint.of(setOf("aa:bb")))

            assertThat(
                repo.nativeApps
                    .first()
                    .denied.keys,
            ).containsExactly(key("example.com", "com.example.app", setOf("AA:BB")))
        }

    @Test
    fun `accept survives reopening the store on the same file`() =
        runBlocking<Unit> {
            val file = freshFile()
            val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            DeniedNativeAppRepository(dataStore(file, firstScope))
                .accept(key("example.com", "com.example.app", setOf("AA:BB")))
            firstScope.cancel()
            firstScope.coroutineContext.job.join()

            val reloaded = DeniedNativeAppRepository(dataStore(file))

            assertThat(reloaded.accepted.first()).containsExactly(
                RpId("example.com"),
                listOf(AssetLinkStatement(PackageName("com.example.app"), CertFingerprint.of(setOf("AA:BB")))),
            )
        }

    @Test
    fun `clearAccepted drops the accepted map but keeps the denied list`() =
        runBlocking<Unit> {
            val repo = DeniedNativeAppRepository(dataStore())
            repo.record(RpId("example.com"), PackageName("com.pending"), CertFingerprint.of(setOf("CC:DD")))
            repo.accept(key("other.test", "com.trusted", setOf("EE:FF")))

            repo.clearAccepted()

            assertThat(repo.accepted.first()).isEmpty()
            assertThat(
                repo.nativeApps
                    .first()
                    .denied.keys,
            ).containsExactly(key("example.com", "com.pending", setOf("CC:DD")))
        }

    @Test
    fun `clear empties the store`() =
        runBlocking<Unit> {
            val repo = DeniedNativeAppRepository(dataStore())
            repo.record(RpId("example.com"), PackageName("com.a"), CertFingerprint.of(setOf("AA")))
            repo.accept(key("example.com", "com.b", setOf("BB")))

            repo.clear()

            assertThat(repo.nativeApps.first().denied).isEmpty()
            assertThat(repo.accepted.first()).isEmpty()
        }
}
