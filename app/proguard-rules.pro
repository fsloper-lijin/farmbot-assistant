# R8 规则：只保留会被动态调用、反射加载的部分，其余交给默认优化

# OpenCV：JNI 通过全限定名查找类和方法，不能混淆
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# MLKit 文字识别：内部有反射注册与动态加载
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_text**

# 原生方法标签：JNI 按名字绑定，混淆后找不到
-keepclasseswithmembernames class * {
    native <methods>;
}
