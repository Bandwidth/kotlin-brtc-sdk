# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.bandwidth.rtc.**$$serializer { *; }
-keepclassmembers class com.bandwidth.rtc.** { *** Companion; }
-keepclasseswithmembers class com.bandwidth.rtc.** { kotlinx.serialization.KSerializer serializer(...); }
