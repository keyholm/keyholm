package app.keyholm.store.encrypted.exceptions

import java.io.IOException

class StoreKeyUnavailableException(
    cause: Throwable,
) : IOException("the device key is unavailable", cause)
