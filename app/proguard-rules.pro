# ---------- JNI 边界 ----------
# native_bridge.cpp 用 FindClass/GetMethodID 按名字查找下面这些类型，
# 一旦被 R8 改名或裁掉，调用时就会 NoSuchMethodError 直接 abort（不是抛异常）。
-keep class com.ufs.bench.core.** { *; }

# external fun 的声明类若被改名，C++ 侧的符号名就对不上
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------- 反射 / 序列化 ----------
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# ---------- Kotlin ----------
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ---------- Compose ----------
# Compose 运行时不依赖反射，但保留可组合函数的元数据便于调试栈
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}

# ---------- 其他 ----------
# 保留行号，便于崩溃栈定位
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
