-keep class app.keyholm.provider.** { *; }
-keep class app.keyholm.ui.** { *; }

# Non-Android library (CBOR/COSE encoding) with no bundled consumer rules of its
# own. Don't risk R8 breaking internal reflection we can't see.
-keep class com.upokecenter.cbor.** { *; }

# Generated protobuf-lite messages (Proto DataStore stores). The lite runtime
# resolves the generated `name_` fields by reflection from newMessageInfo(), so
# R8 must not rename or strip them.
-keep class app.keyholm.store.proto.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}
