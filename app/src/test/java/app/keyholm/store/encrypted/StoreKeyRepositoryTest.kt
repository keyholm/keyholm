package app.keyholm.store.encrypted

import android.app.Application
import androidx.datastore.core.DataStoreFactory
import app.keyholm.keystore.StoreHmac
import app.keyholm.keystore.StoreHmacKeyManager
import app.keyholm.keystore.StoreKeyKind
import app.keyholm.store.encrypted.crypto.DekWrapper
import app.keyholm.store.encrypted.exceptions.StoreKeyLostException
import app.keyholm.store.proto.StoreKeysetProto
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.aead.AesGcmKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val PLAINTEXT = "records".encodeToByteArray()
private val AAD = "vault/passkeys.pb".encodeToByteArray()

private class SoftwareStoreHmacKeyManager : StoreHmacKeyManager {
    val keys = mutableMapOf<StoreKeyKind, SecretKeySpec>()

    override fun generate(kind: StoreKeyKind) {
        keys[kind] = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "HmacSHA256")
    }

    override fun hmac(kind: StoreKeyKind): StoreHmac {
        val key = keys[kind] ?: return StoreHmac.KeyNotFound
        return StoreHmac.Available(Mac.getInstance("HmacSHA256").apply { init(key) })
    }
}

@RunWith(RobolectricTestRunner::class)
class StoreKeyRepositoryTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val scopes = mutableListOf<CoroutineScope>()
    private val dir = File(context.cacheDir, "store_keys_${System.nanoTime()}")
    private val keysetFile = File(dir, "store_keyset.pb")

    @After
    fun tearDown() = endProcess()

    // A repository over the same files stands in for a new process
    private fun process(hmacKey: StoreHmacKeyManager): StoreKeyRepository {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes.add(it) }
        val dataStore = DataStoreFactory.create(serializer = StoreKeysetSerializer, scope = scope, produceFile = { keysetFile })
        return StoreKeyRepository(dataStore, DekWrapper(hmacKey, Dispatchers.IO))
    }

    private fun endProcess() =
        runBlocking<Unit> {
            scopes.forEach { it.cancel() }
            scopes.forEach { it.coroutineContext.job.join() }
            scopes.clear()
        }

    @Test
    fun keysAreStableAcrossProcesses() =
        runBlocking {
            val hmacKey = SoftwareStoreHmacKeyManager()
            val sealed = process(hmacKey).aead().encrypt(PLAINTEXT, AAD)
            endProcess()

            assertThat(process(hmacKey).aead().decrypt(sealed, AAD)).isEqualTo(PLAINTEXT)
        }

    @Test
    fun dekIsNotStoredInPlaintext() =
        runBlocking {
            val hmacKey = SoftwareStoreHmacKeyManager()
            process(hmacKey).aead()
            endProcess()

            val stored = keysetFile.readBytes()
            val device = StoreKeysetProto.parseFrom(stored).device
            val wrapper = DekWrapper(hmacKey, Dispatchers.IO)
            val keyset = wrapper.openKeyset(device.keyset, wrapper.key(device.salt))
            val dek = (keyset.primary.key as AesGcmKey).keyBytes.toByteArray(InsecureSecretKeyAccess.get())

            assertThat(stored.asList().windowed(dek.size).none { it == dek.asList() }).isTrue()
        }

    @Test
    fun keysetNeedsTheKeystoreKey() {
        val hmacKey = SoftwareStoreHmacKeyManager()
        runBlocking {
            process(hmacKey).aead()
        }
        endProcess()
        hmacKey.generate(StoreKeyKind.Basic)

        val result =
            runCatching {
                runBlocking {
                    process(hmacKey).aead()
                }
            }

        assertThat(result.exceptionOrNull()).isInstanceOf(StoreKeyLostException::class.java)
    }

    @Test
    fun missingKeystoreKeyIsLost() {
        val hmacKey = SoftwareStoreHmacKeyManager()
        runBlocking {
            process(hmacKey).aead()
        }
        endProcess()
        hmacKey.keys.clear()

        val result =
            runCatching {
                runBlocking {
                    process(hmacKey).aead()
                }
            }

        assertThat(result.exceptionOrNull()).isInstanceOf(StoreKeyLostException::class.java)
    }
}
