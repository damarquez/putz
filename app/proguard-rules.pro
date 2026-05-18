-keep class com.damarquez.putz.data.model.** { *; }
-keep class com.damarquez.putz.data.local.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.damarquez.putz.**$$serializer { *; }
-keepclassmembers class com.damarquez.putz.** { *** Companion; }
-keepclasseswithmembers class com.damarquez.putz.** { kotlinx.serialization.KSerializer serializer(...); }

# SMBJ and its dependencies
-keep class com.hierynomus.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.slf4j.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
