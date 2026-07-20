# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable
-keepattributes !SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 保持应用包名相关类不被混淆
-keep class cn.com.omnimind.bot.** {*;}

# 保留AndroidX/AppCompat核心类，避免混淆导致主题依赖丢失
-keep class androidx.appcompat.** { *; }
-keep interface androidx.appcompat.** { *; }
-keep class com.google.android.material.** { *; }
-keep interface com.google.android.material.** { *; }
# 保留主题相关的属性和样式类
-keep class android.support.v7.** { *; }
-keep interface android.support.v7.** { *; }
-keepattributes *Annotation*
-keepattributes Signature
# 保留Activity的子类，避免混淆后MIUI无法识别AppCompatActivity
-keep public class * extends androidx.appcompat.app.AppCompatActivity
-keep public class * extends android.app.Activity
# 保留资源相关的类，避免资源压缩导致主题资源缺失
-keep class * extends android.content.res.Resources
-keep class * extends android.content.res.TypedArray
# 保持Flutter WebView相关类不被混淆
-keep class io.flutter.plugins.webviewflutter.** { *; }
-dontwarn io.flutter.plugins.webviewflutter.**
-keep class com.webview_** { *; }
-keep class dev.flutter.pigeon.** { *; }
-keep class android.webkit.** { *; }

## flutter 相关
-keep class io.flutter.** { *; }
-dontwarn io.flutter.embedding.**
-ignorewarnings

# 保持Activity生命周期方法不被混淆
-keep class * extends android.app.Activity {
    protected void onCreate(android.os.Bundle);
    protected void onStart();
    protected void onResume();
    protected void onPause();
    protected void onStop();
    protected void onDestroy();
    protected void onSaveInstanceState(android.os.Bundle);
    protected void onRestoreInstanceState(android.os.Bundle);
}

# 保持包管理器相关类不被混淆，防止NameNotFoundException
-keep class android.content.pm.** {*;}
-keep class android.app.ApplicationPackageManager {*;}

# 特别保护硬编码的包名字符串不被混淆
-keepclassmembernames class * {
    public static final java.lang.String *;
}
-keepclassmembers class ** {
    public static final java.lang.String APPLICATION_ID;
    public static final java.lang.String BUILD_TYPE;
    public static final java.lang.String FLAVOR;
    public static final int VERSION_CODE;
    public static final java.lang.String VERSION_NAME;
}

# 保持Context相关类不被混淆
-keep class android.content.Context { *; }
-keep class android.content.ContextWrapper { *; }

# 保持Kotlin相关类不被混淆 (精简版)
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# 保持AndroidX相关类不被混淆 (精简版)
-dontwarn androidx.**

# 保持Google相关库不被混淆 (精简版)
-dontwarn com.google.**

# 保持JetBrains相关库不被混淆
-dontwarn org.jetbrains.**

# 保持Room数据库相关类不被混淆 (精简版)
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# 保持Gson相关类不被混淆 (精简版)
# 仅保留实际使用的类，而不是整个包
-dontwarn com.google.gson.**

# 保持OkHttp相关类不被混淆 (精简版)
-dontwarn okhttp3.**

# 保持数据类不被混淆
-keep class cn.com.omnimind.baselib.database.** {
    <fields>;
    <methods>;
    public <init>(...);
}

# 保持cn.com.omnimind.assists.api.bean包下所有data类不被混淆
-keep class cn.com.omnimind.assists.api.bean.** {
    <fields>;
    <methods>;
    public <init>(...);
}

# 保持枚举类不被混淆
-keep class cn.com.omnimind.baselib.util.OmniLog$Level { *; }
-keep class cn.com.omnimind.assists.api.enums.TaskType { *; }
-keep enum cn.com.omnimind.** { *; }

# Kotlinx Serialization 保护规则 (精简版)
-keepclassmembers class * {
    *** Companion;
}

# 保护所有带有 @Serializable 注解的类
-keep @kotlinx.serialization.Serializable class * {
    <init>(...);
    <fields>;
}
-keepclassmembers @kotlinx.serialization.Serializable class * {
    <init>(...);
    <fields>;
}
# 保留 Kotlin 序列化生成的 Companion 类（含 serializer() 方法）
-keep class **$Companion { *; }

# 保留序列化相关的函数（如 serializer()）
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 保护 kotlinx.serialization 相关类 (精简版)
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# 保护数据类的构造函数和字段
-keepclassmembers @kotlinx.serialization.Serializable class * {
    public synthetic <init>(...);
}

# 保持接口类不被混淆
-keep interface cn.com.omnimind.baselib.database.** { *; }

# 保持自定义View类不被混淆
-keep class cn.com.omnimind.overlay.view.** { *; }

# 保持Lottie动画相关类不被混淆
-keep class com.airbnb.lottie.** { *; }
-dontwarn com.airbnb.lottie.**

# 保持MMKV相关类不被混淆
-keep class com.tencent.mmkv.** { *; }
-dontwarn com.tencent.mmkv.**

-dontwarn ch.qos.logback.**

# 保持Ktor相关类不被混淆
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# 保持协程相关类不被混淆
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# 保持必须的注解不被混淆
-keep class javax.annotation.** { *; }
-keep class javax.inject.** { *; }
-keep class org.repackage.** {*;}

-keep class com.uyumao.** { *; }

-keepclassmembers class * {
   public <init> (org.json.JSONObject);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep public class cn.com.omnimind.bot.R$* {
    public static final int *;
}

# 广告检测数据模型 - 这些类会被 Gson 序列化为 JSON，字段名不能被混淆
-keep class cn.com.omnimind.assists.detection.detectors.popup.models.** { *; }
-keep class cn.com.omnimind.assists.detection.detectors.button.** { *; }

# NanoHTTPD Web 服务器 (Debug 版本)
-keep class fi.iki.elonen.** { *; }

# ==================== Detection 模块数据模型 ====================
# 这些类涉及 Gson JSON 反序列化，字段名不能被混淆

# 其他优化选项
-optimizationpasses 5
-allowaccessmodification
