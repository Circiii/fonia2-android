# Fonia Colector. Libraries bring their own rules (Compose, Media3, kotlinx.serialization, :platform:audio's JNI).
# The JSON records are read back by the app itself (active.json, session.json, clips.jsonl): keep their serializers.
-keep,includedescriptorclasses class tech.fonia.collector.core.**$$serializer { *; }
-keepclassmembers class tech.fonia.collector.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses, Signature
