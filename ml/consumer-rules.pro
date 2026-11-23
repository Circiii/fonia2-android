# ONNX Runtime's JNI library creates and reads ai.onnxruntime objects (OnnxTensor, OrtSession results, OrtException)
# by class and member name; R8 must not rename or strip them. The AAR's own rules keep only its telemetry classes.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
