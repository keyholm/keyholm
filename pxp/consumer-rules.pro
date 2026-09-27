# The native library exports Java_app_keyholm_pxp_crypto_PxpNative_open etc
# so the class and method names can't be mangled.
-keepclasseswithmembernames class app.keyholm.pxp.crypto.PxpNative {
  native <methods>;
}
