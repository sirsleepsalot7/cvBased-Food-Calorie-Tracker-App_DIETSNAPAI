# ONNX Runtime Mobile keep rules
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Room Database keep rules
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**
