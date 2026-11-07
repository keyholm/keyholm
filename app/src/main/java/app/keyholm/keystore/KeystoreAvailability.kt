package app.keyholm.keystore

import android.content.Context
import android.content.pm.PackageManager
import app.keyholm.ctap.WebAuthnAlgorithm

private const val KEYSTORE_VERSION_ML_DSA = 500

fun isSecureElementAvailable(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

fun isTeeAvailable(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_HARDWARE_KEYSTORE)

fun isAlgorithmAvailable(
    context: Context,
    algorithm: WebAuthnAlgorithm,
): Boolean =
    when (algorithm) {
        WebAuthnAlgorithm.ES256, WebAuthnAlgorithm.ED25519 -> isTeeAvailable(context)

        WebAuthnAlgorithm.ML_DSA_65,
        WebAuthnAlgorithm.ML_DSA_87,
        -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_HARDWARE_KEYSTORE, KEYSTORE_VERSION_ML_DSA)
    }
