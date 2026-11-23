# kotlinx.serialization keeps its generated serializers through its own consumer rules.
# MediaPipe and ONNX Runtime ship consumer rules as well; add model-runtime keeps here when they are wired in.

# ONNX Runtime's consumer rules keep only its telemetry classes. Its JNI code finds the rest by name (OnnxTensor,
# OrtSession, OrtException and their constructors), so the whole API stays as it is.
-keep class ai.onnxruntime.** { *; }
