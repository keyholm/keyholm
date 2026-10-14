package app.keyholm.store.encrypted.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.aead.PredefinedAeadParameters

internal fun newStoreKeyset(): KeysetHandle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, AeadConfig2026.get())

internal fun KeysetHandle.storeAead(): Aead = getPrimitive(AeadConfig2026.get(), Aead::class.java)
