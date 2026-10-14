package app.keyholm.store.encrypted.exceptions

import java.io.IOException

class StoreKeyLostException(
    cause: Throwable,
) : IOException("the data key can't be unwrapped", cause)
