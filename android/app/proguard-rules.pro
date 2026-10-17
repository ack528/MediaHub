# 发布版开启 R8 压缩 / 优化(去掉库里没用到的代码,启动更快、安装包更小)。
# 个人自用:不混淆名字(-dontobfuscate),崩溃日志里的堆栈直接可读,不需要映射文件。

-dontobfuscate

# 本应用自己的代码全部保留(里面有 JNI 回调、kotlinx.serialization 的数据类、通过名字查找的设置字段…),只压缩第三方库
-keep class com.localtg.** { *; }
-keepclassmembers class com.localtg.** { *; }

# JNI:原生方法所在的类和方法名不能变
-keepclasseswithmembernames class * { native <methods>; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }

# OkHttp 的可选平台实现(没有就用 JDK 的),缺失的类只是警告
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
-dontwarn javax.annotation.**

# Media3:通过反射加载的扩展渲染器 / 解析器
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.datasource.** { *; }
-keep class androidx.media3.extractor.** { *; }

# Coil 3
-dontwarn coil3.**
