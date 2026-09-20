package app.keyholm.store

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import app.keyholm.keystore.KeySecurityLevel
import app.keyholm.store.proto.PasskeyRecordsProto
import app.keyholm.webauthn.CredentialId
import app.keyholm.webauthn.CredentialUser
import app.keyholm.webauthn.PackageName
import app.keyholm.webauthn.RelyingParty
import app.keyholm.webauthn.RpId
import app.keyholm.webauthn.UserHandle
import app.keyholm.webauthn.WebAuthnAlgorithm
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PasskeyRepositoryTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() =
        runBlocking<Unit> {
            scopes.forEach { it.cancel() }
            scopes.forEach { it.coroutineContext.job.join() }
        }

    private fun freshFile() = File(context.cacheDir, "passkeys_${System.nanoTime()}.pb")

    private fun newScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes.add(it) }

    private fun dataStore(
        file: File = freshFile(),
        scope: CoroutineScope = newScope(),
    ): DataStore<PasskeyRecordsProto> =
        DataStoreFactory.create(
            serializer = PasskeyRecordsSerializer,
            scope = scope,
            produceFile = { file },
        )

    private fun record(
        credentialId: CredentialId = CredentialId("cred-1"),
        rpId: RpId = RpId("example.com"),
        coseAlgorithm: WebAuthnAlgorithm = WebAuthnAlgorithm.ES256,
        createdAt: Instant = Instant.ofEpochMilli(1_000),
        lastUsedAt: Instant = createdAt,
        likelyInvalid: Boolean = false,
    ) = PasskeyRecord(
        credentialId = credentialId,
        rp = RelyingParty(id = rpId, name = rpId.value),
        user = CredentialUser(handle = UserHandle("user-1"), name = "alice", displayName = "Alice"),
        signCount = 0,
        callingPackage = PackageName("com.example.app"),
        createdAt = createdAt,
        keystore =
            PasskeyRecord.Keystore(
                coseAlgorithm = coseAlgorithm,
                securityLevel = KeySecurityLevel.StrongBox,
                publicKeySpki = ByteString.copyFrom(byteArrayOf(1, 2, 3, 4)),
                prfSecurityLevel = null,
            ),
        lifecycle = RecordLifecycle.Active,
        lastUsedAt = lastUsedAt,
        likelyInvalid = likelyInvalid,
    )

    @Test
    fun `add makes a record retrievable`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore())

            repo.add(record())

            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-1")
        }

    @Test
    fun `data survives reopening the store on the same file`() =
        runBlocking<Unit> {
            val file = freshFile()

            val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            PasskeyRepository(dataStore(file, firstScope)).add(record(credentialId = CredentialId("cred-1")))
            firstScope.cancel()
            firstScope.coroutineContext.job.join()

            val reloaded = PasskeyRepository(dataStore(file))

            assertThat(reloaded.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-1")
        }

    @Test
    fun `update replaces the record with a matching credential id`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore())
            repo.add(record(credentialId = CredentialId("cred-1")))

            val result = repo.update(record(credentialId = CredentialId("cred-1"), rpId = RpId("changed.example")))

            assertThat(result.isSuccess).isTrue()
            assertThat(
                repo.passkeys
                    .first()
                    .single()
                    .rp.id,
            ).isEqualTo(RpId("changed.example"))
        }

    @Test
    fun `update fails when the credential id is not found`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore())
            repo.add(record(credentialId = CredentialId("cred-1")))

            val result = repo.update(record(credentialId = CredentialId("missing")))

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(NoSuchElementException::class.java)
            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-1")
        }

    @Test
    fun `delete fails when the credential id is not found`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore())
            repo.add(record(credentialId = CredentialId("cred-1")))

            val result = repo.delete(CredentialId("missing"))

            assertThat(result.isFailure).isTrue()
            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-1")
        }

    @Test
    fun `delete removes only the matching record`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore())
            repo.add(record(credentialId = CredentialId("cred-1")))
            repo.add(record(credentialId = CredentialId("cred-2")))

            val result = repo.delete(CredentialId("cred-1"))

            assertThat(result.isSuccess).isTrue()
            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-2")
        }

    @Test
    fun `saveAll replaces the whole set`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore())
            repo.add(record(credentialId = CredentialId("cred-1")))

            repo.saveAll(
                listOf(record(credentialId = CredentialId("cred-2")), record(credentialId = CredentialId("cred-3"))),
            )

            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-2", "cred-3")
        }

    @Test
    fun `summary counts records and reports the latest last-used time`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore())
            repo.add(record(credentialId = CredentialId("cred-1"), lastUsedAt = Instant.ofEpochMilli(1_000)))
            repo.add(record(credentialId = CredentialId("cred-2"), lastUsedAt = Instant.ofEpochMilli(3_000)))
            repo.add(record(credentialId = CredentialId("cred-3"), lastUsedAt = Instant.ofEpochMilli(2_000)))

            val summary = repo.summary()

            assertThat(summary.count).isEqualTo(3)
            assertThat(summary.lastUsedTime).isEqualTo(Instant.ofEpochMilli(3_000L))
        }
}
