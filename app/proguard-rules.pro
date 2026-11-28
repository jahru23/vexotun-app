-keep class core.** { *; }
-keep class go.** { *; }
-keep class hev.** { *; }
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.vexotun.app.**$$serializer { *; }
-keepclassmembers class com.vexotun.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.vexotun.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
