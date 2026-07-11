package app.keyholm.util

import co.touchlab.kermit.Logger

fun Any.logger(): Logger = Logger.withTag(this::class.java.canonicalName ?: this::class.java.name)
