package app.keyholm.store.encrypted

import com.google.crypto.tink.KeysetHandle

internal class TestStoreKeys(
    private val handle: KeysetHandle,
) : StoreKeys {
    override suspend fun keyset(): KeysetHandle = handle
}
