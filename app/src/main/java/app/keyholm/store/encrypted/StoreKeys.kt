package app.keyholm.store.encrypted

import app.keyholm.store.encrypted.crypto.storeAead
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle

internal interface StoreKeys {
    suspend fun keyset(): KeysetHandle
}

internal suspend fun StoreKeys.aead(): Aead = keyset().storeAead()
