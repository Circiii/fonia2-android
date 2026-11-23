# libfonia_whisper binds its JNI functions by name to this class.
-keepclasseswithmembernames,includedescriptorclasses class tech.fonia.whisper.WhisperCppNative {
    native <methods>;
}
