# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# SnakeYAML reflects over these
-keep class org.yaml.snakeyaml.** { *; }
-keep class org.yaml.snakeyaml.constructor.** { *; }
-dontwarn org.yaml.snakeyaml.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class dev.rafa.kubemobile.**$$serializer { *; }
-keepclassmembers class dev.rafa.kubemobile.** { *** Companion; }
