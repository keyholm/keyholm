package app.keyholm.store.encrypted.exceptions

import java.io.IOException

class StoreLockedException(
    cause: Throwable,
) : IOException("the stores are locked while the device is locked", cause)
