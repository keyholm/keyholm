package app.keyholm.pxp.util

import co.touchlab.kermit.Logger

internal fun Any.logger(): Logger = Logger.withTag(this::class.java.canonicalName ?: this::class.java.name)
