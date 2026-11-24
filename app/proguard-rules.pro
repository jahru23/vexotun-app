-keep class core.** { *; }
-keep class go.** { *; }
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.vexotun.app.model.**$$serializer { *; }
-keepclassmembers class com.vexotun.app.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.vexotun.app.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
