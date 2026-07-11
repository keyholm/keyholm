package app.keyholm.util

import android.util.Base64
import java.security.MessageDigest

object B64 {
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    fun enc(b: ByteArray): String = Base64.encodeToString(b, FLAGS)

    fun dec(s: String): ByteArray = Base64.decode(s, FLAGS)
}

fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
