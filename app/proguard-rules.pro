# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# OkHttp hostname verifier accessed reflectively
-keep class okhttp3.internal.tls.OkHostnameVerifier { *; }

# SnakeYAML reflects over these
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class dev.rafa.kubemobile.**$$serializer { *; }
-keepclassmembers class dev.rafa.kubemobile.** { *** Companion; }
-keepclassmembers @kotlinx.serialization.Serializable class dev.rafa.kubemobile.** {
    static ** Companion;
}
-keepclasseswithmembers class dev.rafa.kubemobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Update checker
-keep class dev.rafa.kubemobile.update.** { *; }

# FileProvider
-keep class androidx.core.content.FileProvider { *; }
