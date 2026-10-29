# Project-specific rules. NewPipe/Rhino rules were removed in 0.2.2.

# LiteRT-LM looks up these callbacks by name from liblitertlm_jni.so.
# Calls through JNI are invisible to R8: keep both the contracts and their
# implementations, including the streaming callbacks introduced in 0.3.0.
-keep interface com.google.ai.edge.litertlm.LiteRtLmJni$Jni*Callback { *; }
-keep class * implements com.google.ai.edge.litertlm.LiteRtLmJni$Jni*Callback { *; }
