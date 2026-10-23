# ShotTuner R8 规则
#
# 只在 release 启用压缩。两类东西必须显式保留：
# 1. ML Kit 的模型加载走反射与 JNI 回调，类名不能被改；
# 2. CameraX 的 Camera2Interop 通过反射读特征字段。
#
# ML Kit 的 AAR 自带 consumer rules，正常情况无需手写；
# 这里保留是为了在升级 ML Kit 版本后仍然稳，代价只是 APK 大一点点。

-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_face.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_label.** { *; }
-dontwarn com.google.mlkit.**

-keep class androidx.camera.camera2.interop.** { *; }
-dontwarn androidx.camera.**

# Kotlin 元数据：保留后崩溃栈里的行号才有意义
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
