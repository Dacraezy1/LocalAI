# llama.cpp JNI - keep all native method bindings
-keep class com.localai.llm.LlamaWrapper { *; }
-keep class com.localai.llm.LlamaWrapper$TokenCallback { *; }

# Room database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# Gson / JSON models
-keep class com.localai.llm.ModelInfo { *; }
-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Markwon
-keep class io.noties.markwon.** { *; }
