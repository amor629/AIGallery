# ProGuard/R8 混淆规则
# Release 构建时 R8 会读取此文件

# ---- Hilt ----
# Hilt 生成的类保持完整名称，防止混淆后注入失败
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# ---- Room ----
# 保留 Room 实体类的字段名，防止数据库列名混淆
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

# ---- Retrofit + OkHttp（AI 可选模块）----
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ---- Gson（Retrofit 响应体序列化）----
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# ---- Coil ----
-dontwarn coil.**

# ---- 应用自身数据模型 ----
# 防止 domain/model 包下的数据类被混淆（Room 和 API 响应体依赖字段名）
-keep class com.example.aigallery.domain.model.** { *; }

# ---- AI API 请求/响应体 DTO （Gson 序列化，字段名不能混淆）----
-keep class com.example.aigallery.data.ai.dto.** { *; }

# ---- 全局崩溃捕获模块 ----
# CrashActivity 和 GlobalCrashHandler 在崩溃场景下被直接调用，必须保留完整类名
-keep class com.example.aigallery.crash.** { *; }

# ---- Kotlin 元数据（反射和序列化库需要）----
-keepattributes RuntimeVisibleAnnotations
-keepattributes AnnotationDefault
-keepattributes InnerClasses
-keep class kotlin.Metadata { *; }

# ---- 调试信息保留（崩溃日志可读）----
# 保留行号信息，使崩溃堆栈中的行号与源码对应（可结合 mapping.txt 还原）
-keepattributes SourceFile,LineNumberTable
# 保留混淆后的源文件名（降低堆栈追踪难度）
-renamesourcefileattribute SourceFile
