# Foto Xplorr release rules.
#
# The release build is minified AND resource-shrunk, which is what makes a sideloadable APK a
# reasonable size. Both of those are whole-program transforms, so anything reached by reflection
# or from native code has to be named here or it is silently removed — and the failure lands at
# runtime on the owner's device, not in CI, which is the worst place for it to land.
#
# Everything below is a keep for a REFLECTIVE or NATIVE entry point. No blanket `-keep class **`:
# that would turn minification off in all but name and give back the size it just saved.

# ---- ML Kit (face detection, image labelling, OCR) ----
# ML Kit resolves its detectors through a registrar mechanism and loads model pipelines from
# native code, so the option/creator classes are never referenced from Kotlin by name.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# ---- MediaPipe (image embedder, similarity) ----
# The tasks API crosses a JNI boundary and builds its graph from proto descriptors at runtime.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# ---- AutoValue / builders used by the vision stacks ----
-keep class * extends com.google.auto.value.AutoValue { *; }

# ---- Kotlin coroutines / serialization internals reached reflectively ----
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- Compose ----
# Compose's own artifacts ship consumer rules, so this is only the runtime's reflective hook.
-keepclassmembers class androidx.compose.runtime.** { *; }

# ---- The app's own persisted shapes ----
# Enum names are written to SharedPreferences and to the SQLite index and read back by `valueOf`,
# so an obfuscated enum constant means a preference that silently resets on upgrade.
-keepclassmembers enum com.fotoxplorr.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# EXIF reads attribute constants reflectively in places; the library is small and worth keeping whole.
-keep class androidx.exifinterface.** { *; }
