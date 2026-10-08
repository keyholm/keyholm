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
    private val deletedKeys = mutableListOf<PasskeyRecord>()

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
        userHandle: UserHandle = UserHandle("user-${credentialId.b64}"),
        coseAlgorithm: WebAuthnAlgorithm = WebAuthnAlgorithm.ES256,
        createdAt: Instant = Instant.ofEpochMilli(1_000),
        lastUsedAt: Instant = createdAt,
        likelyInvalid: Boolean = false,
        discoverable: Boolean = true,
    ) = PasskeyRecord(
        credentialId = credentialId,
        rp = RelyingParty(id = rpId, name = "Example"),
        user = CredentialUser(handle = userHandle, name = "alice", displayName = "Alice"),
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
        discoverable = discoverable,
    )

    @Test
    fun `put makes a record retrievable`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)

            repo.put(record())

            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-1")
        }

    @Test
    fun `put replaces a record for the same RP and user handle and deletes its keys`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)
            val account = UserHandle("user-1")
            repo.put(record(credentialId = CredentialId("cred-1"), userHandle = account))
            repo.put(record(credentialId = CredentialId("cred-2"), rpId = RpId("other.example"), userHandle = account))

            repo.put(record(credentialId = CredentialId("cred-3"), userHandle = account))

            assertThat(deletedKeys.map { it.credentialId.b64 }).containsExactly("cred-1")
            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-2", "cred-3")
        }

    @Test
    fun `put keeps non-discoverable records for the same account`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)
            val account = UserHandle("user-1")
            repo.put(record(credentialId = CredentialId("cred-1"), userHandle = account, discoverable = false))
            repo.put(record(credentialId = CredentialId("cred-2"), userHandle = account))

            repo.put(record(credentialId = CredentialId("cred-3"), userHandle = account, discoverable = false))

            assertThat(deletedKeys).isEmpty()
            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-1", "cred-2", "cred-3")
        }

    @Test
    fun `data survives reopening the store on the same file`() =
        runBlocking<Unit> {
            val file = freshFile()

            val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            PasskeyRepository(dataStore(file, firstScope), deletedKeys::add)
                .put(record(credentialId = CredentialId("cred-1")))
            firstScope.cancel()
            firstScope.coroutineContext.job.join()

            val reloaded = PasskeyRepository(dataStore(file), deletedKeys::add)

            assertThat(reloaded.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-1")
        }

    @Test
    fun `update replaces the record with a matching credential id`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)
            repo.put(record(credentialId = CredentialId("cred-1")))

            val result = repo.update(record(credentialId = CredentialId("cred-1"), likelyInvalid = true))

            assertThat(result.isSuccess).isTrue()
            assertThat(
                repo.passkeys
                    .first()
                    .single()
                    .likelyInvalid,
            ).isTrue()
        }

    @Test
    fun `setting the rp name replaces it`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)
            repo.put(record(credentialId = CredentialId("cred-1")))

            repo.setRpName(CredentialId("cred-1"), "Acme")

            assertThat(
                repo.passkeys
                    .first()
                    .single()
                    .rp.name,
            ).isEqualTo("Acme")
        }

    @Test
    fun `an empty rp name unsets it`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)
            repo.put(record(credentialId = CredentialId("cred-1")))

            repo.setRpName(CredentialId("cred-1"), "")

            assertThat(
                repo.passkeys
                    .first()
                    .single()
                    .rp.name,
            ).isEmpty()
        }

    @Test
    fun `an rp name equal to the rp id is not shown`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)
            repo.put(record(credentialId = CredentialId("cred-1")))

            repo.setRpName(CredentialId("cred-1"), "example.com")

            assertThat(
                repo.passkeys
                    .first()
                    .single()
                    .rp.name,
            ).isEmpty()
        }

    @Test
    fun `update fails when the credential id is not found`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)
            repo.put(record(credentialId = CredentialId("cred-1")))

            val result = repo.update(record(credentialId = CredentialId("missing")))

            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()).isInstanceOf(NoSuchElementException::class.java)
            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-1")
        }

    @Test
    fun `delete fails when the credential id is not found`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)
            repo.put(record(credentialId = CredentialId("cred-1")))

            val result = repo.delete(CredentialId("missing"))

            assertThat(result.isFailure).isTrue()
            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-1")
        }

    @Test
    fun `delete removes only the matching record`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)
            repo.put(record(credentialId = CredentialId("cred-1")))
            repo.put(record(credentialId = CredentialId("cred-2")))

            val result = repo.delete(CredentialId("cred-1"))

            assertThat(result.isSuccess).isTrue()
            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-2")
        }

    @Test
    fun `saveAll replaces the whole set`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)
            repo.put(record(credentialId = CredentialId("cred-1")))

            repo.saveAll(
                listOf(record(credentialId = CredentialId("cred-2")), record(credentialId = CredentialId("cred-3"))),
            )

            assertThat(repo.passkeys.first().map { it.credentialId.b64 }).containsExactly("cred-2", "cred-3")
        }

    @Test
    fun `summary counts records and reports the latest last-used time`() =
        runBlocking<Unit> {
            val repo = PasskeyRepository(dataStore(), deletedKeys::add)
            repo.put(record(credentialId = CredentialId("cred-1"), lastUsedAt = Instant.ofEpochMilli(1_000)))
            repo.put(record(credentialId = CredentialId("cred-2"), lastUsedAt = Instant.ofEpochMilli(3_000)))
            repo.put(record(credentialId = CredentialId("cred-3"), lastUsedAt = Instant.ofEpochMilli(2_000)))

            val summary = repo.summary()

            assertThat(summary.count).isEqualTo(3)
            assertThat(summary.lastUsedTime).isEqualTo(Instant.ofEpochMilli(3_000L))
        }
}
