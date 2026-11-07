package app.keyholm.authenticator

import android.content.Context
import app.keyholm.ctap.AlgorithmFamily
import app.keyholm.keystore.isAlgorithmAvailable

private fun isFamilyAvailable(
    context: Context,
    family: AlgorithmFamily,
): Boolean = family.algorithms.any { isAlgorithmAvailable(context, it) }

fun availableFamilies(
    context: Context,
    enabled: Set<AlgorithmFamily>,
): Set<AlgorithmFamily> = enabled.filterTo(mutableSetOf()) { isFamilyAvailable(context, it) }

fun availableMlDsaSupport(
    context: Context,
    mlDsa: MlDsaSupport,
): MlDsaSupport = if (isFamilyAvailable(context, AlgorithmFamily.ML_DSA)) mlDsa else MlDsaSupport.OFF
