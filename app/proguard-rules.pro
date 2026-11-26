-keep class core.** { *; }
-keep class go.** { *; }
-keep class hev.** { *; }
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.vexotun.app.model.**$$serializer { *; }
-keep,includedescriptorclasses class com.vexotun.app.vpn.**$$serializer { *; }
-keepclassmembers class com.vexotun.app.model.** {
    *** Companion;
}
-keepclassmembers class com.vexotun.app.vpn.** {
    *** Companion;
}
-keepclasseswithmembers class com.vexotun.app.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.vexotun.app.vpn.** {
    kotlinx.serialization.KSerializer serializer(...);
}
