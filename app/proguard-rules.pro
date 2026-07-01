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
